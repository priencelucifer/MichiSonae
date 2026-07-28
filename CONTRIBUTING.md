# Contributing

MichiSonae is safety-adjacent software. Start with an issue describing the user
outcome, failure modes, privacy impact and validation evidence.

## Workflow

1. Branch from `main`.
2. Keep changes inside one component unless a contract change requires a
   coordinated update.
3. Add or update tests.
4. Run the relevant component checks.
5. Open a pull request explaining behavior, safety/privacy effects, rollout and
   rollback.

Safety-relevant changes to warning timing, diagnostic severity, fuel
reachability or future emergency behavior require an ADR and explicit review.
