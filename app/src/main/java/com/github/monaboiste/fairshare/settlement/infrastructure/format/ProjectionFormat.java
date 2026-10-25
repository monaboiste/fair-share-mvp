package com.github.monaboiste.fairshare.settlement.infrastructure.format;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.money.CurrencyUnit;

public record ProjectionFormat(
        int schemaVersion,
        SettlementId id,
        String name,
        CurrencyUnit currency,
        long version,
        String status,
        List<Participant> participants,
        List<ParticipantId> retired,
        List<Expense> expenses,
        List<Repayment> repayments,
        List<Debt> obligations,
        List<Balance> balances,
        List<ExchangeRateVersion> exchangeRates) {
    public ProjectionFormat {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported projection schema");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Invalid projected stream version");
        }
    }

    public static ProjectionFormat from(SettlementView view, Set<ParticipantId> retired) {
        return new ProjectionFormat(
                1,
                view.id(),
                view.name(),
                view.currency(),
                view.version(),
                view.status().name(),
                view.participants().stream()
                        .map(p -> new Participant(p.id(), p.name()))
                        .toList(),
                List.copyOf(retired),
                view.expenses().stream().map(Expense::from).toList(),
                view.repayments().stream()
                        .map(r -> new Repayment(
                                r.id(),
                                r.paidOn(),
                                r.payer(),
                                r.recipient(),
                                r.status().name(),
                                r.amount()))
                        .toList(),
                view.obligations().stream()
                        .map(o -> new Debt(o.from(), o.to(), o.amount()))
                        .toList(),
                view.balances().entrySet().stream()
                        .map(e -> new Balance(e.getKey(), e.getValue()))
                        .toList(),
                view.exchangeRates());
    }

    public SettlementView view() {
        LinkedHashMap<ParticipantId, Money> amounts = new LinkedHashMap<>();
        balances.forEach(balance -> amounts.put(balance.participantId(), balance.amount()));
        return new SettlementView(
                id,
                name,
                currency,
                version,
                participants.stream()
                        .map(p -> new ParticipantView(p.participantId(), p.name()))
                        .toList(),
                expenses.stream().map(Expense::view).toList(),
                repayments.stream()
                        .map(r -> new RepaymentView(
                                r.id(),
                                r.paidOn(),
                                r.payer(),
                                r.recipient(),
                                r.amount(),
                                RepaymentView.Status.valueOf(r.status())))
                        .toList(),
                obligations.stream()
                        .map(o -> new Obligation<>(o.debtor(), o.creditor(), o.amount()))
                        .toList(),
                amounts,
                exchangeRates,
                SettlementStatus.valueOf(status));
    }

    public record Participant(ParticipantId participantId, String name) {}

    public record Balance(ParticipantId participantId, Money amount) {}

    public record Debt(ParticipantId debtor, ParticipantId creditor, Money amount) {}

    public record Repayment(
            RepaymentId id,
            LocalDate paidOn,
            ParticipantId payer,
            ParticipantId recipient,
            String status,
            Money amount) {}

    public record Expense(String status, ExpenseRecorded fact) {
        static Expense from(ExpenseView expense) {
            return new Expense(
                    expense.status().name(),
                    new ExpenseRecorded(
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
                            expense.shares()));
        }

        ExpenseView view() {
            return new ExpenseView(
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
                    ExpenseView.Status.valueOf(status));
        }
    }
}
