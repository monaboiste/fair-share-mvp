package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.databind.util.StdConverter;
import com.github.monaboiste.fairshare.pricing.component.Validity;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion;

final class ExchangeRateVersionDecoder extends StdConverter<RateVersionFormat, ExchangeRateVersion> {
    @Override
    public ExchangeRateVersion convert(RateVersionFormat version) {
        return new ExchangeRateVersion(
                version.id(), version.rate(), new Validity(version.from(), version.to()), version.definedAt());
    }
}
