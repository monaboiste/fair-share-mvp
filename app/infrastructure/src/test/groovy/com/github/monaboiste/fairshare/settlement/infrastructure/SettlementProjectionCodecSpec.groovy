package com.github.monaboiste.fairshare.settlement.infrastructure

import com.fasterxml.jackson.databind.json.JsonMapper
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId
import com.github.monaboiste.fairshare.pricing.component.Validity
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.application.query.ExpenseView
import com.github.monaboiste.fairshare.settlement.application.query.ParticipantView
import com.github.monaboiste.fairshare.settlement.application.query.RepaymentView
import com.github.monaboiste.fairshare.settlement.application.query.SettlementView
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus
import com.github.monaboiste.fairshare.settlement.domain.Share
import com.github.monaboiste.fairshare.settlement.domain.netting.Obligation
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion
import java.time.LocalDate
import java.time.LocalDateTime
import javax.money.Monetary
import spock.lang.Specification

class SettlementProjectionCodecSpec extends Specification {
    private static final SettlementId ID = new SettlementId(new UUID(0, 8))
    private static final ParticipantId PAYER = new ParticipantId(new UUID(0, 1))
    private static final ParticipantId RECIPIENT = new ParticipantId(new UUID(0, 2))
    private static final ParticipantId RETIRED = new ParticipantId(new UUID(0, 9))
    private static final ComponentVersionId RATE_VERSION = new ComponentVersionId(new UUID(0, 5))
    private static final EUR = Monetary.getCurrency("EUR")
    private static final ExchangeRate RATE = ExchangeRate.of(Monetary.getCurrency("USD"), EUR, 0.9G)

    def codec = new SettlementProjectionCodec(new SettlementEventCodec())
    def mapper = JsonMapper.builder().build()
    def fixtureJson = getClass().getResource("/settlement-projection-v1.json").getText("UTF-8")

    def "a populated projection interchanges with fixed version-one JSON without losing financial history"() {
        given:
        def expected = holidayProjection()

        when:
        def decoded = codec.decode(fixtureJson)
        def encoded = codec.encode(expected)

        then:
        decoded == expected
        decoded.view().balances().keySet().toList() == [RECIPIENT, PAYER]
        mapper.readTree(encoded) == mapper.readTree(fixtureJson)
    }

    def "an empty #status Settlement retains its lifecycle and empty collections"() {
        given:
        def json = """
            {
              "schemaVersion": 1,
              "id": "00000000-0000-0000-0000-000000000008",
              "name": "Holiday",
              "currency": "EUR",
              "version": 1,
              "status": "$status",
              "participants": [],
              "retired": [],
              "expenses": [],
              "repayments": [],
              "obligations": [],
              "balances": [],
              "exchangeRates": []
            }
            """
        def expected = new SettlementProjector.Projection(
                new SettlementView(ID, "Holiday", EUR, 1, [], [], [], [], [:], [], status), Set.of())

        when:
        def decoded = codec.decode(json)

        then:
        decoded == expected
        mapper.readTree(codec.encode(decoded)) == mapper.readTree(json)

        where:
        status << [SettlementStatus.OPEN, SettlementStatus.CLOSED]
    }

    def "a projection with stream version #version cannot be written or restored"() {
        given:
        def projection = new SettlementProjector.Projection(
                new SettlementView(ID, "Holiday", EUR, version, [], [], [], [], [:], [], SettlementStatus.OPEN), Set.of())
        def document = mapper.readTree(fixtureJson)
        document.put("version", version)

        when:
        codec.encode(projection)

        then:
        thrown(IllegalArgumentException)

        when:
        codec.decode(document.toString())

        then:
        thrown(IllegalArgumentException)

        where:
        version << [0L, -1L]
    }

    def "an unsupported projection schema #version is rejected rather than guessed"() {
        given:
        def document = mapper.readTree(fixtureJson)
        document.put("schemaVersion", version)

        when:
        codec.decode(document.toString())

        then:
        thrown(IllegalArgumentException)

        where:
        version << [0, 2]
    }

    def "a projection requires #field rather than a #shape field"() {
        given:
        def document = mapper.readTree(fixtureJson)
        if (shape == "missing") {
            document.remove(field)
        } else {
            document.putNull(field)
        }

        when:
        codec.decode(document.toString())

        then:
        thrown(IllegalArgumentException)

        where:
        [field, shape] << [
                ["schemaVersion", "id", "name", "currency", "version", "status", "participants", "retired",
                 "expenses", "repayments", "obligations", "balances", "exchangeRates"],
                ["missing", "null"]
        ].combinations()
    }

    def "a projection rejects an unknown #history status"() {
        given:
        def document = mapper.readTree(fixtureJson)
        def entry = collection == null ? document : document.get(collection).get(0)
        entry.put("status", "ARCHIVED")

        when:
        codec.decode(document.toString())

        then:
        thrown(IllegalArgumentException)

        where:
        history      | collection
        "Settlement" | null
        "Expense"    | "expenses"
        "Repayment"  | "repayments"
    }

    private static SettlementProjector.Projection holidayProjection() {
        def expenses = [
                expense(3, "Lunch", "2026-10-04", 10, 9, ExpenseView.Status.ACTIVE),
                expense(6, "Dinner", "2026-10-05", 20, 18, ExpenseView.Status.CANCELLED)
        ]
        def repayments = [
                new RepaymentView(new RepaymentId(new UUID(0, 4)), LocalDate.parse("2026-10-06"),
                        RECIPIENT, PAYER, Money.of(4, "EUR"), RepaymentView.Status.ACTIVE),
                new RepaymentView(new RepaymentId(new UUID(0, 7)), LocalDate.parse("2026-10-07"),
                        RECIPIENT, PAYER, Money.of(1, "EUR"), RepaymentView.Status.CANCELLED)
        ]
        def view = new SettlementView(ID, "  Holiday  ", EUR, 14,
                [new ParticipantView(RECIPIENT, "Sam"), new ParticipantView(PAYER, "Sam")],
                expenses, repayments,
                [new Obligation(RECIPIENT, PAYER, Money.of(9, "EUR")),
                 new Obligation(PAYER, RECIPIENT, Money.of(4, "EUR"))],
                [(RECIPIENT): Money.of(-5, "EUR"), (PAYER): Money.of(5, "EUR")],
                [new ExchangeRateVersion(RATE_VERSION, RATE, new Validity(null, null),
                        LocalDateTime.parse("2026-10-01T12:00:00.123456789"))], SettlementStatus.OPEN)
        new SettlementProjector.Projection(view, Set.of(RETIRED))
    }

    private static ExpenseView expense(long id, String description, String incurredOn,
            int originalAmount, int valuation, ExpenseView.Status status) {
        new ExpenseView(new ExpenseId(new UUID(0, id)), new ExpenseDescription(description),
                LocalDate.parse(incurredOn), PAYER, Money.of(originalAmount, "USD"),
                new EqualShareAllocation([RECIPIENT]), RATE_VERSION, RATE, Money.of(valuation, "EUR"),
                [new Share(RECIPIENT, Money.of(valuation, "EUR"))], status)
    }
}
