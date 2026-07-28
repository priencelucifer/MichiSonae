CREATE INDEX road_observations_retention_brin
    ON public.road_observations USING brin (received_at)
    WITH (pages_per_range = 32);
CREATE INDEX observation_outbox_published_idx
    ON public.observation_outbox (published_at, observation_event_id)
    WHERE published_at IS NOT NULL;

CREATE TABLE public.retained_contributor_rollups (
    cluster_key text NOT NULL,
    installation_id varchar(128) NOT NULL,
    first_detected_at timestamptz NOT NULL,
    observation_count bigint NOT NULL CHECK (observation_count > 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (cluster_key, installation_id)
);

ALTER TABLE public.observation_outbox
    ADD COLUMN quarantined_at timestamptz,
    ADD COLUMN quarantine_reason text,
    ADD CONSTRAINT observation_outbox_quarantine_pair_check
        CHECK ((quarantined_at IS NULL) = (quarantine_reason IS NULL)),
    ADD CONSTRAINT observation_outbox_quarantine_dead_check
        CHECK (quarantined_at IS NULL OR dead_lettered_at IS NOT NULL);

ALTER TABLE public.regional_snapshot_work
    ADD COLUMN quarantined_at timestamptz,
    ADD COLUMN quarantine_reason text,
    ADD CONSTRAINT regional_snapshot_work_quarantine_pair_check
        CHECK ((quarantined_at IS NULL) = (quarantine_reason IS NULL)),
    ADD CONSTRAINT regional_snapshot_work_quarantine_dead_check
        CHECK (quarantined_at IS NULL OR dead_lettered_at IS NOT NULL);

CREATE TABLE public.operations_audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    command_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    action text NOT NULL,
    mode text NOT NULL CHECK (mode IN ('dry_run', 'apply')),
    outcome text NOT NULL CHECK (outcome IN ('completed', 'no_op', 'failed')),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX operations_audit_events_command_idx
    ON public.operations_audit_events (command_id, occurred_at);
CREATE INDEX operations_audit_events_occurred_idx
    ON public.operations_audit_events (occurred_at DESC);
