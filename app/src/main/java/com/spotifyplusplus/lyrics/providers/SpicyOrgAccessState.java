package com.spotifyplusplus.lyrics.providers;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A confirmed access loss blocks cached lyrics until the server confirms restored access.
 *
 * <p>It never blocks the request itself: every fresh attempt is allowed to ask, and the answer
 * decides. Only display is gated - {@code CatalogPolicy.eligibleForDisplay()} hides stored
 * results of this source while the flag stands.
 */
public final class SpicyOrgAccessState {
    private static final String STATE = "SpotifyPlusSpicyOrgAccessState";
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

    private SpicyOrgAccessState() { }

    public static final class RequestTicket {
        public final long keyEpoch;
        /**
         * True when the caller asked for an explicit recovery check. Recorded so a request can
         * name the path that issued it; it no longer decides whether access can be restored - a
         * validated response does.
         */
        public final boolean recovery;
        private final long revision;

        private RequestTicket(long keyEpoch, long revision, boolean recovery) {
            this.keyEpoch = keyEpoch;
            this.revision = revision;
            this.recovery = recovery;
        }
    }

    public static boolean isTerminated(Context context) {
        synchronized (SpicyOrgKeyStore.class) {
            return context != null && state(context).getBoolean("terminated", false);
        }
    }

    public static long revision(Context context) {
        synchronized (SpicyOrgKeyStore.class) {
            return context == null ? 0L : state(context).getLong("revision", 0L);
        }
    }

    public static boolean canAcquire(Context context) {
        return beginRequest(context, false) != null;
    }

    /**
     * A new key can be checked, but rotation alone cannot restore cached lyric display.
     *
     * <p>A recorded access loss never refuses a fresh request. This used to return null while a
     * termination was recorded for the current key, which only {@code fetchForAccessCheck} (an
     * explicit recovery, and nothing calls it) could clear - so one refusal silenced
     * SpicyLyrics.org for automatic acquisition and for every later picker check until the owner
     * replaced the key. The other six sources are asked and the response decides; a revoked key
     * answers with the same refusal, which re-records it, and the catalogue's own retry horizon
     * bounds how often that happens. What stays gated is display: {@code eligibleForDisplay()}
     * keeps this source's cached results hidden until a validated response confirms access again.
     */
    public static RequestTicket beginRequest(Context context, boolean explicitRecovery) {
        synchronized (SpicyOrgKeyStore.class) {
            if (context == null) return null;
            return new RequestTicket(SpicyOrgKeyStore.epoch(context),
                    state(context).getLong("revision", 0L), explicitRecovery);
        }
    }

    public static boolean terminate(Context context, RequestTicket ticket, String stableCode) {
        if (!SpicyOrgProtocol.terminatesAccess(stableCode)) return false;
        synchronized (SpicyOrgKeyStore.class) {
            if (!current(context, ticket)) return false;
            state(context).edit().putBoolean("terminated", true)
                    .putLong("terminated_epoch", ticket.keyEpoch)
                    .putLong("revision", ticket.revision + 1L).commit();
        }
        changed();
        return true;
    }

    /** Call only after the matching successful response and its lyrics pass validation. */
    public static boolean acceptValidatedResponse(Context context, RequestTicket ticket) {
        boolean restored = false;
        synchronized (SpicyOrgKeyStore.class) {
            if (!current(context, ticket)) return false;
            SharedPreferences state = state(context);
            if (state.getBoolean("terminated", false)) {
                // Any validated response for the current ticket is the server confirming access
                // again, whichever path asked for it. This used to require ticket.recovery, so an
                // automatic answer could not clear the flag and access stayed lost until a check
                // that no caller ever made.
                state.edit().putBoolean("terminated", false).remove("terminated_epoch")
                        .putLong("revision", ticket.revision + 1L).commit();
                restored = true;
            }
        }
        if (restored) changed();
        return true;
    }

    /** Listeners must post work to their owner thread and read the latest state when it runs. */
    public static void addListener(Runnable listener) { if (listener != null) listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    private static boolean current(Context context, RequestTicket ticket) {
        return context != null && ticket != null
                && SpicyOrgKeyStore.epoch(context) == ticket.keyEpoch
                && state(context).getLong("revision", 0L) == ticket.revision;
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    private static void changed() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }
}
