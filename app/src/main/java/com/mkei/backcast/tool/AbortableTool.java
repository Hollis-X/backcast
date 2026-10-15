package com.mkei.backcast.tool;

import com.mkei.backcast.agent.Tool;

/** A tool whose in-flight call is cancelled by {@link #abort()} bumping an epoch. */
abstract class AbortableTool implements Tool {
    protected volatile int epoch;

    @Override
    public void abort() {
        epoch++;
    }

    /** Cancellation that fires once {@code mine} is no longer the current epoch or the thread is interrupted. */
    protected final ToolchainInstaller.Cancellation cancellationFor(final int mine) {
        return new ToolchainInstaller.Cancellation() {
            @Override public void check() throws Exception {
                if (epoch != mine || Thread.currentThread().isInterrupted()) throw new InterruptedException("已停止。");
            }
        };
    }
}
