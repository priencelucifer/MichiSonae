ARG PYTHON_IMAGE=python:3.12.13-slim-bookworm@sha256:d50fb7611f86d04a3b0471b46d7557818d88983fc3136726336b2a4c657aa30b

FROM ${PYTHON_IMAGE} AS builder
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1
WORKDIR /build
COPY services/api/pyproject.toml services/api/requirements.lock services/api/README.md ./
COPY services/api/src ./src
RUN python -m venv /opt/michisonae \
    && /opt/michisonae/bin/python -m pip install --no-compile \
        --requirement requirements.lock \
    && /opt/michisonae/bin/python -m pip install --no-compile --no-deps .

FROM ${PYTHON_IMAGE} AS runtime
ARG VCS_REF=unknown
ARG IMAGE_VERSION=1.0.0
LABEL org.opencontainers.image.title="MichiSonae backend" \
      org.opencontainers.image.description="API, projection, snapshot, and migration roles" \
      org.opencontainers.image.source="https://github.com/priencelucifer/MichiSonae" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.version="${IMAGE_VERSION}"
ENV PATH="/opt/michisonae/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UVICORN_ACCESS_LOG=0
COPY --from=builder /opt/michisonae /opt/michisonae
RUN groupadd --gid 10001 michisonae \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin michisonae
USER 10001:10001
WORKDIR /app
EXPOSE 8000 9101 9102
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "from urllib.request import urlopen; urlopen('http://127.0.0.1:8000/health/live', timeout=2).read()"]
CMD ["uvicorn", "michisonae_api.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log", "--timeout-graceful-shutdown", "30"]
