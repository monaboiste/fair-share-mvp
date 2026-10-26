package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.github.monaboiste.fairshare.settlement.domain.event.ExchangeRateConfigured;
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseCancelled;
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseRecorded;
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantAdded;
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRemoved;
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRenamed;
import com.github.monaboiste.fairshare.settlement.domain.event.RepaymentCancelled;
import com.github.monaboiste.fairshare.settlement.domain.event.RepaymentRecorded;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementClosed;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementReopened;
import com.github.monaboiste.fairshare.settlement.infrastructure.codecs.DurableJson;

public final class SettlementEventCodec {
    public String encode(SettlementEvent event) {
        return DurableJson.write(event);
    }

    public SettlementEvent decode(String type, int version, String document) {
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported Settlement event schema: " + version);
        }
        Class<? extends SettlementEvent> eventType =
                switch (type) {
                    case "SettlementOpened" -> SettlementOpened.class;
                    case "SettlementRenamed" -> SettlementRenamed.class;
                    case "SettlementClosed" -> SettlementClosed.class;
                    case "SettlementReopened" -> SettlementReopened.class;
                    case "ParticipantAdded" -> ParticipantAdded.class;
                    case "ParticipantRenamed" -> ParticipantRenamed.class;
                    case "ParticipantRemoved" -> ParticipantRemoved.class;
                    case "ExpenseCancelled" -> ExpenseCancelled.class;
                    case "RepaymentCancelled" -> RepaymentCancelled.class;
                    case "ExchangeRateConfigured" -> ExchangeRateConfigured.class;
                    case "RepaymentRecorded" -> RepaymentRecorded.class;
                    case "ExpenseRecorded" -> ExpenseRecorded.class;
                    default -> throw new IllegalArgumentException("Unsupported Settlement event type: " + type);
                };
        return read(document, eventType);
    }

    String write(Object value) {
        return DurableJson.write(value);
    }

    <T> T read(String document, Class<T> type) {
        return DurableJson.read(document, type);
    }
}
