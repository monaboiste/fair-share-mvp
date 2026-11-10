package com.github.monaboiste.fairshare.settlement.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

public final class SettlementLogConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SettlementLogConsumer.class);
    private final CommittedSettlementLog events;
    private final PostgresSettlementProjection projection;
    private final int batchLimit;
    private final long pollMillis;
    private final long retryMillis;
    private final long shutdownMillis;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile @Nullable RuntimeException failure;
    private @Nullable Thread worker;
    private @Nullable Connection lease;

    public SettlementLogConsumer(
            CommittedSettlementLog events,
            PostgresSettlementProjection projection,
            int batchLimit,
            long pollMillis,
            long retryMillis) {
        this(events, projection, batchLimit, pollMillis, retryMillis, 30_000L);
    }

    public SettlementLogConsumer(
            CommittedSettlementLog events,
            PostgresSettlementProjection projection,
            int batchLimit,
            long pollMillis,
            long retryMillis,
            long shutdownMillis) {
        if (batchLimit < 1 || pollMillis < 1 || retryMillis < 1 || shutdownMillis < 1) {
            throw new IllegalArgumentException("Consumer settings must be positive");
        }
        this.events = events;
        this.projection = projection;
        this.batchLimit = batchLimit;
        this.pollMillis = pollMillis;
        this.retryMillis = retryMillis;
        this.shutdownMillis = shutdownMillis;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        lease = projection.acquireExclusive();
        try {
            catchUp(events.highWaterMark(), true);
            running = true;
            ready = true;
            worker = Thread.ofPlatform().name("settlement-log-consumer").start(this::consume);
        } catch (RuntimeException startupFailure) {
            failure = startupFailure;
            releaseLease();
            throw startupFailure;
        }
    }

    public boolean ready() {
        return ready;
    }

    public @Nullable RuntimeException failure() {
        return failure;
    }

    private void catchUp(long highWaterMark, boolean recovery) {
        long checkpoint = projection.checkpoint();
        if (checkpoint > highWaterMark) {
            throw new IllegalStateException("Projection checkpoint exceeds committed log");
        }
        while (checkpoint < highWaterMark && (recovery || running)) {
            List<CommittedSettlementLog.Batch> batches = events.readBatches(checkpoint, highWaterMark, batchLimit);
            if (batches.isEmpty()) {
                throw new IllegalStateException("Incomplete committed log during recovery");
            }
            for (var batch : batches) {
                if (!recovery && !running) {
                    return;
                }
                if (batch.lastPosition() <= checkpoint || batch.lastPosition() > highWaterMark) {
                    throw new IllegalStateException("Invalid committed-log progress during recovery");
                }
                projection.apply(batch);
                checkpoint = batch.lastPosition();
            }
        }
    }

    private void consume() {
        while (running) {
            try {
                catchUp(events.highWaterMark(), false);
                pause(pollMillis);
            } catch (TransientDataAccessException
                    | RecoverableDataAccessException
                    | DataAccessResourceFailureException transientFailure) {
                log.warn("Retrying Settlement projection without advancing checkpoint", transientFailure);
                pause(retryMillis);
            } catch (RuntimeException persistentFailure) {
                failure = persistentFailure;
                ready = false;
                running = false;
                log.error("Settlement projection stopped; financial events were not skipped", persistentFailure);
            }
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public synchronized void stop() {
        ready = false;
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            try {
                current.join(shutdownMillis);
                if (current.isAlive()) {
                    var shutdownFailure = new IllegalStateException("Projection shutdown deadline exceeded");
                    failure = shutdownFailure;
                    throw shutdownFailure;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping Settlement projection", interrupted);
            }
            worker = null;
        }
        releaseLease();
    }

    private void releaseLease() {
        Connection current = lease;
        if (current != null) {
            try {
                current.close();
                lease = null;
            } catch (SQLException closeFailure) {
                throw new IllegalStateException("Cannot release runtime lock", closeFailure);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
