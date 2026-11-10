package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.monaboiste.fairshare.settlement.application.query.ParticipantView;
import com.github.monaboiste.fairshare.settlement.application.query.SettlementView;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.SettlementId;
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.money.Monetary;

public final class CodecSmoke {
    public static void main(String[] arguments) throws Exception {
        var mapper = JsonMapper.builder().build();
        var codec = new SettlementEventCodec();
        var fixtures = mapper.readTree(Files.readString(Path.of(arguments[0])));
        Set<Class<?>> variants = new HashSet<>();
        for (var fixture : fixtures) {
            String type = fixture.get("type").textValue();
            var payload = fixture.get("payload");
            var event = codec.decode(type, 1, payload.toString());
            variants.add(event.getClass());
            if (!mapper.readTree(codec.encode(event)).equals(payload)) {
                throw new AssertionError(type);
            }
        }
        if (!variants.equals(Set.of(SettlementEvent.class.getPermittedSubclasses()))) {
            throw new AssertionError(variants);
        }
        var participant = new ParticipantId(new UUID(0, 2));
        var view = new SettlementView(
                new SettlementId(new UUID(0, 1)),
                "Holiday",
                Monetary.getCurrency("EUR"),
                1,
                List.of(new ParticipantView(participant, "Ada")),
                List.of(),
                List.of(),
                List.of(),
                new LinkedHashMap<>(),
                List.of(),
                SettlementStatus.OPEN);
        var projectionCodec = new SettlementProjectionCodec(codec);
        var projection = new SettlementProjector.Projection(view, Set.of(participant));
        if (!projectionCodec.decode(projectionCodec.encode(projection)).equals(projection)) {
            throw new AssertionError("Projection");
        }
        var beanUtils = Class.forName("org.springframework.beans.BeanUtils");
        var bean = (SettlementEventCodec)
                beanUtils.getMethod("instantiateClass", Class.class).invoke(null, SettlementEventCodec.class);
        if (!bean.decode("SettlementClosed", 1, "{}").type().equals("SettlementClosed")) {
            throw new AssertionError("Codec bean");
        }
        System.out.println("Version-one codecs pass on the module path");
    }
}
