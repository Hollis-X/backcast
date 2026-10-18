package com.mkei.backcast.agent;

/** A turn owns its temporary materials even after cancellation or retargeting. */
public interface TemporaryCleanup {
    void beginTurn();
    String cleanupTemporary();
    String finishTurn();
    /** 重启前留下、本轮之外的清理失败（每条只给一次）；不算本轮失败，只写诊断。没有返回 null。 */
    default String staleFailures() { return null; }
}
