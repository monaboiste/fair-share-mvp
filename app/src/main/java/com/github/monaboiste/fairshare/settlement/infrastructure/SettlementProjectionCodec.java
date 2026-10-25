package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.github.monaboiste.fairshare.settlement.infrastructure.format.ProjectionFormat;
import java.util.Set;

final class SettlementProjectionCodec {
    private final SettlementEventCodec codec;

    SettlementProjectionCodec(SettlementEventCodec codec) {
        this.codec = codec;
    }

    String encode(SettlementProjector.Projection projection) {
        return codec.write(ProjectionFormat.from(projection.view(), projection.retiredParticipantIds()));
    }

    SettlementProjector.Projection decode(String json) {
        ProjectionFormat document = codec.read(json, ProjectionFormat.class);
        return new SettlementProjector.Projection(document.view(), Set.copyOf(document.retired()));
    }
}
