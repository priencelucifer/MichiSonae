CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE public.road_observations (
    event_id uuid PRIMARY KEY,
    installation_id varchar(128) NOT NULL
        CHECK (char_length(installation_id) BETWEEN 16 AND 128),
    detected_at timestamptz NOT NULL,
    location geography(Point, 4326) NOT NULL,
    location_accuracy_m real NOT NULL
        CHECK (location_accuracy_m > 0 AND location_accuracy_m <= 500),
    speed_mps real NOT NULL CHECK (speed_mps >= 0 AND speed_mps <= 100),
    kind text NOT NULL CHECK (kind IN ('road_damage', 'rough_road')),
    severity real NOT NULL CHECK (severity >= 0 AND severity <= 1),
    confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    source text NOT NULL
        CHECK (source IN ('phone', 'roadsense_device', 'fused')),
    detector_version varchar(64) NOT NULL
        CHECK (char_length(detector_version) BETWEEN 1 AND 64),
    payload jsonb NOT NULL,
    payload_sha256 bytea NOT NULL CHECK (octet_length(payload_sha256) = 32),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX road_observations_location_gix
    ON public.road_observations USING gist (location);
CREATE INDEX road_observations_detected_at_idx
    ON public.road_observations (detected_at DESC);

CREATE TABLE public.observation_outbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    observation_event_id uuid NOT NULL UNIQUE
        REFERENCES public.road_observations(event_id) ON DELETE RESTRICT,
    topic text NOT NULL DEFAULT 'road-observation.v1',
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    delivery_attempts integer NOT NULL DEFAULT 0
        CHECK (delivery_attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error text
);

CREATE INDEX observation_outbox_pending_idx
    ON public.observation_outbox (next_attempt_at, id)
    WHERE published_at IS NULL;
