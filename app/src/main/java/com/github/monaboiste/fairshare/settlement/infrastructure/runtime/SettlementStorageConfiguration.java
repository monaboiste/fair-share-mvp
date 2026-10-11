package com.github.monaboiste.fairshare.settlement.infrastructure.runtime;

import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresSettlementProjection;
import com.github.monaboiste.fairshare.settlement.infrastructure.SettlementEventCodec;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods = false)
public class SettlementStorageConfiguration {
    @Bean
    DataSource dataSource(Environment environment) {
        int timeout = databaseTimeout(environment);
        var source = new DriverManagerDataSource(
                environment.getRequiredProperty("fairshare.database.url"),
                environment.getRequiredProperty("fairshare.database.username"),
                environment.getRequiredProperty("fairshare.database.password"));
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", Integer.toString(timeout));
        properties.setProperty("socketTimeout", Integer.toString(timeout));
        properties.setProperty("cancelSignalTimeout", Integer.toString(timeout));
        properties.setProperty("options", "-c statement_timeout=" + Math.multiplyExact(timeout, 1000));
        source.setConnectionProperties(properties);
        return source;
    }

    @Bean(initMethod = "migrate")
    Flyway migrations(DataSource source) {
        return Flyway.configure().dataSource(source).load();
    }

    @Bean
    @DependsOn("migrations")
    JdbcTemplate jdbc(DataSource source, Environment environment) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(databaseTimeout(environment));
        return jdbc;
    }

    static int databaseTimeout(Environment environment) {
        int timeout = environment.getProperty("fairshare.database.timeout-seconds", Integer.class, 10);
        if (timeout < 1) {
            throw new IllegalArgumentException("Database timeout must be positive");
        }
        return timeout;
    }

    @Bean
    JdbcTransactionManager transactions(DataSource source) {
        return new JdbcTransactionManager(source);
    }

    @Bean
    SettlementEventCodec eventCodec() {
        return new SettlementEventCodec();
    }

    @Bean
    PostgresEventStore eventStore(JdbcTemplate jdbc, JdbcTransactionManager transactions, SettlementEventCodec codec) {
        return new PostgresEventStore(jdbc, transactions, codec);
    }

    @Bean
    PostgresSettlementProjection settlementViews(
            JdbcTemplate jdbc, JdbcTransactionManager transactions, SettlementEventCodec codec) {
        return new PostgresSettlementProjection(jdbc, transactions, codec);
    }
}
