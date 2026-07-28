# MichiSonae API

FastAPI service for anonymous installation/device authentication, durable road
observation ingestion and public hazard reads.

The first slice contains health endpoints plus the ingestion contract. Until a
transactional PostgreSQL store is installed, the ingestion route returns
`503 durable_ingestion_unavailable`; it never gives a false acceptance.

## Local development

```text
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"   # Windows
pytest
ruff check .
uvicorn michisonae_api.main:app --reload
```

Environment variables use the `MICHI_` prefix. Do not put secrets in `.env`
files committed to Git.
