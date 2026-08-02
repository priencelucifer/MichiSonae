import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime
from time import monotonic
from typing import Any
from uuid import UUID, uuid4

from fastapi import FastAPI, Query, Request, Response, status
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST

from michisonae_api import __version__
from michisonae_api.models import (
    AnonymousCredentials,
    ApiError,
    InstallationRegistration,
    ObservationBatch,
    ObservationBatchAccepted,
    RefreshCredentialRequest,
    RegionalHazardSnapshot,
)
from michisonae_api.observability import (
    BackendMetrics,
    configure_json_logging,
    correlation_scope,
)
from michisonae_api.security import (
    AuthenticatedInstallation,
    AuthenticationRejected,
    IssuedCredentials,
    PostgresSecurityService,
    SecurityService,
    SecurityUnavailable,
    bearer_token,
    client_ip,
)
from michisonae_api.settings import Settings, get_settings
from michisonae_api.snapshots import (
    HazardSnapshotStore,
    PostgresHazardSnapshotStore,
    SnapshotRecord,
    SnapshotUnavailable,
    empty_snapshot_content,
    empty_snapshot_etag,
    parse_region_id,
)
from michisonae_api.store import (
    EventIdConflict,
    ObservationStore,
    PostgresObservationStore,
    StoreUnavailable,
)

logger = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    observation_store: ObservationStore | None = None,
    snapshot_store: HazardSnapshotStore | None = None,
    security_service: SecurityService | None = None,
    metrics: BackendMetrics | None = None,
) -> FastAPI:
    app_settings = settings or get_settings()
    if app_settings.json_logging_enabled and app_settings.environment != "test":
        configure_json_logging(app_settings.log_level)
    backend_metrics = metrics or BackendMetrics()
    store = observation_store
    if store is None and app_settings.database_url:
        store = PostgresObservationStore(app_settings)
    snapshots = snapshot_store
    if snapshots is None and app_settings.database_url:
        snapshots = PostgresHazardSnapshotStore(app_settings)
    security = security_service
    if security is None and app_settings.database_url:
        security = PostgresSecurityService(app_settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        opened: list[Any] = []
        try:
            for dependency in (store, snapshots, security):
                if dependency is not None:
                    await dependency.open()
                    opened.append(dependency)
            application.state.started = True
            backend_metrics.started.labels("api").set(1)
            yield
        finally:
            application.state.started = False
            backend_metrics.ready.labels("api").set(0)
            backend_metrics.started.labels("api").set(0)
            try:
                async with asyncio.timeout(app_settings.worker_shutdown_timeout_seconds):
                    for dependency in reversed(opened):
                        await dependency.close()
            except TimeoutError:
                backend_metrics.observe_error("api", "shutdown_timeout")
                logger.error(
                    "api_shutdown_timeout",
                    extra={"component": "api", "failure_code": "shutdown_timeout"},
                )

    application = FastAPI(
        title="MichiSonae API",
        version=__version__,
        docs_url="/docs" if app_settings.environment != "production" else None,
        redoc_url=None,
        lifespan=lifespan,
    )
    application.state.settings = app_settings
    application.state.observation_store = store
    application.state.snapshot_store = snapshots
    application.state.security_service = security
    application.state.metrics = backend_metrics
    application.state.started = False

    @application.middleware("http")
    async def request_guard(request: Request, call_next: Any) -> Response:
        started_at = monotonic()
        correlation_id = _correlation_id(request.headers.get("x-correlation-id"))
        request.state.correlation_id = correlation_id
        method = _method_label(request.method)

        def finish(response: Response) -> Response:
            route = _route_label(request)
            duration_seconds = monotonic() - started_at
            backend_metrics.observe_http(
                method=method,
                route=route,
                status_code=response.status_code,
                duration_seconds=duration_seconds,
            )
            response.headers["X-Correlation-ID"] = str(correlation_id)
            logger.info(
                "http_request_completed",
                extra={
                    "component": "api",
                    "duration_ms": round(duration_seconds * 1000, 3),
                    "method": method,
                    "route": route,
                    "status_code": response.status_code,
                },
            )
            return response

        with correlation_scope(correlation_id):
            try:
                peer_host = None if request.client is None else request.client.host
                if app_settings.environment == "test" and peer_host == "testclient":
                    peer_host = "127.0.0.1"
                request.state.client_ip = client_ip(
                    peer_ip=peer_host,
                    forwarded_for=request.headers.get("x-forwarded-for"),
                    trusted_proxy_cidrs=app_settings.trusted_proxy_cidrs,
                )
            except ValueError:
                return finish(
                    JSONResponse(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        content={
                            "code": "invalid_forwarded_address",
                            "message": "The request forwarding address is invalid.",
                        },
                    )
                )

            if request.method in {"POST", "PUT", "PATCH"}:
                content_type = request.headers.get("content-type", "")
                if content_type.split(";", 1)[0].strip().lower() != "application/json":
                    return finish(
                        JSONResponse(
                            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
                            content={
                                "code": "json_content_type_required",
                                "message": "This endpoint requires application/json.",
                            },
                        )
                    )
                length_header = request.headers.get("content-length")
                if length_header is not None:
                    try:
                        declared_length = int(length_header)
                    except ValueError:
                        declared_length = app_settings.maximum_request_bytes + 1
                    if declared_length < 0 or declared_length > app_settings.maximum_request_bytes:
                        return finish(_request_too_large())
                body = await request.body()
                if len(body) > app_settings.maximum_request_bytes:
                    return finish(_request_too_large())

            try:
                response = await call_next(request)
            except Exception:
                route = _route_label(request)
                backend_metrics.observe_error("api", "unhandled_exception")
                logger.exception(
                    "http_request_failed",
                    extra={
                        "component": "api",
                        "method": method,
                        "route": route,
                    },
                )
                raise
            return finish(response)

    @application.get(
        "/health/live",
        tags=["health"],
        operation_id="getLiveness",
    )
    async def liveness() -> dict[str, str]:
        return {"status": "live", "version": __version__}

    @application.get("/health/startup", include_in_schema=False)
    async def startup(request: Request) -> Any:
        if not request.app.state.started:
            return unavailable_response(
                "The API process is still starting.",
                code="startup_incomplete",
            )
        return {"status": "started", "version": __version__}

    @application.get(
        "/health/ready",
        tags=["health"],
        operation_id="getReadiness",
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError}},
    )
    async def readiness(request: Request) -> Any:
        current_store: ObservationStore | None = request.app.state.observation_store
        current_snapshots: HazardSnapshotStore | None = request.app.state.snapshot_store
        current_security: SecurityService | None = request.app.state.security_service
        if current_store is None:
            return unavailable_response(
                "The durable observation database or required schema is unavailable.",
            )
        dependencies = tuple(
            dependency
            for dependency in (
                current_store,
                current_snapshots,
                current_security,
            )
            if dependency is not None
        )
        ready = all(await asyncio.gather(*(item.ready() for item in dependencies)))
        backend_metrics.ready.labels("api").set(1 if ready else 0)
        if not ready:
            return unavailable_response(
                "A required database pool, dependency, or schema is unavailable.",
            )
        return {"status": "ready", "version": __version__}

    @application.get("/metrics", include_in_schema=False)
    async def metrics_endpoint(request: Request) -> Response:
        for component, dependency in (
            ("ingestion", request.app.state.observation_store),
            ("snapshots", request.app.state.snapshot_store),
            ("security", request.app.state.security_service),
        ):
            pool_stats = getattr(dependency, "pool_stats", None)
            if callable(pool_stats):
                backend_metrics.set_pool(component, pool_stats())
        return Response(
            content=backend_metrics.render(),
            media_type=CONTENT_TYPE_LATEST,
        )

    @application.post(
        "/v1/observations:batch",
        tags=["observations"],
        operation_id="ingestObservationBatch",
        status_code=status.HTTP_202_ACCEPTED,
        response_model=ObservationBatchAccepted,
        responses={
            status.HTTP_401_UNAUTHORIZED: {"model": ApiError},
            status.HTTP_403_FORBIDDEN: {"model": ApiError},
            status.HTTP_409_CONFLICT: {"model": ApiError},
            status.HTTP_413_CONTENT_TOO_LARGE: {"model": ApiError},
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE: {"model": ApiError},
            status.HTTP_429_TOO_MANY_REQUESTS: {"model": ApiError},
            status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError},
        },
    )
    async def ingest_observations(
        batch: ObservationBatch,
        request: Request,
    ) -> Any:
        current_security: SecurityService | None = request.app.state.security_service
        if current_security is None:
            return unavailable_response(
                "Authenticated ingestion requires the security database.",
                code="security_service_unavailable",
            )
        ip_decision = await _rate_limit(
            current_security,
            backend_metrics,
            scope="ingestion_ip",
            subject=request.state.client_ip,
            limit=app_settings.ingestion_rate_limit_per_minute,
            window_seconds=60,
        )
        if ip_decision is not None:
            return ip_decision
        principal = await _authenticate_request(current_security, request)
        if isinstance(principal, JSONResponse):
            return principal
        installation_decision = await _rate_limit(
            current_security,
            backend_metrics,
            scope="ingestion_installation",
            subject=principal.installation_id,
            limit=app_settings.ingestion_rate_limit_per_minute,
            window_seconds=60,
        )
        if installation_decision is not None:
            return installation_decision
        identity_error = _observation_identity_error(batch, principal)
        if identity_error is not None:
            return identity_error
        time_error = _observation_time_error(batch, app_settings)
        if time_error is not None:
            return time_error

        current_store: ObservationStore | None = request.app.state.observation_store
        if current_store is None:
            return unavailable_response(
                "Observation ingestion requires a durable database.",
            )

        try:
            result = await current_store.ingest(
                batch,
                request.state.correlation_id,
            )
        except EventIdConflict:
            return JSONResponse(
                status_code=status.HTTP_409_CONFLICT,
                content={
                    "code": "event_id_conflict",
                    "message": (
                        "An event_id was already stored with different content; "
                        "the entire batch was rejected."
                    ),
                },
            )
        except StoreUnavailable:
            backend_metrics.observe_error("ingestion", "store_unavailable")
            logger.exception("Durable observation ingestion failed")
            return unavailable_response(
                "The observation batch was not accepted because durable storage failed.",
            )

        backend_metrics.observe_ingestion(
            received_count=result.received_count,
            stored_count=result.stored_count,
            duplicate_count=result.duplicate_count,
        )
        return ObservationBatchAccepted(
            schema_version="1.0",
            received_count=result.received_count,
            stored_count=result.stored_count,
            duplicate_count=result.duplicate_count,
        )

    @application.post(
        "/v1/installations:register",
        tags=["authentication"],
        operation_id="registerAnonymousInstallation",
        status_code=status.HTTP_201_CREATED,
        response_model=AnonymousCredentials,
        responses={
            status.HTTP_413_CONTENT_TOO_LARGE: {"model": ApiError},
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE: {"model": ApiError},
            status.HTTP_429_TOO_MANY_REQUESTS: {"model": ApiError},
            status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError},
        },
    )
    async def register_installation(
        registration: InstallationRegistration,
        request: Request,
    ) -> Any:
        current_security: SecurityService | None = request.app.state.security_service
        if current_security is None:
            return unavailable_response(
                "Anonymous registration requires the security database.",
                code="security_service_unavailable",
            )
        rate_response = await _rate_limit(
            current_security,
            backend_metrics,
            scope="registration_ip",
            subject=request.state.client_ip,
            limit=app_settings.registration_rate_limit_per_hour,
            window_seconds=3600,
        )
        if rate_response is not None:
            return rate_response
        try:
            credentials = await current_security.register(
                attestation=registration.attestation,
                correlation_id=request.state.correlation_id,
                client_ip=request.state.client_ip,
            )
        except SecurityUnavailable:
            logger.exception("Anonymous installation registration failed")
            return unavailable_response(
                "Anonymous registration is temporarily unavailable.",
                code="security_service_unavailable",
            )
        return _credential_response(credentials, status.HTTP_201_CREATED)

    @application.post(
        "/v1/auth:refresh",
        tags=["authentication"],
        operation_id="refreshAnonymousCredentials",
        response_model=AnonymousCredentials,
        responses={
            status.HTTP_401_UNAUTHORIZED: {"model": ApiError},
            status.HTTP_413_CONTENT_TOO_LARGE: {"model": ApiError},
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE: {"model": ApiError},
            status.HTTP_429_TOO_MANY_REQUESTS: {"model": ApiError},
            status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError},
        },
    )
    async def refresh_credentials(
        refresh: RefreshCredentialRequest,
        request: Request,
    ) -> Any:
        current_security: SecurityService | None = request.app.state.security_service
        if current_security is None:
            return unavailable_response(
                "Credential refresh requires the security database.",
                code="security_service_unavailable",
            )
        rate_response = await _rate_limit(
            current_security,
            backend_metrics,
            scope="refresh_ip",
            subject=request.state.client_ip,
            limit=app_settings.refresh_rate_limit_per_minute,
            window_seconds=60,
        )
        if rate_response is not None:
            return rate_response
        try:
            credentials = await current_security.refresh(
                refresh_token=refresh.refresh_token,
                correlation_id=request.state.correlation_id,
                client_ip=request.state.client_ip,
            )
        except AuthenticationRejected as error:
            return _authentication_error(error)
        except SecurityUnavailable:
            logger.exception("Anonymous credential refresh failed")
            return unavailable_response(
                "Credential refresh is temporarily unavailable.",
                code="security_service_unavailable",
            )
        return _credential_response(credentials)

    @application.delete(
        "/v1/installations/current",
        tags=["authentication"],
        operation_id="revokeAnonymousInstallation",
        status_code=status.HTTP_204_NO_CONTENT,
        response_class=Response,
        response_model=None,
        responses={
            status.HTTP_401_UNAUTHORIZED: {"model": ApiError},
            status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError},
        },
    )
    async def revoke_installation(request: Request) -> Any:
        current_security: SecurityService | None = request.app.state.security_service
        if current_security is None:
            return unavailable_response(
                "Installation revocation requires the security database.",
                code="security_service_unavailable",
            )
        principal = await _authenticate_request(current_security, request)
        if isinstance(principal, JSONResponse):
            return principal
        try:
            await current_security.revoke(
                principal,
                correlation_id=request.state.correlation_id,
                client_ip=request.state.client_ip,
            )
        except SecurityUnavailable:
            logger.exception("Anonymous installation revocation failed")
            return unavailable_response(
                "Installation revocation is temporarily unavailable.",
                code="security_service_unavailable",
            )
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @application.get(
        "/v1/regions/{region_id}/hazards",
        tags=["hazards"],
        operation_id="getRegionalHazards",
        response_model=RegionalHazardSnapshot,
        responses={
            status.HTTP_304_NOT_MODIFIED: {"description": "Snapshot is unchanged."},
            status.HTTP_400_BAD_REQUEST: {"model": ApiError},
            status.HTTP_404_NOT_FOUND: {"model": ApiError},
            status.HTTP_429_TOO_MANY_REQUESTS: {"model": ApiError},
            status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError},
        },
    )
    async def get_region_hazards(
        region_id: str,
        request: Request,
        version: str | None = Query(
            default=None,
            pattern=r"^[0-9a-f]{64}$",
            description="Immutable content-addressed snapshot version.",
        ),
    ) -> Any:
        region_cell = parse_region_id(
            region_id,
            app_settings.snapshot_region_geohash_precision,
        )
        if region_cell is None:
            return JSONResponse(
                status_code=status.HTTP_400_BAD_REQUEST,
                content={
                    "code": "invalid_region_id",
                    "message": (
                        "region_id must use the configured global geohash form "
                        f"gh{app_settings.snapshot_region_geohash_precision}:"
                        "<cell>."
                    ),
                },
            )

        current_security: SecurityService | None = request.app.state.security_service
        if current_security is not None:
            rate_response = await _rate_limit(
                current_security,
                backend_metrics,
                scope="public_read_ip",
                subject=request.state.client_ip,
                limit=app_settings.public_read_rate_limit_per_minute,
                window_seconds=60,
            )
            if rate_response is not None:
                return rate_response

        current_store: HazardSnapshotStore | None = request.app.state.snapshot_store
        if current_store is None:
            return unavailable_response(
                "Regional hazard snapshots require a durable database.",
                code="hazard_snapshot_unavailable",
            )
        try:
            snapshot = await current_store.get(region_id, version)
        except SnapshotUnavailable:
            logger.exception("Regional hazard snapshot read failed")
            return unavailable_response(
                "The regional hazard snapshot is temporarily unavailable.",
                code="hazard_snapshot_unavailable",
            )

        if snapshot is None and version is not None:
            return JSONResponse(
                status_code=status.HTTP_404_NOT_FOUND,
                content={
                    "code": "snapshot_version_not_found",
                    "message": "The requested immutable regional snapshot does not exist.",
                },
                headers={"Cache-Control": "public, max-age=30"},
            )

        if snapshot is None:
            etag = empty_snapshot_etag(region_id)
            headers = _snapshot_headers(
                app_settings,
                etag,
                immutable=False,
                generated_at=None,
            )
            if _etag_matches(request.headers.get("if-none-match"), etag):
                return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)
            content = empty_snapshot_content(region_id)
            response = RegionalHazardSnapshot.model_validate(
                {
                    **content,
                    "version": None,
                    "generated_at": None,
                    "source_updated_at": None,
                    "hazard_count": 0,
                }
            )
            return JSONResponse(
                content=response.model_dump(mode="json"),
                headers=headers,
            )

        headers = _snapshot_headers(
            app_settings,
            snapshot.version,
            immutable=version is not None,
            generated_at=snapshot.generated_at,
        )
        if _etag_matches(request.headers.get("if-none-match"), snapshot.version):
            return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)
        response = _snapshot_response(snapshot)
        return JSONResponse(
            content=response.model_dump(mode="json"),
            headers=headers,
        )

    return application


def unavailable_response(
    message: str,
    *,
    code: str = "durable_ingestion_unavailable",
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={
            "code": code,
            "message": message,
        },
        headers={"Retry-After": "1"},
    )


def _snapshot_response(snapshot: SnapshotRecord) -> RegionalHazardSnapshot:
    return RegionalHazardSnapshot.model_validate(
        {
            **snapshot.payload,
            "version": snapshot.version,
            "generated_at": snapshot.generated_at,
            "source_updated_at": snapshot.source_updated_at,
            "hazard_count": snapshot.hazard_count,
        }
    )


def _snapshot_headers(
    settings: Settings,
    etag: str,
    *,
    immutable: bool,
    generated_at: datetime | None,
) -> dict[str, str]:
    if immutable:
        cache_control = "public, max-age=31536000, immutable"
    elif generated_at is None:
        cache_control = "public, max-age=30"
    else:
        cache_control = (
            f"public, max-age={settings.snapshot_cache_max_age_seconds}, "
            "stale-while-revalidate="
            f"{settings.snapshot_stale_while_revalidate_seconds}"
        )
    headers = {
        "ETag": f'"{etag}"',
        "Cache-Control": cache_control,
        "Vary": "Accept-Encoding",
    }
    if generated_at is not None:
        normalized = generated_at.astimezone(UTC)
        headers["Last-Modified"] = format_datetime(normalized, usegmt=True)
        age = (datetime.now(UTC) - normalized).total_seconds()
        headers["X-Snapshot-Freshness"] = (
            "stale" if age > settings.snapshot_stale_after_seconds else "current"
        )
    else:
        headers["X-Snapshot-Freshness"] = "unavailable"
    return headers


def _etag_matches(header: str | None, etag: str) -> bool:
    if header is None:
        return False
    for candidate in header.split(","):
        normalized = candidate.strip()
        if normalized == "*":
            return True
        if normalized.startswith("W/"):
            normalized = normalized[2:].strip()
        if normalized.startswith('"') and normalized.endswith('"'):
            normalized = normalized[1:-1]
        if normalized == etag:
            return True
    return False


async def _authenticate_request(
    security: SecurityService,
    request: Request,
) -> AuthenticatedInstallation | JSONResponse:
    try:
        token = bearer_token(request.headers.get("authorization"))
        return await security.authenticate(token)
    except AuthenticationRejected as error:
        return _authentication_error(error)
    except SecurityUnavailable:
        logger.exception("Access credential lookup failed")
        return unavailable_response(
            "Authentication is temporarily unavailable.",
            code="security_service_unavailable",
        )


async def _rate_limit(
    security: SecurityService,
    metrics: BackendMetrics,
    *,
    scope: str,
    subject: str,
    limit: int,
    window_seconds: int,
) -> JSONResponse | None:
    try:
        decision = await security.check_rate_limit(
            scope=scope,
            subject=subject,
            limit=limit,
            window_seconds=window_seconds,
        )
    except SecurityUnavailable:
        metrics.observe_error("security", "rate_limit_unavailable")
        logger.exception("Atomic rate limit failed")
        return unavailable_response(
            "Abuse protection is temporarily unavailable.",
            code="security_service_unavailable",
        )
    metrics.observe_rate_limit(scope, decision.allowed)
    if decision.allowed:
        return None
    return JSONResponse(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        content={
            "code": "rate_limit_exceeded",
            "message": "Too many requests; retry after the stated delay.",
        },
        headers={
            "Retry-After": str(decision.retry_after_seconds),
            "X-RateLimit-Limit": str(decision.limit),
            "X-RateLimit-Remaining": "0",
        },
    )


def _credential_response(
    credentials: IssuedCredentials,
    response_status: int = status.HTTP_200_OK,
) -> JSONResponse:
    body = AnonymousCredentials(
        installation_id=credentials.installation_id,
        access_token=credentials.access_token,
        access_expires_at=credentials.access_expires_at,
        refresh_token=credentials.refresh_token,
        refresh_expires_at=credentials.refresh_expires_at,
    )
    return JSONResponse(
        status_code=response_status,
        content=body.model_dump(mode="json"),
        headers={
            "Cache-Control": "no-store",
            "Pragma": "no-cache",
        },
    )


def _authentication_error(error: AuthenticationRejected) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_401_UNAUTHORIZED,
        content={"code": error.code, "message": error.message},
        headers={
            "WWW-Authenticate": "Bearer",
            "Cache-Control": "no-store",
        },
    )


def _observation_identity_error(
    batch: ObservationBatch,
    principal: AuthenticatedInstallation,
) -> JSONResponse | None:
    if any(
        observation.installation_id != principal.installation_id
        for observation in batch.observations
    ):
        return JSONResponse(
            status_code=status.HTTP_403_FORBIDDEN,
            content={
                "code": "installation_identity_mismatch",
                "message": ("Every observation must belong to the authenticated installation."),
            },
        )
    return None


def _observation_time_error(
    batch: ObservationBatch,
    settings: Settings,
) -> JSONResponse | None:
    now = datetime.now(UTC)
    oldest = now - timedelta(seconds=settings.observation_maximum_age_seconds)
    newest = now + timedelta(seconds=settings.observation_future_skew_seconds)
    if any(
        observation.detected_at < oldest or observation.detected_at > newest
        for observation in batch.observations
    ):
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            content={
                "code": "observation_time_out_of_bounds",
                "message": (
                    "Observation time is outside the accepted offline or clock-skew window."
                ),
            },
        )
    return None


def _request_too_large() -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_413_CONTENT_TOO_LARGE,
        content={
            "code": "request_too_large",
            "message": "The request exceeds the configured size limit.",
        },
    )


def _correlation_id(value: str | None) -> UUID:
    if value is not None:
        try:
            return UUID(value)
        except ValueError:
            pass
    return uuid4()


def _method_label(method: str) -> str:
    normalized = method.upper()
    if normalized in {"DELETE", "GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH"}:
        return normalized
    return "OTHER"


def _route_label(request: Request) -> str:
    route = request.scope.get("route")
    template = getattr(route, "path", None)
    if isinstance(template, str):
        return template
    return "request_guard"


app = create_app()
