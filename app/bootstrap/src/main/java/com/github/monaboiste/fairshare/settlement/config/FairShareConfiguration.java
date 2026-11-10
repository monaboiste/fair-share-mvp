package com.github.monaboiste.fairshare.settlement.config;

import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresSettlementProjection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Import({FairShareConfiguration.Normal.class, FairShareConfiguration.Rebuild.class})
public class FairShareConfiguration {
    @Configuration(proxyBeanMethods = false)
    @Profile("!rebuild")
    @Import(SettlementRuntimeConfiguration.class)
    static class Normal {}

    @Configuration(proxyBeanMethods = false)
    @Profile("rebuild")
    @Import(SettlementStorageConfiguration.class)
    static class Rebuild {
        @Bean
        ApplicationRunner rebuild(
                PostgresEventStore events,
                PostgresSettlementProjection views,
                @Value("${fairshare.consumer.batch-limit:64}") int batchLimit) {
            return _ -> views.rebuildOffline(events, batchLimit);
        }
    }
}
