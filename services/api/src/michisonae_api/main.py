from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse

from michisonae_api import __version__
from michisonae_api.models import ApiError, ObservationBatch
from michisonae_api.settings import Settings, get_settings


def create_app(settings: Settings | None = None) -> FastAPI:
    app_settings = settings or get_settings()
    application = FastAPI(
        title="MichiSonae API",
        version=__version__,
        docs_url="/docs" if app_settings.environment != "production" else None,
        redoc_url=None,
    )
    application.state.settings = app_settings

    @application.get("/health/live", tags=["health"])
    async def liveness() -> dict[str, str]:
        return {"status": "live", "version": __version__}

    @application.get(
        "/health/ready",
        tags=["health"],
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError}},
    )
    async def readiness(request: Request) -> Any:
        current: Settings = request.app.state.settings
        if not current.durable_ingestion_configured:
            return JSONResponse(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                content={
                    "code": "durable_ingestion_unavailable",
                    "message": "A durable observation store is not configured.",
                },
            )
        return {"status": "ready", "version": __version__}

    @application.post(
        "/v1/observations:batch",
        tags=["observations"],
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        responses={status.HTTP_503_SERVICE_UNAVAILABLE: {"model": ApiError}},
    )
    async def ingest_observations(batch: ObservationBatch) -> JSONResponse:
        # Parsing happens before this guard so malformed clients receive 422.
        # Do not return 2xx until the future database/outbox transaction commits.
        _ = batch
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "code": "durable_ingestion_unavailable",
                "message": "Observation ingestion is disabled until durable storage is configured.",
            },
        )

    return application


app = create_app()
