# Backend release, canary, and rollback

## Artifact rule

Build the backend once from a reviewed commit. Identify it everywhere as
`registry/repository@sha256:<digest>`, retain its SPDX JSON SBOM and scan
results, and promote that exact digest from CI to staging and then production.
Never rebuild a production artifact from the same Git tag.

## Expand, migrate, contract

1. Confirm all CI jobs are green: database integration, restore/rebuild,
   supply-chain and load gates.
2. Create an encrypted backup/PITR checkpoint and record its identifier.
3. Deploy an additive/expand schema migration as the one-shot `migrate` role.
   Do not start it from API or worker replicas.
4. Start one canary API plus one worker of each kind with the new digest.
5. Check startup/readiness, error rate, p95 latency, database pool headroom,
   queue age, dead letters, snapshot freshness and logs for 15 minutes.
6. Run the staging synthetic probe. It must verify registration, durable
   upload, duplicate retry, projection, snapshot publication and public read.
7. Shift traffic 5%, 25%, 50%, then 100%, holding each step for at least 15
   minutes and stopping on any alert.
8. Scale workers only when leases and database connections remain within the
   recorded budget.
9. Keep the previous image digest available through the observation window.
10. Remove old columns or behavior only in a later contract release after all
    running versions no longer use them.

## Rollback

Stop traffic progression immediately when an SLO, safety invariant, migration,
or synthetic probe fails. If the schema is backward-compatible, route traffic
to the previous digest and drain the new workers. Never reverse a data
migration by guessing.

If schema or data is damaged:

1. freeze writes and destructive maintenance;
2. preserve logs, metrics, image digest, migration output and correlation IDs;
3. restore the pre-release backup into an isolated database;
4. run `michi-maintain check`, then a deterministic rebuild if derived state is
   suspect;
5. validate the restored API before a reviewed database routing change;
6. record actual RPO/RTO and complete an incident review.

The old environment remains read-only until reconciliation is complete.
