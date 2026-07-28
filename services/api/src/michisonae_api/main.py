import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from michisonae_api import __version__
from michisonae_api.models import (
    ApiError,
    ObservationBatch,
    ObservationBatchAccepted,
)
from michisonae_api.settings import Settings, get_settings
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
) -> FastAPI:
    app_settings = settings or get_settings()
    store = observation_store
    if store is None and app_settings.database_url:
        store = PostgresObservationStore(app_settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        if store is not None:
            await store.open()
        try:
            yield
        finally:
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

    @application.get("/health/live", tags=["health"])
    async def liveness() -> dict[str, str]:
        return {"status": "live", "version": __version__}

    @application.get(
        "/health/ready",
        tags=["health"],
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

    return application


def unavailable_response(message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={
            "code": "durable_ingestion_unavailable",
            "message": message,
        },
        headers={"Retry-After": "1"},
    )


app = create_app()
