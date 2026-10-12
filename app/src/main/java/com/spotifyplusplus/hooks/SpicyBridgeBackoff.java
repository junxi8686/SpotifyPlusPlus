package com.spotifyplusplus.hooks;

/**
 * When the HyperGlow bridge may try to reach its consumer again.
 *
 * <p>Every publication used to attempt a bind; a refused bind attempted the provider; a failed
 * provider call attempted the bind again. With no companion installed that cycle ran once per
 * state change and once per second heartbeat - one absent app producing 37 pairs of identical log
 * lines and the battery cost of a retry loop.
 *
 * <p>These are pure functions of a monotonic clock so the policy is testable without a device: an
 * attempt is due once its deadline has passed, and the deadline grows from one second to a minute.
 */
final class SpicyBridgeBackoff {
    static final long BASE_MS = 1_000L;
    static final long MAX_MS = 60_000L;
    /**
     * How long a known-absent consumer is trusted to stay absent. Also how quickly installing the
     * companion later is noticed, which is why it is a minute rather than forever.
     */
    static final long PRESENCE_TTL_MS = 60_000L;

    private SpicyBridgeBackoff() {}

    /** Backoff after {@code failures} consecutive failures: 1s, 2s, 4s ... capped at a minute. */
    static long delayMs(int failures) {
        if (failures <= 0) return BASE_MS;
        long delay = BASE_MS << Math.min(failures, 16);
        return delay <= 0L || delay > MAX_MS ? MAX_MS : delay;
    }

    static boolean attemptDue(long nowMs, long retryAtMs) {
        return retryAtMs == 0L || nowMs >= retryAtMs;
    }

    /** True when the consumer's presence must be re-probed; zero means it never has been. */
    static boolean presenceCheckDue(long nowMs, long checkedAtMs) {
        return checkedAtMs == 0L || nowMs < checkedAtMs || nowMs - checkedAtMs >= PRESENCE_TTL_MS;
    }
}
