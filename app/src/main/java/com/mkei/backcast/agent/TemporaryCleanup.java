package com.mkei.backcast.agent;

/** A turn owns its temporary materials even after cancellation or retargeting. */
public interface TemporaryCleanup {
    void beginTurn();
    String cleanupTemporary();
    String finishTurn();
}
