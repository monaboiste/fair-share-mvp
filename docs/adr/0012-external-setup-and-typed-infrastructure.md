# External setup and typed infrastructure

## Decision

Schema setup belongs outside the executable application.
Remove Flyway, application migrations and the Flyway-only automatic-module transform.
Do not replace them with another migration tool or automatic schema initialization.

Keep the existing PostgreSQL table definitions in `scripts/postgres/schema.sql`.
Run `scripts/setup.sh` explicitly against a fresh database.
The script uses containerized `psql` through Compose and supports standalone `docker-compose`.

Refuse an occupied `public` schema without resetting or overwriting data.
Create the schema and sample in one transaction.
Never delete or truncate financial history during setup.

Seed one Settlement with two Participants, one Expense and a partial Repayment.
Store only authoritative events, stream versions, complete append metadata and a matching global cursor.
Normal startup derives projection documents from the committed history.

Place Spring configuration in `com.github.monaboiste.fairshare.settlement.config` and keep the bootstrap separate.
Inject typed configuration values with `@Value` and preserve property names, defaults and positive bounds.
Keep normal handler discovery, exact typed dispatch, registration checks and ordered interceptors.

Use Spring profiles for normal processing and offline rebuild.
A non-web rebuild process exits after its runners complete through the ordinary shutdown hook.
The normal consumer keeps its process active until shutdown.

Use one explicitly configured `JsonMapper` with infrastructure mixins behind the existing codecs.
Keep the event type and schema-version allowlist explicit.
Use small typed format adapters for legacy allocation entries, flat Exchange Rate versions and projection documents.

Preserve all version-one field names, scalar identifiers, decimal strings, nullable fields and ordered collections.
Decode stored financial values directly without new business validation or recalculation.
Keep strict creator, null, root and coercion checks while accepting unknown object properties.

Keep Jackson and Spring dependencies outside domain and core sources.
Open only the infrastructure format package to Jackson reflection.
Keep SQL adapters in infrastructure packages within the existing app module.

## Consequences

Operators own schema setup and future schema changes outside application startup.
Existing databases need no schema or data conversion for this change.
The executable application contains neither Flyway nor schema SQL.

Version-one golden fixtures and public store, projection, recovery and dispatch tests protect compatibility.
Isolated PostgreSQL tests verify sample results, setup refusal and transaction rollback.
Artifact tests check packaged contents and normal/rebuild process behavior.

## Supersession

This decision supersedes only ADR-0011's Flyway migration ownership. The codec implementation changes without a durable
format change. ADR-0011's event-log ordering, append atomicity, asynchronous projection, recovery, runtime lock and
offline rebuild decisions remain accepted. No domain terminology changes.
