from __future__ import annotations

import asyncio
import json
import logging
import signal
import sys
from urllib.error import HTTPError
from urllib.request import urlopen
from uuid import uuid4

import pytest
from fakes import (
    MemoryObservationStore,
    MemorySecurityService,
    MemorySnapshotStore,
)
from fastapi.testclient import TestClient

from michisonae_api.main import create_app
from michisonae_api.observability import (
    BackendMetrics,
    JsonLogFormatter,
    WorkerHealthServer,
    WorkerHealthState,
    graceful_worker_loop,
    install_shutdown_handlers,
)
from michisonae_api.probe import geohash, run_probe
from michisonae_api.settings import Settings


def test_json_logs_redact_credentials_installations_locations_and_event_ids() -> None:
    correlation_id = uuid4()
    event_id = uuid4()
    try:
        raise RuntimeError("password=hunter2 at 26.1445,91.7362")
    except RuntimeError:
        exc_info = sys.exc_info()
    record = logging.LogRecord(
        name="michisonae.test",
        level=logging.ERROR,
        pathname=__file__,
        lineno=1,
        msg=(
            "authorization=Bearer top-secret "
            "access_token=michi_at_abcdef "
            "installation=ins_0123456789abcdef0123456789abcdef "
            f"event={event_id} latitude=26.1445 longitude=91.7362 "
            "client_ip=203.0.113.42"
        ),
        args=(),
        exc_info=exc_info,
    )
    record.correlation_id = correlation_id

    payload = json.loads(JsonLogFormatter().format(record))
    encoded = json.dumps(payload)

    assert payload["correlation_id"] == str(correlation_id)
    assert "top-secret" not in encoded
    assert "michi_at_abcdef" not in encoded
    assert "ins_0123456789abcdef" not in encoded
    assert str(event_id) not in encoded
    assert "26.1445" not in encoded
    assert "91.7362" not in encoded
    assert "203.0.113.42" not in encoded
    assert "hunter2" not in encoded


def test_metrics_use_route_templates_and_never_identity_labels() -> None:
    metrics = BackendMetrics()
    arbitrary_component = f"ins_{uuid4().hex}"
    arbitrary_code = str(uuid4())
    metrics.observe_error(arbitrary_component, arbitrary_code)
    metrics.observe_ingestion(
        received_count=3,
        stored_count=2,
        duplicate_count=1,
    )
    security = MemorySecurityService()
    with TestClient(
        create_app(
            Settings(environment="test"),
            observation_store=MemoryObservationStore(),
            snapshot_store=MemorySnapshotStore(),
            security_service=security,
            metrics=metrics,
        )
    ) as client:
        response = client.get(
            "/v1/regions/gh5:wh9hx/hazards",
            headers={"X-Correlation-ID": str(uuid4())},
        )
        exposition = client.get("/metrics").text

    assert response.status_code == 200
    assert 'route="/v1/regions/{region_id}/hazards"' in exposition
    assert "gh5:wh9hx" not in exposition
    assert security.installation_id not in exposition
    assert arbitrary_component not in exposition
    assert arbitrary_code not in exposition
    assert 'michisonae_errors_total{code="other",component="other"} 1.0' in exposition
    assert 'michisonae_ingestion_observations_total{outcome="stored"} 2.0' in exposition
    assert "michisonae_rate_limit_decisions_total" in exposition
    assert 'scope="public_read_ip"' in exposition


def test_api_startup_readiness_and_liveness_are_separate() -> None:
    with TestClient(
        create_app(
            Settings(environment="test"),
            observation_store=MemoryObservationStore(),
            snapshot_store=MemorySnapshotStore(),
            security_service=MemorySecurityService(),
        )
    ) as client:
        startup = client.get("/health/startup")
        ready = client.get("/health/ready")
        live = client.get("/health/live")

    assert startup.status_code == 200
    assert startup.json()["status"] == "started"
    assert ready.status_code == 200
    assert live.status_code == 200


def test_worker_health_server_tracks_startup_readiness_and_metrics() -> None:
    metrics = BackendMetrics()
    state = WorkerHealthState("projection", metrics)
    server = WorkerHealthServer(
        host="127.0.0.1",
        port=0,
        state=state,
        metrics=metrics,
    )
    server.start()
    try:
        live = urlopen(
            f"http://127.0.0.1:{server.port}/health/live",
            timeout=2,
        )
        try:
            urlopen(
                f"http://127.0.0.1:{server.port}/health/ready",
                timeout=2,
            )
        except HTTPError as error:
            assert error.code == 503
        state.set_started(True)
        state.set_ready(True)
        ready = urlopen(
            f"http://127.0.0.1:{server.port}/health/ready",
            timeout=2,
        )
        exposition = urlopen(
            f"http://127.0.0.1:{server.port}/metrics",
            timeout=2,
        ).read()
    finally:
        server.close()

    assert live.status == 200
    assert ready.status == 200
    assert b'michisonae_process_ready{component="projection"} 1.0' in exposition


def test_graceful_loop_finishes_in_flight_work_before_stopping() -> None:
    async def exercise() -> tuple[list[str], list[str]]:
        stop_event = asyncio.Event()
        effects: list[str] = []

        async def run_once() -> str:
            effects.append("started")
            await asyncio.sleep(0)
            stop_event.set()
            effects.append("committed")
            return "completed"

        results = [
            result
            async for result in graceful_worker_loop(
                run_once,
                stop_event=stop_event,
                idle_seconds=0.01,
            )
        ]
        return effects, results

    effects, results = asyncio.run(exercise())

    assert effects == ["started", "committed"]
    assert results == ["completed"]


def test_sigterm_is_wired_to_the_graceful_stop_event(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    registered: dict[signal.Signals, object] = {}

    class FakeLoop:
        def add_signal_handler(
            self,
            received_signal: signal.Signals,
            callback: object,
        ) -> None:
            registered[received_signal] = callback

    monkeypatch.setattr(asyncio, "get_running_loop", lambda: FakeLoop())
    install_shutdown_handlers(asyncio.Event())

    assert signal.SIGTERM in registered


def test_synthetic_probe_is_geospatially_deterministic_and_forbidden_in_production() -> None:
    assert geohash(26.1445, 91.7362, 5) == "wh9hx"

    with pytest.raises(ValueError, match="forbidden in production"):
        run_probe(
            settings=Settings(
                environment="production",
                database_url="postgresql://unused/probe",
                rate_limit_hash_secret="a-production-secret-that-is-long-enough",
            ),
            base_url="https://example.invalid",
            latitude=26.1445,
            longitude=91.7362,
        )

    with pytest.raises(ValueError, match="json_logging_enabled"):
        Settings(
            environment="production",
            rate_limit_hash_secret="a-production-secret-that-is-long-enough",
            json_logging_enabled=False,
        )
