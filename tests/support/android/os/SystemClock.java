package android.os;

/** Deterministic monotonic clock used only by the JVM regression tests. */
public final class SystemClock {
    private static volatile long now = 100000L;

    private SystemClock() {}

    public static long elapsedRealtime() {
        return now;
    }

    public static void set(long value) {
        now = value;
    }

    public static void advance(long delta) {
        now += delta;
    }
}
