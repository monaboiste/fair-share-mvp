# Fair Share

Fair Share records shared Expenses and derives Proposed Repayments between Participants.
A Settlement retains its financial history and frozen Expense Valuations.

## Modules

| Gradle project        | Ownership                                                            |
| --------------------- | -------------------------------------------------------------------- |
| `:app`                | Grouping and task aggregation only, without sources                  |
| `:app:domain`         | Settlement model, events, allocation, valuation, netting             |
| `:app:application`    | Commands, queries, handlers, read interfaces, views                  |
| `:app:infrastructure` | Repositories, PostgreSQL adapters, codecs, projections, log consumer |
| `:app:bootstrap`      | Executable startup, Spring configuration, runtime resources          |

Start the application with `./gradlew :app:bootstrap:bootRun`.
Build the executable artifact with `./gradlew :app:bootstrap:bootJar`.

Domain and application have no Spring, Jackson, JDBC, or PostgreSQL production dependencies.
Infrastructure depends on application and domain. Bootstrap wires all three layers.

Each source-owning child owns its coverage and mutation report.
Reports use tests across all app modules without duplicate production classes.
Run the database-free JPMS codec check with `./gradlew :app:infrastructure:modulePathSmoke`.
