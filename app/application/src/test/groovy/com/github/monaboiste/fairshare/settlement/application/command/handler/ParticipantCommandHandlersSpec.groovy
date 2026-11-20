package com.github.monaboiste.fairshare.settlement.application.command.handler

import com.github.monaboiste.fairshare.common.events.CommitResult
import com.github.monaboiste.fairshare.common.events.VersionConflictException
import com.github.monaboiste.fairshare.settlement.application.command.AddParticipant
import com.github.monaboiste.fairshare.settlement.application.command.RemoveParticipant
import com.github.monaboiste.fairshare.settlement.application.command.RenameParticipant
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.ParticipantIdentifierConflict
import com.github.monaboiste.fairshare.settlement.domain.ParticipantName
import com.github.monaboiste.fairshare.settlement.domain.ParticipantNotFound
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.SettlementIsClosed
import com.github.monaboiste.fairshare.settlement.domain.SettlementName
import com.github.monaboiste.fairshare.settlement.domain.SettlementNotFound
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantAdded
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRemoved
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRenamed
import com.github.monaboiste.fairshare.settlement.domain.model.Settlement
import com.github.monaboiste.fairshare.settlement.domain.model.SettlementRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.money.Monetary
import spock.lang.Specification

class ParticipantCommandHandlersSpec extends Specification {
    private static final CLOCK = Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC)
    private static final ADA = new ParticipantId(new UUID(0, 11))
    private static final CHARLIE = new ParticipantId(new UUID(0, 13))

    private final SettlementRepository repository = Mock()

    def "#handlerType.simpleName saves the Participant change and returns the repository commit"() {
        given:
        def settlement = settlementWithParticipant()
        if (handlerType == RenameParticipantHandler) {
            assert settlement.close().success()
        }
        def before = settlement.pendingEvents()
        def committed = new CommitResult(settlement.id(), [], 17)
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(commandFor.call(settlement.id()))

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
        AddParticipantHandler | { addCommand(it) } | new ParticipantAdded(CHARLIE, "Charlie")
        RenameParticipantHandler | { renameCommand(it) } | new ParticipantRenamed(ADA, "Adele")
        RemoveParticipantHandler | { new RemoveParticipant(it, ADA) } | new ParticipantRemoved(ADA)
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
        AddParticipantHandler | { addCommand(it) }
        RenameParticipantHandler | { renameCommand(it) }
        RemoveParticipantHandler | { new RemoveParticipant(it, ADA) }
    }

    def "#handlerType.simpleName saves a Participant no-op and returns the empty commit"() {
        given: "a Participant whose current name differs from its original add data"
        def settlement = settlementWithParticipant()
        assert settlement.renameParticipant(ADA, new ParticipantName("Adele")).success()
        def before = settlement.pendingEvents()
        def committed = new CommitResult(settlement.id(), [], 7)
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(commandFor.call(settlement.id()))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(settlement) >> committed
        0 * repository._
        result.getSuccess().is(committed)
        settlement.pendingEvents() == before

        where:
        handlerType | commandFor
        AddParticipantHandler | { new AddParticipant(it, ADA, new ParticipantName("Ada")) }
        RenameParticipantHandler | { renameCommand(it) }
    }

    def "#handlerType.simpleName returns the domain rejection without saving"() {
        given: "a removed Participant whose identifier remains retired"
        def settlement = settlementWithParticipant()
        assert settlement.removeParticipant(ADA).success()
        def before = settlement.pendingEvents()
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(commandFor.call(settlement.id()))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        0 * repository._
        result.getFailure() == rejectionType.newInstance(settlement.id(), ADA)
        settlement.pendingEvents() == before

        where:
        handlerType | commandFor | rejectionType
        AddParticipantHandler | { new AddParticipant(it, ADA, new ParticipantName("Adele")) } | ParticipantIdentifierConflict
        RenameParticipantHandler | { renameCommand(it) } | ParticipantNotFound
        RemoveParticipantHandler | { new RemoveParticipant(it, ADA) } | ParticipantNotFound
    }

    def "#handlerType.simpleName rejects membership changes while closed without saving"() {
        given:
        def settlement = settlementWithParticipant()
        assert settlement.close().success()
        def before = settlement.pendingEvents()
        def handler = handlerType.newInstance(repository)

        when:
        def result = handler.handle(commandFor.call(settlement.id()))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        0 * repository._
        result.getFailure() == new SettlementIsClosed(settlement.id())
        settlement.pendingEvents() == before

        where:
        handlerType | commandFor
        AddParticipantHandler | { new AddParticipant(it, ADA, new ParticipantName("Ada")) }
        RemoveParticipantHandler | { new RemoveParticipant(it, ADA) }
    }

    def "#handlerType.simpleName propagates save conflicts without retrying"() {
        given:
        def settlement = settlementWithParticipant()
        def conflict = new VersionConflictException(settlement.id(), 7, 8)
        def handler = handlerType.newInstance(repository)

        when:
        handler.handle(commandFor.call(settlement.id()))

        then:
        1 * repository.findById(settlement.id()) >> Optional.of(settlement)
        1 * repository.save(settlement) >> { throw conflict }
        0 * repository._
        def propagated = thrown(VersionConflictException)
        propagated.is(conflict)

        where:
        handlerType | commandFor
        AddParticipantHandler | { addCommand(it) }
        RenameParticipantHandler | { renameCommand(it) }
        RemoveParticipantHandler | { new RemoveParticipant(it, ADA) }
    }

    private static AddParticipant addCommand(SettlementId id) {
        new AddParticipant(id, CHARLIE, new ParticipantName("Charlie"))
    }

    private static RenameParticipant renameCommand(SettlementId id) {
        new RenameParticipant(id, ADA, new ParticipantName("Adele"))
    }

    private static Settlement settlementWithParticipant() {
        def settlement = Settlement.open(new SettlementName("Alpine holiday"), Monetary.getCurrency("EUR"), CLOCK)
        assert settlement.addParticipant(ADA, new ParticipantName("Ada")).success()
        settlement
    }
}
