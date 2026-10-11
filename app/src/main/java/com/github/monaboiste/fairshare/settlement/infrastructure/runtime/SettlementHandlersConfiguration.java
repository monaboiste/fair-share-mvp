package com.github.monaboiste.fairshare.settlement.infrastructure.runtime;

import com.github.monaboiste.fairshare.settlement.application.command.handler.AddParticipantHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.CancelExpenseHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.CancelRepaymentHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.CloseSettlementHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.ConfigureExchangeRateHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.OpenSettlementHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.RecordExpenseHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.RecordRepaymentHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.RemoveParticipantHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.RenameParticipantHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.RenameSettlementHandler;
import com.github.monaboiste.fairshare.settlement.application.command.handler.ReopenSettlementHandler;
import com.github.monaboiste.fairshare.settlement.application.query.SettlementViews;
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetProposedRepaymentsHandler;
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetSettlementHandler;
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetSettlementHistoryHandler;
import com.github.monaboiste.fairshare.settlement.domain.model.SettlementRepository;
import com.github.monaboiste.fairshare.settlement.infrastructure.EventSourcedSettlementRepository;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SettlementHandlersConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SettlementRepository repository(PostgresEventStore store, Clock clock) {
        return new EventSourcedSettlementRepository(store, clock);
    }

    @Bean
    OpenSettlementHandler openSettlementHandler(SettlementRepository repository, Clock clock) {
        return new OpenSettlementHandler(repository, clock);
    }

    @Bean
    RenameSettlementHandler renameSettlementHandler(SettlementRepository repository) {
        return new RenameSettlementHandler(repository);
    }

    @Bean
    CloseSettlementHandler closeSettlementHandler(SettlementRepository repository) {
        return new CloseSettlementHandler(repository);
    }

    @Bean
    ReopenSettlementHandler reopenSettlementHandler(SettlementRepository repository) {
        return new ReopenSettlementHandler(repository);
    }

    @Bean
    AddParticipantHandler addParticipantHandler(SettlementRepository repository) {
        return new AddParticipantHandler(repository);
    }

    @Bean
    RenameParticipantHandler renameParticipantHandler(SettlementRepository repository) {
        return new RenameParticipantHandler(repository);
    }

    @Bean
    RemoveParticipantHandler removeParticipantHandler(SettlementRepository repository) {
        return new RemoveParticipantHandler(repository);
    }

    @Bean
    ConfigureExchangeRateHandler configureExchangeRateHandler(SettlementRepository repository, Clock clock) {
        return new ConfigureExchangeRateHandler(repository, clock);
    }

    @Bean
    RecordExpenseHandler recordExpenseHandler(SettlementRepository repository, Clock clock) {
        return new RecordExpenseHandler(repository, clock);
    }

    @Bean
    CancelExpenseHandler cancelExpenseHandler(SettlementRepository repository) {
        return new CancelExpenseHandler(repository);
    }

    @Bean
    RecordRepaymentHandler recordRepaymentHandler(SettlementRepository repository) {
        return new RecordRepaymentHandler(repository);
    }

    @Bean
    CancelRepaymentHandler cancelRepaymentHandler(SettlementRepository repository) {
        return new CancelRepaymentHandler(repository);
    }

    @Bean
    GetSettlementHandler getSettlementHandler(SettlementViews views) {
        return new GetSettlementHandler(views);
    }

    @Bean
    GetSettlementHistoryHandler getSettlementHistoryHandler(PostgresEventStore store) {
        return new GetSettlementHistoryHandler(store);
    }

    @Bean
    GetProposedRepaymentsHandler getProposedRepaymentsHandler(SettlementViews views) {
        return new GetProposedRepaymentsHandler(views);
    }
}
