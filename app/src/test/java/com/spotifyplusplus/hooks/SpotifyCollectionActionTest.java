package com.spotifyplusplus.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.SpotifyTrack;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class SpotifyCollectionActionTest {
    private static SpotifyTrack track(String uri, boolean saved) {
        return new SpotifyTrack("t", "a", "al", uri, 0, "", 0, null, 0, saved);
    }

    private static final class FakeSession implements SpotifyCollectionAction.Session {
        String packageName = "com.spotify.music";
        String trackUri;
        List<SpotifyCollectionAction.AdvertisedAction> actions;
        final AtomicReference<String> dispatched = new AtomicReference<>();

        @Override public String packageName() {
            return packageName;
        }

        @Override public String trackUri() {
            return trackUri;
        }

        @Override public List<SpotifyCollectionAction.AdvertisedAction> advertisedActions() {
            return actions;
        }

        @Override public boolean dispatch(String actionId) {
            dispatched.set(actionId);
            return true;
        }
    }

    private static SpotifyCollectionAction.AdvertisedAction action(String id, String label) {
        return new SpotifyCollectionAction.AdvertisedAction(id, label);
    }

    @Test
    public void onlyNonOffModesAreEnabled() {
        assertFalse(SpotifyCollectionAction.enabled("Off"));
        assertFalse(SpotifyCollectionAction.enabled("Plus"));
        assertTrue(SpotifyCollectionAction.enabled("Heart"));
        assertTrue(SpotifyCollectionAction.enabled("Star"));
        assertFalse(SpotifyCollectionAction.enabled("bogus"));
        assertFalse(SpotifyCollectionAction.enabled(null));
    }

    @Test
    public void offModeNeverDispatches() {
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "Add to collection"));
        SpotifyTrack expected = track("spotify:track:abc", false);
        assertFalse(SpotifyCollectionAction.dispatch("Off", expected, () -> expected, () -> session));
        assertNull(session.dispatched.get());
    }

    @Test
    public void rejectsNonSongsAndMismatches() {
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "Add to collection"));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", track("spotify:episode:1", false),
                () -> track("spotify:episode:1", false), () -> session));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", null, () -> null, () -> session));
        SpotifyTrack expected = track("spotify:track:abc", false);
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected,
                () -> track("spotify:track:other", false), () -> session));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> null));
        session.packageName = "com.other.app";
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session));
    }

    @Test
    public void requiresAdvertisedCollectionAction() {
        SpotifyTrack expected = track("spotify:track:abc", false);
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("play", "Play"));
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session));
        session.actions = null;
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session));
    }

    @Test
    public void dispatchesCollectionActionMatchedByIdOrLabel() {
        SpotifyTrack expected = track("spotify:track:abc", false);
        FakeSession byLabel = new FakeSession();
        byLabel.trackUri = "spotify:track:abc";
        byLabel.actions = Arrays.asList(action("play", "Play"),
                action("custom.add", "Add to Collection"));
        assertTrue(SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> byLabel));
        assertEquals("custom.add", byLabel.dispatched.get());

        FakeSession byId = new FakeSession();
        byId.trackUri = "spotify:track:abc";
        byId.actions = Collections.singletonList(action("com.spotify.ADD_TO_COLLECTION", null));
        assertTrue(SpotifyCollectionAction.dispatch("Star", expected, () -> expected, () -> byId));
        assertEquals("com.spotify.ADD_TO_COLLECTION", byId.dispatched.get());
    }

    @Test
    public void abortsWhenTrackChangesBeforeDispatch() {
        SpotifyTrack expected = track("spotify:track:abc", false);
        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "collection"));
        Supplier<SpotifyTrack> flapping = new Supplier<SpotifyTrack>() {
            boolean first = true;

            @Override public SpotifyTrack get() {
                if (first) {
                    first = false;
                    return expected;
                }
                return track("spotify:track:abc", true);
            }
        };
        assertFalse(SpotifyCollectionAction.dispatch("Heart", expected, flapping, () -> session));
        assertNull(session.dispatched.get());
    }

    // --- Why a tap did nothing, and the spellings a build may use ---

    /**
     * The reason token is what the shell logs. Without it a dead button leaves a toast and nothing
     * else, which is how the previous reports arrived.
     */
    @Test
    public void everyRefusalNamesTheCheckThatRefused() {
        SpotifyTrack expected = track("spotify:track:abc", false);

        FakeSession session = new FakeSession();
        session.trackUri = "spotify:track:abc";
        session.actions = Collections.singletonList(action("x", "Add to collection"));

        assertEquals("mode_off", SpotifyCollectionAction.outcome(
                "Off", expected, () -> expected, () -> session).reason);
        assertEquals("not_a_song", SpotifyCollectionAction.outcome(
                "Heart", track("spotify:episode:1", false), () -> expected, () -> session).reason);
        assertEquals("track_changed", SpotifyCollectionAction.outcome(
                "Heart", expected, () -> track("spotify:track:other", false), () -> session).reason);
        assertEquals("no_session", SpotifyCollectionAction.outcome(
                "Heart", expected, () -> expected, () -> null).reason);

        FakeSession noAction = new FakeSession();
        noAction.trackUri = "spotify:track:abc";
        noAction.actions = Collections.singletonList(action("play", "Play"));
        assertEquals("no_collection_action", SpotifyCollectionAction.outcome(
                "Heart", expected, () -> expected, () -> noAction).reason);

        FakeSession otherApp = new FakeSession();
        otherApp.packageName = "com.other.app";
        otherApp.trackUri = "spotify:track:abc";
        otherApp.actions = Collections.singletonList(action("x", "collection"));
        assertEquals("not_spotify", SpotifyCollectionAction.outcome(
                "Heart", expected, () -> expected, () -> otherApp).reason);

        SpotifyCollectionAction.Outcome ok = SpotifyCollectionAction.outcome(
                "Heart", expected, () -> expected, () -> session);
        assertTrue(ok.dispatched);
        assertEquals("dispatched", ok.reason);
    }

    /** A moved action id must still be recognized: the token is the contract, not the spelling. */
    @Test
    public void recognizesTheSpellingsASpotifyBuildMayUse() {
        String[] accepted = {
                "com.spotify.music.playback.action.LIKE",
                "com.spotify.music.playback.action.ADD_TO_COLLECTION",
                "Add to Liked Songs",
                "Save to Library",
                "收藏",
                "喜欢",
                "com.spotify.heart"
        };
        for (String value : accepted) {
            assertTrue(value, SpotifyCollectionAction.isCollectionAction(value));
        }
        String[] refused = {
                "Play", "Skip Next", "SHUFFLE", "SAVE_OFFLINE", "DOWNLOAD", "Repeat", "x", "", null
        };
        for (String value : refused) {
            assertFalse(String.valueOf(value), SpotifyCollectionAction.isCollectionAction(value));
        }
    }

    /**
     * A session that publishes no media id is not evidence of a mismatch: a build that stopped
     * publishing it left the button dead while the session was healthy. A reported, different id
     * still refuses.
     */
    @Test
    public void anUnknownSessionTrackDoesNotBlockTheAction() {
        SpotifyTrack expected = track("spotify:track:abc", false);

        assertTrue(dispatchWithSessionTrackUri(expected, null));
        assertTrue(dispatchWithSessionTrackUri(expected, ""));
        // A metadata field that carries the bare id matches the full uri for the same track.
        assertTrue(dispatchWithSessionTrackUri(expected, "abc"));
        // A padded uri is the same track; a different id never is.
        assertTrue(dispatchWithSessionTrackUri(expected, "spotify:track:abc?si=0123"));
        assertFalse(dispatchWithSessionTrackUri(expected, "spotify:track:other"));
        assertFalse(dispatchWithSessionTrackUri(expected, "spotify:episode:abc"));
    }

    @Test
    public void theOfferedActionsAreReportedForTheLog() {
        String described = SpotifyCollectionAction.describeActions(Arrays.asList(
                action("com.spotify.music.playback.action.PLAY", "Play"),
                action("com.spotify.music.playback.action.LIKE", null)));

        assertTrue(described, described.contains("\"com.spotify.music.playback.action.PLAY\""));
        assertTrue(described, described.contains("name=\"Play\""));
        assertTrue(described, described.contains("\"com.spotify.music.playback.action.LIKE\""));
        assertEquals("none", SpotifyCollectionAction.describeActions(null));
        assertEquals("none", SpotifyCollectionAction.describeActions(Collections.emptyList()));
        assertTrue(SpotifyCollectionAction.wantedTokens().contains("collection"));
    }

    private static boolean dispatchWithSessionTrackUri(SpotifyTrack expected, String sessionTrackUri) {
        FakeSession session = new FakeSession();
        session.trackUri = sessionTrackUri;
        session.actions = Collections.singletonList(action("x", "Add to collection"));
        boolean dispatched =
                SpotifyCollectionAction.dispatch("Heart", expected, () -> expected, () -> session);
        if (dispatched) assertEquals("x", session.dispatched.get());
        return dispatched;
    }
}
