package com.github.monaboiste.fairshare.settlement.application.query

import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus
import com.github.monaboiste.fairshare.settlement.domain.Share
import com.github.monaboiste.fairshare.settlement.domain.netting.Obligation
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate
import java.time.LocalDate
import javax.money.Monetary
import spock.lang.Specification

class SettlementViewSpec extends Specification {
    def "a query snapshot retains financial entries and frozen Shares after projection inputs change"() {
        given: "Ada paid for Bob and Bob's attempted repayment was cancelled"
        def ada = new ParticipantId(new UUID(0, 1))
        def bob = new ParticipantId(new UUID(0, 2))
        def amount = Money.of(12, "EUR")
        def date = LocalDate.of(2026, 1, 1)
        def eur = Monetary.getCurrency("EUR")
        def shares = [new Share(bob, amount)]
        def expense = new ExpenseView(new ExpenseId(UUID.randomUUID()), new ExpenseDescription("Lunch"),
                date, ada, amount, new EqualShareAllocation([bob]), ComponentVersionId.generate(),
                ExchangeRate.of(eur, eur, BigDecimal.ONE), amount, shares, ExpenseView.Status.ACTIVE)
        def repayment = new RepaymentView(new RepaymentId(UUID.randomUUID()), date, bob, ada, amount,
                RepaymentView.Status.CANCELLED)
        def expenses = [expense]
        def repayments = [repayment]
        def balances = [(ada): amount, (bob): Money.of(-12, "EUR")]
        def view = new SettlementView(SettlementId.random(), "Trip", eur, 6,
                [new ParticipantView(ada, "Ada"), new ParticipantView(bob, "Bob")],
                expenses, repayments, [new Obligation(bob, ada, amount)], balances, [], SettlementStatus.OPEN)

        when: "the caller reuses the collections for another projection"
        expenses.clear()
        repayments.clear()
        shares.clear()
        balances.clear()

        then: "the snapshot and its nested Expense still describe the original version"
        view.expenses() == [expense]
        view.expenses().first().shares() == [new Share(bob, amount)]
        view.repayments() == [repayment]
        view.balances() == [(ada): amount, (bob): Money.of(-12, "EUR")]
    }
}
