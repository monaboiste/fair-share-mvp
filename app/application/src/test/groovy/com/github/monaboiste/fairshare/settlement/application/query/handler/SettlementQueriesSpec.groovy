package com.github.monaboiste.fairshare.settlement.application.query.handler

import com.github.monaboiste.fairshare.common.events.EventId
import com.github.monaboiste.fairshare.common.events.EventStreamReader
import com.github.monaboiste.fairshare.common.events.PendingEvent
import com.github.monaboiste.fairshare.common.events.StreamNotFoundException
import com.github.monaboiste.fairshare.common.events.inmemory.InMemoryEventStore
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.application.query.GetProposedRepayments
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlement
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlementHistory
import com.github.monaboiste.fairshare.settlement.application.query.ParticipantView
import com.github.monaboiste.fairshare.settlement.application.query.ProposedRepaymentGraphView
import com.github.monaboiste.fairshare.settlement.application.query.SettlementView
import com.github.monaboiste.fairshare.settlement.application.query.SettlementViews
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementNotFound
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed
import com.github.monaboiste.fairshare.settlement.domain.netting.Obligation
import com.github.monaboiste.fairshare.settlement.domain.netting.ProposedRepayment
import java.time.Instant
import javax.money.Monetary
import spock.lang.Specification

class SettlementQueriesSpec extends Specification {
    private final id = SettlementId.random()
    private final eur = Monetary.getCurrency("EUR")
    private final SettlementViews views = Stub()
    private final streams = new InMemoryEventStore<SettlementId, SettlementEvent>()

    def "proposals net opposite obligations and order Participants without changing the source view"() {
        given: "a projection with Participants in arrival order and a partial repayment"
        def ada = new ParticipantView(new ParticipantId(new UUID(0, 1)), "Ada")
        def bob = new ParticipantView(new ParticipantId(new UUID(0, 2)), "Bob")
        def view = new SettlementView(id, "Trip", eur, 6, [bob, ada], [], [], [
                new Obligation(bob.id(), ada.id(), Money.of(10, "EUR")),
                new Obligation(ada.id(), bob.id(), Money.of(3, "EUR"))
        ], [(bob.id()): Money.of(-7, "EUR"), (ada.id()): Money.of(7, "EUR")], [], SettlementStatus.OPEN)
        views.findById(id) >> Optional.of(view)

        when: "the proposal and current Settlement are queried at the same version"
        def proposed = new GetProposedRepaymentsHandler(views).handle(new GetProposedRepayments(id)).getSuccess()
        def current = new GetSettlementHandler(views).handle(new GetSettlement(id)).getSuccess()

        then: "Bob owes only the difference and the projected roster keeps its original order"
        proposed == new ProposedRepaymentGraphView(id, eur, 6, [ada, bob],
                [new ProposedRepayment(bob.id(), ada.id(), Money.of(7, "EUR"))])
        current == view
        current.participants() == [bob, ada]
    }

    def "history is available before the first projection and excludes other streams"() {
        given: "two Settlements are opened before the requested one is renamed"
        def opened = streams.append(id, 0, [pending(new SettlementOpened("Trip", eur))])
        streams.append(SettlementId.random(), 0, [pending(new SettlementOpened("Other", eur))])
        def renamed = streams.append(id, 1, [pending(new SettlementRenamed("Holiday"))])
        views.findById(id) >> Optional.empty()

        when: "history and projection queries read from their respective sources"
        def history = new GetSettlementHistoryHandler(streams).handle(new GetSettlementHistory(id))
        def current = new GetSettlementHandler(views).handle(new GetSettlement(id))
        def proposed = new GetProposedRepaymentsHandler(views).handle(new GetProposedRepayments(id))

        then: "committed envelopes remain readable even though neither projected view exists yet"
        history.getSuccess() == opened.events() + renamed.events()
        current.getFailure() == new SettlementNotFound(id)
        proposed.getFailure() == new SettlementNotFound(id)
    }

    def "history rejects an unknown stream instead of returning empty history"() {
        when: "a Settlement with no committed facts is requested"
        def result = new GetSettlementHistoryHandler(streams).handle(new GetSettlementHistory(id))

        then: "the rejection identifies the missing Settlement"
        result.getFailure() == new SettlementNotFound(id)
    }

    def "a stream lost during a history read surfaces as a store failure"() {
        given: "the stream existed at lookup but cannot be loaded"
        def failure = new StreamNotFoundException(id)
        EventStreamReader reader = Stub {
            exists(id) >> true
            load(id) >> { throw failure }
        }

        when: "the caller requests its committed history"
        new GetSettlementHistoryHandler(reader).handle(new GetSettlementHistory(id))

        then: "the read error is not converted to a not-found query result"
        def error = thrown(StreamNotFoundException)
        error.is(failure)
    }

    private static PendingEvent<SettlementEvent> pending(SettlementEvent fact) {
        new PendingEvent<>(EventId.random(), fact, Instant.EPOCH)
    }
}
