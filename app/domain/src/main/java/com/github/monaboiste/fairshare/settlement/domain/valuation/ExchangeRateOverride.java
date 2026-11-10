package com.github.monaboiste.fairshare.settlement.domain.valuation;

import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId;
import java.time.LocalDateTime;

public record ExchangeRateOverride(ExchangeRate rate, ComponentVersionId versionId, LocalDateTime definedAt) {}
