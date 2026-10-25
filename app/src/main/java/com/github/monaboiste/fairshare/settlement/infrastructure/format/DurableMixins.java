package com.github.monaboiste.fairshare.settlement.infrastructure.format;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription;
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.Share;
import com.github.monaboiste.fairshare.settlement.domain.ShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.money.CurrencyUnit;
import org.jspecify.annotations.Nullable;

final class DurableMixins {
    private DurableMixins() {}

    abstract static class IdentifierMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        IdentifierMixin(UUID value) {}

        @JsonValue
        abstract UUID value();
    }

    abstract static class DescriptionMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        DescriptionMixin(String value) {}

        @JsonValue
        abstract String value();
    }

    @JsonAutoDetect(
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE)
    abstract static class MoneyMixin {
        @JsonCreator
        static Money of(@JsonProperty("amount") BigDecimal amount, @JsonProperty("currency") String currency) {
            return Money.of(amount, currency);
        }

        @JsonProperty("amount")
        abstract BigDecimal value();

        @JsonProperty("currency")
        abstract String currency();
    }

    abstract static class RateMixin {
        @JsonCreator
        RateMixin(
                @JsonProperty("source") CurrencyUnit sourceCurrency,
                @JsonProperty("target") CurrencyUnit targetCurrency,
                @JsonProperty("value") BigDecimal value) {}

        @JsonProperty("source")
        abstract CurrencyUnit sourceCurrency();

        @JsonProperty("target")
        abstract CurrencyUnit targetCurrency();
    }

    @JsonSerialize(converter = RateVersionFormat.Encode.class)
    @JsonDeserialize(converter = RateVersionFormat.Decode.class)
    abstract static class RateVersionMixin {}

    @JsonSerialize(using = AllocationFormat.Encode.class)
    @JsonDeserialize(using = AllocationFormat.Decode.class)
    abstract static class AllocationMixin {}

    record ExpenseMixin(
            ExpenseId expenseId,
            ExpenseDescription description,
            LocalDate incurredOn,
            ParticipantId payer,
            Money originalAmount,
            ShareAllocation allocation,
            @JsonSetter(nulls = Nulls.SET) @Nullable ExchangeRate exchangeRateOverride,
            ComponentVersionId componentVersionId,
            ExchangeRate exchangeRate,
            Money valuation,
            List<Share> shares) {}
}
