package com.github.monaboiste.fairshare.settlement.infrastructure

import com.github.monaboiste.fairshare.common.Result
import com.github.monaboiste.fairshare.common.commands.Command
import com.github.monaboiste.fairshare.common.commands.CommandDispatcher
import com.github.monaboiste.fairshare.common.commands.CommandInterceptor
import com.github.monaboiste.fairshare.common.events.EventId
import com.github.monaboiste.fairshare.common.events.PendingEvent
import com.github.monaboiste.fairshare.common.queries.QueryDispatcher
import com.github.monaboiste.fairshare.pricing.component.Validity
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.application.command.AddParticipant
import com.github.monaboiste.fairshare.settlement.application.command.CancelExpense
import com.github.monaboiste.fairshare.settlement.application.command.CancelRepayment
import com.github.monaboiste.fairshare.settlement.application.command.CloseSettlement
import com.github.monaboiste.fairshare.settlement.application.command.ConfigureExchangeRate
import com.github.monaboiste.fairshare.settlement.application.command.OpenSettlement
import com.github.monaboiste.fairshare.settlement.application.command.RecordExpense
import com.github.monaboiste.fairshare.settlement.application.command.RecordRepayment
import com.github.monaboiste.fairshare.settlement.application.command.RemoveParticipant
import com.github.monaboiste.fairshare.settlement.application.command.RenameParticipant
import com.github.monaboiste.fairshare.settlement.application.command.RenameSettlement
import com.github.monaboiste.fairshare.settlement.application.command.ReopenSettlement
import com.github.monaboiste.fairshare.settlement.application.command.handler.OpenSettlementHandler
import com.github.monaboiste.fairshare.settlement.application.query.GetProposedRepayments
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlement
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlementHistory
import com.github.monaboiste.fairshare.settlement.application.query.SettlementViews
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetSettlementHandler
import com.github.monaboiste.fairshare.settlement.config.FairShareConfiguration
import com.github.monaboiste.fairshare.settlement.config.SettlementRuntimeConfiguration
import com.github.monaboiste.fairshare.settlement.config.SettlementStorageConfiguration
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantName
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementIsClosed
import com.github.monaboiste.fairshare.settlement.domain.SettlementName
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import com.github.monaboiste.fairshare.settlement.domain.model.SettlementRepository
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.money.Monetary
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.Ordered
import org.springframework.core.env.MapPropertySource
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class SpringSettlementSpec extends Specification {
    def "typed database settings use the #settings timeout without automatic schema setup"() {
        given:
        def context = new AnnotationConfigApplicationContext()
        def properties = [
            "fairshare.database.url": "jdbc:postgresql://localhost:1/not_connected",
            "fairshare.database.username": "example",
            "fairshare.database.password": "secret"
        ]
        if (configured != null) {
            properties.put("fairshare.database.timeout-seconds", configured)
        }
        context.environment.propertySources.addFirst(new MapPropertySource("database", properties))
        context.register(SettlementStorageConfiguration)

        when:
        context.refresh()
        def jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate)
        def source = context.getBean(javax.sql.DataSource)

        then:
        jdbc.queryTimeout == seconds
        source.url == "jdbc:postgresql://localhost:1/not_connected"
        source.username == "example"
        source.password == "secret"
        source.connectionProperties.getProperty("connectTimeout") == seconds.toString()
        source.connectionProperties.getProperty("socketTimeout") == seconds.toString()
        source.connectionProperties.getProperty("cancelSignalTimeout") == seconds.toString()
        source.connectionProperties.getProperty("options") == "-c statement_timeout=${milliseconds}"

        cleanup:
        context?.close()

        where:
        settings   | configured | seconds | milliseconds
        "default"  | null       | 10      | 10000
        "explicit" | "3"        | 3       | 3000
    }

    def "a #setting value of #value prevents ready Spring dispatch"() {
        given:
        def database = new PostgresFixture()
        def context = configuredApplication(database)
        context.environment.propertySources.addFirst(new MapPropertySource("invalid", [(setting): value]))

        when:
        context.refresh()

        then:
        thrown(org.springframework.beans.BeansException)
        !context.active

        cleanup:
        context?.close()
        database?.close()

        where:
        setting                              | value
        "fairshare.database.timeout-seconds" | "0"
        "fairshare.database.timeout-seconds" | "-1"
        "fairshare.consumer.batch-limit"     | "0"
        "fairshare.consumer.poll-millis"     | "0"
        "fairshare.consumer.retry-millis"    | "0"
    }

    def "a #registration #family handler prevents Spring startup"() {
        given:
        def database = new PostgresFixture()
        def context = configuredApplication(database)
        if (registration == "missing") {
            context.addBeanFactoryPostProcessor({ factory -> context.removeBeanDefinition(family == "command" ? "openSettlementHandler" : "getSettlementHandler") } as BeanFactoryPostProcessor)
        } else if (family == "command") {
            context.registerBean("duplicateOpening", OpenSettlementHandler, { -> new OpenSettlementHandler(context.getBean(SettlementRepository), context.getBean(Clock)) } as java.util.function.Supplier)
        } else {
            context.registerBean("duplicateView", GetSettlementHandler, { -> new GetSettlementHandler(context.getBean(SettlementViews)) } as java.util.function.Supplier)
        }

        when:
        context.refresh()

        then:
        thrown(org.springframework.beans.BeansException)
        !context.active

        cleanup:
        context?.close()
        database?.close()

        where:
        registration | family
        "missing"    | "command"
        "duplicate"  | "command"
        "missing"    | "query"
        "duplicate"  | "query"
    }

    def "the Boot offline profile rebuilds stored history without enabling normal dispatch"() {
        given:
        def database = new PostgresFixture()
        def id = SettlementId.random()
        database.store().append(id, 0, [new PendingEvent(EventId.random(), new SettlementOpened("Offline", Monetary.getCurrency("EUR")), Instant.EPOCH)])
        def application = new SpringApplication(FairShareConfiguration)
        application.webApplicationType = WebApplicationType.NONE
        application.registerShutdownHook = false

        when:
        def context = application.run("--spring.profiles.active=rebuild", "--spring.main.banner-mode=off",
            "--fairshare.database.url=${database.dataSource.url}".toString(),
            "--fairshare.database.username=${database.database.username}".toString(),
            "--fairshare.database.password=${database.database.password}".toString())
        def views = context.getBean(PostgresSettlementProjection)

        then:
        views.checkpoint() == 1
        views.findById(id).get().name() == "Offline"
        context.getBeansOfType(CommandDispatcher).isEmpty()
        context.getBeansOfType(QueryDispatcher).isEmpty()
        context.getBeansOfType(SettlementLogConsumer).isEmpty()

        cleanup:
        context?.close()
        database?.close()
    }

    def "Spring orders command interceptors before exact-class handler dispatch"() {
        given:
        def database = new PostgresFixture()
        def context = configuredApplication(database)
        def calls = []
        context.registerBean("inner", RecordingInterceptor, { -> new RecordingInterceptor(20, "inner", calls) } as java.util.function.Supplier)
        context.registerBean("outer", RecordingInterceptor, { -> new RecordingInterceptor(-10, "outer", calls) } as java.util.function.Supplier)
        context.refresh()

        when:
        def result = context.getBean(CommandDispatcher).dispatch(new OpenSettlement(new SettlementName("Holiday"), Monetary.getCurrency("EUR")))

        then:
        result.success()
        calls == ["outer before", "inner before", "inner after", "outer after"]

        when:
        context.getBean(SettlementLogConsumer).stop()
        context.getBean(CommandDispatcher).dispatch(new RenameSettlement(result.getSuccess().streamId(), new SettlementName("Blocked")))

        then:
        thrown(IllegalStateException)
        calls.size() == 4

        cleanup:
        context?.close()
        database?.close()
    }

    def "a blocked startup recovery fails within the database timeout instead of accepting dispatch"() {
        given:
        def database = new PostgresFixture()
        def id = SettlementId.random()
        database.store().append(id, 0, [new PendingEvent(EventId.random(), new SettlementOpened("Blocked", Monetary.getCurrency("EUR")), Instant.EPOCH)])
        def held = database.dataSource.connection
        held.autoCommit = false
        held.createStatement().withCloseable { it.executeQuery("SELECT position FROM projection_checkpoint FOR UPDATE").close() }
        def executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        def context = configuredApplication(database)
        def startup = executor.submit({ -> context.refresh() } as Runnable)

        when:
        startup.get(5, java.util.concurrent.TimeUnit.SECONDS)

        then:
        def failure = thrown(java.util.concurrent.ExecutionException)
        failure.cause instanceof org.springframework.beans.BeansException
        !context.active
        database.store().load(id).size() == 1
        new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec()).checkpoint() == 0

        cleanup:
        held?.close()
        context?.close()
        executor?.shutdownNow()
        database?.close()
    }

    def "shutdown finishes when a live projection is blocked by a database lock"() {
        given:
        def database = new PostgresFixture()
        def context = application(database)
        def views = context.getBean(PostgresSettlementProjection)
        def consumer = context.getBean(SettlementLogConsumer)
        def held = database.dataSource.connection
        held.autoCommit = false
        held.createStatement().withCloseable { it.executeQuery("SELECT position FROM projection_checkpoint FOR UPDATE").close() }
        def executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        def opened = context.getBean(CommandDispatcher).dispatch(new OpenSettlement(new SettlementName("Blocked"), Monetary.getCurrency("EUR")))
        database.awaitProjectionLockWait()

        when:
        def shutdown = executor.submit({ -> context.close() } as Runnable)
        shutdown.get(5, java.util.concurrent.TimeUnit.SECONDS)

        then:
        !consumer.running
        !consumer.ready()
        views.checkpoint() == 0
        views.findById(opened.getSuccess().streamId()).empty

        cleanup:
        held?.close()
        shutdown?.get(10, java.util.concurrent.TimeUnit.SECONDS)
        context?.close()
        executor?.shutdownNow()
        database?.close()
    }

    def "Spring startup cannot expose ready dispatch before the stored high-water mark is projected"() {
        given:
        def database = new PostgresFixture()
        def id = SettlementId.random()
        database.store().append(id, 0, [new PendingEvent(EventId.random(), new SettlementOpened("Recovered", Monetary.getCurrency("EUR")), Instant.EPOCH)])
        def held = database.dataSource.connection
        held.autoCommit = false
        held.createStatement().withCloseable { it.executeQuery("SELECT position FROM projection_checkpoint FOR UPDATE").close() }
        def executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        def initializing = new java.util.concurrent.CountDownLatch(1)
        def context = configuredApplication(database)
        context.addBeanFactoryPostProcessor({ factory -> initializing.countDown() } as BeanFactoryPostProcessor)
        def startup = executor.submit({ -> context.refresh() } as Runnable)
        assert initializing.await(10, java.util.concurrent.TimeUnit.SECONDS)

        when:
        startup.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)

        then:
        thrown(java.util.concurrent.TimeoutException)
        !startup.done

        when:
        held.commit()
        startup.get(10, java.util.concurrent.TimeUnit.SECONDS)
        def view = context.getBean(QueryDispatcher).dispatch(new GetSettlement(id))

        then:
        context.getBean(SettlementLogConsumer).ready()
        view.getSuccess().name() == "Recovered"
        view.getSuccess().version() == 1

        cleanup:
        held?.close()
        startup?.get(10, java.util.concurrent.TimeUnit.SECONDS)
        context?.close()
        executor?.shutdownNow()
        database?.close()
    }

    def "durable Spring dispatch preserves frozen Valuations, ordered Balances and Settlement lifecycle"() {
        given:
        def database = new PostgresFixture()
        def context = application(database)
        def commands = context.getBean(CommandDispatcher)
        def eur = Monetary.getCurrency("EUR")
        def usd = Monetary.getCurrency("USD")
        def id = commands.dispatch(new OpenSettlement(new SettlementName("Holiday"), eur)).getSuccess().streamId()
        def bob = new ParticipantId(new UUID(0, 2))
        def amy = new ParticipantId(new UUID(0, 1))
        def retired = new ParticipantId(new UUID(0, 3))
        def expense = new ExpenseId(new UUID(0, 4))
        def repayment = new RepaymentId(new UUID(0, 5))
        def date = LocalDate.of(2026, 10, 4)
        assert commands.dispatch(new AddParticipant(id, bob, new ParticipantName("Bob"))).success()
        assert commands.dispatch(new AddParticipant(id, amy, new ParticipantName("Amy"))).success()
        assert commands.dispatch(new AddParticipant(id, retired, new ParticipantName("Sam"))).success()
        assert commands.dispatch(new RenameParticipant(id, bob, new ParticipantName("Robert"))).success()
        assert commands.dispatch(new RemoveParticipant(id, retired)).success()
        assert commands.dispatch(new ConfigureExchangeRate(id, ExchangeRate.of(usd, eur, 0.9G), Validity.always())).success()
        assert commands.dispatch(new RecordExpense(id, expense, new ExpenseDescription("Dinner"), date, amy, Money.of(100, "USD"), new EqualShareAllocation([bob, amy]))).success()
        assert commands.dispatch(new ConfigureExchangeRate(id, ExchangeRate.of(usd, eur, 0.8G), Validity.always())).success()
        assert commands.dispatch(new RecordRepayment(id, repayment, date, bob, amy, Money.of(10, "EUR"))).success()

        when:
        context.close()
        context = application(database)
        commands = context.getBean(CommandDispatcher)
        def queries = context.getBean(QueryDispatcher)
        def view = queries.dispatch(new GetSettlement(id)).getSuccess()
        def proposed = queries.dispatch(new GetProposedRepayments(id)).getSuccess()

        then:
        view.participants()*.id() == [bob, amy]
        view.balances().keySet().toList() == [bob, amy]
        view.balances().values()*.value() == [-35G, 35G]
        view.expenses().first().valuation().value() == 90G
        view.expenses().first().exchangeRate().value() == 0.9G
        view.exchangeRates()*.exchangeRate()*.value() == [0.9G, 0.8G]
        proposed.proposedRepayments()*.amount()*.value() == [35G]
        proposed.proposedRepayments()*.debtor() == [bob]
        proposed.proposedRepayments()*.creditor() == [amy]
        queries.dispatch(new GetSettlementHistory(id)).getSuccess().size() == view.version()

        when:
        assert commands.dispatch(new CancelExpense(id, expense)).success()
        assert commands.dispatch(new CancelRepayment(id, repayment)).success()
        assert commands.dispatch(new CloseSettlement(id)).success()
        def rejected = commands.dispatch(new AddParticipant(id, retired, new ParticipantName("Sam")))
        assert commands.dispatch(new RenameSettlement(id, new SettlementName("Settled"))).success()
        assert commands.dispatch(new ReopenSettlement(id)).success()
        new PollingConditions(timeout: 10).eventually {
            assert queries.dispatch(new GetSettlement(id)).getSuccess().status() == SettlementStatus.OPEN
            assert queries.dispatch(new GetSettlement(id)).getSuccess().name() == "Settled"
        }
        def finalView = queries.dispatch(new GetSettlement(id)).getSuccess()
        context.close()
        new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec()).rebuildOffline(database.store(), 1)
        context = application(database)

        then:
        rejected.getFailure() instanceof SettlementIsClosed
        finalView.balances().values()*.value() == [0G, 0G]
        finalView.expenses()*.status()*.name() == ["CANCELLED"]
        finalView.repayments()*.status()*.name() == ["CANCELLED"]
        context.getBean(QueryDispatcher).dispatch(new GetSettlement(id)).getSuccess() == finalView
        context.getBean(CommandDispatcher).dispatch(new AddParticipant(id, retired, new ParticipantName("Sam"))).getSuccess().events().empty

        cleanup:
        context?.close()
        database?.close()
    }

    def "Spring-managed dispatchers commit commands and recover query views on restart"() {
        given:
        def database = new PostgresFixture()
        def context = application(database)
        def commands = context.getBean(CommandDispatcher)

        when:
        def opened = commands.dispatch(new OpenSettlement(new SettlementName("Holiday"), Monetary.getCurrency("EUR")))
        def id = opened.getSuccess().streamId()
        commands.dispatch(new RenameSettlement(id, new SettlementName("Mountains")))
        context.close()
        context = application(database)
        def queries = context.getBean(QueryDispatcher)
        def view = queries.dispatch(new GetSettlement(id))

        then:
        context.getBean(SettlementLogConsumer).ready()
        view.getSuccess().name() == "Mountains"
        view.getSuccess().version() == 2

        cleanup:
        context?.close()
        database?.close()
    }

    static class RecordingInterceptor implements CommandInterceptor, Ordered {
        final int order
        final String name
        final List calls

        RecordingInterceptor(int order, String name, List calls) {
            this.order = order
            this.name = name
            this.calls = calls
        }

        @Override
        int getOrder() {
            order
        }

        @Override
        Result intercept(Command command, CommandInterceptor.Proceed next) {
            calls.add("${name} before".toString())
            def result = next.handle(command)
            calls.add("${name} after".toString())
            result
        }
    }

    private static AnnotationConfigApplicationContext application(PostgresFixture database) {
        def context = configuredApplication(database)
        context.refresh()
        context
    }

    private static AnnotationConfigApplicationContext configuredApplication(PostgresFixture database) {
        def context = new AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(new MapPropertySource("database", [
            "fairshare.database.url": database.dataSource.url,
            "fairshare.database.username": database.database.username,
            "fairshare.database.password": database.database.password,
            "fairshare.consumer.poll-millis": "20",
            "fairshare.database.timeout-seconds": "1"
        ]))
        context.register(SettlementRuntimeConfiguration)
        context
    }
}
