package com.spotifyplusplus.hooks;

import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.settings.values.LikedSongsButton;
import com.spotifyplusplus.settings.values.StoredValues;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The Add to Liked Songs action, resolved from what Spotify's own MediaSession advertises.
 *
 * <p>Spotify does not expose "save this track" through the MediaController API: it publishes it as
 * a {@link android.media.PlaybackState.CustomAction} and applies it when that action is sent back
 * through the session. The action id is not a public contract and its spelling has moved between
 * builds, so it is matched on a vocabulary rather than one literal string.
 *
 * <p>Every failure also carries a reason token. The shell has no other way to say which of the
 * checks refused, and a toast alone has already cost three rounds of guessing at which branch fired.
 */
final class SpotifyCollectionAction {
    static final class AdvertisedAction {
        final String id;
        final String label;

        AdvertisedAction(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    interface Session {
        String packageName();

        /** The session's track uri. Empty when the session does not publish one. */
        String trackUri();

        List<AdvertisedAction> advertisedActions();

        boolean dispatch(String actionId);
    }

    /** Whether the action was sent, and the first check that refused when it was not. */
    static final class Outcome {
        final boolean dispatched;
        final String reason;

        Outcome(boolean dispatched, String reason) {
            this.dispatched = dispatched;
            this.reason = reason;
        }
    }

    private SpotifyCollectionAction() {}

    static boolean enabled(String mode) {
        return StoredValues.fromStored(LikedSongsButton.class, mode) != LikedSongsButton.DEFAULT;
    }

    static boolean dispatch(String mode, SpotifyTrack expected, Supplier<SpotifyTrack> currentTrack,
                            Supplier<Session> sessionSupplier) {
        return outcome(mode, expected, currentTrack, sessionSupplier).dispatched;
    }

    /**
     * The same decision as {@link #dispatch}, with the reason it ended where it did.
     *
     * <p>Cheapest and most local check first, the action scan last, so the reason names the first
     * thing actually missing rather than a later consequence of it.
     */
    static Outcome outcome(String mode, SpotifyTrack expected, Supplier<SpotifyTrack> currentTrack,
                           Supplier<Session> sessionSupplier) {
        if (!enabled(mode)) return new Outcome(false, "mode_off");
        if (!isSong(expected)) return new Outcome(false, "not_a_song");
        try {
            SpotifyTrack current = currentTrack.get();
            if (!matches(expected, current)) return new Outcome(false, "track_changed");
            Session session = sessionSupplier.get();
            if (session == null) return new Outcome(false, "no_session");
            if (!"com.spotify.music".equals(session.packageName())) {
                return new Outcome(false, "not_spotify");
            }
            String sessionUri = safe(session.trackUri());
            // A session that publishes no media id is not evidence of a mismatch: the metadata key
            // is Spotify's to publish, and a build that stopped publishing it left the button dead
            // while the session itself was healthy. A reported, different uri still refuses.
            if (!sessionUri.isEmpty() && !sameTrackUri(expected.uri, sessionUri)) {
                return new Outcome(false, "session_track_mismatch");
            }
            String actionId = collectionActionId(session.advertisedActions());
            if (actionId == null) return new Outcome(false, "no_collection_action");
            // The scan and the session read take time; acting on a track that changed underneath
            // would save the next song.
            if (!matches(expected, currentTrack.get())) return new Outcome(false, "track_changed");
            return new Outcome(session.dispatch(actionId), "dispatched");
        } catch (RuntimeException unavailable) {
            return new Outcome(false, "error_" + unavailable.getClass().getSimpleName());
        }
    }

    static String collectionActionId(List<AdvertisedAction> actions) {
        if (actions == null) return null;
        for (AdvertisedAction action : actions) {
            if (action == null || action.id == null) continue;
            if (isCollectionAction(action.id) || isCollectionAction(action.label)) {
                return action.id;
            }
        }
        return null;
    }

    /**
     * The vocabulary the action is recognized by, printed verbatim in the log when nothing matched.
     *
     * <p>Exact-id matching broke once already, and a wrong guess here is a dead button for a whole
     * release. Tokens match on word boundaries, so {@code SAVE_OFFLINE} is not a like.
     */
    static final String[] ACTION_TOKENS = {
            "collection", "like", "liked", "favorite", "favourite", "heart", "喜欢", "收藏", "点赞"
    };

    /** Ambiguous on their own, so these only count next to a library-ish word. */
    private static final String[] QUALIFIED_TOKENS = {"save", "saved", "add"};

    private static final String[] QUALIFIERS = {"library", "song", "track", "music", "collection"};

    static boolean isCollectionAction(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return false;
        for (String token : ACTION_TOKENS) {
            if (hasToken(text, token)) return true;
        }
        for (String token : QUALIFIED_TOKENS) {
            if (!hasToken(text, token)) continue;
            for (String qualifier : QUALIFIERS) {
                if (text.contains(qualifier)) return true;
            }
        }
        return false;
    }

    private static boolean hasToken(String text, String token) {
        int at = text.indexOf(token);
        while (at >= 0) {
            boolean start = at == 0 || !isWordChar(text.charAt(at - 1));
            int end = at + token.length();
            boolean stop = end >= text.length() || !isWordChar(text.charAt(end));
            if (start && stop) return true;
            at = text.indexOf(token, at + 1);
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * Same track, however the session spells it: the full uri, the bare id, or a uri a metadata
     * field padded with a query or fragment. The id decides, and the item type decides when the
     * session publishes one: an episode sharing a track's id is not that track, and an empty id
     * never matches, so an unreadable value cannot be mistaken for agreement.
     */
    static boolean sameTrackUri(String expectedUri, String sessionUri) {
        String expectedId = trackIdOf(expectedUri);
        String sessionId = trackIdOf(sessionUri);
        if (expectedId.isEmpty() || !expectedId.equals(sessionId)) return false;
        String expectedType = itemTypeOf(expectedUri);
        String sessionType = itemTypeOf(sessionUri);
        return sessionType.isEmpty() || expectedType.isEmpty() || sessionType.equals(expectedType);
    }

    private static String trackIdOf(String value) {
        String text = safe(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '?' || c == '#') {
                text = text.substring(0, i);
                break;
            }
        }
        int colon = text.lastIndexOf(':');
        return (colon >= 0 ? text.substring(colon + 1) : text).trim();
    }

    /** "spotify:track:abc" is a track; a bare id carries no type to compare. */
    private static String itemTypeOf(String value) {
        String text = safe(value);
        int first = text.indexOf(':');
        if (first < 0) return "";
        int second = text.indexOf(':', first + 1);
        return second < 0 ? "" : text.substring(first + 1, second).trim();
    }

    /** The action ids and labels a session offers, for the log line. Never a user value. */
    static String describeActions(List<AdvertisedAction> actions) {
        if (actions == null || actions.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (AdvertisedAction action : actions) {
            if (action == null) continue;
            if (out.length() > 0) out.append(',');
            out.append('"').append(bound(action.id)).append('"');
            if (action.label != null && !action.label.isEmpty()) {
                out.append(" name=\"").append(bound(action.label)).append('"');
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** What a resolution attempt wanted, so a log line names it without repeating the list. */
    static String wantedTokens() {
        StringBuilder out = new StringBuilder();
        for (String token : ACTION_TOKENS) {
            if (out.length() > 0) out.append('|');
            out.append(token);
        }
        return out.toString();
    }

    private static String bound(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').trim();
        return text.length() <= 64 ? text : text.substring(0, 64);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static boolean isSong(SpotifyTrack track) {
        return track != null && track.uri != null && track.uri.startsWith("spotify:track:")
                && track.uri.length() > "spotify:track:".length();
    }

    private static boolean matches(SpotifyTrack expected, SpotifyTrack current) {
        return current != null && expected.uri.equals(current.uri) && expected.saved == current.saved;
    }
}
