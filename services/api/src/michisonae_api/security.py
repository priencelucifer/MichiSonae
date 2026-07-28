from __future__ import annotations

import hmac
import ipaddress
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from typing import Any, Protocol
from uuid import UUID, uuid4

from psycopg import Error as PsycopgError
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from michisonae_api.migration import expected_migration
from michisonae_api.settings import Settings

INSERT_INSTALLATION_SQL = """
INSERT INTO public.anonymous_installations (
    installation_id,
    attestation_present,
    attestation_hash
)
VALUES (%s, %s, %s)
"""

INSERT_FAMILY_SQL = """
INSERT INTO public.auth_token_families (
    family_id,
    installation_id,
    expires_at
)
VALUES (%s, %s, %s)
"""

INSERT_REFRESH_SQL = """
INSERT INTO public.auth_refresh_tokens (
    token_hash,
    family_id,
    generation,
    expires_at
)
VALUES (%s, %s, %s, %s)
"""

INSERT_ACCESS_SQL = """
INSERT INTO public.auth_access_tokens (
    token_hash,
    family_id,
    installation_id,
    expires_at
)
VALUES (%s, %s, %s, %s)
"""

INSERT_AUDIT_SQL = """
INSERT INTO public.security_audit_events (
    event_type,
    installation_id,
    correlation_id,
    client_ip_hash,
    details
)
VALUES (%s, %s, %s, %s, %s)
"""

LOAD_REFRESH_FOR_UPDATE_SQL = """
SELECT
    refresh.family_id,
    refresh.generation,
    refresh.expires_at,
    refresh.used_at,
    family.expires_at,
    family.revoked_at,
    installation.installation_id,
    installation.status
FROM public.auth_refresh_tokens AS refresh
JOIN public.auth_token_families AS family
  ON family.family_id = refresh.family_id
JOIN public.anonymous_installations AS installation
  ON installation.installation_id = family.installation_id
WHERE refresh.token_hash = %s
FOR UPDATE OF refresh, family, installation
"""

USE_REFRESH_SQL = """
UPDATE public.auth_refresh_tokens
SET used_at = clock_timestamp(),
    replaced_by_hash = %s
WHERE token_hash = %s
  AND used_at IS NULL
RETURNING token_hash
"""

REVOKE_FAMILY_SQL = """
UPDATE public.auth_token_families
SET revoked_at = COALESCE(revoked_at, clock_timestamp()),
    revocation_reason = COALESCE(revocation_reason, %s)
WHERE family_id = %s
"""

REVOKE_FAMILY_ACCESS_SQL = """
UPDATE public.auth_access_tokens
SET revoked_at = COALESCE(revoked_at, clock_timestamp())
WHERE family_id = %s
"""

BOUND_ACCESS_SQL = """
UPDATE public.auth_access_tokens
SET revoked_at = COALESCE(revoked_at, clock_timestamp())
WHERE token_hash IN (
    SELECT token_hash
    FROM public.auth_access_tokens
    WHERE installation_id = %s
      AND revoked_at IS NULL
      AND expires_at > clock_timestamp()
    ORDER BY created_at DESC
    OFFSET %s
)
"""

PRUNE_ACCESS_SQL = """
DELETE FROM public.auth_access_tokens
WHERE installation_id = %s
  AND expires_at <= clock_timestamp()
"""

LOAD_ACCESS_SQL = """
SELECT access.installation_id, access.family_id, access.expires_at
FROM public.auth_access_tokens AS access
JOIN public.auth_token_families AS family
  ON family.family_id = access.family_id
JOIN public.anonymous_installations AS installation
  ON installation.installation_id = access.installation_id
WHERE access.token_hash = %s
  AND access.revoked_at IS NULL
  AND access.expires_at > clock_timestamp()
  AND family.revoked_at IS NULL
  AND family.expires_at > clock_timestamp()
  AND installation.status = 'active'
"""

REVOKE_INSTALLATION_SQL = """
UPDATE public.anonymous_installations
SET status = 'revoked',
    revoked_at = COALESCE(revoked_at, clock_timestamp())
WHERE installation_id = %s
"""

REVOKE_INSTALLATION_FAMILIES_SQL = """
UPDATE public.auth_token_families
SET revoked_at = COALESCE(revoked_at, clock_timestamp()),
    revocation_reason = COALESCE(revocation_reason, 'installation_revoked')
WHERE installation_id = %s
"""

REVOKE_INSTALLATION_ACCESS_SQL = """
UPDATE public.auth_access_tokens
SET revoked_at = COALESCE(revoked_at, clock_timestamp())
WHERE installation_id = %s
"""

RATE_LIMIT_SQL = """
WITH instant AS (
    SELECT clock_timestamp() AS now
),
rate_window AS (
    SELECT
        now,
        to_timestamp(floor(extract(epoch FROM now) / %s) * %s) AS started_at
    FROM instant
),
counted AS (
    INSERT INTO public.security_rate_limits (
        scope,
        subject_hash,
        window_started_at,
        request_count
    )
    SELECT %s, %s, started_at, 1
    FROM rate_window
    ON CONFLICT (scope, subject_hash, window_started_at) DO UPDATE
    SET request_count = security_rate_limits.request_count + 1
    RETURNING request_count, window_started_at
)
SELECT
    counted.request_count,
    GREATEST(
        1,
        ceil(
            extract(
                epoch FROM counted.window_started_at + %s::interval - rate_window.now
            )
        )
    )::integer AS retry_after
FROM counted
CROSS JOIN rate_window
"""

READINESS_SQL = """
SELECT EXISTS (
    SELECT 1
    FROM public.schema_migrations
    WHERE version = %s AND checksum = %s
)
"""


class SecurityUnavailable(RuntimeError):
    """Raised when authentication state cannot be used safely."""


class AuthenticationRejected(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(message)


@dataclass(frozen=True)
class IssuedCredentials:
    installation_id: str
    access_token: str
    access_expires_at: datetime
    refresh_token: str
    refresh_expires_at: datetime


@dataclass(frozen=True)
class AuthenticatedInstallation:
    installation_id: str
    family_id: UUID
    access_expires_at: datetime


@dataclass(frozen=True)
class RateLimitDecision:
    allowed: bool
    limit: int
    remaining: int
    retry_after_seconds: int


class SecurityService(Protocol):
    async def open(self) -> None: ...

    async def close(self) -> None: ...

    async def ready(self) -> bool: ...

    async def register(
        self,
        *,
        attestation: str | None,
        correlation_id: UUID,
        client_ip: str,
    ) -> IssuedCredentials: ...

    async def refresh(
        self,
        *,
        refresh_token: str,
        correlation_id: UUID,
        client_ip: str,
    ) -> IssuedCredentials: ...

    async def authenticate(self, access_token: str) -> AuthenticatedInstallation: ...

    async def revoke(
        self,
        principal: AuthenticatedInstallation,
        *,
        correlation_id: UUID,
        client_ip: str,
    ) -> None: ...

    async def check_rate_limit(
        self,
        *,
        scope: str,
        subject: str,
        limit: int,
        window_seconds: int,
    ) -> RateLimitDecision: ...


class PostgresSecurityService:
    def __init__(self, settings: Settings) -> None:
        if not settings.database_url:
            raise ValueError("database_url is required for authentication")
        self._settings = settings
        self._pool: AsyncConnectionPool[Any] = AsyncConnectionPool(
            conninfo=settings.database_url,
            min_size=settings.database_pool_min_size,
            max_size=settings.database_pool_max_size,
            timeout=settings.database_pool_timeout_seconds,
            max_waiting=settings.database_pool_max_size * 10,
            kwargs={
                "autocommit": True,
                "connect_timeout": settings.database_connect_timeout_seconds,
            },
            open=False,
            name="michisonae-security",
        )

    async def open(self) -> None:
        await self._pool.open(wait=False)

    async def close(self) -> None:
        await self._pool.close()

    async def ready(self) -> bool:
        migration = expected_migration()
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    READINESS_SQL,
                    (migration.version, migration.checksum),
                )
                row = await cursor.fetchone()
                return bool(row and row[0])
        except PsycopgError:
            return False

    def pool_stats(self) -> dict[str, int]:
        return self._pool.get_stats()

    async def register(
        self,
        *,
        attestation: str | None,
        correlation_id: UUID,
        client_ip: str,
    ) -> IssuedCredentials:
        now = datetime.now(UTC)
        installation_id = f"ins_{uuid4().hex}"
        family_id = uuid4()
        family_expires_at = now + timedelta(seconds=self._settings.token_family_ttl_seconds)
        credentials = self._new_credentials(
            installation_id=installation_id,
            refresh_expires_at=min(
                now + timedelta(seconds=self._settings.refresh_token_ttl_seconds),
                family_expires_at,
            ),
            now=now,
        )
        attestation_hash = None if attestation is None else sha256(attestation.encode()).digest()
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    await connection.execute(
                        INSERT_INSTALLATION_SQL,
                        (
                            installation_id,
                            attestation is not None,
                            attestation_hash,
                        ),
                    )
                    await connection.execute(
                        INSERT_FAMILY_SQL,
                        (family_id, installation_id, family_expires_at),
                    )
                    await self._insert_credentials(
                        connection,
                        credentials,
                        family_id=family_id,
                        generation=1,
                    )
                    await self._audit(
                        connection,
                        event_type="installation_registered",
                        installation_id=installation_id,
                        correlation_id=correlation_id,
                        client_ip=client_ip,
                        details={"attestation_present": attestation is not None},
                    )
        except PsycopgError as error:
            raise SecurityUnavailable("anonymous registration transaction failed") from error
        return credentials

    async def refresh(
        self,
        *,
        refresh_token: str,
        correlation_id: UUID,
        client_ip: str,
    ) -> IssuedCredentials:
        token_hash = _token_hash(refresh_token)
        now = datetime.now(UTC)
        credentials: IssuedCredentials | None = None
        rejection: AuthenticationRejected | None = None
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    cursor = await connection.execute(
                        LOAD_REFRESH_FOR_UPDATE_SQL,
                        (token_hash,),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        rejection = AuthenticationRejected(
                            "invalid_refresh_token",
                            "The refresh credential is invalid or expired.",
                        )
                    else:
                        family_id = UUID(str(row[0]))
                        generation = int(row[1])
                        refresh_expires_at: datetime = row[2]
                        used_at: datetime | None = row[3]
                        family_expires_at: datetime = row[4]
                        family_revoked_at: datetime | None = row[5]
                        installation_id = str(row[6])
                        installation_status = str(row[7])
                        if used_at is not None:
                            await self._revoke_family_for_reuse(
                                connection,
                                family_id=family_id,
                                installation_id=installation_id,
                                correlation_id=correlation_id,
                                client_ip=client_ip,
                            )
                            rejection = AuthenticationRejected(
                                "refresh_token_reuse",
                                "Refresh credential reuse revoked this token family.",
                            )
                        elif (
                            refresh_expires_at <= now
                            or family_expires_at <= now
                            or family_revoked_at is not None
                            or installation_status != "active"
                        ):
                            rejection = AuthenticationRejected(
                                "invalid_refresh_token",
                                "The refresh credential is invalid or expired.",
                            )
                        else:
                            credentials = self._new_credentials(
                                installation_id=installation_id,
                                refresh_expires_at=min(
                                    now
                                    + timedelta(seconds=self._settings.refresh_token_ttl_seconds),
                                    family_expires_at,
                                ),
                                now=now,
                            )
                            successor_hash = _token_hash(credentials.refresh_token)
                            used_cursor = await connection.execute(
                                USE_REFRESH_SQL,
                                (successor_hash, token_hash),
                            )
                            if await used_cursor.fetchone() is None:
                                raise SecurityUnavailable("refresh rotation lost its row lock")
                            await self._insert_credentials(
                                connection,
                                credentials,
                                family_id=family_id,
                                generation=generation + 1,
                            )
                            await connection.execute(
                                BOUND_ACCESS_SQL,
                                (
                                    installation_id,
                                    self._settings.maximum_active_access_tokens,
                                ),
                            )
                            await connection.execute(
                                PRUNE_ACCESS_SQL,
                                (installation_id,),
                            )
                            await self._audit(
                                connection,
                                event_type="refresh_rotated",
                                installation_id=installation_id,
                                correlation_id=correlation_id,
                                client_ip=client_ip,
                                details={"generation": generation + 1},
                            )
        except SecurityUnavailable:
            raise
        except PsycopgError as error:
            raise SecurityUnavailable("refresh rotation transaction failed") from error

        if rejection is not None:
            raise rejection
        if credentials is None:
            raise SecurityUnavailable("refresh rotation returned no outcome")
        return credentials

    async def authenticate(self, access_token: str) -> AuthenticatedInstallation:
        try:
            token_hash = _token_hash(access_token)
        except ValueError as error:
            raise AuthenticationRejected(
                "invalid_access_token",
                "The access credential is invalid or expired.",
            ) from error
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(LOAD_ACCESS_SQL, (token_hash,))
                row = await cursor.fetchone()
        except PsycopgError as error:
            raise SecurityUnavailable("access credential lookup failed") from error
        if row is None:
            raise AuthenticationRejected(
                "invalid_access_token",
                "The access credential is invalid or expired.",
            )
        return AuthenticatedInstallation(
            installation_id=str(row[0]),
            family_id=UUID(str(row[1])),
            access_expires_at=row[2],
        )

    async def revoke(
        self,
        principal: AuthenticatedInstallation,
        *,
        correlation_id: UUID,
        client_ip: str,
    ) -> None:
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    await connection.execute(
                        REVOKE_INSTALLATION_SQL,
                        (principal.installation_id,),
                    )
                    await connection.execute(
                        REVOKE_INSTALLATION_FAMILIES_SQL,
                        (principal.installation_id,),
                    )
                    await connection.execute(
                        REVOKE_INSTALLATION_ACCESS_SQL,
                        (principal.installation_id,),
                    )
                    await self._audit(
                        connection,
                        event_type="installation_revoked",
                        installation_id=principal.installation_id,
                        correlation_id=correlation_id,
                        client_ip=client_ip,
                        details={},
                    )
        except PsycopgError as error:
            raise SecurityUnavailable("installation revocation failed") from error

    async def check_rate_limit(
        self,
        *,
        scope: str,
        subject: str,
        limit: int,
        window_seconds: int,
    ) -> RateLimitDecision:
        subject_hash = self._subject_hash(scope, subject)
        interval = str(timedelta(seconds=window_seconds))
        try:
            async with self._pool.connection(
                timeout=self._settings.database_pool_timeout_seconds
            ) as connection:
                cursor = await connection.execute(
                    RATE_LIMIT_SQL,
                    (
                        window_seconds,
                        window_seconds,
                        scope,
                        subject_hash,
                        interval,
                    ),
                )
                row = await cursor.fetchone()
        except PsycopgError as error:
            raise SecurityUnavailable("atomic rate limit failed") from error
        if row is None:
            raise SecurityUnavailable("atomic rate limit returned no result")
        count = int(row[0])
        return RateLimitDecision(
            allowed=count <= limit,
            limit=limit,
            remaining=max(0, limit - count),
            retry_after_seconds=int(row[1]),
        )

    def _new_credentials(
        self,
        *,
        installation_id: str,
        refresh_expires_at: datetime,
        now: datetime,
    ) -> IssuedCredentials:
        return IssuedCredentials(
            installation_id=installation_id,
            access_token=_new_token("michi_at"),
            access_expires_at=now + timedelta(seconds=self._settings.access_token_ttl_seconds),
            refresh_token=_new_token("michi_rt"),
            refresh_expires_at=refresh_expires_at,
        )

    async def _insert_credentials(
        self,
        connection: Any,
        credentials: IssuedCredentials,
        *,
        family_id: UUID,
        generation: int,
    ) -> None:
        await connection.execute(
            INSERT_REFRESH_SQL,
            (
                _token_hash(credentials.refresh_token),
                family_id,
                generation,
                credentials.refresh_expires_at,
            ),
        )
        await connection.execute(
            INSERT_ACCESS_SQL,
            (
                _token_hash(credentials.access_token),
                family_id,
                credentials.installation_id,
                credentials.access_expires_at,
            ),
        )

    async def _revoke_family_for_reuse(
        self,
        connection: Any,
        *,
        family_id: UUID,
        installation_id: str,
        correlation_id: UUID,
        client_ip: str,
    ) -> None:
        await connection.execute(
            REVOKE_FAMILY_SQL,
            ("refresh_token_reuse", family_id),
        )
        await connection.execute(REVOKE_FAMILY_ACCESS_SQL, (family_id,))
        await self._audit(
            connection,
            event_type="refresh_token_reuse",
            installation_id=installation_id,
            correlation_id=correlation_id,
            client_ip=client_ip,
            details={},
        )

    async def _audit(
        self,
        connection: Any,
        *,
        event_type: str,
        installation_id: str | None,
        correlation_id: UUID,
        client_ip: str,
        details: dict[str, Any],
    ) -> None:
        await connection.execute(
            INSERT_AUDIT_SQL,
            (
                event_type,
                installation_id,
                correlation_id,
                self._subject_hash("audit_ip", client_ip),
                Jsonb(details),
            ),
        )

    def _subject_hash(self, scope: str, subject: str) -> bytes:
        key = self._settings.rate_limit_hash_secret.encode()
        return hmac.new(
            key,
            f"{scope}\0{subject}".encode(),
            sha256,
        ).digest()


def bearer_token(authorization: str | None) -> str:
    if authorization is None:
        raise AuthenticationRejected(
            "missing_access_token",
            "A Bearer access credential is required.",
        )
    scheme, separator, token = authorization.partition(" ")
    if separator != " " or scheme.lower() != "bearer" or not token.strip():
        raise AuthenticationRejected(
            "invalid_access_token",
            "A valid Bearer access credential is required.",
        )
    return token.strip()


def client_ip(
    *,
    peer_ip: str | None,
    forwarded_for: str | None,
    trusted_proxy_cidrs: str,
) -> str:
    peer = _ip(peer_ip or "0.0.0.0")
    trusted_networks = _trusted_networks(trusted_proxy_cidrs)
    if not any(peer in network for network in trusted_networks):
        return str(peer)
    chain = []
    if forwarded_for:
        for value in forwarded_for.split(","):
            chain.append(_ip(value.strip()))
    chain.append(peer)
    while chain and any(chain[-1] in network for network in trusted_networks):
        chain.pop()
    return str(chain[-1] if chain else peer)


def _trusted_networks(value: str) -> tuple[Any, ...]:
    if not value.strip():
        return ()
    try:
        return tuple(
            ipaddress.ip_network(item.strip(), strict=True)
            for item in value.split(",")
            if item.strip()
        )
    except ValueError as error:
        raise ValueError("trusted_proxy_cidrs contains an invalid network") from error


def _ip(value: str) -> Any:
    try:
        return ipaddress.ip_address(value)
    except ValueError as error:
        raise ValueError("request contains an invalid client IP address") from error


def _new_token(prefix: str) -> str:
    return f"{prefix}_{secrets.token_urlsafe(32)}"


def _token_hash(token: str) -> bytes:
    if len(token) < 40 or len(token) > 256:
        raise ValueError("credential has an invalid length")
    return sha256(token.encode()).digest()
