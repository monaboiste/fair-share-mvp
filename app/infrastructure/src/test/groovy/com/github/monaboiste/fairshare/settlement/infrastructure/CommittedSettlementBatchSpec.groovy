package com.github.monaboiste.fairshare.settlement.infrastructure

import com.github.monaboiste.fairshare.common.events.EventEnvelope
import com.github.monaboiste.fairshare.common.events.EventId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed
import java.time.Instant
import spock.lang.Specification

class CommittedSettlementBatchSpec extends Specification {
    private final SettlementId settlement = SettlementId.random()

    def "a committed batch keeps its events and replay bounds after the caller changes the list"() {
        given: "two consecutive events after a previous checkpoint"
        def first = rename(4, 12, "Summer")
        def last = rename(5, 13, "Summer trip")
        def events = [first, last]

        when: "the caller clears its list after committing the batch"
        def batch = new CommittedSettlementLog.Batch(events)
        events.clear()

        then: "the consumer can still replay both events and advance to the last global position"
        batch.events() == [first, last]
        batch.firstPosition() == 12
        batch.lastPosition() == 13
    }

    def "a committed batch rejects #invalidHistory"() {
        when: "a batch is built from the supplied global positions"
        new CommittedSettlementLog.Batch(positions.withIndex().collect { position, index ->
            rename(index + 1, position, "Trip")
        })

        then: "the invalid history cannot be consumed as one append"
        thrown(IllegalArgumentException)

        where:
        invalidHistory          | positions
        "an empty append"       | []
        "a missing position"    | [12, 14]
        "a duplicate position"  | [12, 12]
        "out-of-order delivery" | [13, 12]
    }

    private EventEnvelope<SettlementId, SettlementEvent> rename(long sequence, long position, String name) {
        new EventEnvelope<>(settlement, sequence, position, EventId.random(), Instant.EPOCH, new SettlementRenamed(name))
    }
}
