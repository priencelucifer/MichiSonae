CREATE TABLE public.anonymous_installations (
    installation_id varchar(36) PRIMARY KEY
        CHECK (installation_id ~ '^ins_[0-9a-f]{32}$'),
    status text NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'revoked')),
    attestation_present boolean NOT NULL DEFAULT false,
    attestation_hash bytea,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    CHECK (
        (attestation_present AND attestation_hash IS NOT NULL)
        OR (NOT attestation_present AND attestation_hash IS NULL)
    ),
    CHECK ((status = 'revoked') = (revoked_at IS NOT NULL))
);

CREATE TABLE public.auth_token_families (
    family_id uuid PRIMARY KEY,
    installation_id varchar(36) NOT NULL
        REFERENCES public.anonymous_installations(installation_id)
        ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason text,
    CHECK (expires_at > created_at),
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);

CREATE INDEX auth_token_families_installation_idx
    ON public.auth_token_families (installation_id, created_at DESC);

CREATE TABLE public.auth_refresh_tokens (
    token_hash bytea PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    family_id uuid NOT NULL
        REFERENCES public.auth_token_families(family_id) ON DELETE RESTRICT,
    generation integer NOT NULL CHECK (generation > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    replaced_by_hash bytea,
    CHECK (expires_at > created_at),
    CHECK ((used_at IS NULL) = (replaced_by_hash IS NULL)),
    CHECK (replaced_by_hash IS NULL OR octet_length(replaced_by_hash) = 32),
    UNIQUE (family_id, generation)
);

CREATE INDEX auth_refresh_tokens_family_idx
    ON public.auth_refresh_tokens (family_id, generation DESC);

CREATE TABLE public.auth_access_tokens (
    token_hash bytea PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    family_id uuid NOT NULL
        REFERENCES public.auth_token_families(family_id) ON DELETE RESTRICT,
    installation_id varchar(36) NOT NULL
        REFERENCES public.anonymous_installations(installation_id)
        ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);

CREATE INDEX auth_access_tokens_installation_idx
    ON public.auth_access_tokens (installation_id, expires_at DESC);
CREATE INDEX auth_access_tokens_family_idx
    ON public.auth_access_tokens (family_id, expires_at DESC);

CREATE TABLE public.security_rate_limits (
    scope text NOT NULL,
    subject_hash bytea NOT NULL CHECK (octet_length(subject_hash) = 32),
    window_started_at timestamptz NOT NULL,
    request_count integer NOT NULL CHECK (request_count > 0),
    PRIMARY KEY (scope, subject_hash, window_started_at)
);

CREATE INDEX security_rate_limits_window_idx
    ON public.security_rate_limits (window_started_at);

CREATE TABLE public.security_audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    event_type text NOT NULL,
    installation_id varchar(36),
    correlation_id uuid NOT NULL,
    client_ip_hash bytea CHECK (
        client_ip_hash IS NULL OR octet_length(client_ip_hash) = 32
    ),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX security_audit_events_occurred_idx
    ON public.security_audit_events (occurred_at DESC);
CREATE INDEX security_audit_events_installation_idx
    ON public.security_audit_events (installation_id, occurred_at DESC)
    WHERE installation_id IS NOT NULL;
