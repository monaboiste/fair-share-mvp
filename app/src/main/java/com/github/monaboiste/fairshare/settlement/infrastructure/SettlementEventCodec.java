package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId;
import com.github.monaboiste.fairshare.pricing.component.Validity;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ExactShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription;
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId;
import com.github.monaboiste.fairshare.settlement.domain.Share;
import com.github.monaboiste.fairshare.settlement.domain.ShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.WeightedShareAllocation;
import com.github.monaboiste.fairshare.settlement.domain.event.ExchangeRateConfigured;
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseCancelled;
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseRecorded;
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantAdded;
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRemoved;
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRenamed;
import com.github.monaboiste.fairshare.settlement.domain.event.RepaymentCancelled;
import com.github.monaboiste.fairshare.settlement.domain.event.RepaymentRecorded;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementClosed;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementReopened;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.money.Monetary;
import org.jspecify.annotations.Nullable;

public final class SettlementEventCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(SettlementEvent event) {
        ObjectNode json = object();
        switch (event) {
            case SettlementOpened(var name, var currency) -> {
                json.put("name", name);
                json.put("currency", currency.getCurrencyCode());
            }
            case SettlementRenamed(var name) -> json.put("name", name);
            case SettlementClosed _ -> {}
            case SettlementReopened _ -> {}
            case ParticipantAdded(var id, var name) -> {
                json.put("participantId", id.value().toString());
                json.put("name", name);
            }
            case ParticipantRenamed(var id, var name) -> {
                json.put("participantId", id.value().toString());
                json.put("name", name);
            }
            case ParticipantRemoved(var id) ->
                json.put("participantId", id.value().toString());
            case ExpenseCancelled(var id) -> json.put("expenseId", id.value().toString());
            case RepaymentCancelled(var id) ->
                json.put("repaymentId", id.value().toString());
            case ExchangeRateConfigured(var version) -> json.set("version", rateVersion(version));
            case RepaymentRecorded recorded -> {
                json.put("repaymentId", recorded.repaymentId().value().toString());
                json.put("paidOn", recorded.paidOn().toString());
                json.put("payer", recorded.payer().value().toString());
                json.put("recipient", recorded.recipient().value().toString());
                json.set("amount", money(recorded.amount()));
            }
            case ExpenseRecorded recorded -> {
                json.put("expenseId", recorded.expenseId().value().toString());
                json.put("description", recorded.description().value());
                json.put("incurredOn", recorded.incurredOn().toString());
                json.put("payer", recorded.payer().value().toString());
                json.set("originalAmount", money(recorded.originalAmount()));
                json.set("allocation", allocation(recorded.allocation()));
                ExchangeRate override = recorded.exchangeRateOverride();
                json.set("exchangeRateOverride", override == null ? mapper.nullNode() : rate(override));
                json.put(
                        "componentVersionId",
                        recorded.componentVersionId().value().toString());
                json.set("exchangeRate", rate(recorded.exchangeRate()));
                json.set("valuation", money(recorded.valuation()));
                json.set("shares", shares(recorded.shares()));
            }
        }
        return json.toString();
    }

    public SettlementEvent decode(String type, int version, String document) {
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported Settlement event schema: " + version);
        }
        JsonNode json = parse(document);
        return switch (type) {
            case "SettlementOpened" ->
                new SettlementOpened(text(json, "name"), Monetary.getCurrency(text(json, "currency")));
            case "SettlementRenamed" -> new SettlementRenamed(text(json, "name"));
            case "SettlementClosed" -> new SettlementClosed();
            case "SettlementReopened" -> new SettlementReopened();
            case "ParticipantAdded" -> new ParticipantAdded(participant(json, "participantId"), text(json, "name"));
            case "ParticipantRenamed" -> new ParticipantRenamed(participant(json, "participantId"), text(json, "name"));
            case "ParticipantRemoved" -> new ParticipantRemoved(participant(json, "participantId"));
            case "ExpenseCancelled" -> new ExpenseCancelled(new ExpenseId(uuid(json, "expenseId")));
            case "RepaymentCancelled" -> new RepaymentCancelled(new RepaymentId(uuid(json, "repaymentId")));
            case "ExchangeRateConfigured" -> new ExchangeRateConfigured(rateVersion(json.required("version")));
            case "RepaymentRecorded" ->
                new RepaymentRecorded(
                        new RepaymentId(uuid(json, "repaymentId")),
                        LocalDate.parse(text(json, "paidOn")),
                        participant(json, "payer"),
                        participant(json, "recipient"),
                        money(json.required("amount")));
            case "ExpenseRecorded" ->
                new ExpenseRecorded(
                        new ExpenseId(uuid(json, "expenseId")),
                        new ExpenseDescription(text(json, "description")),
                        LocalDate.parse(text(json, "incurredOn")),
                        participant(json, "payer"),
                        money(json.required("originalAmount")),
                        allocation(json.required("allocation")),
                        json.required("exchangeRateOverride").isNull()
                                ? null
                                : rate(json.required("exchangeRateOverride")),
                        new ComponentVersionId(uuid(json, "componentVersionId")),
                        rate(json.required("exchangeRate")),
                        money(json.required("valuation")),
                        shares(json.required("shares")));
            default -> throw new IllegalArgumentException("Unsupported Settlement event type: " + type);
        };
    }

    ObjectNode object() {
        return mapper.createObjectNode();
    }

    ArrayNode array() {
        return mapper.createArrayNode();
    }

    JsonNode parse(String document) {
        try {
            return requireObject(mapper.readTree(document).requireNonNull());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid durable JSON", failure);
        }
    }

    static JsonNode requireObject(JsonNode json) {
        if (!json.isObject()) {
            throw new IllegalArgumentException("Expected durable JSON object");
        }
        return json;
    }

    static JsonNode requireArray(JsonNode json) {
        if (!json.isArray()) {
            throw new IllegalArgumentException("Expected durable JSON array");
        }
        return json;
    }

    static String text(JsonNode json, String key) {
        JsonNode value = requireObject(json).required(key);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Expected string: " + key);
        }
        return value.textValue();
    }

    static UUID uuid(JsonNode json, String key) {
        return UUID.fromString(text(json, key));
    }

    static ParticipantId participant(JsonNode json, String key) {
        return new ParticipantId(uuid(json, key));
    }

    ObjectNode money(Money amount) {
        return object().put("amount", amount.value().toPlainString()).put("currency", amount.currency());
    }

    Money money(JsonNode json) {
        return Money.of(new BigDecimal(text(json, "amount")), text(json, "currency"));
    }

    ObjectNode rate(ExchangeRate rate) {
        return object().put("source", rate.sourceCurrency().getCurrencyCode())
                .put("target", rate.targetCurrency().getCurrencyCode())
                .put("value", rate.value().toPlainString());
    }

    ExchangeRate rate(JsonNode json) {
        return ExchangeRate.of(
                Monetary.getCurrency(text(json, "source")),
                Monetary.getCurrency(text(json, "target")),
                new BigDecimal(text(json, "value")));
    }

    ObjectNode rateVersion(ExchangeRateVersion version) {
        ObjectNode json = object().put("id", version.id().value().toString())
                .put("definedAt", version.definedAt().toString());
        json.set("rate", rate(version.exchangeRate()));
        json.put("from", timestamp(version.validity().from()));
        json.put("to", timestamp(version.validity().to()));
        return json;
    }

    ExchangeRateVersion rateVersion(JsonNode json) {
        return new ExchangeRateVersion(
                new ComponentVersionId(uuid(json, "id")),
                rate(json.required("rate")),
                new Validity(timestamp(json, "from"), timestamp(json, "to")),
                LocalDateTime.parse(text(json, "definedAt")));
    }

    private static @Nullable String timestamp(@Nullable LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private static @Nullable LocalDateTime timestamp(JsonNode json, String key) {
        return json.required(key).isNull() ? null : LocalDateTime.parse(text(json, key));
    }

    ObjectNode allocation(ShareAllocation allocation) {
        ObjectNode json = object();
        ArrayNode entries = array();
        switch (allocation) {
            case EqualShareAllocation(var recipients) -> {
                json.put("kind", "equal");
                recipients.forEach(id ->
                        entries.add(object().put("participantId", id.value().toString())));
            }
            case ExactShareAllocation(var amounts) -> {
                json.put("kind", "exact");
                amounts.forEach((id, amount) -> {
                    ObjectNode entry = object().put("participantId", id.value().toString());
                    entry.set("amount", money(amount));
                    entries.add(entry);
                });
            }
            case WeightedShareAllocation(var weights) -> {
                json.put("kind", "weighted");
                weights.forEach((id, weight) -> entries.add(
                        object().put("participantId", id.value().toString()).put("weight", weight)));
            }
        }
        json.set("entries", entries);
        return json;
    }

    ShareAllocation allocation(JsonNode json) {
        JsonNode entries = requireArray(requireObject(json).required("entries"));
        return switch (text(json, "kind")) {
            case "equal" -> {
                List<ParticipantId> recipients = new ArrayList<>();
                entries.forEach(entry -> recipients.add(participant(entry, "participantId")));
                yield new EqualShareAllocation(recipients);
            }
            case "exact" -> {
                LinkedHashMap<ParticipantId, Money> amounts = new LinkedHashMap<>();
                entries.forEach(
                        entry -> amounts.put(participant(entry, "participantId"), money(entry.required("amount"))));
                yield new ExactShareAllocation(amounts);
            }
            case "weighted" -> {
                LinkedHashMap<ParticipantId, Integer> weights = new LinkedHashMap<>();
                entries.forEach(entry -> {
                    JsonNode weight = entry.required("weight");
                    if (!weight.isIntegralNumber() || !weight.canConvertToInt()) {
                        throw new IllegalArgumentException("Share weight must be an integer");
                    }
                    weights.put(participant(entry, "participantId"), weight.intValue());
                });
                yield new WeightedShareAllocation(weights);
            }
            default -> throw new IllegalArgumentException("Unsupported Share Allocation");
        };
    }

    ArrayNode shares(List<Share> shares) {
        ArrayNode entries = array();
        shares.forEach(share -> {
            ObjectNode entry =
                    object().put("participantId", share.participantId().value().toString());
            entry.set("amount", money(share.amount()));
            entries.add(entry);
        });
        return entries;
    }

    List<Share> shares(JsonNode json) {
        List<Share> shares = new ArrayList<>();
        requireArray(json)
                .forEach(entry ->
                        shares.add(new Share(participant(entry, "participantId"), money(entry.required("amount")))));
        return List.copyOf(shares);
    }
}
