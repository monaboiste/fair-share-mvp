package com.github.monaboiste.fairshare.settlement.infrastructure;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresSettlementProjection implements SettlementViews {
    private static final long RUNTIME_LOCK = 741830921L;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SettlementProjectionCodec codec;

    public PostgresSettlementProjection(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, SettlementEventCodec codec) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.codec = new SettlementProjectionCodec(codec);
    }

    @Override
    public Optional<SettlementView> findById(SettlementId id) {
        List<SettlementView> views = jdbc.query(
                "SELECT version, document FROM settlement_projection WHERE id = ?",
                (row, _) -> {
                    var projection = codec.decode(row.getString("document"));
                    if (row.getLong("version") != projection.view().version()
                            || !projection.view().id().equals(id)) {
                        throw new IllegalStateException("Projection metadata mismatch");
                    }
                    return projection.view();
                },
                id.value());
        return views.stream().findFirst();
    }

    public long checkpoint() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT position FROM projection_checkpoint WHERE singleton", Long.class));
    }

    public void apply(CommittedSettlementLog.Batch batch) {
        transaction.executeWithoutResult(_ -> {
            long previous = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT position FROM projection_checkpoint WHERE singleton FOR UPDATE", Long.class));
            if (batch.lastPosition() <= previous) {
                return;
            }
            if (batch.firstPosition() != previous + 1) {
                throw new IllegalStateException("Gap in committed log at " + batch.firstPosition());
            }
            Map<SettlementId, SettlementProjector.Projection> projections = new HashMap<>();
            batch.events().stream().map(event -> event.streamId()).distinct().forEach(id -> {
                List<SettlementProjector.Projection> stored = jdbc.query(
                        "SELECT document FROM settlement_projection WHERE id = ?",
                        (row, _) -> codec.decode(row.getString(1)),
                        id.value());
                if (!stored.isEmpty()) {
                    projections.put(id, stored.getFirst());
                }
            });
            SettlementProjector.project(projections, batch.events());
            write(projections);
            jdbc.update("UPDATE projection_checkpoint SET position = ? WHERE singleton", batch.lastPosition());
        });
    }

    public void rebuildOffline(CommittedSettlementLog log, int batchLimit) {
        try (Connection ignored = acquireExclusive()) {
            transaction.executeWithoutResult(_ -> {
                jdbc.queryForObject(
                        "SELECT position FROM projection_checkpoint WHERE singleton FOR UPDATE", Long.class);
                long highWaterMark = log.highWaterMark();
                jdbc.update("DELETE FROM settlement_projection");
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
                jdbc.update("UPDATE projection_checkpoint SET position = ? WHERE singleton", highWaterMark);
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot release offline rebuild lock", failure);
        }
    }

    private void applyRebuildBatch(CommittedSettlementLog.Batch batch) {
        Map<SettlementId, SettlementProjector.Projection> projections = new HashMap<>();
        batch.events().stream().map(event -> event.streamId()).distinct().forEach(id -> {
            List<SettlementProjector.Projection> stored = jdbc.query(
                    "SELECT document FROM settlement_projection WHERE id = ?",
                    (row, _) -> codec.decode(row.getString(1)),
                    id.value());
            if (!stored.isEmpty()) {
                projections.put(id, stored.getFirst());
            }
        });
        SettlementProjector.project(projections, batch.events());
        write(projections);
    }

    private void write(Map<SettlementId, SettlementProjector.Projection> projections) {
        projections.forEach((id, projection) -> jdbc.update(
                "INSERT INTO settlement_projection VALUES (?, ?, ?::jsonb) ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version, document = EXCLUDED.document",
                id.value(),
                projection.view().version(),
                codec.encode(projection)));
    }

    Connection acquireExclusive() {
        try {
            Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection();
            try {
                try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                    statement.setLong(1, RUNTIME_LOCK);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        if (!result.getBoolean(1)) {
                            throw new IllegalStateException("Normal application or offline rebuild already running");
                        }
                    }
                }
                return connection;
            } catch (RuntimeException | SQLException failure) {
                connection.close();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot acquire exclusive runtime lock", failure);
        }
    }
}
