package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.github.monaboiste.fairshare.common.events.AllEventsReader;
import com.github.monaboiste.fairshare.common.events.EventEnvelope;
import com.github.monaboiste.fairshare.settlement.domain.SettlementId;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent;
import java.util.List;

public interface CommittedSettlementLog extends AllEventsReader<SettlementId, SettlementEvent> {
    long highWaterMark();

    List<Batch> readBatches(long afterPosition, long throughPosition, int batchLimit);

    record Batch(List<EventEnvelope<SettlementId, SettlementEvent>> events) {
        public Batch {
            events = List.copyOf(events);
            if (events.isEmpty()) {
                throw new IllegalArgumentException("Committed batch requires events");
            }
            long position = events.getFirst().position();
            for (var event : events) {
                if (event.position() != position++) {
                    throw new IllegalArgumentException("Noncontiguous committed batch");
                }
            }
        }

        public long firstPosition() {
            return events.getFirst().position();
        }

        public long lastPosition() {
            return events.getLast().position();
        }
    }
}
