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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresEventStore implements EventStore<SettlementId, SettlementEvent>, CommittedSettlementLog {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SettlementEventCodec codec;

    public PostgresEventStore(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions, SettlementEventCodec codec) {
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
        return transaction.execute(_ -> {
            String lockCursorSql = "SELECT position FROM event_cursor WHERE singleton FOR UPDATE";
            Long cursor = jdbc.queryForObject(lockCursorSql, Map.of(), Long.class);
            if (cursor == null) {
                throw new IllegalStateException("Event cursor not found");
            }

            String queryVersionsSql = "SELECT version FROM settlement_stream WHERE id = :id";
            List<Long> versions = jdbc.query(queryVersionsSql, Map.of("id", id.value()), (row, _) -> row.getLong(1));

            long actual = versions.isEmpty() ? 0 : versions.getFirst();
            if (actual != expectedVersion) {
                throw new VersionConflictException(id, expectedVersion, actual);
            }

            long version = Math.addExact(actual, events.size());
            long lastPosition = Math.addExact(cursor, events.size());

            String updateStreamSql = """
                    INSERT INTO settlement_stream (id, version)
                    VALUES (:id, :version) ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version
                    """;
            jdbc.update(updateStreamSql, Map.of("id", id.value(), "version", version));

            String appendBatchSql = "INSERT INTO append_batch VALUES (:cursor, :lastPosition, :streamId)";
            jdbc.update(
                    appendBatchSql, Map.of("cursor", cursor + 1, "lastPosition", lastPosition, "streamId", id.value()));
            List<EventEnvelope<SettlementId, SettlementEvent>> committed = new ArrayList<>();
            for (PendingEvent<SettlementEvent> event : events) {
                long sequence = actual + committed.size() + 1;
                long position = cursor + committed.size() + 1;

                String payload = codec.encode(event.payload());
                String insertEventSql = """
                        INSERT INTO settlement_event VALUES (
                            :position,
                            :cursor,
                            :streamId,
                            :sequence,
                            :eventId,
                            :occurredAtSeconds,
                            :occurredAtNano,
                            :eventType,
                            :schemaVersion,
                            :payload::jsonb
                        )
                        """;
                jdbc.update(
                        insertEventSql,
                        Map.of(
                                "position", position,
                                "cursor", cursor + 1,
                                "streamId", id.value(),
                                "sequence", sequence,
                                "eventId", event.eventId().value(),
                                "occurredAtSeconds", event.occurredAt().getEpochSecond(),
                                "occurredAtNano", event.occurredAt().getNano(),
                                "eventType", event.payload().type(),
                                "schemaVersion", event.payload().schemaVersion(),
                                "payload", payload));
                committed.add(new EventEnvelope<>(
                        id, sequence, position, event.eventId(), event.occurredAt(), event.payload()));
            }

            String updateCursorSql = "UPDATE event_cursor SET position = :lastPosition WHERE singleton";
            jdbc.update(updateCursorSql, Map.of("lastPosition", lastPosition));
            return new CommitResult<>(id, committed, version);
        });
    }

    @Override
    public List<EventEnvelope<SettlementId, SettlementEvent>> load(SettlementId id) {
        String queryAllSql = """
                SELECT
                    event_id,
                    event_type,
                    schema_version,
                    stream_id,
                    sequence,
                    position,
                    batch_position,
                    occurred_second,
                    occurred_nano,
                    payload
                FROM settlement_event WHERE stream_id = :id ORDER BY sequence
                """;
        List<EventEnvelope<SettlementId, SettlementEvent>> events =
                jdbc.query(queryAllSql, Map.of("id", id.value()), this::envelope);
        if (events.isEmpty()) {
            throw new StreamNotFoundException(id);
        }
        return List.copyOf(events);
    }

    @Override
    public boolean exists(SettlementId id) {
        String existsSql = "SELECT EXISTS (SELECT 1 FROM settlement_stream WHERE id = :id)";
        Boolean exists = jdbc.queryForObject(existsSql, Map.of("id", id.value()), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<EventEnvelope<SettlementId, SettlementEvent>> readAll(long afterPosition) {
        String queryAllSql = """
        SELECT
            event_id,
            event_type,
            schema_version,
            stream_id,
            sequence,
            position,
            batch_position,
            occurred_second,
            occurred_nano,
            payload
        FROM settlement_event WHERE position > :afterPosition ORDER BY position
        """;
        return jdbc.query(queryAllSql, Map.of("afterPosition", afterPosition), this::envelope);
    }

    @Override
    public long highWaterMark() {
        String queryPositionSql = "SELECT position FROM event_cursor WHERE singleton";
        return Objects.requireNonNull(jdbc.queryForObject(queryPositionSql, Map.of(), Long.class));
    }

    @Override
    public List<Batch> readBatches(long afterPosition, long throughPosition, int batchLimit) {
        if (afterPosition < 0 || throughPosition < afterPosition || batchLimit < 1) {
            throw new IllegalArgumentException("Invalid committed-log bounds");
        }
        String existsBatchSql = """
                SELECT EXISTS (
                    SELECT 1 FROM append_batch
                    WHERE first_position <= :firstPosition AND last_position > :lastPosition
                )
                """;
        Boolean batchExists = jdbc.queryForObject(
                existsBatchSql, Map.of("firstPosition", afterPosition, "lastPosition", afterPosition), Boolean.class);
        if (Boolean.TRUE.equals(batchExists)) {
            throw new IllegalArgumentException("Checkpoint splits a committed batch");
        }
        String queryPositionsSql = """
                SELECT first_position, last_position
                FROM append_batch
                WHERE first_position > :firstPosition AND last_position <= :lastPosition
                ORDER BY first_position LIMIT :limit
                """;
        List<StoredBatch> batches = jdbc.query(
                queryPositionsSql,
                Map.of("firstPosition", afterPosition, "lastPosition", throughPosition, "limit", batchLimit),
                (row, _) -> new StoredBatch(row.getLong(1), row.getLong(2)));
        String queryEventsSql = """
            SELECT
                event_id,
                schema_version,
                event_type,
                stream_id,
                sequence,
                position,
                batch_position,
                occurred_second,
                occurred_nano,
                payload
            FROM settlement_event WHERE batch_position = :batchPosition ORDER BY position
            """;
        return batches.stream()
                .map(stored -> {
                    List<EventEnvelope<SettlementId, SettlementEvent>> events =
                            jdbc.query(queryEventsSql, Map.of("batchPosition", stored.first()), this::envelope);
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
