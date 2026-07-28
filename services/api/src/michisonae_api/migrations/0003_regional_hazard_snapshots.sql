CREATE TABLE public.regional_snapshot_work (
    region_id varchar(32) PRIMARY KEY,
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    claimed_generation bigint,
    dirty_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claimed_by text,
    claimed_until timestamptz,
    delivery_attempts integer NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_attempt_at timestamptz,
    last_error text,
    dead_lettered_at timestamptz,
    dead_letter_reason text,
    CHECK (region_id ~ '^gh[1-9][0-9]?:[0123456789bcdefghjkmnpqrstuvwxyz]+$'),
    CHECK ((claimed_by IS NULL) = (claimed_until IS NULL)),
    CHECK ((claimed_by IS NULL) = (claimed_generation IS NULL))
);

CREATE INDEX regional_snapshot_work_claimable_idx
    ON public.regional_snapshot_work (next_attempt_at, dirty_at, region_id)
    WHERE dead_lettered_at IS NULL;

CREATE TABLE public.regional_hazard_snapshots (
    region_id varchar(32) NOT NULL,
    version char(64) NOT NULL,
    content_hash bytea NOT NULL CHECK (octet_length(content_hash) = 32),
    schema_version text NOT NULL DEFAULT '1.0' CHECK (schema_version = '1.0'),
    generated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    source_updated_at timestamptz,
    hazard_count integer NOT NULL CHECK (hazard_count >= 0),
    payload jsonb NOT NULL,
    PRIMARY KEY (region_id, version),
    UNIQUE (region_id, content_hash),
    CHECK (version = encode(content_hash, 'hex'))
);

CREATE INDEX regional_hazard_snapshots_generated_idx
    ON public.regional_hazard_snapshots (region_id, generated_at DESC);

CREATE TABLE public.regional_snapshot_heads (
    region_id varchar(32) PRIMARY KEY,
    version char(64) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (region_id, version)
        REFERENCES public.regional_hazard_snapshots(region_id, version)
        ON DELETE RESTRICT
);

INSERT INTO public.regional_snapshot_work (region_id)
SELECT DISTINCT 'gh5:' || left(spatial_cell, 5)
FROM public.hazard_clusters
ON CONFLICT (region_id) DO NOTHING;
