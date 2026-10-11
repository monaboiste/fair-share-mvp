package com.github.monaboiste.fairshare.settlement.infrastructure;

import static com.github.monaboiste.fairshare.settlement.infrastructure.SettlementEventCodec.participant;
import static com.github.monaboiste.fairshare.settlement.infrastructure.SettlementEventCodec.text;
import static com.github.monaboiste.fairshare.settlement.infrastructure.SettlementEventCodec.uuid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.monaboiste.fairshare.quantity.money.Money;
import com.github.monaboiste.fairshare.settlement.application.query.ExpenseView;
import com.github.monaboiste.fairshare.settlement.application.query.ParticipantView;
import com.github.monaboiste.fairshare.settlement.application.query.RepaymentView;
import com.github.monaboiste.fairshare.settlement.application.query.SettlementView;
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId;
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId;
import com.github.monaboiste.fairshare.settlement.domain.SettlementId;
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus;
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseRecorded;
import com.github.monaboiste.fairshare.settlement.domain.netting.Obligation;
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.money.Monetary;

final class SettlementProjectionCodec {
    private final SettlementEventCodec codec;

    SettlementProjectionCodec(SettlementEventCodec codec) {
        this.codec = codec;
    }

    String encode(SettlementProjector.Projection projection) {
        SettlementView view = projection.view();
        ObjectNode document = codec.object()
                .put("schemaVersion", 1)
                .put("id", view.id().value().toString())
                .put("name", view.name())
                .put("currency", view.currency().getCurrencyCode())
                .put("version", view.version())
                .put("status", view.status().name());
        ArrayNode participants = codec.array();
        view.participants()
                .forEach(p -> participants.add(codec.object()
                        .put("participantId", p.id().value().toString())
                        .put("name", p.name())));
        document.set("participants", participants);
        ArrayNode retired = codec.array();
        projection.retiredParticipantIds().forEach(id -> retired.add(id.value().toString()));
        document.set("retired", retired);
        ArrayNode expenses = codec.array();
        view.expenses().forEach(expense -> {
            ExpenseRecorded fact = new ExpenseRecorded(
                    expense.id(),
                    expense.description(),
                    expense.incurredOn(),
                    expense.payer(),
                    expense.originalAmount(),
                    expense.allocation(),
                    null,
                    expense.componentVersionId(),
                    expense.exchangeRate(),
                    expense.valuation(),
                    expense.shares());
            ObjectNode entry = codec.object().put("status", expense.status().name());
            entry.set("fact", codec.parse(codec.encode(fact)));
            expenses.add(entry);
        });
        document.set("expenses", expenses);
        ArrayNode repayments = codec.array();
        view.repayments().forEach(repayment -> {
            ObjectNode entry = codec.object()
                    .put("id", repayment.id().value().toString())
                    .put("paidOn", repayment.paidOn().toString())
                    .put("payer", repayment.payer().value().toString())
                    .put("recipient", repayment.recipient().value().toString())
                    .put("status", repayment.status().name());
            entry.set("amount", codec.money(repayment.amount()));
            repayments.add(entry);
        });
        document.set("repayments", repayments);
        ArrayNode obligations = codec.array();
        view.obligations().forEach(obligation -> {
            ObjectNode entry = codec.object()
                    .put("debtor", obligation.from().value().toString())
                    .put("creditor", obligation.to().value().toString());
            entry.set("amount", codec.money(obligation.amount()));
            obligations.add(entry);
        });
        document.set("obligations", obligations);
        ArrayNode balances = codec.array();
        view.balances().forEach((id, money) -> {
            ObjectNode entry = codec.object().put("participantId", id.value().toString());
            entry.set("amount", codec.money(money));
            balances.add(entry);
        });
        document.set("balances", balances);
        ArrayNode rates = codec.array();
        view.exchangeRates().forEach(rate -> rates.add(codec.rateVersion(rate)));
        document.set("exchangeRates", rates);
        return document.toString();
    }

    SettlementProjector.Projection decode(String json) {
        JsonNode document = codec.parse(json);
        if (document.required("schemaVersion").intValue() != 1) {
            throw new IllegalArgumentException("Unsupported projection schema");
        }
        List<ParticipantView> participants = new ArrayList<>();
        document.required("participants")
                .forEach(p -> participants.add(new ParticipantView(participant(p, "participantId"), text(p, "name"))));
        Set<ParticipantId> retired = new HashSet<>();
        document.required("retired").forEach(id -> retired.add(new ParticipantId(UUID.fromString(id.textValue()))));
        List<ExpenseView> expenses = new ArrayList<>();
        document.required("expenses").forEach(entry -> {
            ExpenseRecorded fact = (ExpenseRecorded)
                    codec.decode("ExpenseRecorded", 1, entry.required("fact").toString());
            expenses.add(new ExpenseView(
                    fact.expenseId(),
                    fact.description(),
                    fact.incurredOn(),
                    fact.payer(),
                    fact.originalAmount(),
                    fact.allocation(),
                    fact.componentVersionId(),
                    fact.exchangeRate(),
                    fact.valuation(),
                    fact.shares(),
                    ExpenseView.Status.valueOf(text(entry, "status"))));
        });
        List<RepaymentView> repayments = new ArrayList<>();
        document.required("repayments")
                .forEach(entry -> repayments.add(new RepaymentView(
                        new RepaymentId(uuid(entry, "id")),
                        LocalDate.parse(text(entry, "paidOn")),
                        participant(entry, "payer"),
                        participant(entry, "recipient"),
                        codec.money(entry.required("amount")),
                        RepaymentView.Status.valueOf(text(entry, "status")))));
        List<Obligation<ParticipantId>> obligations = new ArrayList<>();
        document.required("obligations")
                .forEach(entry -> obligations.add(new Obligation<>(
                        participant(entry, "debtor"),
                        participant(entry, "creditor"),
                        codec.money(entry.required("amount")))));
        LinkedHashMap<ParticipantId, Money> balances = new LinkedHashMap<>();
        document.required("balances")
                .forEach(entry ->
                        balances.put(participant(entry, "participantId"), codec.money(entry.required("amount"))));
        List<ExchangeRateVersion> rates = new ArrayList<>();
        document.required("exchangeRates").forEach(entry -> rates.add(codec.rateVersion(entry)));
        SettlementView view = new SettlementView(
                new SettlementId(uuid(document, "id")),
                text(document, "name"),
                Monetary.getCurrency(text(document, "currency")),
                document.required("version").longValue(),
                participants,
                expenses,
                repayments,
                obligations,
                balances,
                rates,
                SettlementStatus.valueOf(text(document, "status")));
        return new SettlementProjector.Projection(view, Set.copyOf(retired));
    }
}
