# ADR 0006: Account-free installation security

- Status: Accepted
- Date: 2026-07-28

## Context

MichiSonae's core experience must work without a personal account, but an open
ingestion endpoint would allow identity spoofing, replay, emulator floods, and
unbounded database work. Shared ingest secrets from the prototypes cannot
distinguish installations and cannot be safely revoked.

Forwarding headers are attacker-controlled unless a known proxy replaces and
forwards them correctly. Raw IP addresses and credentials are sensitive and
must not become a new tracking store.

## Decision

- Registration creates an opaque `ins_<random>` installation identity. It does
  not collect a name, email, phone number, trip, or account profile.
- The server issues random opaque access and refresh credentials. PostgreSQL
  stores only 32-byte SHA-256 hashes, never raw credentials.
- Access credentials are short-lived and checked against active installation,
  family, expiry, and revocation state.
- Refresh credentials rotate once under row locks. Reuse revokes the token
  family and all its access credentials. Clients must single-flight refresh.
- Installation revocation invalidates all families and access credentials.
- Ingestion compares every body installation ID to the authenticated principal
  before durable storage.
- Events older than the offline acceptance window or beyond the clock-skew
  allowance are rejected before ingestion, so delayed replay cannot make a
  hazard appear recent.
- Registration, refresh, ingestion, and origin reads use atomic PostgreSQL
  fixed-window counters. Subjects are HMAC-pseudonymized with an environment
  secret.
- Forwarded addresses are ignored unless the immediate peer is in an explicit
  trusted CIDR. Trusted proxy chains are walked from right to left to select the
  rightmost untrusted client.
- Optional attestation is stored only as presence plus a digest and influences
  risk analysis only. It never bypasses consensus, rate limits, or validation.
- JSON content type, body size, and correlation IDs are enforced centrally.
- Security audit events contain bounded event codes, correlation IDs,
  pseudonymized IPs, and minimized metadata. They contain no credentials, raw
  bodies, or precise movement sequences.
- Security and rate-limit storage fail closed for contribution endpoints.

## Consequences

- A person can contribute without creating an account.
- A database read alone cannot recover bearer credentials.
- Legitimate clients that send the same refresh credential concurrently can
  trigger family revocation; Android must serialize refresh and re-register if
  reuse is reported.
- PostgreSQL is sufficient for the small alpha. Redis may later accelerate
  atomic counters without becoming the source of truth.
- HMAC key rotation needs an operational plan because active rate-limit buckets
  will change pseudonyms.
- Device/app attestation remains only one abuse signal; consensus and anomaly
  detection are still required.
