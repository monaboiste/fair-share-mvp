package com.github.monaboiste.fairshare.settlement.application.query

import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.netting.ProposedRepayment
import javax.money.Monetary
import spock.lang.Specification

class ProposedRepaymentGraphViewSpec extends Specification {
    private static final SettlementId SETTLEMENT = new SettlementId(new UUID(0, 1))
    private static final EUR = Monetary.getCurrency("EUR")
    private static final ParticipantId ADA = new ParticipantId(new UUID(0, 11))
    private static final ParticipantId BOB = new ParticipantId(new UUID(0, 12))

    def "graph snapshots are independent of mutable constructor collections"() {
        given: "mutable semantic vertices and Proposed Repayments"
        def participants = [new ParticipantView(ADA, "Ada"), new ParticipantView(BOB, "Bob")]
        def repayments = [new ProposedRepayment<>(BOB, ADA, Money.of(10, "EUR"))]
        def graph = new ProposedRepaymentGraphView(SETTLEMENT, EUR, 7, participants, repayments)

        when: "the original collections are cleared"
        participants.clear()
        repayments.clear()

        then: "the snapshot retains its semantic graph and projection metadata"
        graph.id() == SETTLEMENT
        graph.currency() == EUR
        graph.version() == 7
        graph.participants() == [new ParticipantView(ADA, "Ada"), new ParticipantView(BOB, "Bob")]
        graph.proposedRepayments() == [new ProposedRepayment<>(BOB, ADA, Money.of(10, "EUR"))]
    }

    def "callers cannot mutate graph #collectionName"() {
        given: "a semantic Proposed Repayment Graph"
        def graph = new ProposedRepaymentGraphView(SETTLEMENT,
                EUR, 7, [new ParticipantView(ADA, "Ada"), new ParticipantView(BOB, "Bob")],
                [new ProposedRepayment<>(BOB, ADA, Money.of(10, "EUR"))])

        when: "a caller tries to remove the graph contents"
        collection.call(graph).clear()

        then: "mutation is refused"
        thrown(UnsupportedOperationException)
        graph.participants().size() == 2
        graph.proposedRepayments().size() == 1

        where:
        collectionName       | collection
        "Participants"       | { it.participants() }
        "Proposed Repayments" | { it.proposedRepayments() }
    }
}
