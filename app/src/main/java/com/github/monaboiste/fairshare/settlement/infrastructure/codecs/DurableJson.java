package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription;
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId;
import com.github.monaboiste.fairshare.settlement.domain.SettlementId;
import com.github.monaboiste.fairshare.settlement.domain.ShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseRecorded;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Function;
import javax.money.CurrencyUnit;
import javax.money.Monetary;

public final class DurableJson {
    private static final JsonMapper MAPPER = mapper();

    private DurableJson() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid durable JSON", failure);
        }
    }

    public static <T> T read(String document, Class<T> type) {
        try (JsonParser parser = MAPPER.createParser(document)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Expected durable JSON object");
            }
            return MAPPER.readValue(parser, type);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid durable JSON", failure);
        }
    }

    private static JsonMapper mapper() {
        SimpleModule scalars = new SimpleModule();
        scalar(scalars, UUID.class, UUID::fromString, UUID::toString);
        scalar(scalars, CurrencyUnit.class, Monetary::getCurrency, CurrencyUnit::getCurrencyCode);
        scalar(scalars, BigDecimal.class, BigDecimal::new, BigDecimal::toPlainString);
        scalar(scalars, LocalDate.class, LocalDate::parse, LocalDate::toString);
        scalar(scalars, LocalDateTime.class, LocalDateTime::parse, LocalDateTime::toString);
        JsonMapper mapper = JsonMapper.builder()
                .addModule(scalars)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .defaultSetterInfo(JsonSetter.Value.construct(Nulls.FAIL, Nulls.FAIL))
                .addMixIn(Money.class, DurableMixins.MoneyMixin.class)
                .addMixIn(ExpenseDescription.class, DurableMixins.DescriptionMixin.class)
                .addMixIn(ExchangeRate.class, DurableMixins.RateMixin.class)
                .addMixIn(ExchangeRateVersion.class, DurableMixins.RateVersionMixin.class)
                .addMixIn(ShareAllocation.class, DurableMixins.AllocationMixin.class)
                .addMixIn(ExpenseRecorded.class, DurableMixins.ExpenseMixin.class)
                .build();
        for (Class<?> type : new Class<?>[] {
            SettlementId.class, ParticipantId.class, ExpenseId.class, RepaymentId.class, ComponentVersionId.class
        }) {
            mapper.addMixIn(type, DurableMixins.IdentifierMixin.class);
        }
        for (CoercionInputShape shape : new CoercionInputShape[] {
            CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        for (CoercionInputShape shape : new CoercionInputShape[] {
            CoercionInputShape.String,
            CoercionInputShape.Float,
            CoercionInputShape.Boolean,
            CoercionInputShape.EmptyString
        }) {
            mapper.coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail);
        }
        return mapper;
    }

    private static <T> void scalar(
            SimpleModule module, Class<T> type, Function<String, T> decode, Function<T, String> encode) {
        module.addSerializer(type, new StringScalarEncoder<>(encode));
        module.addDeserializer(type, new StringScalarDecoder<>(type, decode));
    }
}
