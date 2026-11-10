package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
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
}
