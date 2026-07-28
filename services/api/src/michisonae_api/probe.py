from __future__ import annotations

import argparse
import asyncio
import json
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
from uuid import UUID, uuid4

from michisonae_api.projection import PostgresProjectionWorker
from michisonae_api.settings import Settings, get_settings
from michisonae_api.snapshots import PostgresSnapshotPublisher

GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"


def geohash(latitude: float, longitude: float, precision: int) -> str:
    latitude_interval = [-90.0, 90.0]
    longitude_interval = [-180.0, 180.0]
    encoded: list[str] = []
    bits = (16, 8, 4, 2, 1)
    bit_index = 0
    character = 0
    use_longitude = True
    while len(encoded) < precision:
        interval = longitude_interval if use_longitude else latitude_interval
        value = longitude if use_longitude else latitude
        midpoint = (interval[0] + interval[1]) / 2
        if value >= midpoint:
            character |= bits[bit_index]
            interval[0] = midpoint
        else:
            interval[1] = midpoint
        use_longitude = not use_longitude
        if bit_index < 4:
            bit_index += 1
        else:
            encoded.append(GEOHASH_ALPHABET[character])
            bit_index = 0
            character = 0
    return "".join(encoded)


def _request(
    base_url: str,
    method: str,
    path: str,
    *,
    correlation_id: UUID,
    payload: dict[str, Any] | None = None,
    access_token: str | None = None,
) -> tuple[int, dict[str, Any] | None]:
    headers = {
        "Accept": "application/json",
        "X-Correlation-ID": str(correlation_id),
    }
    body = None
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    if access_token is not None:
        headers["Authorization"] = f"Bearer {access_token}"
    request = Request(
        urljoin(base_url.rstrip("/") + "/", path.lstrip("/")),
        data=body,
        headers=headers,
        method=method,
    )
    with urlopen(request, timeout=10) as response:
        response_body = response.read()
        decoded = None if not response_body else json.loads(response_body)
        return int(response.status), decoded


async def _project_and_publish(settings: Settings) -> tuple[int, int]:
    projector = PostgresProjectionWorker(
        settings,
        worker_id=f"synthetic-probe-project-{uuid4().hex[:8]}",
    )
    publisher = PostgresSnapshotPublisher(
        settings,
        worker_id=f"synthetic-probe-snapshot-{uuid4().hex[:8]}",
    )
    await asyncio.gather(projector.open(), publisher.open())
    try:
        projected = await projector.drain()
        await publisher.seed_current_regions()
        published = await publisher.drain()
        return projected.projected_count, published.published_count
    finally:
        await asyncio.gather(projector.close(), publisher.close())


def run_probe(
    *,
    settings: Settings,
    base_url: str,
    latitude: float,
    longitude: float,
) -> dict[str, Any]:
    if settings.environment == "production":
        raise ValueError("synthetic upload probes are forbidden in production")
    if not settings.database_url:
        raise ValueError("MICHI_DATABASE_URL is required")
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("base URL must be an absolute HTTP(S) URL")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("base URL must not contain credentials")
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise ValueError("probe coordinates are out of bounds")

    correlation_id = uuid4()
    event_id = uuid4()
    access_token: str | None = None
    try:
        registration_status, registration = _request(
            base_url,
            "POST",
            "/v1/installations:register",
            correlation_id=correlation_id,
            payload={"schema_version": "1.0"},
        )
        if registration_status != 201 or registration is None:
            raise RuntimeError("synthetic registration did not succeed")
        installation_id = str(registration["installation_id"])
        access_token = str(registration["access_token"])
        observation = {
            "event_id": str(event_id),
            "installation_id": installation_id,
            "detected_at": datetime.now(UTC).isoformat(),
            "latitude": latitude,
            "longitude": longitude,
            "location_accuracy_m": 10.0,
            "speed_mps": 5.0,
            "kind": "rough_road",
            "severity": 0.01,
            "confidence": 0.01,
            "source": "phone",
            "detector_version": "synthetic-probe-v1",
        }
        batch = {"schema_version": "1.0", "observations": [observation]}
        upload_status, uploaded = _request(
            base_url,
            "POST",
            "/v1/observations:batch",
            correlation_id=correlation_id,
            payload=batch,
            access_token=access_token,
        )
        retry_status, retried = _request(
            base_url,
            "POST",
            "/v1/observations:batch",
            correlation_id=correlation_id,
            payload=batch,
            access_token=access_token,
        )
        if (
            upload_status != 202
            or retry_status != 202
            or uploaded is None
            or retried is None
            or uploaded.get("stored_count") != 1
            or retried.get("duplicate_count") != 1
        ):
            raise RuntimeError("synthetic idempotent upload did not succeed")

        projected_count, published_count = asyncio.run(_project_and_publish(settings))
        region_cell = geohash(
            latitude,
            longitude,
            settings.snapshot_region_geohash_precision,
        )
        read_status, snapshot = _request(
            base_url,
            "GET",
            (f"/v1/regions/gh{settings.snapshot_region_geohash_precision}:{region_cell}/hazards"),
            correlation_id=correlation_id,
        )
        if read_status != 200 or snapshot is None:
            raise RuntimeError("synthetic regional read did not succeed")
        return {
            "correlation_id": str(correlation_id),
            "upload_stored_count": 1,
            "duplicate_count": 1,
            "projected_count": projected_count,
            "published_count": published_count,
            "read_status": read_status,
            "trip_sequence_retained": False,
            "verified": True,
        }
    finally:
        if access_token is not None:
            _request(
                base_url,
                "DELETE",
                "/v1/installations/current",
                correlation_id=correlation_id,
                access_token=access_token,
            )


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Verify staging register/upload/retry/project/read without creating a trip sequence."
        )
    )
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--latitude", type=float, default=26.1445)
    parser.add_argument("--longitude", type=float, default=91.7362)
    arguments = parser.parse_args()
    result = run_probe(
        settings=get_settings(),
        base_url=arguments.base_url,
        latitude=arguments.latitude,
        longitude=arguments.longitude,
    )
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
