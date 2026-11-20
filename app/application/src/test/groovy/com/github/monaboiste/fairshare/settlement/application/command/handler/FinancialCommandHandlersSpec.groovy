package com.github.monaboiste.fairshare.settlement.application.command.handler

import com.github.monaboiste.fairshare.common.events.CommitResult
import com.github.monaboiste.fairshare.pricing.component.ComponentVersionId
import com.github.monaboiste.fairshare.pricing.component.Validity
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.application.command.CancelExpense
import com.github.monaboiste.fairshare.settlement.application.command.CancelRepayment
import com.github.monaboiste.fairshare.settlement.application.command.ConfigureExchangeRate
import com.github.monaboiste.fairshare.settlement.application.command.RecordExpense
import com.github.monaboiste.fairshare.settlement.application.command.RecordRepayment
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation
import com.github.monaboiste.fairshare.settlement.domain.ExchangeRateTargetMismatch
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDetails
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId
import com.github.monaboiste.fairshare.settlement.domain.ExpenseNotFound
import com.github.monaboiste.fairshare.settlement.domain.NonPositiveExpenseAmount
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantName
import com.github.monaboiste.fairshare.settlement.domain.RepaymentAmountPrecisionExceeded
import com.github.monaboiste.fairshare.settlement.domain.RepaymentDetails
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId
import com.github.monaboiste.fairshare.settlement.domain.RepaymentNotFound
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementName
import com.github.monaboiste.fairshare.settlement.domain.SettlementNotFound
import com.github.monaboiste.fairshare.settlement.domain.Share
import com.github.monaboiste.fairshare.settlement.domain.event.ExchangeRateConfigured
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseCancelled
import com.github.monaboiste.fairshare.settlement.domain.event.ExpenseRecorded
import com.github.monaboiste.fairshare.settlement.domain.event.RepaymentCancelled
import com.github.monaboiste.fairshare.settlement.domain.event.RepaymentRecorded
import com.github.monaboiste.fairshare.settlement.domain.model.Settlement
import com.github.monaboiste.fairshare.settlement.domain.model.SettlementRepository
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRateVersion
import com.github.monaboiste.fairshare.settlement.domain.valuation.ValuationEngine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.money.Monetary
import spock.lang.Specification

class FinancialCommandHandlersSpec extends Specification {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-15T10:15:30Z"), ZoneOffset.ofHours(2))
    private static final SettlementId MISSING_SETTLEMENT = new SettlementId(new UUID(0, 1))
    private static final ParticipantId ADA = new ParticipantId(new UUID(0, 11))
    private static final ParticipantId BOB = new ParticipantId(new UUID(0, 12))
    private static final ExpenseId EXPENSE = new ExpenseId(new UUID(0, 21))
    private static final RepaymentId REPAYMENT = new RepaymentId(new UUID(0, 31))
    private static final ComponentVersionId CONFIGURED_VERSION = new ComponentVersionId(new UUID(0, 41))
    private static final ComponentVersionId OVERRIDE_VERSION = new ComponentVersionId(new UUID(0, 42))
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 1, 20)
    private static final EUR = Monetary.getCurrency("EUR")
    private static final USD = Monetary.getCurrency("USD")

    private final SettlementRepository repository = Mock()

    def "#operation returns the missing Settlement without saving"() {
        given:
        def handler = handlerFor.call(repository)
        def command = commandFor.call(MISSING_SETTLEMENT)

        when:
        def result = handler.handle(command)

        then:
        1 * repository.findById(MISSING_SETTLEMENT) >> Optional.empty()
        0 * repository._
        result.getFailure() == new SettlementNotFound(MISSING_SETTLEMENT)

        where:
        operation                 | handlerFor
        "Record Expense"          | { new RecordExpenseHandler(it, CLOCK) }
        "Cancel Expense"          | { new CancelExpenseHandler(it) }
        "Configure Exchange Rate" | { new ConfigureExchangeRateHandler(it, CLOCK) }
        "Record Repayment"        | { new RecordRepaymentHandler(it) }
        "Cancel Repayment"        | { new CancelRepaymentHandler(it) }

        commandFor << [
            { expenseCommand(it) },
            { new CancelExpense(it, EXPENSE) },
            { configureCommand(it) },
            { repaymentCommand(it) },
            { new CancelRepayment(it, REPAYMENT) }
        ]
    }

    def "#operation propagates domain rejection without saving"() {
        given:
        def settlement = openSettlement()
        def handler = handlerFor.call(repository)
        def command = commandFor.call(settlement.id())

        when:
        def result = handler.handle(command)

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        0 * repository._
        result.getFailure() == rejectionFor.call(settlement.id())

        where:
        operation                 | handlerFor
        "Record Expense"          | { new RecordExpenseHandler(it, CLOCK) }
        "Cancel Expense"          | { new CancelExpenseHandler(it) }
        "Configure Exchange Rate" | { new ConfigureExchangeRateHandler(it, CLOCK) }
        "Record Repayment"        | { new RecordRepaymentHandler(it) }
        "Cancel Repayment"        | { new CancelRepaymentHandler(it) }
        __
        commandFor                                                            | rejectionFor
        ({ expenseCommand(it, Money.of(0, "EUR")) })                          | { new NonPositiveExpenseAmount(it, EXPENSE) }
        ({ new CancelExpense(it, EXPENSE) })                                  | { new ExpenseNotFound(it, EXPENSE) }
        ({ configureCommand(it, ExchangeRate.of(EUR, USD, BigDecimal.ONE)) }) | { new ExchangeRateTargetMismatch(it, USD) }
        ({ repaymentCommand(it, Money.of(new BigDecimal("10.005"), "EUR")) }) | { new RepaymentAmountPrecisionExceeded(it, REPAYMENT) }
        ({ new CancelRepayment(it, REPAYMENT) })                              | { new RepaymentNotFound(it, REPAYMENT) }
    }

    def "Record Expense commits the #valuationSource Valuation"() {
        given:
        def settlement = openSettlement()
        assert settlement.configureExchangeRate(usdToEur(), Validity.always(), CONFIGURED_VERSION,
                LocalDateTime.of(2026, 1, 1, 9, 0)).success()
        def command = new RecordExpense(settlement.id(), EXPENSE, new ExpenseDescription("Lunch"), BUSINESS_DATE,
                ADA, Money.of(10, "USD"), new EqualShareAllocation([ADA, BOB]), override)
        def previousEventCount = settlement.pendingEvents().size()
        def commit = new CommitResult(settlement.id(), [], 23)
        def handler = handlerFor.call(repository)

        when:
        def result = handler.handle(command)

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(settlement) >> { Settlement saved ->
            def events = saved.pendingEvents().drop(previousEventCount)*.payload()
            assert events.size() == 1
            def recorded = events.first() as ExpenseRecorded
            assert recorded.expenseId() == EXPENSE
            assert recorded.description() == new ExpenseDescription("Lunch")
            assert recorded.incurredOn() == BUSINESS_DATE
            assert recorded.payer() == ADA
            assert recorded.originalAmount() == Money.of(10, "USD")
            assert recorded.allocation() == new EqualShareAllocation([ADA, BOB])
            assert recorded.exchangeRateOverride() == override
            assert recorded.componentVersionId() == expectedVersion
            assert recorded.exchangeRate() == expectedRate
            assert recorded.valuation() == expectedValuation
            assert recorded.shares() == expectedShares
            commit
        }
        0 * repository._
        result.getSuccess().is(commit)

        where:
        valuationSource            | override       | expectedVersion    | expectedRate
        "configured Exchange Rate" | null           | CONFIGURED_VERSION | usdToEur()
        "Exchange Rate Override"   | overrideRate() | OVERRIDE_VERSION   | overrideRate()
        __
        expectedValuation     | expectedShares
        Money.of(9, "EUR")     | [new Share(ADA, Money.of(4.50, "EUR")), new Share(BOB, Money.of(4.50, "EUR"))]
        Money.of(12.50, "EUR") | [new Share(ADA, Money.of(6.25, "EUR")), new Share(BOB, Money.of(6.25, "EUR"))]

        handlerFor << [
            { new RecordExpenseHandler(it, CLOCK) },
            { new RecordExpenseHandler(it, CLOCK, ValuationEngine.standard(), { OVERRIDE_VERSION }) }
        ]
    }

    def "#operation saves the decided event and returns the repository commit unchanged"() {
        given:
        def settlement = openSettlement()
        prepare.call(settlement)
        def previousEventCount = settlement.pendingEvents().size()
        def commit = new CommitResult(settlement.id(), [], 37)
        def command = commandFor.call(settlement.id())
        def handler = handlerFor.call(repository)

        when:
        def result = handler.handle(command)

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(settlement) >> { Settlement saved ->
            assert saved.pendingEvents().drop(previousEventCount)*.payload() == [expectedEvent]
            commit
        }
        0 * repository._
        result.getSuccess().is(commit)

        where:
        operation                 | handlerFor
        "Cancel Expense"          | { new CancelExpenseHandler(it) }
        "Configure Exchange Rate" | { new ConfigureExchangeRateHandler(it, CLOCK, { CONFIGURED_VERSION }) }
        "Record Repayment"        | { new RecordRepaymentHandler(it) }
        "Cancel Repayment"        | { new CancelRepaymentHandler(it) }
        __
        prepare                    | commandFor
        ({ recordExpense(it) })    | { new CancelExpense(it, EXPENSE) }
        ({ })                      | { configureCommand(it) }
        ({ })                      | { repaymentCommand(it) }
        ({ recordRepayment(it) })   | { new CancelRepayment(it, REPAYMENT) }

        expectedEvent << [
            new ExpenseCancelled(EXPENSE),
            new ExchangeRateConfigured(new ExchangeRateVersion(CONFIGURED_VERSION, usdToEur(),
                    Validity.from(LocalDateTime.of(2026, 1, 1, 0, 0)), LocalDateTime.of(2026, 2, 15, 12, 15, 30))),
            new RepaymentRecorded(REPAYMENT, BUSINESS_DATE, BOB, ADA, Money.of(5, "EUR")),
            new RepaymentCancelled(REPAYMENT)
        ]
    }

    private static void recordExpense(Settlement settlement) {
        def details = new ExpenseDetails(EXPENSE, new ExpenseDescription("Lunch"), BUSINESS_DATE,
                ADA, Money.of(10, "EUR"), new EqualShareAllocation([ADA, BOB]))
        assert settlement.recordExpense(details, null, ValuationEngine.standard()).success()
    }

    private static void recordRepayment(Settlement settlement) {
        assert settlement.recordRepayment(new RepaymentDetails(
                REPAYMENT, BUSINESS_DATE, BOB, ADA, Money.of(5, "EUR"))).success()
    }

    private static Settlement openSettlement() {
        def settlement = Settlement.open(new SettlementName("Weekend"), EUR, CLOCK)
        assert settlement.addParticipant(ADA, new ParticipantName("Ada")).success()
        assert settlement.addParticipant(BOB, new ParticipantName("Bob")).success()
        settlement
    }

    private static RecordExpense expenseCommand(SettlementId settlementId, Money amount = Money.of(10, "EUR")) {
        new RecordExpense(settlementId, EXPENSE, new ExpenseDescription("Lunch"), BUSINESS_DATE,
                ADA, amount, new EqualShareAllocation([ADA, BOB]))
    }

    private static RecordRepayment repaymentCommand(SettlementId settlementId, Money amount = Money.of(5, "EUR")) {
        new RecordRepayment(settlementId, REPAYMENT, BUSINESS_DATE, BOB, ADA, amount)
    }

    private static ConfigureExchangeRate configureCommand(SettlementId settlementId, ExchangeRate rate = usdToEur()) {
        new ConfigureExchangeRate(settlementId, rate, Validity.from(LocalDateTime.of(2026, 1, 1, 0, 0)))
    }

    private static ExchangeRate overrideRate() {
        ExchangeRate.of(USD, EUR, new BigDecimal("1.25"))
    }

    private static ExchangeRate usdToEur() {
        ExchangeRate.of(USD, EUR, new BigDecimal("0.90"))
    }
}
