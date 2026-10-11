# Persist Settlements in PostgreSQL

Use a custom PostgreSQL event log with Spring JDBC and one JSONB projection document per Settlement. Current queries
load whole Settlements; Java projection and Netting logic remain unchanged. The existing app module owns Spring Boot
runtime, IoC handler registration, PostgreSQL adapters and consumer lifecycle. Domain source and core modules remain
Spring-free. No new module, ORM, broker or hosting target is introduced.

A transactionally locked singleton cursor serializes appends through commit, preserving expected-version checks,
count-based stream versions and safe positive global replay positions. PostgreSQL sequences cannot establish commit
visibility order. Persist complete append boundaries; consumers read bounded whole batches. Serialized writes limit
throughput intentionally for one application instance and modest volume.

Explicit allowlisted version-one JSON codecs preserve identifiers, exact decimals/currencies, frozen Valuations and
ordered collections. Registration Instants use epoch seconds plus nanoseconds. Projection documents include retired
Participant identifiers; stream versions and a global checkpoint are durable. Flyway owns versioned SQL migrations.
Unsupported types/schemas fail explicitly rather than guessing or skipping financial facts.

Production delivery is asynchronous replay of the authoritative committed log. Projection changes and checkpoint advance
in one transaction. Startup catches up to a finite committed high-water mark before dispatch readiness. Transient
database failures retry the projection, not commands; persistent decode/apply failures stop and report. Offline rebuild
requires the normal JVM stopped and replaces documents/checkpoint atomically, protected by an exclusive runtime advisory
lock.

This supersedes ADR-0007's synchronous **production** listener delivery only. Append remains the commit point,
optimistic concurrency and immutable envelope metadata remain in force, and in-memory delivery remains useful for fast
tests. Query lag and transient `SettlementNotFound` remain permitted. Request idempotency and automatic command retry
remain deferred. KurrentDB, Axon, MongoDB, Kafka and a redundant publication log add services or framework commitments
without benefit for this workload. PostgreSQL alone runs locally in Compose; the JVM runs via `:app:bootRun`.
