package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.github.monaboiste.fairshare.common.events.CommitResult;
import com.github.monaboiste.fairshare.common.events.EventEnvelope;
import com.github.monaboiste.fairshare.common.events.EventId;
import com.github.monaboiste.fairshare.common.events.EventStore;
import com.github.monaboiste.fairshare.common.events.PendingEvent;
import com.github.monaboiste.fairshare.common.events.StreamNotFoundException;
import com.github.monaboiste.fairshare.common.events.VersionConflictException;
import com.github.monaboiste.fairshare.settlement.domain.SettlementId;
import com.github.monaboiste.fairshare.settlement.domain.event.SettlementEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresEventStore implements EventStore<SettlementId, SettlementEvent>, CommittedSettlementLog {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SettlementEventCodec codec;

    public PostgresEventStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, SettlementEventCodec codec) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.codec = codec;
    }

    @Override
    public CommitResult<SettlementId, SettlementEvent> append(
            SettlementId id, long expectedVersion, List<PendingEvent<SettlementEvent>> incoming) {
        List<PendingEvent<SettlementEvent>> events = List.copyOf(incoming);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Append requires events");
        }
        return Objects.requireNonNull(transaction.execute(_ -> {
            long cursor = Objects.requireNonNull(
                    jdbc.queryForObject("SELECT position FROM event_cursor WHERE singleton FOR UPDATE", Long.class));
            List<Long> versions = jdbc.query(
                    "SELECT version FROM settlement_stream WHERE id = ?", (row, _) -> row.getLong(1), id.value());
            long actual = versions.isEmpty() ? 0 : versions.getFirst();
            if (actual != expectedVersion) {
                throw new VersionConflictException(id, expectedVersion, actual);
            }
            long version = Math.addExact(actual, events.size());
            long lastPosition = Math.addExact(cursor, events.size());
            jdbc.update(
                    "INSERT INTO settlement_stream (id, version) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version",
                    id.value(),
                    version);
            jdbc.update("INSERT INTO append_batch VALUES (?, ?, ?)", cursor + 1, lastPosition, id.value());
            List<EventEnvelope<SettlementId, SettlementEvent>> committed = new ArrayList<>();
            for (PendingEvent<SettlementEvent> event : events) {
                long sequence = actual + committed.size() + 1;
                long position = cursor + committed.size() + 1;
                jdbc.update(
                        "INSERT INTO settlement_event VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                        position,
                        cursor + 1,
                        id.value(),
                        sequence,
                        event.eventId().value(),
                        event.occurredAt().getEpochSecond(),
                        event.occurredAt().getNano(),
                        event.payload().type(),
                        event.payload().schemaVersion(),
                        codec.encode(event.payload()));
                committed.add(new EventEnvelope<>(
                        id, sequence, position, event.eventId(), event.occurredAt(), event.payload()));
            }
            jdbc.update("UPDATE event_cursor SET position = ? WHERE singleton", lastPosition);
            return new CommitResult<>(id, committed, version);
        }));
    }

    @Override
    public List<EventEnvelope<SettlementId, SettlementEvent>> load(SettlementId id) {
        List<EventEnvelope<SettlementId, SettlementEvent>> events = jdbc.query(
                "SELECT * FROM settlement_event WHERE stream_id = ? ORDER BY sequence", this::envelope, id.value());
        if (events.isEmpty()) {
            throw new StreamNotFoundException(id);
        }
        return List.copyOf(events);
    }

    @Override
    public boolean exists(SettlementId id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM settlement_stream WHERE id = ?)", Boolean.class, id.value()));
    }

    @Override
    public List<EventEnvelope<SettlementId, SettlementEvent>> readAll(long afterPosition) {
        return List.copyOf(jdbc.query(
                "SELECT * FROM settlement_event WHERE position > ? ORDER BY position", this::envelope, afterPosition));
    }

    @Override
    public long highWaterMark() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT position FROM event_cursor WHERE singleton", Long.class));
    }

    @Override
    public List<Batch> readBatches(long afterPosition, long throughPosition, int batchLimit) {
        if (afterPosition < 0 || throughPosition < afterPosition || batchLimit < 1) {
            throw new IllegalArgumentException("Invalid committed-log bounds");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM append_batch WHERE first_position <= ? AND last_position > ?)",
                Boolean.class,
                afterPosition,
                afterPosition))) {
            throw new IllegalArgumentException("Checkpoint splits a committed batch");
        }
        List<StoredBatch> batches = jdbc.query(
                "SELECT first_position, last_position FROM append_batch WHERE first_position > ? AND last_position <= ? ORDER BY first_position LIMIT ?",
                (row, _) -> new StoredBatch(row.getLong(1), row.getLong(2)),
                afterPosition,
                throughPosition,
                batchLimit);
        return batches.stream()
                .map(stored -> {
                    List<EventEnvelope<SettlementId, SettlementEvent>> events = jdbc.query(
                            "SELECT * FROM settlement_event WHERE batch_position = ? ORDER BY position",
                            this::envelope,
                            stored.first());
                    if (events.size() != stored.last() - stored.first() + 1
                            || events.getFirst().position() != stored.first()
                            || events.getLast().position() != stored.last()) {
                        throw new IllegalStateException("Incomplete committed append at " + stored.first());
                    }
                    return new Batch(events);
                })
                .toList();
    }

    private record StoredBatch(long first, long last) {}

    private EventEnvelope<SettlementId, SettlementEvent> envelope(ResultSet row, int ignored) throws SQLException {
        return new EventEnvelope<>(
                new SettlementId(row.getObject("stream_id", UUID.class)),
                row.getLong("sequence"),
                row.getLong("position"),
                new EventId(row.getObject("event_id", UUID.class)),
                Instant.ofEpochSecond(row.getLong("occurred_second"), row.getInt("occurred_nano")),
                codec.decode(row.getString("event_type"), row.getInt("schema_version"), row.getString("payload")));
    }
}
