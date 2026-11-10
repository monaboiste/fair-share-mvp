package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ExactShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.ShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.WeightedShareAllocation;
import java.io.IOException;
import java.util.LinkedHashMap;

final class ShareAllocationDecoder extends JsonDeserializer<ShareAllocation> {
    @Override
    public ShareAllocation deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        AllocationFormat format = parser.readValueAs(AllocationFormat.class);
        return switch (format) {
            case AllocationFormat.Equal(var entries) ->
                new EqualShareAllocation(entries.stream()
                        .map(AllocationFormat.Recipient::participantId)
                        .toList());
            case AllocationFormat.Exact(var entries) -> {
                LinkedHashMap<ParticipantId, Money> amounts = new LinkedHashMap<>();
                entries.forEach(entry -> amounts.put(entry.participantId(), entry.amount()));
                yield new ExactShareAllocation(amounts);
            }
            case AllocationFormat.Weighted(var entries) -> {
                LinkedHashMap<ParticipantId, Integer> weights = new LinkedHashMap<>();
                entries.forEach(entry -> weights.put(entry.participantId(), entry.weight()));
                yield new WeightedShareAllocation(weights);
            }
        };
    }
}
