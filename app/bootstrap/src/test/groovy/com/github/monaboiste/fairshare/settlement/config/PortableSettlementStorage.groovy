package com.github.monaboiste.fairshare.settlement.config

import com.github.monaboiste.fairshare.common.events.inmemory.InMemoryEventStore
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent
import com.github.monaboiste.fairshare.settlement.domain.model.SettlementRepository
import com.github.monaboiste.fairshare.settlement.infrastructure.EventSourcedSettlementRepository
import com.github.monaboiste.fairshare.settlement.infrastructure.SettlementProjector
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration(proxyBeanMethods = false)
class PortableSettlementStorage {
    @Bean
    @Primary
    SettlementProjector portableViews() {
        return new SettlementProjector()
    }

    @Bean
    @Primary
    SettlementRepository portableRepository(Clock clock, SettlementProjector projector) {
        def events = new InMemoryEventStore<SettlementId, SettlementEvent>()
        events.subscribe(projector)
        return new EventSourcedSettlementRepository(events, clock)
    }
}
