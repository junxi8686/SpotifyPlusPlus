package com.spotifyplusplus.lyrics.providers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One live automatic request per source and track.
 *
 * <p>The device log showed three QQ Music searches inside the same millisecond, twenty-five of them
 * in twelve seconds. Nothing in the adapter can produce that - one walk of the spelling plan is
 * spaced by {@code VARIANT_DELAY_MS} and the retry no longer exists - so the requests were coming
 * from separate rounds. Each of them walked the plan concurrently, and the reason they could is that
 * no part of the path held a claim on the source: the session's fetch gate opens as soon as the first
 * source of a race delivers, while the losing sources' HTTP calls are still in flight, so the next
 * round is free to ask the same provider for the same track again.
 *
 * <p>This is that claim, and it is deliberately narrow. It covers only the automatic race, because
 * the owner's explicit source check from the picker is a request they made by hand and must still go
 * out; a manual check therefore does not consult this guard and is never dropped by it. A claim that
 * is never released - a callback that never fires - expires after {@link #STALE_MS}, which bounds the
 * cost of that at one skipped duplicate rather than a source that goes silent for the session.
 */
final class SourceRequestGuard {
    /**
     * How long a claim stays live without its callback. A provider that is slow enough to answer
     * after this is a provider the round has already given up on, so the slot can be reused.
     */
    static final long STALE_MS = 90_000L;

    private static final Map<String, Long> IN_FLIGHT = new ConcurrentHashMap<String, Long>();

    private SourceRequestGuard() {
    }

    /** Claims the slot for one automatic attempt; false when an identical one is already live. */
    static boolean claim(String source, String trackId) {
        return claim(source, trackId, System.currentTimeMillis());
    }

    static boolean claim(String source, String trackId, long nowMs) {
        String key = key(source, trackId);
        if (key == null) return true;
        synchronized (IN_FLIGHT) {
            Long startedAtMs = IN_FLIGHT.get(key);
            if (startedAtMs != null && nowMs - startedAtMs < STALE_MS) return false;
            IN_FLIGHT.put(key, nowMs);
            return true;
        }
    }

    /** Releases a claim. Idempotent: a provider that reports twice releases once. */
    static void release(String source, String trackId) {
        String key = key(source, trackId);
        if (key != null) IN_FLIGHT.remove(key);
    }

    /** Test seam: how many claims are live. */
    static int liveClaims() {
        return IN_FLIGHT.size();
    }

    /** Test seam: forget every claim. */
    static void clear() {
        IN_FLIGHT.clear();
    }

    private static String key(String source, String trackId) {
        if (source == null || source.isEmpty() || trackId == null || trackId.isEmpty()) return null;
        return source + "\u0000" + trackId;
    }
}
