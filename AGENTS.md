# MichiSonae repository rules

These rules apply to the entire repository.

## Non-negotiable product constraints

- Keep the Android client native Kotlin/Jetpack Compose.
- Phone-only road detection must work without RoadSense hardware or OBD.
- Never send ECU writes, code clearing, coding, actuation or manufacturer write
  commands.
- Never acknowledge a road observation before durable storage succeeds.
- Do not add trip history.
- Do not upload raw microphone audio, raw diagnostic data, diagnostic AI
  prompts/responses, or local seven-day tuning traces.
- Generative AI is explanation-only. Deterministic policy owns severity, safe
  actions, warning thresholds, fuel calculations and emergency state.
- LoRa and crash/SOS code requires an approved future-phase ADR; do not add it
  to v1 modules.

## Component dependency direction

- `contracts` has no dependency on application components.
- `services`, `apps`, `firmware` and `simulator` may consume `contracts`.
- `apps/android` must not import backend implementation code.
- `services/api` must not depend on Android or firmware implementation code.
- Hardware CAD/BOM files do not contain credentials or environment endpoints.

## Change quality

- Add tests with behavior changes.
- Keep production secrets and local SDK paths out of Git.
- Pin production dependencies through reviewed lockfiles/wrappers.
- Update the relevant ADR and component README when a public contract or safety
  boundary changes.
- Prefer small vertical slices that preserve offline and failure behavior.
