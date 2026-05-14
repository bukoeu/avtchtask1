package com.avtchtask.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Async group-commit writer for a single SQLite connection.
 *
 * Single responsibility: accept write tasks from any thread, drain them in
 * batches, and execute each batch as one transaction (group commit).
 *
 * The caller blocks on a CompletableFuture until the batch is committed.
 * Concurrent callers are automatically coalesced into a single transaction,
 * reducing per-write overhead by up to batchSize times.
 */
class AsyncBatchWriter {

    // ---- Write task ----------------------------------------------------------

    private static final class WriteTask {
        final String   sql;
        final String[] params;
        final long     enqueueNanos;
        final CompletableFuture<Void> future;

        WriteTask(String sql, String[] params, long enqueueNanos, CompletableFuture<Void> future) {
            this.sql          = sql;
            this.params       = params;
            this.enqueueNanos = enqueueNanos;
            this.future       = future;
        }
    }

    // ---- State ---------------------------------------------------------------

    private final Connection    conn;
    private final ReentrantLock lock;
    private final int           batchSize;
    private final WriteMetrics  metrics;

    private final BlockingQueue<WriteTask> writeQueue = new LinkedBlockingQueue<>();
    private final Thread                   writerThread;
    private volatile boolean               running    = true;

    // ---- Constructor ---------------------------------------------------------

    /**
     * @param conn      shared SQLite connection (also used by reads — lock guards all access)
     * @param lock      fair ReentrantLock shared with the read path
     * @param batchSize max writes per transaction (>= 1)
     * @param metrics   write-timing recorder
     */
    AsyncBatchWriter(Connection conn, ReentrantLock lock, int batchSize, WriteMetrics metrics) {
        this.conn      = conn;
        this.lock      = lock;
        this.batchSize = batchSize;
        this.metrics   = metrics;

        writerThread = new Thread(this::writerLoop, "db-writer-0");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    // ---- Public API ----------------------------------------------------------

    /**
     * Enqueues {@code sql} for async execution and blocks until committed.
     * Concurrent callers are automatically batched into one transaction.
     */
    void enqueue(String sql, String... params) {
        long enqueueNanos = System.nanoTime();
        CompletableFuture<Void> future = new CompletableFuture<>();
        writeQueue.offer(new WriteTask(sql, params, enqueueNanos, future));
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            System.err.println("[DB ERROR] " + e.getCause().getMessage());
        }
    }

    int getBatchSize() { return batchSize; }

    /** Stops the writer thread. Call before closing the connection. */
    void close() {
        running = false;
        writerThread.interrupt();
        try { writerThread.join(2000); } catch (InterruptedException ignored) { }
    }

    // ---- Writer internals ----------------------------------------------------

    private void writerLoop() {
        List<WriteTask> batch = new ArrayList<>(batchSize);
        while (running || !writeQueue.isEmpty()) {
            try {
                WriteTask first = writeQueue.poll(10, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.clear();
                batch.add(first);
                if (batchSize > 1) writeQueue.drainTo(batch, batchSize - 1);
                executeBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void executeBatch(List<WriteTask> batch) {
        List<WriteTask> succeeded = new ArrayList<>(batch.size());
        lock.lock();
        try {
            boolean multi = batch.size() > 1;
            if (multi) conn.setAutoCommit(false);

            for (WriteTask t : batch) {
                long waitNanos  = System.nanoTime() - t.enqueueNanos;
                int  writeNo    = metrics.startWrite(waitNanos);
                metrics.logLocked(Thread.currentThread().getName(), writeNo, waitNanos / 1_000_000);

                long writeStart = System.nanoTime();
                try (PreparedStatement ps = conn.prepareStatement(t.sql)) {
                    for (int i = 0; i < t.params.length; i++) ps.setString(i + 1, t.params[i]);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    System.err.println("[DB ERROR] " + e.getMessage());
                    t.future.completeExceptionally(e);
                    continue;
                }
                long writeNanos = System.nanoTime() - writeStart;
                metrics.endWrite(writeNo, waitNanos, writeNanos);
                metrics.logReleased(Thread.currentThread().getName(), writeNo, writeNanos / 1_000_000);
                succeeded.add(t);
            }

            if (multi) {
                conn.commit();
                conn.setAutoCommit(true);
            }
            for (WriteTask t : succeeded) t.future.complete(null);

        } catch (SQLException e) {
            System.err.println("[DB ERROR] batch commit: " + e.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) { }
            for (WriteTask t : batch) {
                if (!t.future.isDone()) t.future.completeExceptionally(e);
            }
        } finally {
            lock.unlock();
        }
    }
}
