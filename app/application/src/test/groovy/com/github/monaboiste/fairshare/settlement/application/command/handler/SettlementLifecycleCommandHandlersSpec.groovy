package com.github.monaboiste.fairshare.settlement.application.command.handler

import com.github.monaboiste.fairshare.common.events.CommitResult
import com.github.monaboiste.fairshare.common.events.VersionConflictException
import com.github.monaboiste.fairshare.quantity.money.Money
import com.github.monaboiste.fairshare.settlement.application.command.CloseSettlement
import com.github.monaboiste.fairshare.settlement.application.command.OpenSettlement
import com.github.monaboiste.fairshare.settlement.application.command.RenameSettlement
import com.github.monaboiste.fairshare.settlement.application.command.ReopenSettlement
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantName
import com.github.monaboiste.fairshare.settlement.domain.RepaymentDetails
import com.github.monaboiste.fairshare.settlement.domain.RepaymentId
import com.github.monaboiste.fairshare.settlement.domain.SettlementAlreadyOpen
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementIsClosed
import com.github.monaboiste.fairshare.settlement.domain.SettlementName
import com.github.monaboiste.fairshare.settlement.domain.SettlementNotFound
import com.github.monaboiste.fairshare.settlement.domain.SettlementNotSettled
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementClosed
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementReopened
import com.github.monaboiste.fairshare.settlement.domain.model.Settlement
import com.github.monaboiste.fairshare.settlement.domain.model.SettlementRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.money.Monetary
import spock.lang.Specification

class SettlementLifecycleCommandHandlersSpec extends Specification {
    private static final NOW = Instant.parse("2026-05-10T12:00:00Z")
    private static final CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
    private static final EUR = Monetary.getCurrency("EUR")

    private final SettlementRepository repository = Mock()

    def "opening creates a Settlement with command details and the supplied clock and returns its commit"() {
        given:
        def command = new OpenSettlement(new SettlementName("Alpine holiday"), EUR)
        CommitResult committed

        when:
        def result = new OpenSettlementHandler(repository, CLOCK).handle(command)

        then:
        1 * repository.save(_ as Settlement) >> { Settlement settlement ->
            assert settlement.id() != null
            assert settlement.pendingEvents().size() == 1
            def opened = settlement.pendingEvents().first()
            assert opened.payload() == new SettlementOpened("Alpine holiday", EUR)
            assert opened.occurredAt() == NOW
            committed = new CommitResult(settlement.id(), [], 1)
            committed
        }
        0 * repository._
        result.getSuccess().is(committed)
    }

    def "#handlerType.simpleName saves the changed Settlement and returns the repository commit"() {
        given:
        def settlement = openSettlement()
        if (handlerType != CloseSettlementHandler) {
            assert settlement.close().success()
        }
        def before = settlement.pendingEvents()
        def command = commandFor.call(settlement.id())
        def committed = new CommitResult(settlement.id(), [], 17)
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(command)

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(_ as Settlement) >> { Settlement saved ->
            assert saved.is(settlement)
            assert saved.pendingEvents().drop(before.size())*.payload() == [expectedEvent]
            committed
        }
        0 * repository._
        result.getSuccess().is(committed)

        where:
        handlerType | commandFor | expectedEvent
        RenameSettlementHandler | { renameCommand(it) } | new SettlementRenamed("Lake holiday")
        CloseSettlementHandler | { new CloseSettlement(it) } | new SettlementClosed()
        ReopenSettlementHandler | { new ReopenSettlement(it) } | new SettlementReopened()
    }

    def "renaming to the current name still saves and returns an empty commit"() {
        given:
        def settlement = openSettlement()
        def before = settlement.pendingEvents()
        def committed = new CommitResult(settlement.id(), [], 7)

        when:
        def result = new RenameSettlementHandler(repository).handle(
                new RenameSettlement(settlement.id(), new SettlementName("Alpine holiday")))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(settlement) >> committed
        0 * repository._
        result.getSuccess().is(committed)
        settlement.pendingEvents() == before
    }

    def "#handlerType.simpleName rejects a missing Settlement without saving"() {
        given:
        def missing = new SettlementId(new UUID(0, 404))
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(commandFor.call(missing))

        then:
        1 * repository.findById(missing) >> Optional.empty()
        0 * repository._
        result.getFailure() == new SettlementNotFound(missing)

        where:
        handlerType | commandFor
        RenameSettlementHandler | { renameCommand(it) }
        CloseSettlementHandler | { new CloseSettlement(it) }
        ReopenSettlementHandler | { new ReopenSettlement(it) }
    }

    def "#handlerType.simpleName rejects a #state Settlement without saving or adding events"() {
        given:
        def settlement = settlementIn(state)
        def before = settlement.pendingEvents()
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(commandFor.call(settlement.id()))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        0 * repository._
        result.getFailure() == rejectionType.newInstance(settlement.id())
        settlement.pendingEvents() == before

        where:
        handlerType | state | commandFor | rejectionType
        CloseSettlementHandler | "closed" | { new CloseSettlement(it) } | SettlementIsClosed
        CloseSettlementHandler | "unsettled" | { new CloseSettlement(it) } | SettlementNotSettled
        ReopenSettlementHandler | "open" | { new ReopenSettlement(it) } | SettlementAlreadyOpen
    }

    def "#handlerType.simpleName propagates a load failure without saving or retrying"() {
        given:
        def id = new SettlementId(new UUID(0, 31))
        def failure = new IllegalStateException("store unavailable")
        def handler = handlerType.newInstance(repository)

        when:
        handler.handle(commandFor.call(id))

        then:
        1 * repository.findById(id) >> { throw failure }
        0 * repository._
        def propagated = thrown(IllegalStateException)
        propagated.is(failure)

        where:
        handlerType | commandFor
        RenameSettlementHandler | { renameCommand(it) }
        CloseSettlementHandler | { new CloseSettlement(it) }
        ReopenSettlementHandler | { new ReopenSettlement(it) }
    }

    def "save conflicts propagate unchanged without retrying"() {
        given:
        def settlement = openSettlement()
        def conflict = new VersionConflictException(settlement.id(), 7, 8)

        when:
        new RenameSettlementHandler(repository).handle(renameCommand(settlement.id()))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(settlement) >> { throw conflict }
        0 * repository._
        def propagated = thrown(VersionConflictException)
        propagated.is(conflict)
    }

    def "opening propagates store failures without retrying"() {
        given:
        def failure = new IllegalStateException("store unavailable")

        when:
        new OpenSettlementHandler(repository, CLOCK).handle(
                new OpenSettlement(new SettlementName("Alpine holiday"), EUR))

        then:
        1 * repository.save(_ as Settlement) >> { throw failure }
        0 * repository._
        def propagated = thrown(IllegalStateException)
        propagated.is(failure)
    }

    private static RenameSettlement renameCommand(SettlementId id) {
        new RenameSettlement(id, new SettlementName("Lake holiday"))
    }

    private static Settlement settlementIn(String state) {
        def settlement = openSettlement()
        if (state == "closed") {
            assert settlement.close().success()
        } else if (state == "unsettled") {
            def ada = new ParticipantId(new UUID(0, 11))
            def bob = new ParticipantId(new UUID(0, 12))
            assert settlement.addParticipant(ada, new ParticipantName("Ada")).success()
            assert settlement.addParticipant(bob, new ParticipantName("Bob")).success()
            assert settlement.recordRepayment(new RepaymentDetails(new RepaymentId(new UUID(0, 21)),
                    LocalDate.of(2026, 5, 10), ada, bob, Money.of(10, "EUR"))).success()
        }
        settlement
    }

    private static Settlement openSettlement() {
        Settlement.open(new SettlementName("Alpine holiday"), EUR, CLOCK)
    }
}
