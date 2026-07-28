# ADR 0002: Deterministic safety boundary

- Status: Accepted
- Date: 2026-07-28

## Decision

Deterministic, versioned policy owns:

- hazard warning eligibility and timing;
- diagnostic severity and safe action;
- fuel-range and station reachability;
- persistent critical-warning state;
- future crash/emergency state.

An on-device language model may rewrite retrieved approved facts into simpler
English. Its structured output is validated, and static text is always
available. The model cannot clear codes, change severity, calculate reachability
or suppress a warning.

## Rationale

Safety behavior must remain reproducible, testable and available when the local
model is absent, removed, slow or invalid.
