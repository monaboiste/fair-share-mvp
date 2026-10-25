package com.github.monaboiste.fairshare.settlement.infrastructure.format;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId;
import com.github.monaboiste.fairshare.pricing.component.Validity;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

record RateVersionFormat(
        ComponentVersionId id,
        LocalDateTime definedAt,
        ExchangeRate rate,
        @JsonSetter(nulls = Nulls.SET) @Nullable LocalDateTime from,
        @JsonSetter(nulls = Nulls.SET) @Nullable LocalDateTime to) {
    static final class Encode extends StdConverter<ExchangeRateVersion, RateVersionFormat> {
        @Override
        public RateVersionFormat convert(ExchangeRateVersion version) {
            return new RateVersionFormat(
                    version.id(),
                    version.definedAt(),
                    version.exchangeRate(),
                    version.validity().from(),
                    version.validity().to());
        }
    }

    static final class Decode extends StdConverter<RateVersionFormat, ExchangeRateVersion> {
        @Override
        public ExchangeRateVersion convert(RateVersionFormat version) {
            return new ExchangeRateVersion(
                    version.id(), version.rate(), new Validity(version.from(), version.to()), version.definedAt());
        }
    }
}
