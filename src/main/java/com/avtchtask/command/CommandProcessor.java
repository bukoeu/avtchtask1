package com.avtchtask.command;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandProcessor {
    private final ExecutorService executor;
    private final AtomicBoolean shutdownSignaled = new AtomicBoolean(false);
    /**
     * Counted down by ExitCommand (running inside the pool) so the main thread knows
     * it is safe to call executor.shutdown() from outside, avoiding a deadlock that
     * would arise from calling awaitTermination() from within a pool thread.
     */
    private final CountDownLatch exitLatch = new CountDownLatch(1);

    public CommandProcessor(int threadCount) {
        this.executor = Executors.newFixedThreadPool(threadCount);
    }

    public void submit(Command command) {
        if (shutdownSignaled.get()) return;
        executor.submit(command::execute);
    }

    /** Called by ExitCommand from within the executor thread. */
    public void signalExit() {
        shutdownSignaled.set(true);
        exitLatch.countDown();
    }

    public boolean isShutdownSignaled() {
        return shutdownSignaled.get();
    }

    /**
     * Called by the main thread after ExitCommand has been submitted.
     * Waits for ExitCommand to actually run, then shuts the executor down
     * gracefully (finishing all queued tasks before terminating).
     */
    public void gracefulShutdown() throws InterruptedException {
        exitLatch.await();           // wait for ExitCommand to execute
        executor.shutdown();         // no new tasks; finish queued ones
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();  // force-cancel if timeout exceeded
        }
        System.out.println("[EXIT] Shutdown complete.");
    }
}
