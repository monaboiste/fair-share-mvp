package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

record RateVersionFormat(
        ComponentVersionId id,
        LocalDateTime definedAt,
        ExchangeRate rate,
        @JsonSetter(nulls = Nulls.SET) @Nullable LocalDateTime from,
        @JsonSetter(nulls = Nulls.SET) @Nullable LocalDateTime to) {}
