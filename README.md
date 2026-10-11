# Fair Share

Fair Share helps groups of people settle shared expenses in a simple and transparent way.

Imagine a group of friends going on a weekend trip to the mountains: one person pays for the accommodation, another for
groceries, someone else for fuel, and some expenses are shared only between part of the group. Fair Share keeps track of
who paid for what, applies the appropriate split rules, and calculates who owes whom at the end of the trip.

Expenses can also be made in different currencies, while preserving the exchange rate used at the time of each expense
so that historical settlements remain consistent.

## Local runtime

Requires Java 25 from `.sdkmanrc` and Docker. PostgreSQL 18.3 is pinned with native ARM64 support. Only PostgreSQL runs
in Compose; the Spring Boot 4.1.1 JVM runs on the host. There is no HTTP interface, authentication or deployment setup.

```sh
export JAVA_HOME="$HOME/.local/bin/sdkman/candidates/java/25-librca"
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait
./gradlew :app:bootRun
```

If your Docker CLI does not discover the Compose plugin, use the equivalent `docker-compose` commands.

Flyway applies versioned SQL migrations at startup. The application resumes its durable projection checkpoint and
catches up to the startup committed high-water mark before typed command/query dispatch becomes ready. Commands succeed
at durable append, not projection completion. Live queries may briefly lag, including `SettlementNotFound` for a
just-opened Settlement. History reads the authoritative stream. Database failure never selects an in-memory fallback.

Development-only defaults: database `fairshare`, username `fairshare_dev`, password `local_dev_only`, localhost port
5432. Override `FAIRSHARE_DATABASE_URL`, `FAIRSHARE_DATABASE_USERNAME`, `FAIRSHARE_DATABASE_PASSWORD` for the JVM;
Compose shares username/password and accepts `FAIRSHARE_DATABASE_PORT`. Changing the port also requires changing the
JDBC URL. Existing volumes retain their initialized credentials. Keep real credentials outside version control. Polling
settings are `FAIRSHARE_BATCH_LIMIT` (64 whole commits), `FAIRSHARE_POLL_MILLIS` (100), and `FAIRSHARE_RETRY_MILLIS`
(1000).
`FAIRSHARE_DATABASE_TIMEOUT_SECONDS` (10, positive) bounds connection, socket, cancellation and SQL statement waits;
shutdown stops between complete batches and reports failure if its derived deadline is exceeded. Do not disable these
bounds through JDBC URL options. A startup lock/query timeout fails recovery instead of exposing ready dispatch.

Stop the JVM with Ctrl-C. `docker compose down` stops PostgreSQL without deleting the named `settlement-data` volume;
restart with `docker compose up -d --wait` to retain event history and views. Never remove the volume unless discarding
all local financial history is intentional.

## Recovery and offline rebuild

Transient projection database failures retry without advancing the failed checkpoint. Unsupported durable schemas or
invalid financial events stop projection progress and log an error; no event is skipped. Startup recovery failure fails
startup. Investigate and repair the compatibility/application fault before restarting. Never blindly retry a command
whose append might already have committed; request idempotency and automatic command retries are not implemented.

Stop the normal JVM first, keep PostgreSQL running, then rebuild:

```sh
./gradlew :app:bootRun --args='--spring.profiles.active=rebuild'
```

The rebuild process exits after replay. A database advisory lock prevents normal processing and rebuild from running
together. Rebuild reads the event log in whole batches and replaces all documents plus checkpoint in one transaction;
failure preserves the previously published views/checkpoint. Readers see the old or complete new state. This
deliberately uses one long transaction for offline maintenance, not online generation swapping. Restart the normal JVM
afterward.

## Verification

```sh
./gradlew format check
./gradlew coverageCheck mutationCheck scan
```

Durability specifications use isolated real PostgreSQL Testcontainers and require Docker; unavailable Docker fails
tests. Fast domain and in-memory specifications remain available. Serialized appends ensure safe global replay order,
trading write throughput for correctness at the intended one-instance, modest-volume scale.
