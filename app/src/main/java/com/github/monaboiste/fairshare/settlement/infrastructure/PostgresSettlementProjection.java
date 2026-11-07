package com.github.monaboiste.fairshare.settlement.infrastructure;

import com.github.monaboiste.fairshare.common.events.EventEnvelope;
import com.github.monaboiste.fairshare.settlement.application.query.SettlementView;
import com.github.monaboiste.fairshare.settlement.application.query.SettlementViews;
import com.github.monaboiste.fairshare.settlement.domain.SettlementId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresSettlementProjection implements SettlementViews {
    private static final long RUNTIME_LOCK = 741830921L;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SettlementProjectionCodec codec;

    public PostgresSettlementProjection(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions, SettlementEventCodec codec) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.codec = new SettlementProjectionCodec(codec);
    }

    @Override
    public Optional<SettlementView> findById(SettlementId id) {
        String querySql = "SELECT version, document FROM settlement_projection WHERE id = :id";
        List<SettlementView> views = jdbc.query(querySql, Map.of("id", id.value()), (row, _) -> {
            var projection = codec.decode(row.getString("document"));
            if (row.getLong("version") != projection.view().version()
                    || !projection.view().id().equals(id)) {
                throw new IllegalStateException("Projection metadata mismatch");
            }
            return projection.view();
        });
        return views.stream().findFirst();
    }

    public long checkpoint() {
        String queryPositionSql = "SELECT position FROM projection_checkpoint WHERE singleton";
        return Objects.requireNonNull(jdbc.queryForObject(queryPositionSql, Map.of(), Long.class));
    }

    public void apply(CommittedSettlementLog.Batch batch) {
        transaction.executeWithoutResult(_ -> {
            String lockPositionSql = "SELECT position FROM projection_checkpoint WHERE singleton FOR UPDATE";
            long previous = Objects.requireNonNull(jdbc.queryForObject(lockPositionSql, Map.of(), Long.class));
            if (batch.lastPosition() <= previous) {
                return;
            }
            if (batch.firstPosition() != previous + 1) {
                throw new IllegalStateException("Gap in committed log at " + batch.firstPosition());
            }

            Map<SettlementId, SettlementProjector.Projection> projections = new HashMap<>();
            batch.events().stream().map(EventEnvelope::streamId).distinct().forEach(id -> {
                String queryDocumentSql = "SELECT document FROM settlement_projection WHERE id = :id";
                List<SettlementProjector.Projection> stored = jdbc.query(
                        queryDocumentSql, Map.of("id", id.value()), (row, _) -> codec.decode(row.getString(1)));
                if (!stored.isEmpty()) {
                    projections.put(id, stored.getFirst());
                }
            });
            SettlementProjector.project(projections, batch.events());
            write(projections);
            String updateCheckpointSql = "UPDATE projection_checkpoint SET position = :lastPosition WHERE singleton";
            jdbc.update(updateCheckpointSql, Map.of("lastPosition", batch.lastPosition()));
        });
    }

    public void rebuildOffline(CommittedSettlementLog log, int batchLimit) {
        try (var _ = acquireExclusive()) {
            transaction.executeWithoutResult(_ -> {
                String queryPositionSql = "SELECT position FROM projection_checkpoint WHERE singleton FOR UPDATE";
                jdbc.queryForObject(queryPositionSql, Map.of(), Long.class);
                long highWaterMark = log.highWaterMark();
                String deleteProjectionSql = "DELETE FROM settlement_projection";
                jdbc.update(deleteProjectionSql, Map.of());
                long position = 0;
                while (position < highWaterMark) {
                    List<CommittedSettlementLog.Batch> batches = log.readBatches(position, highWaterMark, batchLimit);
                    if (batches.isEmpty()) {
                        throw new IllegalStateException("Incomplete committed log during rebuild");
                    }
                    for (var batch : batches) {
                        applyRebuildBatch(batch);
                        position = batch.lastPosition();
                    }
                }
                String updateCheckpointSql = "UPDATE projection_checkpoint SET position = :position WHERE singleton";
                jdbc.update(updateCheckpointSql, Map.of("position", highWaterMark));
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot release offline rebuild lock", failure);
        }
    }

    private void applyRebuildBatch(CommittedSettlementLog.Batch batch) {
        Map<SettlementId, SettlementProjector.Projection> projections = new HashMap<>();
        batch.events().stream().map(EventEnvelope::streamId).distinct().forEach(id -> {
            String queryDocumentSql = "SELECT document FROM settlement_projection WHERE id = ?";
            List<SettlementProjector.Projection> stored =
                    jdbc.query(queryDocumentSql, Map.of("id", id.value()), (row, _) -> codec.decode(row.getString(1)));
            if (!stored.isEmpty()) {
                projections.put(id, stored.getFirst());
            }
        });
        SettlementProjector.project(projections, batch.events());
        write(projections);
    }

    private void write(Map<SettlementId, SettlementProjector.Projection> projections) {
        String insertProjectionSql = """
                INSERT INTO settlement_projection
                VALUES (:id, :version, document::jsonb) ON CONFLICT (ID) DO
                UPDATE SET version = EXCLUDED.version, DOCUMENT = EXCLUDED.document
                """;
        projections.forEach((id, projection) -> jdbc.update(
                insertProjectionSql,
                Map.of(
                        "id", id.value(),
                        "version", projection.view().version(),
                        "document", codec.encode(projection))));
    }

    Connection acquireExclusive() {
        try {
            return acquireLockedConnection();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot acquire exclusive runtime lock", failure);
        }
    }

    private Connection acquireLockedConnection() throws SQLException {
        var connection =
                Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource()).getConnection();

        try {
            acquireLock(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            closeOnFailure(connection, failure);
            throw failure;
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, RUNTIME_LOCK);

            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("Normal application or offline rebuild already running");
                }
            }
        }
    }

    private static void closeOnFailure(Connection connection, Exception failure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
