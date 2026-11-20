package com.github.monaboiste.fairshare.settlement.config

import com.github.monaboiste.fairshare.pricing.component.Validity
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.application.command.AddParticipant
import com.github.monaboiste.fairshare.settlement.application.command.CancelExpense
import com.github.monaboiste.fairshare.settlement.application.command.CancelRepayment
import com.github.monaboiste.fairshare.settlement.application.command.CloseSettlement
import com.github.monaboiste.fairshare.settlement.application.command.ConfigureExchangeRate
import com.github.monaboiste.fairshare.settlement.application.command.OpenSettlement
import com.github.monaboiste.fairshare.settlement.application.command.RecordExpense
import com.github.monaboiste.fairshare.settlement.application.command.RecordRepayment
import com.github.monaboiste.fairshare.settlement.application.command.RemoveParticipant
import com.github.monaboiste.fairshare.settlement.application.command.RenameParticipant
import com.github.monaboiste.fairshare.settlement.application.command.RenameSettlement
import com.github.monaboiste.fairshare.settlement.application.command.ReopenSettlement
import com.github.monaboiste.fairshare.settlement.application.command.handler.AddParticipantHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.CancelExpenseHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.CancelRepaymentHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.CloseSettlementHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.ConfigureExchangeRateHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.OpenSettlementHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.RecordExpenseHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.RecordRepaymentHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.RemoveParticipantHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.RenameParticipantHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.RenameSettlementHandler
import com.github.monaboiste.fairshare.settlement.application.command.handler.ReopenSettlementHandler
import com.github.monaboiste.fairshare.settlement.application.query.ExpenseView
import com.github.monaboiste.fairshare.settlement.application.query.GetProposedRepayments
import com.github.monaboiste.fairshare.settlement.application.query.GetSettlement
import com.github.monaboiste.fairshare.settlement.application.query.RepaymentView
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetProposedRepaymentsHandler
import com.github.monaboiste.fairshare.settlement.application.query.handler.GetSettlementHandler
import com.github.monaboiste.fairshare.settlement.domain.EqualShareAllocation
import com.github.monaboiste.fairshare.settlement.domain.ExpenseDescription
import com.github.monaboiste.fairshare.settlement.domain.ExpenseId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantName
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementName
import com.github.monaboiste.fairshare.settlement.domain.SettlementStatus
import com.github.monaboiste.fairshare.settlement.domain.netting.ProposedRepayment
import com.github.monaboiste.fairshare.settlement.domain.valuation.ExchangeRate
import java.time.LocalDate
import javax.money.Monetary
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import spock.lang.Specification

class SettlementHandlersConfigurationSpec extends Specification {
    private static final ParticipantId ADA = new ParticipantId(new UUID(0, 11))
    private static final ParticipantId BOB = new ParticipantId(new UUID(0, 12))
    private static final EUR = Monetary.getCurrency("EUR")
    private AnnotationConfigApplicationContext context

    def setup() {
        context = new AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(new MapPropertySource("portable-database", [
                "fairshare.database.url": "jdbc:postgresql://unused/fairshare",
                "fairshare.database.username": "unused",
                "fairshare.database.password": "unused"
        ]))
        context.register(SettlementStorageConfiguration, SettlementHandlersConfiguration, PortableSettlementStorage)
        context.refresh()
    }

    def cleanup() {
        context?.close()
    }

    def "configured handlers share settlement state across membership and lifecycle changes"() {
        given: "a Settlement opened through the production handler configuration"
        def settlement = handle(OpenSettlementHandler, new OpenSettlement(new SettlementName("Holiday"), EUR)).streamId()
        handle(AddParticipantHandler, new AddParticipant(settlement, ADA, new ParticipantName("Ada")))
        handle(AddParticipantHandler, new AddParticipant(settlement, BOB, new ParticipantName("Bob")))

        when: "names are corrected, an unused Participant leaves and the Settlement closes"
        handle(RenameSettlementHandler, new RenameSettlement(settlement, new SettlementName("Mountains")))
        handle(RenameParticipantHandler, new RenameParticipant(settlement, ADA, new ParticipantName("Adeline")))
        handle(RemoveParticipantHandler, new RemoveParticipant(settlement, BOB))
        handle(CloseSettlementHandler, new CloseSettlement(settlement))
        def closed = view(settlement)

        then: "the configured query handler sees every committed change"
        closed.name() == "Mountains"
        closed.participants()*.id() == [ADA]
        closed.participants()*.name() == ["Adeline"]
        closed.status() == SettlementStatus.CLOSED
        closed.version() == 7

        when: "the Settlement is explicitly reopened"
        handle(ReopenSettlementHandler, new ReopenSettlement(settlement))
        def reopened = view(settlement)

        then: "the same query wiring exposes the new lifecycle state"
        reopened.status() == SettlementStatus.OPEN
        reopened.version() == 8
        reopened.participants() == closed.participants()
    }

    def "configured financial handlers expose valued expenses repayments and cancellations through queries"() {
        given: "two Participants and a configured USD to EUR Exchange Rate"
        def settlement = handle(OpenSettlementHandler, new OpenSettlement(new SettlementName("Holiday"), EUR)).streamId()
        handle(AddParticipantHandler, new AddParticipant(settlement, ADA, new ParticipantName("Ada")))
        handle(AddParticipantHandler, new AddParticipant(settlement, BOB, new ParticipantName("Bob")))
        handle(ConfigureExchangeRateHandler, new ConfigureExchangeRate(settlement,
                ExchangeRate.of(Monetary.getCurrency("USD"), EUR, 0.90G), Validity.always()))
        def expense = new ExpenseId(new UUID(0, 21))
        def repayment = new RepaymentId(new UUID(0, 31))
        def date = LocalDate.of(2026, 2, 3)

        when: "Ada pays 20 USD for Bob and Bob repays 3 EUR"
        handle(RecordExpenseHandler, new RecordExpense(settlement, expense, new ExpenseDescription("Dinner"),
                date, ADA, Money.of(20, "USD"), new EqualShareAllocation([BOB])))
        handle(RecordRepaymentHandler, new RecordRepayment(settlement, repayment, date, BOB, ADA, Money.of(3, "EUR")))
        def current = view(settlement)
        def proposed = handle(GetProposedRepaymentsHandler, new GetProposedRepayments(settlement))

        then: "the query handlers share the valued financial state"
        current.expenses()*.valuation() == [Money.of(18, "EUR")]
        current.repayments()*.amount() == [Money.of(3, "EUR")]
        current.balances() == [(ADA): Money.of(15, "EUR"), (BOB): Money.of(-15, "EUR")]
        proposed.proposedRepayments() == [new ProposedRepayment(BOB, ADA, Money.of(15, "EUR"))]
        proposed.version() == 6

        when: "both financial entries are cancelled"
        handle(CancelExpenseHandler, new CancelExpense(settlement, expense))
        handle(CancelRepaymentHandler, new CancelRepayment(settlement, repayment))
        def cancelled = view(settlement)

        then: "queries preserve entries but exclude their amounts from current Balances"
        cancelled.expenses()*.status() == [ExpenseView.Status.CANCELLED]
        cancelled.repayments()*.status() == [RepaymentView.Status.CANCELLED]
        cancelled.balances() == [(ADA): Money.zero("EUR"), (BOB): Money.zero("EUR")]
        handle(GetProposedRepaymentsHandler, new GetProposedRepayments(settlement)).proposedRepayments().empty
        cancelled.version() == 8
    }

    private handle(Class handler, command) {
        context.getBean(handler).handle(command).getSuccess()
    }

    private view(SettlementId settlement) {
        handle(GetSettlementHandler, new GetSettlement(settlement))
    }
}
