ALTER TABLE public.observation_outbox
    ADD COLUMN correlation_id uuid NOT NULL DEFAULT gen_random_uuid();

CREATE INDEX observation_outbox_correlation_idx
    ON public.observation_outbox (correlation_id);

ALTER TABLE public.regional_snapshot_work
    ADD COLUMN correlation_id uuid NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE public.operations_audit_events
    ADD COLUMN correlation_id uuid NOT NULL DEFAULT gen_random_uuid();

CREATE INDEX operations_audit_events_correlation_idx
    ON public.operations_audit_events (correlation_id, occurred_at);
