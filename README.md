# Fair Share

Fair Share records shared Expenses and derives Proposed Repayments between Participants.
A Settlement retains its financial history and frozen Expense Valuations.

## Local runtime

Use Java 25 from `.sdkmanrc` and Docker.
PostgreSQL runs in Compose. The Spring Boot JVM runs on the host.

No HTTP interface, authentication or deployment setup exists.

```sh
export JAVA_HOME="$HOME/.local/bin/sdkman/candidates/java/25-librca"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait
./scripts/setup.sh
./gradlew :app:bootRun
```

Use `docker-compose` if the Docker CLI cannot discover the Compose plugin.
The setup script selects that fallback automatically.

### Explicit fresh-database setup

Run `scripts/setup.sh` once, only for a fresh database.
It uses containerized `psql` and needs no host `psql` installation.

Schema and sample creation share one transaction. A failure leaves no partial setup.
The script refuses an occupied `public` schema.

The script never resets, deletes or truncates financial history.
Existing databases need no setup or data conversion for this change.

Canonical SQL lives in `scripts/postgres`, outside application resources.
The executable artifact contains neither Flyway nor schema SQL.

Schema upgrades stay outside application scope.
The application and container startup do not execute setup or migrations.

### Sample Settlement

The sample Settlement identifier is `10000000-0000-0000-0000-000000000001`.
Amy pays USD 100 for Dinner at an Exchange Rate of 0.9 EUR.
The EUR 90 Valuation assigns EUR 45 to each Participant.

Bob repays EUR 10 to Amy.
Amy has a EUR 35 Balance, and Bob has a EUR -35 Balance.
The Proposed Repayment transfers EUR 35 from Bob to Amy.

Setup seeds only authoritative events, stream versions, complete append metadata and the global cursor.
Normal startup derives projection documents and advances the checkpoint.

### Configuration and operation

Development defaults use database `fairshare`, username `fairshare_dev`, password `local_dev_only` and localhost port
5432. Keep real credentials outside version control.

Set `FAIRSHARE_DATABASE_URL`, `FAIRSHARE_DATABASE_USERNAME` and `FAIRSHARE_DATABASE_PASSWORD` for the JVM.
Compose shares username/password and accepts `FAIRSHARE_DATABASE_PORT`.
Change the JDBC URL when you change the port.

Existing volumes retain their initialized credentials. Consumer settings are `FAIRSHARE_BATCH_LIMIT` (64 whole commits),
`FAIRSHARE_POLL_MILLIS` (100) and `FAIRSHARE_RETRY_MILLIS` (1000).

`FAIRSHARE_DATABASE_TIMEOUT_SECONDS` defaults to 10 and must stay positive.
It bounds connection, socket, cancellation and SQL statement waits.
Do not disable these bounds through JDBC URL options.

Startup recovers to the committed high-water mark before typed command/query dispatch becomes ready.
A startup lock or query timeout fails recovery instead of accepting dispatch.

Commands succeed at durable append, not projection completion.
Live queries can briefly lag, including `SettlementNotFound` for a new Settlement.
History reads the authoritative stream.

Database failure never selects an in-memory fallback.
Shutdown stops between complete batches and reports failure if its derived deadline expires.

Stop the JVM with Ctrl-C.
`docker compose down` stops PostgreSQL and retains the named `settlement-data` volume.

Restart with `docker compose up -d --wait` to retain event history and views.
Do not repeat setup or remove the volume.

## Recovery and offline rebuild

Transient projection database failures retry without advancing the failed checkpoint.
Unsupported durable schemas or invalid financial events stop projection progress and log an error.
The consumer skips no events.

Startup recovery failure fails startup.
Repair the compatibility or application fault before you restart.
Do not blindly retry a command whose append can already exist.

Request idempotency and automatic command retries remain outside scope.

Stop the normal JVM first. Keep PostgreSQL active, then rebuild:

```sh
./gradlew :app:bootRun --args='--spring.profiles.active=rebuild'
```

The rebuild process exits after replay through the ordinary Spring shutdown hook.
A database advisory lock prevents normal processing and rebuild from concurrent execution.

Rebuild reads whole event batches and replaces all documents plus checkpoint in one transaction.
Failure preserves the previous views and checkpoint.
Readers see the old or complete new state.

Offline maintenance uses one long transaction, not online generation replacement.
Restart the normal JVM after rebuild.

## Verification

```sh
./gradlew format check
./gradlew coverageCheck mutationCheck scan
```

Durability specifications use isolated real PostgreSQL Testcontainers and need Docker.
Unavailable Docker fails tests. Fast domain and in-memory specifications also remain available.

Serialized appends preserve safe global replay order.
They exchange write throughput for correctness at the intended one-instance scale.
