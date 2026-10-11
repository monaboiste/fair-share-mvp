package com.github.monaboiste.fairshare.settlement.infrastructure

import com.github.monaboiste.fairshare.common.events.EventId
import com.github.monaboiste.fairshare.common.events.PendingEvent
import com.github.monaboiste.fairshare.common.events.VersionConflictException
import com.github.monaboiste.fairshare.settlement.domain.SettlementId
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementOpened
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementRenamed
import java.time.Instant
import javax.money.Monetary
import spock.lang.Specification

class PostgresEventStoreSpec extends Specification {
    def "every payload variant survives a PostgreSQL process restart"() {
        given:
        def database = new PostgresFixture()
        def codec = new SettlementEventCodec()
        def fixtures = new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResource("/settlement-events-v1.json"))
        def facts = fixtures.collect { fixture -> new PendingEvent(EventId.random(), codec.decode(fixture.get("type").textValue(), 1, fixture.get("payload").toString()), Instant.parse("2026-10-04T10:20:30.987654321Z")) }
        def id = SettlementId.random()
        def committed = database.store().append(id, 0, facts)

        when:
        database.restart()
        new spock.util.concurrent.PollingConditions(timeout: 20).eventually {
            assert database.store().load(id) == committed.events()
        }
        def restarted = database.store()

        then:
        restarted.readBatches(0, restarted.highWaterMark(), 1)*.events() == [committed.events()]
        restarted.load(id)*.payload() == facts*.payload()
        restarted.load(id)*.eventId() == facts*.eventId()
        restarted.highWaterMark() == 14

        cleanup:
        database?.close()
    }

    def "an incomplete stored append is rejected instead of exposing a partial committed batch"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        store.append(id, 0, [new PendingEvent(EventId.random(), new SettlementOpened("Holiday", Monetary.getCurrency("EUR")), Instant.EPOCH), new PendingEvent(EventId.random(), new SettlementRenamed("Mountains"), Instant.EPOCH)])
        database.jdbc.update("DELETE FROM settlement_event WHERE position = 2")

        when:
        store.readBatches(0, store.highWaterMark(), 1)

        then:
        thrown(IllegalStateException)

        cleanup:
        database?.close()
    }

    def "a failed multi-event append leaves neither history nor a consumed position"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def fact = new PendingEvent(EventId.random(), new SettlementOpened("Holiday", Monetary.getCurrency("EUR")), Instant.EPOCH)

        when:
        store.append(id, 0, [fact, fact])

        then:
        thrown(org.springframework.dao.DataIntegrityViolationException)
        !store.exists(id)
        store.readAll(0).empty
        store.highWaterMark() == 0

        when:
        def result = store.append(id, 0, [fact])

        then:
        result.events()*.position() == [1L]

        when:
        store.append(id, 1, [])

        then:
        thrown(IllegalArgumentException)
        store.load(id) == result.events()

        cleanup:
        database?.close()
    }

    def "concurrent appends cannot publish a later position past an uncommitted append"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def other = SettlementId.random()
        def executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        def entered = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        def transaction = new org.springframework.transaction.support.TransactionTemplate(database.transactions)
        def fact = { new PendingEvent(EventId.random(), new SettlementOpened("Holiday", Monetary.getCurrency("EUR")), Instant.EPOCH) }
        def first = executor.submit({ ->
            transaction.execute { status ->
                def result = store.append(id, 0, [fact()])
                entered.countDown()
                release.await()
                result
            }
        } as java.util.concurrent.Callable)
        assert entered.await(10, java.util.concurrent.TimeUnit.SECONDS)

        when:
        def later = executor.submit({ -> store.append(other, 0, [fact()]) } as java.util.concurrent.Callable)

        then:
        store.highWaterMark() == 0
        store.readBatches(0, 0, 10).empty
        !later.done

        when:
        release.countDown()
        def committedFirst = first.get(10, java.util.concurrent.TimeUnit.SECONDS)
        def committedLater = later.get(10, java.util.concurrent.TimeUnit.SECONDS)

        then:
        committedFirst.events()*.position() == [1L]
        committedLater.events()*.position() == [2L]
        store.readBatches(0, 2, 1)*.lastPosition() == [1L]
        store.readBatches(1, 2, 1)*.lastPosition() == [2L]

        cleanup:
        release?.countDown()
        executor?.shutdownNow()
        database?.close()
    }

    def "committed whole batches survive a new adapter with exact registration time"() {
        given:
        def database = new PostgresFixture()
        def store = database.store()
        def id = SettlementId.random()
        def at = Instant.parse("2026-10-04T10:20:30.123456789Z")
        def first = new PendingEvent(EventId.random(), new SettlementOpened("Holiday", Monetary.getCurrency("EUR")), at)
        def second = new PendingEvent(EventId.random(), new SettlementRenamed("Mountains"), at)

        when:
        def result = store.append(id, 0, [first, second])
        def restarted = database.store()

        then:
        result.version() == 2
        result.events()*.sequence() == [1L, 2L]
        result.events()*.position() == [1L, 2L]
        restarted.load(id) == result.events()
        restarted.readBatches(0, restarted.highWaterMark(), 1)*.events() == [result.events()]
        restarted.readAll(1) == [result.events()[1]]
        restarted.exists(id)
        !restarted.exists(SettlementId.random())
        result.events()*.occurredAt() == [at, at]

        when:
        restarted.readBatches(1, 2, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        restarted.append(id, 0, [first])

        then:
        thrown(VersionConflictException)
        restarted.load(id) == result.events()

        cleanup:
        database?.close()
    }
}
