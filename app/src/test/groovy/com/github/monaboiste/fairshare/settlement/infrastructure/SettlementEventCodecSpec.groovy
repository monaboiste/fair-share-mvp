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

    def "an Expense rejects #shape in #path"() {
        given:
        def mapper = new ObjectMapper()
        def fixtures = mapper.readTree(getClass().getResource("/settlement-events-v1.json"))
        def document = fixtures.last().get("payload").deepCopy()
        def parent = document
        def fields = path.split("/")
        fields.dropRight(1).each { parent = it.isInteger() ? parent.get(it.toInteger()) : parent.get(it) }
        if (json == "missing") {
            parent.remove(fields.last())
        } else {
            parent.set(fields.last(), mapper.readTree(json))
        }

        when:
        new SettlementEventCodec().decode("ExpenseRecorded", 1, document.toString())

        then:
        thrown(IllegalArgumentException)

        where:
        path                          | shape              | json
        "exchangeRateOverride"        | "missing override" | "missing"
        "shares"                      | "null element"     | "[null]"
        "payer"                       | "Base64 UUID"      | "\"AAAAAAAAAAAAAAAAAAAAAQ==\""
        "description"                 | "boolean text"     | "true"
        "originalAmount/amount"       | "numeric decimal"  | "100"
        "originalAmount/currency"     | "null currency"    | "null"
        "allocation/entries"          | "null entries"     | "null"
        "allocation/entries"          | "null element"     | "[null]"
        "allocation/entries/0/weight" | "text integer"     | "\"3\""
        "allocation/entries/0/weight" | "integer overflow" | "2147483648"
        "allocation/entries/0/weight" | "float integer"    | "3.0"
    }

    def "nullable validity endpoints remain present and time strings retain their exact format"() {
        given:
        def codec = new SettlementEventCodec()
        def json = """{"version":{"id":"00000000-0000-0000-0000-000000000005","rate":{"source":"USD","target":"EUR","value":"0.9"},"from":null,"to":"2026-10-04T12:00","definedAt":"2026-10-04T00:00"}}"""
        def mapper = new ObjectMapper()

        when:
        def event = codec.decode("ExchangeRateConfigured", 1, json)

        then:
        mapper.readTree(codec.encode(event)) == mapper.readTree(json)

        when:
        codec.decode("ExchangeRateConfigured", 1, json.replace("\"from\":null,", ""))

        then:
        thrown(IllegalArgumentException)
    }

    def "historical #kind Allocations retain #history without business validation"() {
        given:
        def mapper = new ObjectMapper()
        def fixtures = mapper.readTree(getClass().getResource("/settlement-events-v1.json"))
        def document = fixtures.last().get("payload").deepCopy()
        document.set("allocation", mapper.readTree(allocation))
        document.put("futureProperty", "ignored")

        when:
        def event = new SettlementEventCodec().decode("ExpenseRecorded", 1, document.toString())

        then:
        event.allocation().recipients()*.value() == recipients.collect { new UUID(0, it) }
        event.shares()*.amount()*.value() == [49.39G, 49.38G]
        if (weight != null) {
            assert event.allocation().weights().values().toList() == [weight]
        }

        where:
        kind       | history                 | allocation                                                                                                                                                                                            | recipients | weight
        "equal"    | "empty entries"         | """{"kind":"equal","entries":[]}"""                                                                                                                                                              | []         | null
        "equal"    | "duplicate recipients"  | """{"kind":"equal","entries":[{"participantId":"00000000-0000-0000-0000-000000000001"},{"participantId":"00000000-0000-0000-0000-000000000001"}]}"""                                             | [1]        | null
        "exact"    | "empty entries"         | """{"kind":"exact","entries":[]}"""                                                                                                                                                              | []         | null
        "weighted" | "empty entries"         | """{"kind":"weighted","entries":[]}"""                                                                                                                                                           | []         | null
        "weighted" | "nonpositive weights"   | """{"kind":"weighted","entries":[{"participantId":"00000000-0000-0000-0000-000000000001","weight":0}]}"""                                                                                         | [1]        | 0
        "weighted" | "last duplicate weight" | """{"kind":"weighted","entries":[{"participantId":"00000000-0000-0000-0000-000000000001","weight":3},{"participantId":"00000000-0000-0000-0000-000000000001","weight":-2}]}"""                   | [1]        | -2
    }

    def "a #type fact rejects a #shape #field creator value"() {
        when:
        new SettlementEventCodec().decode(type, 1, document)

        then:
        thrown(IllegalArgumentException)

        where:
        [type, field, shape, document] << missingEventFields()
    }

    private static List missingEventFields() {
        def mapper = new ObjectMapper()
        def fixtures = mapper.readTree(SettlementEventCodecSpec.getResource("/settlement-events-v1.json"))
        fixtures.collectMany { fixture ->
            fixture.get("payload").fieldNames().toList().collectMany { field ->
                ["missing", "null"].findAll { shape -> shape != "null" || field != "exchangeRateOverride" }.collect { shape ->
                    def document = fixture.get("payload").deepCopy()
                    if (shape == "missing") {
                        document.remove(field)
                    } else {
                        document.putNull(field)
                    }
                    [fixture.get("type").textValue(), field, shape, document.toString()]
                }
            }
        }
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
