package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.databind.util.StdConverter;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion;

final class ExchangeRateVersionEncoder extends StdConverter<ExchangeRateVersion, RateVersionFormat> {
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
