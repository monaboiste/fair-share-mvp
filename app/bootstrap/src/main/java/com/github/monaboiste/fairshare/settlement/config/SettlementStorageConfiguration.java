package com.github.monaboiste.fairshare.settlement.config;

import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresSettlementProjection;
import com.github.monaboiste.fairshare.settlement.infrastructure.SettlementEventCodec;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods = false)
public class SettlementStorageConfiguration {
    @Bean
    DataSource dataSource(
            @Value("${fairshare.database.url}") String url,
            @Value("${fairshare.database.username}") String username,
            @Value("${fairshare.database.password}") String password,
            @Value("${fairshare.database.timeout-seconds:10}") int timeout) {
        databaseTimeout(timeout);
        var source = new DriverManagerDataSource(url, username, password);
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", Integer.toString(timeout));
        properties.setProperty("socketTimeout", Integer.toString(timeout));
        properties.setProperty("cancelSignalTimeout", Integer.toString(timeout));
        properties.setProperty("options", "-c statement_timeout=" + Math.multiplyExact(timeout, 1000));
        source.setConnectionProperties(properties);
        return source;
    }

    @Bean
    NamedParameterJdbcTemplate jdbc(DataSource source, @Value("${fairshare.database.timeout-seconds:10}") int timeout) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(databaseTimeout(timeout));
        return new NamedParameterJdbcTemplate(template);
    }

    static int databaseTimeout(int timeout) {
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
    PostgresEventStore eventStore(
            NamedParameterJdbcTemplate jdbc, JdbcTransactionManager transactions, SettlementEventCodec codec) {
        return new PostgresEventStore(jdbc, transactions, codec);
    }

    @Bean
    PostgresSettlementProjection settlementViews(
            NamedParameterJdbcTemplate jdbc, JdbcTransactionManager transactions, SettlementEventCodec codec) {
        return new PostgresSettlementProjection(jdbc, transactions, codec);
    }
}
