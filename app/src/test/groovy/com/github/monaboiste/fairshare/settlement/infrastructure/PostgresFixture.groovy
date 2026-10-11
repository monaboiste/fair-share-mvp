package com.github.monaboiste.fairshare.settlement.infrastructure

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresFixture implements AutoCloseable {
    static final String IMAGE = "postgres:18.3@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db"
    final PostgreSQLContainer database = SharedPostgres.database
    private final String databaseName = "settlement_${UUID.randomUUID().toString().replace("-", "")}"
    final DriverManagerDataSource dataSource
    final JdbcTemplate jdbc
    final JdbcTransactionManager transactions

    PostgresFixture() {
        new JdbcTemplate(new DriverManagerDataSource(connectionUrl("test"), database.username, database.password))
                .execute("CREATE DATABASE ${databaseName}")
        dataSource = new DriverManagerDataSource(connectionUrl(databaseName), database.username, database.password)
        Flyway.configure().dataSource(dataSource).load().migrate()
        jdbc = new JdbcTemplate(dataSource)
        transactions = new JdbcTransactionManager(dataSource)
    }

    void awaitProjectionLockWait() {
        new spock.util.concurrent.PollingConditions(timeout: 10).eventually {
            assert jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT position FROM projection_checkpoint%'", Long) > 0
        }
    }

    void restart() {
        database.dockerClient.restartContainerCmd(database.containerId).exec()
        dataSource.url = connectionUrl(databaseName)
    }

    private String connectionUrl(String name) {
        def info = database.dockerClient.inspectContainerCmd(database.containerId).exec()
        def ports = info.networkSettings.ports.bindings
        def port = ports[com.github.dockerjava.api.model.ExposedPort.tcp(5432)].first().hostPortSpec
        "jdbc:postgresql://${database.host}:${port}/${name}"
    }

    private static class SharedPostgres {
        static final PostgreSQLContainer database = startDatabase()

        private static PostgreSQLContainer startDatabase() {
            def database = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            database.start()
            database
        }
    }

    PostgresEventStore store() {
        new PostgresEventStore(jdbc, transactions, new SettlementEventCodec())
    }

    @Override
    void close() {
        new JdbcTemplate(new DriverManagerDataSource(connectionUrl("test"), database.username, database.password))
                .execute("DROP DATABASE IF EXISTS ${databaseName} WITH (FORCE)")
    }
}
