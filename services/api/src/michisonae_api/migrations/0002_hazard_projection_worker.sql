ALTER TABLE public.observation_outbox
    ADD COLUMN claimed_by text,
    ADD COLUMN claimed_until timestamptz,
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN dead_lettered_at timestamptz,
    ADD COLUMN dead_letter_reason text,
    ADD CONSTRAINT observation_outbox_claim_pair_check
        CHECK ((claimed_by IS NULL) = (claimed_until IS NULL)),
    ADD CONSTRAINT observation_outbox_terminal_state_check
        CHECK (NOT (published_at IS NOT NULL AND dead_lettered_at IS NOT NULL));

DROP INDEX public.observation_outbox_pending_idx;
CREATE INDEX observation_outbox_claimable_idx
    ON public.observation_outbox (next_attempt_at, id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
CREATE INDEX observation_outbox_claim_expiry_idx
    ON public.observation_outbox (claimed_until)
    WHERE published_at IS NULL
      AND dead_lettered_at IS NULL
      AND claimed_until IS NOT NULL;

CREATE TABLE public.hazard_clusters (
    cluster_key text PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('road_damage', 'rough_road')),
    spatial_cell text NOT NULL,
    road_segment_id text,
    match_state text NOT NULL DEFAULT 'unmatched'
        CHECK (match_state IN ('unmatched', 'map_matched', 'operator_verified')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (kind, spatial_cell)
);

CREATE TABLE public.hazard_contributors (
    cluster_key text NOT NULL
        REFERENCES public.hazard_clusters(cluster_key) ON DELETE CASCADE,
    installation_id varchar(128) NOT NULL,
    latest_event_id uuid NOT NULL
        REFERENCES public.road_observations(event_id) ON DELETE RESTRICT,
    first_detected_at timestamptz NOT NULL,
    last_detected_at timestamptz NOT NULL,
    latest_location geography(Point, 4326) NOT NULL,
    latest_severity real NOT NULL CHECK (latest_severity BETWEEN 0 AND 1),
    latest_confidence real NOT NULL CHECK (latest_confidence BETWEEN 0 AND 1),
    observation_count integer NOT NULL DEFAULT 1
        CHECK (observation_count > 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (cluster_key, installation_id),
    CHECK (last_detected_at >= first_detected_at)
);

CREATE INDEX hazard_contributors_location_gix
    ON public.hazard_contributors USING gist (latest_location);

CREATE TABLE public.hazard_projections (
    cluster_key text PRIMARY KEY
        REFERENCES public.hazard_clusters(cluster_key) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('road_damage', 'rough_road')),
    road_segment_id text,
    match_state text NOT NULL
        CHECK (match_state IN ('unmatched', 'map_matched', 'operator_verified')),
    location geography(Point, 4326) NOT NULL,
    severity real NOT NULL CHECK (severity BETWEEN 0 AND 1),
    confidence real NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    contributor_count integer NOT NULL CHECK (contributor_count > 0),
    lifecycle_state text NOT NULL CHECK (
        lifecycle_state IN (
            'community_unverified',
            'provisional',
            'confirmed',
            'resolved'
        )
    ),
    first_detected_at timestamptz NOT NULL,
    last_detected_at timestamptz NOT NULL,
    policy_version text NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (last_detected_at >= first_detected_at)
);

CREATE INDEX hazard_projections_location_gix
    ON public.hazard_projections USING gist (location);
CREATE INDEX hazard_projections_current_idx
    ON public.hazard_projections (lifecycle_state, last_detected_at DESC);

CREATE TABLE public.projection_processed_events (
    event_id uuid PRIMARY KEY
        REFERENCES public.road_observations(event_id) ON DELETE RESTRICT,
    outbox_id bigint NOT NULL UNIQUE
        REFERENCES public.observation_outbox(id) ON DELETE RESTRICT,
    cluster_key text NOT NULL
        REFERENCES public.hazard_clusters(cluster_key) ON DELETE RESTRICT,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
