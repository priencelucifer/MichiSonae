\set ON_ERROR_STOP on

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'michi_api') THEN
        CREATE ROLE michi_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'michi_projection') THEN
        CREATE ROLE michi_projection NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'michi_snapshot') THEN
        CREATE ROLE michi_snapshot NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'michi_maintenance') THEN
        CREATE ROLE michi_maintenance NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'michi_backup') THEN
        CREATE ROLE michi_backup NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO
    michi_api,
    michi_projection,
    michi_snapshot,
    michi_maintenance,
    michi_backup;

GRANT SELECT ON schema_migrations TO
    michi_api,
    michi_projection,
    michi_snapshot,
    michi_maintenance,
    michi_backup;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    anonymous_installations,
    auth_token_families,
    auth_refresh_tokens,
    auth_access_tokens,
    security_audit_events,
    security_rate_limits,
    road_observations,
    observation_outbox
TO michi_api;

GRANT SELECT ON regional_hazard_snapshots, regional_snapshot_heads TO michi_api;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    road_observations,
    observation_outbox,
    hazard_clusters,
    hazard_contributors,
    hazard_projections,
    projection_processed_events,
    retained_contributor_rollups,
    regional_snapshot_work
TO michi_projection;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    hazard_clusters,
    hazard_contributors,
    hazard_projections,
    retained_contributor_rollups,
    regional_snapshot_work,
    regional_hazard_snapshots,
    regional_snapshot_heads
TO michi_snapshot;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO michi_maintenance;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO michi_backup;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO
    michi_api,
    michi_projection,
    michi_snapshot,
    michi_maintenance;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO michi_backup;
