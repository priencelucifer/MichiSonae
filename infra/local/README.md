# Local infrastructure

The next backend slice will add a pinned local PostgreSQL/PostGIS environment
and migrations together. Avoid introducing a database container without the
schema, health check and reset/restore instructions that consume it.
