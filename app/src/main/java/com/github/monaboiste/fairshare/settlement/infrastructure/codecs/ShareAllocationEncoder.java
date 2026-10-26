package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ExactShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.WeightedShareAllocation;
import java.io.IOException;

final class ShareAllocationEncoder extends JsonSerializer<ShareAllocation> {
    @Override
    public void serialize(ShareAllocation allocation, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        AllocationFormat format =
                switch (allocation) {
                    case EqualShareAllocation(var recipients) ->
                        new AllocationFormat.Equal(recipients.stream()
                                .map(AllocationFormat.Recipient::new)
                                .toList());
                    case ExactShareAllocation(var amounts) ->
                        new AllocationFormat.Exact(amounts.entrySet().stream()
                                .map(entry -> new AllocationFormat.Amount(entry.getKey(), entry.getValue()))
                                .toList());
                    case WeightedShareAllocation(var weights) ->
                        new AllocationFormat.Weighted(weights.entrySet().stream()
                                .map(entry -> new AllocationFormat.Weight(entry.getKey(), entry.getValue()))
                                .toList());
                };
        provider.defaultSerializeValue(format, generator);
    }
}
