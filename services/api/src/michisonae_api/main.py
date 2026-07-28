import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from email.utils import format_datetime
from typing import Any

from fastapi import FastAPI, Query, Request, Response, status
from fastapi.responses import JSONResponse

from michisonae_api import __version__
from michisonae_api.models import (
    ApiError,
    ObservationBatch,
    ObservationBatchAccepted,
    RegionalHazardSnapshot,
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
) -> FastAPI:
    app_settings = settings or get_settings()
    store = observation_store
    if store is None and app_settings.database_url:
        store = PostgresObservationStore(app_settings)
    snapshots = snapshot_store
    if snapshots is None and app_settings.database_url:
        snapshots = PostgresHazardSnapshotStore(app_settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        if store is not None:
            await store.open()
        if snapshots is not None:
            await snapshots.open()
        try:
            yield
        finally:
            if snapshots is not None:
                await snapshots.close()
            if store is not None:
                await store.close()

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

    @application.get(
        "/health/live",
        tags=["health"],
        operation_id="getLiveness",
    )
    async def liveness() -> dict[str, str]:
        return {"status": "live", "version": __version__}

    @application.get(
        "/health/ready",
        tags=["health"],
        operation_id="getReadiness",
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError}},
    )
    async def readiness(request: Request) -> Any:
        current_store: ObservationStore | None = request.app.state.observation_store
        if current_store is None or not await current_store.ready():
            return unavailable_response(
                "The durable observation database or required schema is unavailable.",
            )
        return {"status": "ready", "version": __version__}

    @application.post(
        "/v1/observations:batch",
        tags=["observations"],
        operation_id="ingestObservationBatch",
        status_code=status.HTTP_202_ACCEPTED,
        response_model=ObservationBatchAccepted,
        responses={
            status.HTTP_409_CONFLICT: {"model": ApiError},
            status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError},
        },
    )
    async def ingest_observations(
        batch: ObservationBatch,
        request: Request,
    ) -> Any:
        current_store: ObservationStore | None = request.app.state.observation_store
        if current_store is None:
            return unavailable_response(
                "Observation ingestion requires a durable database.",
            )

        try:
            result = await current_store.ingest(batch)
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
            logger.exception("Durable observation ingestion failed")
            return unavailable_response(
                "The observation batch was not accepted because durable storage failed.",
            )

        return ObservationBatchAccepted(
            received_count=result.received_count,
            stored_count=result.stored_count,
            duplicate_count=result.duplicate_count,
        )

    @application.get(
        "/v1/regions/{region_id}/hazards",
        tags=["hazards"],
        operation_id="getRegionalHazards",
        response_model=RegionalHazardSnapshot,
        responses={
            status.HTTP_304_NOT_MODIFIED: {"description": "Snapshot is unchanged."},
            status.HTTP_400_BAD_REQUEST: {"model": ApiError},
            status.HTTP_404_NOT_FOUND: {"model": ApiError},
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


app = create_app()
