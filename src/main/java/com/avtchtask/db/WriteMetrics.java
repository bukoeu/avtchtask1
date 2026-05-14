package com.avtchtask.db;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects write-performance data for a DatabaseManager.
 * Single responsibility: measure and expose DB write timing.
 *
 * Verbose log messages are queued and printed by a dedicated daemon thread
 * so that DB-write threads never block on I/O while holding the lock.
 */
public class WriteMetrics {

    private final AtomicInteger writeCounter   = new AtomicInteger(0);
    private final AtomicLong    totalWaitNanos  = new AtomicLong(0);
    private final AtomicLong    totalWriteNanos = new AtomicLong(0);
    private final AtomicLong    minWaitNanos    = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong    maxWaitNanos    = new AtomicLong(0);
    private final AtomicLong    minWriteNanos   = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong    maxWriteNanos   = new AtomicLong(0);

    // Raw wait/write nanos for percentile calculation (only when recordSamples=true)
    private final CopyOnWriteArrayList<Long> allWaitNanos  = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Long> allWriteNanos = new CopyOnWriteArrayList<>();

    /** Thread-safe list of [writeNo, waitMs, writeMs] — for charting. */
    public final CopyOnWriteArrayList<long[]> writeSamples = new CopyOnWriteArrayList<>();

    /** Set to true to record per-write samples into writeSamples. */
    public volatile boolean recordSamples = false;

    /** Set to true to print lock-acquire / release events to stdout (async). */
    public volatile boolean verboseLogging = false;

    // --- Async logging ---
    // LogEntry: threadName, writeNo, waitMs, writeMs, locked(true=LOCKED, false=RELEASED)
    private static final class LogEntry {
        final String threadName;
        final int    writeNo;
        final long   waitMs;
        final long   writeMs;
        final boolean locked;
        LogEntry(String threadName, int writeNo, long waitMs, long writeMs, boolean locked) {
            this.threadName = threadName;
            this.writeNo    = writeNo;
            this.waitMs     = waitMs;
            this.writeMs    = writeMs;
            this.locked     = locked;
        }
    }

    private final BlockingQueue<LogEntry> logQueue = new LinkedBlockingQueue<>();
    private final Thread logThread;

    /** Where verbose log lines go. Default: stdout. Call setLogFile() to redirect to a file. */
    private volatile PrintStream logOut = System.out;

    /**
     * Redirects verbose log output to the given file path.
     * Must be called before verboseLogging is set to true.
     */
    public void setLogFile(String path) throws FileNotFoundException {
        this.logOut = new PrintStream(path);
    }

    public WriteMetrics() {
        logThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LogEntry e = logQueue.take();
                    // String.format() happens here — NOT in the DB lock
                    if (e.locked) {
                        logOut.printf("  [DB LOCKED   %-25s] write#%d  waited=%dms%n",
                                e.threadName, e.writeNo, e.waitMs);
                    } else {
                        logOut.printf("  [DB RELEASED %-25s] write#%d  write=%dms%n",
                                e.threadName, e.writeNo, e.writeMs);
                    }
                } catch (InterruptedException ex) {
                    // drain remaining entries, then exit
                    LogEntry e;
                    while ((e = logQueue.poll()) != null) {
                        if (e.locked) {
                            logOut.printf("  [DB LOCKED   %-25s] write#%d  waited=%dms%n",
                                    e.threadName, e.writeNo, e.waitMs);
                        } else {
                            logOut.printf("  [DB RELEASED %-25s] write#%d  write=%dms%n",
                                    e.threadName, e.writeNo, e.writeMs);
                        }
                    }
                    break;
                }
            }
        }, "WriteMetrics-logger");
        logThread.setDaemon(true);
        logThread.start();
    }

    /**
     * Enqueues raw lock-acquired data. String.format() happens in the logger thread.
     * Returns immediately — no allocation or I/O in the caller's critical section.
     */
    public void logLocked(String threadName, int writeNo, long waitMs) {
        if (verboseLogging) logQueue.offer(new LogEntry(threadName, writeNo, waitMs, 0, true));
    }

    /**
     * Enqueues raw lock-released data. String.format() happens in the logger thread.
     */
    public void logReleased(String threadName, int writeNo, long writeMs) {
        if (verboseLogging) logQueue.offer(new LogEntry(threadName, writeNo, 0, writeMs, false));
    }

    /**
     * Called immediately after the lock is acquired.
     * Records the wait time and returns the sequential write number.
     */
    int startWrite(long waitNanos) {
        totalWaitNanos.addAndGet(waitNanos);
        updateMin(minWaitNanos, waitNanos);
        updateMax(maxWaitNanos, waitNanos);
        if (recordSamples) allWaitNanos.add(waitNanos);
        return writeCounter.incrementAndGet();
    }

    /**
     * Called after the SQL write completes.
     * Records the write duration and (optionally) appends a sample for charting.
     */
    void endWrite(int writeNo, long waitNanos, long writeNanos) {
        totalWriteNanos.addAndGet(writeNanos);
        updateMin(minWriteNanos, writeNanos);
        updateMax(maxWriteNanos, writeNanos);
        if (recordSamples) {
            allWriteNanos.add(writeNanos);
            writeSamples.add(new long[]{writeNo, waitNanos / 1_000_000, writeNanos / 1_000_000});
        }
    }

    private static void updateMin(AtomicLong ref, long val) {
        long cur;
        do { cur = ref.get(); } while (val < cur && !ref.compareAndSet(cur, val));
    }

    private static void updateMax(AtomicLong ref, long val) {
        long cur;
        do { cur = ref.get(); } while (val > cur && !ref.compareAndSet(cur, val));
    }

    private static long percentile(List<Long> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** One-line summary (used in the stats box header). */
    public String getSummary() {
        int n = writeCounter.get();
        if (n == 0) return "no writes";
        long avgWait  = totalWaitNanos.get()  / 1_000_000 / n;
        long avgWrite = totalWriteNanos.get() / 1_000_000 / n;
        long totWait  = totalWaitNanos.get()  / 1_000_000;
        long totWrite = totalWriteNanos.get() / 1_000_000;
        return String.format(
            "writes=%d | avgWait=%dms avgSQL=%dms | totalWait=%dms totalSQL=%dms",
            n, avgWait, avgWrite, totWait, totWrite);
    }

    /**
     * Multi-line detailed statistics block.
     * Includes avg / min / max / P95 / P99 for lock-wait and SQL-write time,
     * plus throughput (ms/op and ops/sec) based on the supplied elapsed time.
     *
     * @param totalElapsedMs wall-clock duration of the entire test in milliseconds
     */
    public String getDetailedSummary(long totalElapsedMs) {
        int n = writeCounter.get();
        if (n == 0) return "no writes";

        long avgWaitMs  = totalWaitNanos.get()  / 1_000_000 / n;
        long avgWriteMs = totalWriteNanos.get() / 1_000_000 / n;
        long minWaitMs  = minWaitNanos.get()  == Long.MAX_VALUE ? 0 : minWaitNanos.get()  / 1_000_000;
        long maxWaitMs  = maxWaitNanos.get()  / 1_000_000;
        long minWriteMs = minWriteNanos.get() == Long.MAX_VALUE ? 0 : minWriteNanos.get() / 1_000_000;
        long maxWriteMs = maxWriteNanos.get() / 1_000_000;
        long totWaitMs  = totalWaitNanos.get()  / 1_000_000;
        long totWriteMs = totalWriteNanos.get() / 1_000_000;

        double msPerOp   = totalElapsedMs > 0 ? (double) totalElapsedMs / n : 0;
        long   opsPerSec = totalElapsedMs > 0 ? (long) (n / (totalElapsedMs / 1000.0)) : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("writes       : %d%n", n));
        sb.append(String.format("Throughput   : %.3f ms/op  |  %d ops/sec%n", msPerOp, opsPerSec));
        sb.append(String.format("Lock-Wait    : avg=%dms  min=%dms  max=%dms  total=%dms%n",
                avgWaitMs, minWaitMs, maxWaitMs, totWaitMs));
        sb.append(String.format("SQL-Write    : avg=%dms  min=%dms  max=%dms  total=%dms%n",
                avgWriteMs, minWriteMs, maxWriteMs, totWriteMs));

        if (!allWaitNanos.isEmpty()) {
            List<Long> sortedWait  = new ArrayList<>(allWaitNanos);
            List<Long> sortedWrite = new ArrayList<>(allWriteNanos);
            Collections.sort(sortedWait);
            Collections.sort(sortedWrite);
            sb.append(String.format("Wait  P95/P99: %dms / %dms%n",
                    percentile(sortedWait, 95)  / 1_000_000,
                    percentile(sortedWait, 99)  / 1_000_000));
            sb.append(String.format("Write P95/P99: %dms / %dms",
                    percentile(sortedWrite, 95) / 1_000_000,
                    percentile(sortedWrite, 99) / 1_000_000));
        } else {
            sb.append("(P95/P99 not available: recordSamples=false)");
        }
        return sb.toString();
    }
}
