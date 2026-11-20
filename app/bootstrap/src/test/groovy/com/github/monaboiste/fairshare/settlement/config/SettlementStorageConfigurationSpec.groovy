package com.github.monaboiste.fairshare.settlement.config

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import spock.lang.Specification

class SettlementStorageConfigurationSpec extends Specification {
    private AnnotationConfigApplicationContext context

    def cleanup() {
        context?.close()
    }

    def "database settings bound connection and statement waits with #setting timeout"() {
        given: "database credentials and an optional timeout"
        context = new AnnotationConfigApplicationContext()
        def properties = [
                "fairshare.database.url": "jdbc:postgresql://database.example/settlements",
                "fairshare.database.username": "settlement_writer",
                "fairshare.database.password": "test-password"
        ]
        if (configured != null) {
            properties["fairshare.database.timeout-seconds"] = configured
        }
        context.environment.propertySources.addFirst(new MapPropertySource("database-settings", properties))
        context.register(SettlementStorageConfiguration)

        when: "production storage configuration is initialized without connecting to a database"
        context.refresh()
        def source = context.getBean(DriverManagerDataSource)
        def jdbc = context.getBean(NamedParameterJdbcTemplate).jdbcTemplate

        then: "credentials and every database wait limit come from the same settings"
        source.url == "jdbc:postgresql://database.example/settlements"
        source.username == "settlement_writer"
        source.password == "test-password"
        source.connectionProperties == [
                connectTimeout: seconds,
                socketTimeout: seconds,
                cancelSignalTimeout: seconds,
                options: statementOption
        ] as Properties
        jdbc.dataSource.is(source)
        jdbc.queryTimeout == queryTimeout

        where:
        setting    | configured | seconds | statementOption              | queryTimeout
        "default"  | null       | "10"    | "-c statement_timeout=10000" | 10
        "explicit" | "3"        | "3"     | "-c statement_timeout=3000"  | 3
    }

    def "nonpositive database timeout is rejected before opening connections: #timeout"() {
        given: "production storage configuration"
        def configuration = new SettlementStorageConfiguration()

        when: "a connection source is requested with an invalid timeout"
        configuration.dataSource("jdbc:postgresql://unused/fairshare", "unused", "unused", timeout)

        then: "startup refuses an unbounded database wait"
        def failure = thrown(IllegalArgumentException)
        failure.message == "Database timeout must be positive"

        where:
        timeout << [0, -1]
    }
}
