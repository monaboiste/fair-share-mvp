package com.github.monaboiste.fairshare.settlement.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import javax.money.Monetary
import spock.lang.Specification

class SettlementEventCodecSpec extends Specification {
    def "every durable event variant interchanges with fixed version-one JSON"() {
        given:
        def codec = new SettlementEventCodec()
        def mapper = new ObjectMapper()
        def fixtures = mapper.readTree(getClass().getResource("/settlement-events-v1.json"))
        def events = fixtures.collect { fixture -> codec.decode(fixture.get("type").textValue(), 1, fixture.get("payload").toString()) }

        when:
        def documents = events.collect { event -> mapper.readTree(codec.encode(event)) }

        then:
        documents == fixtures.collect { it.get("payload") }
        events.collect { it.getClass() }.toSet() == SettlementEvent.permittedSubclasses.toSet()
        events.find { it.type() == "RepaymentRecorded" }.amount().value() == new BigDecimal("12345678901234567890.01")
        events.find { it.type() == "ExchangeRateConfigured" }.version().exchangeRate().value().toPlainString() == "0.987654321012"
        events.findAll { it.type() == "ExpenseRecorded" }.every { it.allocation().recipients()*.value() == [new UUID(0, 2), new UUID(0, 1)] }
        events.findAll { it.type() == "ExpenseRecorded" }.every { it.valuation().value() == 98.77G && it.shares()*.amount()*.value() == [49.39G, 49.38G] }
    }

    def "an Expense with #shape Shares is rejected instead of losing its contributions"() {
        given:
        def codec = new SettlementEventCodec()
        def mapper = new ObjectMapper()
        def fixtures = mapper.readTree(getClass().getResource("/settlement-events-v1.json"))
        def document = fixtures.find { it.get("type").textValue() == "ExpenseRecorded" }.get("payload").deepCopy()
        document.set("shares", mapper.readTree(shares))

        when:
        codec.decode("ExpenseRecorded", 1, document.toString())

        then:
        thrown(IllegalArgumentException)

        where:
        shape          | shares
        "null"         | "null"
        "object"       | "{}"
        "string"       | "\"lost\""
        "number"       | "0"
        "boolean"      | "true"
    }

    def "a #type fact requires an object document rather than #shape"() {
        given:
        def codec = new SettlementEventCodec()

        when:
        codec.decode(type, 1, json)

        then:
        thrown(IllegalArgumentException)

        where:
        type                 | shape     | json
        "SettlementClosed"   | "array"   | "[]"
        "SettlementClosed"   | "string"  | "\"closed\""
        "SettlementReopened" | "number"  | "1"
        "SettlementReopened" | "boolean" | "true"
    }

    def "a fractional Share weight is never silently truncated"() {
        given:
        def codec = new SettlementEventCodec()
        def fixtures = new ObjectMapper().readTree(getClass().getResource("/settlement-events-v1.json"))
        def document = fixtures.last().get("payload").deepCopy()
        document.get("allocation").get("entries").get(0).put("weight", 2.5)

        when:
        codec.decode("ExpenseRecorded", 1, document.toString())

        then:
        thrown(IllegalArgumentException)
    }

    def "an unsupported #format is rejected rather than guessed"() {
        given:
        def codec = new SettlementEventCodec()

        when:
        codec.decode(type, version, json)

        then:
        thrown(IllegalArgumentException)

        where:
        format        | type                           | version | json
        "type"        | "java.lang.Runtime"            | 1       | "{}"
        "version"     | "SettlementOpened"             | 2       | "{}"
        "invalid JSON"| "SettlementOpened"             | 1       | "not json"
        "value"       | "SettlementOpened"             | 1       | """{"name":1,"currency":"EUR"}"""
        "allocation"  | "ExpenseRecorded"              | 1       | """{"expenseId":"00000000-0000-0000-0000-000000000003","description":"Dinner","incurredOn":"2026-10-04","payer":"00000000-0000-0000-0000-000000000001","originalAmount":{"amount":"100","currency":"USD"},"allocation":{"kind":"unknown","entries":[]}}"""
    }

    def "an independent opening document preserves spacing and currency"() {
        given:
        def codec = new SettlementEventCodec()
        def json = """{"name":"  Holiday  ","currency":"EUR"}"""

        when:
        def event = codec.decode("SettlementOpened", 1, json)

        then:
        event == new SettlementOpened("  Holiday  ", Monetary.getCurrency("EUR"))
        codec.decode(event.type(), event.schemaVersion(), codec.encode(event)) == event
    }
}
