package com.mkei.backcast.tool;

import java.io.IOException;

/** Cleanup must finish independently of a cancelled operation, within one deadline. */
final class CleanupBudget implements ToolchainInstaller.Cancellation, AutoCloseable {
    static final long DEFAULT_NANOS = 10000000000L;
    final long deadlineNanos;
    private final Thread owner = Thread.currentThread();
    private boolean interrupted = Thread.interrupted();

    CleanupBudget(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

    @Override public void check() throws IOException {
        captureInterrupt();
        if (System.nanoTime() - deadlineNanos >= 0) throw new IOException("进程清理超过时限。");
    }

    int remainingMillis(int maximum) throws IOException {
        check();
        return (int)Math.max(1, Math.min(maximum, (deadlineNanos - System.nanoTime()) / 1000000L));
    }

    void pause(int millis) throws IOException {
        int wait = remainingMillis(millis);
        try { Thread.sleep(wait); }
        catch (InterruptedException cancelled) { recordInterrupt(); }
    }

    void recordInterrupt() { if (Thread.currentThread() == owner) interrupted = true; }

    void captureInterrupt() {
        if (Thread.currentThread() == owner && Thread.interrupted()) interrupted = true;
    }

    @Override public void close() {
        captureInterrupt();
        if (interrupted) owner.interrupt();
    }
}
