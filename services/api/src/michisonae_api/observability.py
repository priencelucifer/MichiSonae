from __future__ import annotations

import asyncio
import json
import logging
import re
import signal
import threading
from collections.abc import AsyncIterator, Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from time import monotonic
from typing import Any
from uuid import UUID

from prometheus_client import (
    CONTENT_TYPE_LATEST,
    CollectorRegistry,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

CORRELATION_ID: ContextVar[UUID | None] = ContextVar(
    "michisonae_correlation_id",
    default=None,
)
SAFE_LOG_FIELDS = (
    "component",
    "correlation_id",
    "duration_ms",
    "failure_code",
    "method",
    "outcome",
    "received_count",
    "route",
    "status_code",
    "stored_count",
    "worker_kind",
)
SECRET_PATTERN = re.compile(
    r"(?i)\b(authorization|access[_-]?token|refresh[_-]?token|password|secret)"
    r"\b([\"'=:\s]+)([^,\s\"}]+)"
)
BEARER_PATTERN = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/\-=]+")
TOKEN_PATTERN = re.compile(r"\bmichi_(?:at|rt)_[A-Za-z0-9_-]+\b")
DATABASE_URL_PATTERN = re.compile(r"(?i)\b(postgres(?:ql)?://)[^:@/\s]+:[^@/\s]+@")
INSTALLATION_PATTERN = re.compile(r"\bins_[0-9a-fA-F]{16,}\b")
UUID_PATTERN = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\b"
)
LOCATION_FIELD_PATTERN = re.compile(
    r'(?i)(["\']?(?:latitude|longitude|lat|lon)["\']?\s*[:=]\s*)'
    r"-?\d{1,3}(?:\.\d+)?"
)
COORDINATE_PAIR_PATTERN = re.compile(r"(?<!\d)-?\d{1,3}\.\d{4,}\s*[,/]\s*-?\d{1,3}\.\d{4,}(?!\d)")
IPV4_PATTERN = re.compile(
    r"\b(?:25[0-5]|2[0-4]\d|1?\d?\d)"
    r"(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}\b"
)
IPV6_PATTERN = re.compile(r"(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\b")
RATE_LIMIT_SCOPES = {
    "ingestion_installation",
    "ingestion_ip",
    "public_read_ip",
    "refresh_ip",
    "registration_ip",
}
METRIC_COMPONENTS = {"api", "ingestion", "projection", "security", "snapshot", "snapshots"}
ERROR_CODES = {
    "rate_limit_unavailable",
    "shutdown_timeout",
    "store_unavailable",
    "unhandled_exception",
}
WORKER_OUTCOMES = {
    "claimed",
    "dead_letter",
    "projected",
    "published",
    "replayed",
    "retry",
    "unchanged",
}


def redact_text(value: str) -> str:
    redacted = BEARER_PATTERN.sub("Bearer [REDACTED]", value)
    redacted = SECRET_PATTERN.sub(r"\1\2[REDACTED]", redacted)
    redacted = TOKEN_PATTERN.sub("[REDACTED_TOKEN]", redacted)
    redacted = DATABASE_URL_PATTERN.sub(r"\1[REDACTED]@", redacted)
    redacted = INSTALLATION_PATTERN.sub("[REDACTED_INSTALLATION]", redacted)
    redacted = LOCATION_FIELD_PATTERN.sub(r"\1[REDACTED_LOCATION]", redacted)
    redacted = COORDINATE_PAIR_PATTERN.sub("[REDACTED_LOCATION]", redacted)
    redacted = IPV4_PATTERN.sub("[REDACTED_IP]", redacted)
    redacted = IPV6_PATTERN.sub("[REDACTED_IP]", redacted)
    return UUID_PATTERN.sub("[REDACTED_UUID]", redacted)


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        correlation_id = getattr(record, "correlation_id", None) or CORRELATION_ID.get()
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname.lower(),
            "logger": record.name,
            "message": redact_text(record.getMessage()),
        }
        if correlation_id is not None:
            payload["correlation_id"] = str(correlation_id)
        for field in SAFE_LOG_FIELDS:
            if field == "correlation_id":
                continue
            value = getattr(record, field, None)
            if value is not None:
                payload[field] = redact_text(value) if isinstance(value, str) else value
        if record.exc_info is not None:
            payload["exception"] = redact_text(self.formatException(record.exc_info))
        return json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )


def configure_json_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    formatter = JsonLogFormatter()
    if not root.handlers:
        created_handler = logging.StreamHandler()
        root.addHandler(created_handler)
    for root_handler in root.handlers:
        root_handler.setFormatter(formatter)
    for logger_name in ("uvicorn", "uvicorn.access", "uvicorn.error"):
        configured_logger = logging.getLogger(logger_name)
        for configured_handler in configured_logger.handlers:
            configured_handler.setFormatter(formatter)
    logging.getLogger("uvicorn.access").disabled = True
    root.setLevel(level.upper())


@contextmanager
def correlation_scope(correlation_id: UUID) -> Iterator[None]:
    token = CORRELATION_ID.set(correlation_id)
    try:
        yield
    finally:
        CORRELATION_ID.reset(token)


class BackendMetrics:
    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry(auto_describe=True)
        self.http_requests = Counter(
            "michisonae_http_requests_total",
            "HTTP requests by bounded route, method, and status class.",
            ("method", "route", "status_class"),
            registry=self.registry,
        )
        self.http_duration = Histogram(
            "michisonae_http_request_duration_seconds",
            "HTTP request duration by bounded route and method.",
            ("method", "route"),
            buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
            registry=self.registry,
        )
        self.ingestion_observations = Counter(
            "michisonae_ingestion_observations_total",
            "Observation records received, stored, or deduplicated.",
            ("outcome",),
            registry=self.registry,
        )
        self.rate_limit_decisions = Counter(
            "michisonae_rate_limit_decisions_total",
            "Rate-limit decisions by fixed scope and outcome.",
            ("scope", "outcome"),
            registry=self.registry,
        )
        self.errors = Counter(
            "michisonae_errors_total",
            "Sanitized backend failures by bounded component and code.",
            ("component", "code"),
            registry=self.registry,
        )
        self.worker_items = Counter(
            "michisonae_worker_items_total",
            "Worker item outcomes.",
            ("worker_kind", "outcome"),
            registry=self.registry,
        )
        self.queue_pending = Gauge(
            "michisonae_queue_pending",
            "Current pending work by queue.",
            ("queue",),
            registry=self.registry,
        )
        self.queue_dead_letters = Gauge(
            "michisonae_queue_dead_letters",
            "Current dead-letter work by queue.",
            ("queue",),
            registry=self.registry,
        )
        self.queue_oldest_seconds = Gauge(
            "michisonae_queue_oldest_pending_seconds",
            "Age of the oldest pending item by queue.",
            ("queue",),
            registry=self.registry,
        )
        self.pool_values = Gauge(
            "michisonae_database_pool_connections",
            "Database pool values by fixed component and state.",
            ("component", "state"),
            registry=self.registry,
        )
        self.started = Gauge(
            "michisonae_process_started",
            "Whether process startup completed.",
            ("component",),
            registry=self.registry,
        )
        self.ready = Gauge(
            "michisonae_process_ready",
            "Whether process dependencies are ready.",
            ("component",),
            registry=self.registry,
        )

    def observe_http(
        self,
        *,
        method: str,
        route: str,
        status_code: int,
        duration_seconds: float,
    ) -> None:
        method = (
            method
            if method in {"DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"}
            else "OTHER"
        )
        status_class = f"{status_code // 100}xx"
        self.http_requests.labels(method, route, status_class).inc()
        self.http_duration.labels(method, route).observe(duration_seconds)

    def observe_ingestion(
        self,
        *,
        received_count: int,
        stored_count: int,
        duplicate_count: int,
    ) -> None:
        self.ingestion_observations.labels("received").inc(received_count)
        self.ingestion_observations.labels("stored").inc(stored_count)
        self.ingestion_observations.labels("duplicate").inc(duplicate_count)

    def observe_rate_limit(self, scope: str, allowed: bool) -> None:
        scope = scope if scope in RATE_LIMIT_SCOPES else "other"
        self.rate_limit_decisions.labels(
            scope,
            "allowed" if allowed else "rejected",
        ).inc()

    def observe_error(self, component: str, code: str) -> None:
        component = component if component in METRIC_COMPONENTS else "other"
        code = code if code in ERROR_CODES else "other"
        self.errors.labels(component, code).inc()

    def observe_worker(
        self,
        worker_kind: str,
        outcomes: dict[str, int],
    ) -> None:
        worker_kind = worker_kind if worker_kind in {"projection", "snapshot"} else "other"
        for outcome, count in outcomes.items():
            if count:
                outcome = outcome if outcome in WORKER_OUTCOMES else "other"
                self.worker_items.labels(worker_kind, outcome).inc(count)

    def set_queue(
        self,
        queue: str,
        *,
        pending_count: int,
        dead_letter_count: int,
        oldest_pending_seconds: float,
    ) -> None:
        queue = queue if queue in {"projection", "snapshot"} else "other"
        self.queue_pending.labels(queue).set(pending_count)
        self.queue_dead_letters.labels(queue).set(dead_letter_count)
        self.queue_oldest_seconds.labels(queue).set(oldest_pending_seconds)

    def set_pool(self, component: str, stats: dict[str, int]) -> None:
        component = component if component in METRIC_COMPONENTS else "other"
        mapping = {
            "size": stats.get("pool_size", 0),
            "available": stats.get("pool_available", 0),
            "waiting": stats.get("requests_waiting", 0),
        }
        for state, value in mapping.items():
            self.pool_values.labels(component, state).set(value)

    def render(self) -> bytes:
        return generate_latest(self.registry)


class WorkerHealthState:
    def __init__(self, component: str, metrics: BackendMetrics) -> None:
        self.component = component if component in METRIC_COMPONENTS else "other"
        self.metrics = metrics
        self._lock = threading.Lock()
        self._started = False
        self._ready = False

    def set_started(self, value: bool) -> None:
        with self._lock:
            self._started = value
        self.metrics.started.labels(self.component).set(1 if value else 0)

    def set_ready(self, value: bool) -> None:
        with self._lock:
            self._ready = value
        self.metrics.ready.labels(self.component).set(1 if value else 0)

    def snapshot(self) -> tuple[bool, bool]:
        with self._lock:
            return self._started, self._ready


class WorkerHealthServer:
    def __init__(
        self,
        *,
        host: str,
        port: int,
        state: WorkerHealthState,
        metrics: BackendMetrics,
    ) -> None:
        self._state = state
        self._metrics = metrics
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                started, ready = owner._state.snapshot()
                if self.path == "/metrics":
                    self._send(200, owner._metrics.render(), CONTENT_TYPE_LATEST)
                elif self.path == "/health/live":
                    self._send_json(200, {"status": "live"})
                elif self.path == "/health/startup":
                    self._send_json(
                        200 if started else 503,
                        {"status": "started" if started else "starting"},
                    )
                elif self.path == "/health/ready":
                    self._send_json(
                        200 if ready else 503,
                        {"status": "ready" if ready else "unavailable"},
                    )
                else:
                    self._send_json(404, {"status": "not_found"})

            def _send_json(self, status_code: int, payload: dict[str, str]) -> None:
                self._send(
                    status_code,
                    json.dumps(payload, separators=(",", ":")).encode(),
                    "application/json",
                )

            def _send(
                self,
                status_code: int,
                payload: bytes,
                content_type: str,
            ) -> None:
                self.send_response(status_code)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, format: str, *args: Any) -> None:
                del format, args

        self._server = ThreadingHTTPServer((host, port), Handler)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name=f"{state.component}-health",
            daemon=True,
        )

    @property
    def port(self) -> int:
        return int(self._server.server_address[1])

    def start(self) -> None:
        self._thread.start()

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


def install_shutdown_handlers(stop_event: asyncio.Event) -> None:
    loop = asyncio.get_running_loop()
    for received_signal in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(received_signal, stop_event.set)
        except (NotImplementedError, RuntimeError):
            continue


async def graceful_worker_loop(
    run_once: Callable[[], Any],
    *,
    stop_event: asyncio.Event,
    idle_seconds: float,
    idle_when: Callable[[Any], bool] | None = None,
) -> AsyncIterator[Any]:
    while not stop_event.is_set():
        started = monotonic()
        result = await run_once()
        yield result
        if stop_event.is_set():
            return
        if idle_when is not None and not idle_when(result):
            continue
        elapsed = monotonic() - started
        wait_seconds = max(0.0, idle_seconds - elapsed)
        if wait_seconds:
            try:
                await asyncio.wait_for(stop_event.wait(), timeout=wait_seconds)
            except TimeoutError:
                pass
