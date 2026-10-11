package com.github.monaboiste.fairshare.settlement.infrastructure

import com.github.monaboiste.fairshare.common.events.EventEnvelope
import com.github.monaboiste.fairshare.common.events.EventId
import com.github.monaboiste.fairshare.common.events.PendingEvent
import com.github.monaboiste.fairshare.settlement.domain.ParticipantId
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantAdded
import com.github.monaboiste.fairshare.settlement.domain.event.ParticipantRemoved
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed
import java.time.Instant
import javax.money.Monetary
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class PostgresProjectionSpec extends Specification {
    def "a Settlement document with #shape #collection is rejected without advancing projection"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def views = projection(database)
        def opening = store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        views.apply(new CommittedSettlementLog.Batch(opening.events()))
        def rename = store.append(id, 1, [pending(new SettlementRenamed("Mountains"))])
        database.jdbc.update("UPDATE settlement_projection SET document = jsonb_set(document, ARRAY[?]::text[], ?::jsonb) WHERE id = ?", collection, json, id.value())

        when:
        views.findById(id)

        then:
        thrown(IllegalArgumentException)

        when:
        views.apply(new CommittedSettlementLog.Batch(rename.events()))

        then:
        thrown(IllegalArgumentException)
        views.checkpoint() == 1
        store.load(id).size() == 2

        when:
        database.jdbc.update("UPDATE settlement_projection SET document = jsonb_set(document, ARRAY[?]::text[], '[]'::jsonb) WHERE id = ?", collection, id.value())
        views.apply(new CommittedSettlementLog.Batch(rename.events()))

        then:
        views.findById(id).get().name() == "Mountains"
        views.checkpoint() == 2

        cleanup:
        database?.close()

        where:
        collection      | shape             | json
        "participants"  | "null"            | "null"
        "retired"       | "null"            | "null"
        "expenses"      | "null"            | "null"
        "repayments"    | "null"            | "null"
        "obligations"   | "null"            | "null"
        "balances"      | "null"            | "null"
        "exchangeRates" | "null"            | "null"
        "participants"  | "object"          | "{}"
        "obligations"   | "object"          | "{}"
        "balances"      | "string"          | "\"lost\""
        "retired"       | "numeric entries" | "[1]"
    }

    def "a Settlement document rejects #format #field without narrowing its value"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def opening = store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        def views = projection(database)
        views.apply(new CommittedSettlementLog.Batch(opening.events()))
        database.jdbc.update("UPDATE settlement_projection SET document = jsonb_set(document, ARRAY[?]::text[], ?::jsonb) WHERE id = ?", field, json, id.value())

        when:
        views.findById(id)

        then:
        thrown(IllegalArgumentException)
        views.checkpoint() == 1
        store.load(id) == opening.events()

        cleanup:
        database?.close()

        where:
        field           | format       | json
        "schemaVersion" | "fractional" | "1.5"
        "schemaVersion" | "overflowed" | "4294967297"
        "schemaVersion" | "string"     | "\"1\""
        "schemaVersion" | "boolean"    | "true"
        "schemaVersion" | "unsupported"| "2"
        "version"       | "fractional" | "1.5"
        "version"       | "overflowed" | "18446744073709551617"
        "version"       | "string"     | "\"1\""
        "version"       | "zero"       | "0"
        "version"       | "negative"   | "-1"
    }

    def "a transient live projection failure retries from the same checkpoint"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        def views = projection(database)
        def consumer = new SettlementLogConsumer(store, views, 1, 10, 10)
        consumer.start()
        database.jdbc.execute("CREATE FUNCTION retry_checkpoint() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'try again' USING ERRCODE = '40001'; END \$\$")
        database.jdbc.execute("CREATE TRIGGER retry_checkpoint BEFORE UPDATE ON projection_checkpoint FOR EACH ROW EXECUTE FUNCTION retry_checkpoint()")

        when:
        store.append(id, 1, [pending(new SettlementRenamed("Mountains"))])
        Thread.sleep(100)

        then:
        consumer.running
        consumer.failure() == null
        views.checkpoint() == 1
        views.findById(id).get().name() == "Holiday"

        when:
        database.jdbc.execute("DROP TRIGGER retry_checkpoint ON projection_checkpoint")

        then:
        new PollingConditions(timeout: 10).eventually {
            assert views.checkpoint() == 2
            assert views.findById(id).get().name() == "Mountains"
        }
        consumer.ready()
        consumer.failure() == null

        cleanup:
        consumer?.stop()
        database?.close()
    }

    def "a persistent live apply failure stops progress without undoing prior commits"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        def views = projection(database)
        def consumer = new SettlementLogConsumer(store, views, 1, 10, 10)
        consumer.start()

        when:
        store.append(id, 1, [pending(new ParticipantRemoved(new ParticipantId(new UUID(0, 99))))])
        store.append(id, 2, [pending(new SettlementRenamed("Must not skip"))])

        then:
        new PollingConditions(timeout: 10).eventually {
            assert consumer.failure() instanceof IllegalStateException
            assert !consumer.running
            assert !consumer.ready()
        }
        views.checkpoint() == 1
        views.findById(id).get().name() == "Holiday"
        store.load(id).size() == 3

        cleanup:
        consumer?.stop()
        database?.close()
    }

    def "offline rebuild and a second consumer cannot run beside the normal consumer"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def views = projection(database)
        def consumer = new SettlementLogConsumer(store, views, 1, 10, 10)
        def second = new SettlementLogConsumer(store, views, 1, 10, 10)
        consumer.start()

        when:
        views.rebuildOffline(store, 1)

        then:
        thrown(IllegalStateException)
        consumer.ready()

        when:
        second.start()

        then:
        thrown(IllegalStateException)
        !second.ready()

        when:
        consumer.stop()
        views.rebuildOffline(store, 1)

        then:
        views.checkpoint() == 0
        !consumer.ready()

        cleanup:
        second?.stop()
        consumer?.stop()
        database?.close()
    }

    def "duplicate batches are harmless but a sequence gap leaves the entire batch unapplied"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def views = projection(database)
        def committed = store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        def opening = new CommittedSettlementLog.Batch(committed.events())
        views.apply(opening)
        def before = views.findById(id)

        when:
        views.apply(opening)

        then:
        views.findById(id) == before
        views.checkpoint() == 1

        when:
        views.apply(new CommittedSettlementLog.Batch([
            new EventEnvelope(id, 2, 2, EventId.random(), Instant.EPOCH, new SettlementRenamed("Staged")),
            new EventEnvelope(id, 4, 3, EventId.random(), Instant.EPOCH, new SettlementRenamed("Gap"))
        ]))

        then:
        thrown(IllegalStateException)
        views.findById(id) == before
        views.checkpoint() == 1

        cleanup:
        database?.close()
    }

    def "a checkpoint write failure rolls back every newly staged view"() {
        given:
        def database = new PostgresFixture()
        def id = SettlementId.random()
        def store = database.store()
        def committed = store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR"))), pending(new SettlementRenamed("Mountains"))])
        def views = projection(database)
        database.jdbc.execute("CREATE FUNCTION reject_checkpoint() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'checkpoint unavailable'; END \$\$")
        database.jdbc.execute("CREATE TRIGGER reject_checkpoint BEFORE UPDATE ON projection_checkpoint FOR EACH ROW EXECUTE FUNCTION reject_checkpoint()")

        when:
        views.apply(new CommittedSettlementLog.Batch(committed.events()))

        then:
        thrown(org.springframework.dao.DataAccessException)
        views.findById(id).empty
        views.checkpoint() == 0
        store.load(id) == committed.events()

        when:
        database.jdbc.execute("DROP TRIGGER reject_checkpoint ON projection_checkpoint")
        views.apply(new CommittedSettlementLog.Batch(committed.events()))

        then:
        views.findById(id).get().name() == "Mountains"
        views.checkpoint() == 2

        cleanup:
        database?.close()
    }

    def "retired Participant identifiers remain retired after restarting the durable projection"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def participant = new ParticipantId(new UUID(0, 11))
        def history = store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR"))), pending(new ParticipantAdded(participant, "Sam")), pending(new ParticipantRemoved(participant))])
        projection(database).apply(new CommittedSettlementLog.Batch(history.events()))
        def restarted = projection(database)
        def invalid = store.append(id, 3, [pending(new ParticipantAdded(participant, "Replacement"))])

        when:
        restarted.apply(new CommittedSettlementLog.Batch(invalid.events()))

        then:
        thrown(IllegalStateException)
        restarted.findById(id).get().participants().empty
        restarted.findById(id).get().version() == 3
        restarted.checkpoint() == 3

        cleanup:
        database?.close()
    }

    def "a failed offline rebuild never publishes a partial replacement or checkpoint"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def opening = store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        def views = projection(database)
        views.apply(new CommittedSettlementLog.Batch(opening.events()))
        def before = views.findById(id)
        store.append(id, 1, [pending(new ParticipantRemoved(new ParticipantId(new UUID(0, 99))))])

        when:
        views.rebuildOffline(store, 1)

        then:
        thrown(IllegalStateException)
        views.findById(id) == before
        views.checkpoint() == 1
        store.highWaterMark() == 2

        cleanup:
        database?.close()
    }

    def "an unsupported stored event prevents startup rather than skipping financial history"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        database.jdbc.update("UPDATE settlement_event SET schema_version = 2")
        def views = projection(database)
        def consumer = new SettlementLogConsumer(store, views, 1, 20, 20)

        when:
        consumer.start()

        then:
        thrown(IllegalArgumentException)
        !consumer.ready()
        !consumer.running
        consumer.failure() != null
        views.checkpoint() == 0
        views.findById(id).empty

        cleanup:
        consumer?.stop()
        database?.close()
    }

    def "a committed but undelivered command is recovered before readiness and rebuild is equivalent"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        store.append(id, 0, [pending(new SettlementOpened("Holiday", Monetary.getCurrency("EUR")))])
        store.append(id, 1, [pending(new SettlementRenamed("Mountains"))])
        def views = new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec())
        def consumer = new SettlementLogConsumer(store, views, 1, 20, 20)

        when:
        consumer.start()

        then:
        consumer.ready()
        views.checkpoint() == 2
        views.findById(id).get().name() == "Mountains"
        views.findById(id).get().version() == 2

        when:
        consumer.stop()
        def restarted = new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec())
        def before = restarted.findById(id)
        restarted.rebuildOffline(store, 1)

        then:
        restarted.findById(id) == before
        restarted.checkpoint() == 2

        cleanup:
        consumer?.stop()
        database?.close()
    }

    private static PostgresSettlementProjection projection(PostgresFixture database) {
        new PostgresSettlementProjection(database.jdbc, database.transactions, new SettlementEventCodec())
    }

    private static PendingEvent pending(event) {
        new PendingEvent(EventId.random(), event, Instant.EPOCH)
    }
}
