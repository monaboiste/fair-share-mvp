package com.github.monaboiste.fairshare.settlement.infrastructure.runtime;

import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresSettlementProjection;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

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
                PostgresEventStore events, PostgresSettlementProjection views, Environment environment) {
            return _ -> views.rebuildOffline(
                    events, environment.getProperty("fairshare.consumer.batch-limit", Integer.class, 64));
        }
    }
}
