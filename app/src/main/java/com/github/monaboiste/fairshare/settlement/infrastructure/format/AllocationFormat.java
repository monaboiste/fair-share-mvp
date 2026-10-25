package com.github.monaboiste.fairshare.settlement.infrastructure.format;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ExactShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.ShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.WeightedShareAllocation;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AllocationFormat.Equal.class, name = "equal"),
    @JsonSubTypes.Type(value = AllocationFormat.Exact.class, name = "exact"),
    @JsonSubTypes.Type(value = AllocationFormat.Weighted.class, name = "weighted")
})
sealed interface AllocationFormat {
    record Recipient(ParticipantId participantId) {}

    record Amount(ParticipantId participantId, Money amount) {}

    record Weight(ParticipantId participantId, int weight) {}

    record Equal(List<Recipient> entries) implements AllocationFormat {}

    record Exact(List<Amount> entries) implements AllocationFormat {}

    record Weighted(List<Weight> entries) implements AllocationFormat {}

    final class Encode extends JsonSerializer<ShareAllocation> {
        @Override
        public void serialize(ShareAllocation allocation, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            AllocationFormat format =
                    switch (allocation) {
                        case EqualShareAllocation(var recipients) ->
                            new Equal(recipients.stream().map(Recipient::new).toList());
                        case ExactShareAllocation(var amounts) ->
                            new Exact(amounts.entrySet().stream()
                                    .map(entry -> new Amount(entry.getKey(), entry.getValue()))
                                    .toList());
                        case WeightedShareAllocation(var weights) ->
                            new Weighted(weights.entrySet().stream()
                                    .map(entry -> new Weight(entry.getKey(), entry.getValue()))
                                    .toList());
                    };
            provider.defaultSerializeValue(format, generator);
        }
    }

    final class Decode extends JsonDeserializer<ShareAllocation> {
        @Override
        public ShareAllocation deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            AllocationFormat format = parser.readValueAs(AllocationFormat.class);
            return switch (format) {
                case Equal(var entries) ->
                    new EqualShareAllocation(
                            entries.stream().map(Recipient::participantId).toList());
                case Exact(var entries) -> {
                    LinkedHashMap<ParticipantId, Money> amounts = new LinkedHashMap<>();
                    entries.forEach(entry -> amounts.put(entry.participantId(), entry.amount()));
                    yield new ExactShareAllocation(amounts);
                }
                case Weighted(var entries) -> {
                    LinkedHashMap<ParticipantId, Integer> weights = new LinkedHashMap<>();
                    entries.forEach(entry -> weights.put(entry.participantId(), entry.weight()));
                    yield new WeightedShareAllocation(weights);
                }
            };
        }
    }
}
