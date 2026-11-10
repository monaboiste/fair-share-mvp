# Separate physical app layers

The app parent groups four peer Gradle modules: domain, application, infrastructure, and bootstrap.
Each child owns a matching JPMS module. The parent owns no sources or executable artifact.

This split makes module dependencies enforce the existing layer direction.
Bootstrap owns startup, Spring configuration, runtime resources, `:app:bootstrap:bootRun`, and `:app:bootstrap:bootJar`.
Domain and application exclude adapter frameworks.

Qualified exports give bootstrap typed access to handlers and infrastructure.
Application, infrastructure, and bootstrap receive qualified access to the domain model.

Only the codec format package opens to Jackson.
Spring reflection stays in bootstrap startup and configuration packages, and the infrastructure lifecycle package.

This decision supersedes only module ownership and visibility statements in ADR-0007, ADR-0011, and ADR-0012.
Their financial, delivery, database, and durable codec decisions stay unchanged.
