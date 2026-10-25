package com.github.monaboiste.fairshare.settlement.infrastructure

import com.github.monaboiste.fairshare.common.queries.QueryDispatcher
import com.github.monaboiste.fairshare.settlement.application.query.GetProposedRepayments
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlement
import com.github.monaboiste.fairshare.settlement.config.SettlementRuntimeConfiguration
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import spock.lang.Specification

class ExternalSetupSpec extends Specification {
    def "external setup preserves complete history and startup derives sample Balances and Proposed Repayments"() {
        given:
        def database = new PostgresFixture(false)
        def id = new SettlementId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
        def amy = new ParticipantId(new UUID(0, 1))
        def bob = new ParticipantId(new UUID(0, 2))

        when:
        def setup = database.setup()
        def store = database.store()
        def views = new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec())

        then:
        setup.exitCode == 0
        views.checkpoint() == 0
        views.findById(id).empty
        store.highWaterMark() == 6
        store.load(id)*.sequence() == [1, 2, 3, 4, 5, 6]
        store.load(id)*.occurredAt()*.nano == [123456789] * 6
        store.readBatches(0, 6, 1)*.events()*.size() == [6]

        when:
        def context = new AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(new MapPropertySource("database", [
            "fairshare.database.url": database.dataSource.url,
            "fairshare.database.username": database.database.username,
            "fairshare.database.password": database.database.password
        ]))
        context.register(SettlementRuntimeConfiguration)
        context.refresh()
        def queries = context.getBean(QueryDispatcher)
        def view = queries.dispatch(new GetSettlement(id)).getSuccess()
        def proposed = queries.dispatch(new GetProposedRepayments(id)).getSuccess()

        then:
        view.name() == "Mountain weekend"
        view.version() == 6
        view.balances().keySet().toList() == [amy, bob]
        view.balances().values()*.value() == [35G, -35G]
        view.expenses()*.valuation()*.value() == [90G]
        view.repayments()*.amount()*.value() == [10G]
        proposed.proposedRepayments()*.debtor() == [bob]
        proposed.proposedRepayments()*.creditor() == [amy]
        proposed.proposedRepayments()*.amount()*.value() == [35G]

        when:
        context.close()
        def repeated = database.setup()
        views.rebuildOffline(store, 1)

        then:
        repeated.exitCode != 0
        repeated.stderr.contains("empty public schema")
        store.load(id).size() == 6
        views.findById(id).get() == view

        cleanup:
        context?.close()
        database?.close()
    }

    def "a failed sample script leaves no partial schema and a later explicit setup succeeds"() {
        given:
        def database = new PostgresFixture(false)

        when:
        def failed = database.setup("SELECT 1 / 0;")
        def retry = database.setup()

        then:
        failed.exitCode != 0
        retry.exitCode == 0
        database.store().highWaterMark() == 6

        cleanup:
        database?.close()
    }
}
