package android.os;

/**
 * JVM stand-in for {@link android.os.SystemClock}, used only by the unit-test source set.
 *
 * <p>Same reason as the test-source {@code android.os.Bundle} and {@code android.net.Uri}: the
 * stubbed {@code android.jar} throws {@code RuntimeException("Method elapsedRealtime ... not
 * mocked")}, and the Android Auto rejection key reads the clock when a packet carries a prefetched
 * "next" row.
 *
 * <p>This one is a faithful stand-in rather than a stub: {@code elapsedRealtime()} and
 * {@code uptimeMillis()} are monotonic millisecond clocks, and every consumer in this codebase
 * uses them only as deltas (fade timers, sample ages, bind backoff). The origin differs from a real
 * device's boot time, which no caller observes.
 */
public final class SystemClock {
    private SystemClock() {
    }

    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }

    public static long uptimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
