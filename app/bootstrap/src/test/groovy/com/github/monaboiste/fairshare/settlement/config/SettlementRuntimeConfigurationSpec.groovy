package com.github.monaboiste.fairshare.settlement.config

import com.github.monaboiste.fairshare.common.commands.CommandHandler
import com.github.monaboiste.fairshare.common.commands.CommandInterceptor
import com.github.monaboiste.fairshare.common.queries.QueryHandler
import com.github.monaboiste.fairshare.settlement.application.command.OpenSettlement
import com.github.monaboiste.fairshare.settlement.application.command.RenameSettlement
import com.github.monaboiste.fairshare.settlement.application.command.handler.OpenSettlementHandler
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlement
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetSettlementHandler
import com.github.monaboiste.fairshare.settlement.domain.SettlementName
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresSettlementProjection
import javax.money.Monetary
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import spock.lang.Specification

class SettlementRuntimeConfigurationSpec extends Specification {
    private AnnotationConfigApplicationContext context
    private final configuration = new SettlementRuntimeConfiguration()

    def setup() {
        context = new AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(new MapPropertySource("portable-database", [
                "fairshare.database.url": "jdbc:postgresql://unused/fairshare",
                "fairshare.database.username": "unused",
                "fairshare.database.password": "unused"
        ]))
        context.register(SettlementStorageConfiguration, SettlementHandlersConfiguration, PortableSettlementStorage)
        context.refresh()
    }

    def cleanup() {
        context?.close()
    }

    def "commands cannot change a Settlement before projection recovery is ready"() {
        given: "configured handlers and a consumer that has not recovered its projection"
        def consumer = configuration.consumer(context.getBean(PostgresEventStore),
                context.getBean(PostgresSettlementProjection), 64, 100, 1000, 10)
        def commands = configuration.commandDispatcher(context.getBeansOfType(CommandHandler).values().toList(),
                context.getBeanProvider(CommandInterceptor), consumer)
        def settlement = context.getBean(OpenSettlementHandler)
                .handle(new OpenSettlement(new SettlementName("Holiday"), Monetary.getCurrency("EUR")))
                .getSuccess().streamId()

        when: "a command is dispatched before readiness"
        commands.dispatch(new RenameSettlement(settlement, new SettlementName("Mountains")))

        then: "dispatch rejects instead of invoking the registered handler"
        def failure = thrown(IllegalStateException)
        failure.message == "Settlement dispatch is not ready"
        failure.cause == null
        context.getBean(GetSettlementHandler).handle(new GetSettlement(settlement)).getSuccess().name() == "Holiday"
    }

    def "queries cannot expose a Settlement before projection recovery is ready"() {
        given: "configured query handlers and a consumer that has not recovered its projection"
        def consumer = configuration.consumer(context.getBean(PostgresEventStore),
                context.getBean(PostgresSettlementProjection), 64, 100, 1000, 10)
        def queries = configuration.queryDispatcher(context.getBeansOfType(QueryHandler).values().toList(), consumer)
        def settlement = context.getBean(OpenSettlementHandler)
                .handle(new OpenSettlement(new SettlementName("Holiday"), Monetary.getCurrency("EUR")))
                .getSuccess().streamId()

        when: "a query is dispatched before readiness"
        queries.dispatch(new GetSettlement(settlement))

        then: "dispatch refuses even a Settlement already available to its handler"
        def failure = thrown(IllegalStateException)
        failure.message == "Settlement dispatch is not ready"
        failure.cause == null
        context.getBean(GetSettlementHandler).handle(new GetSettlement(settlement)).success()
    }
}
