package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The per-source in-flight claim: one live automatic request per source and track.
 *
 * <p>The bug it exists for is on the owner's device log - three QQ Music searches inside the same
 * millisecond, twenty-five in twelve seconds. Nothing in the adapter can produce that, so the
 * requests were coming from separate rounds, each free to walk its own spelling plan because nothing
 * held the source.
 */
public class SourceRequestGuardTest {
    @Before
    public void setUp() {
        SourceRequestGuard.clear();
    }

    @After
    public void tearDown() {
        SourceRequestGuard.clear();
    }

    @Test
    public void oneLiveClaimPerSourceAndTrack() {
        assertTrue(SourceRequestGuard.claim("QQ Music", "track-1", 1000L));
        assertFalse("a second automatic attempt for the same track is refused",
                SourceRequestGuard.claim("QQ Music", "track-1", 1001L));
        assertEquals(1, SourceRequestGuard.liveClaims());
        assertTrue("a different track is a different request",
                SourceRequestGuard.claim("QQ Music", "track-2", 1001L));
        assertTrue("and so is a different source on the same track",
                SourceRequestGuard.claim("NetEase", "track-1", 1001L));
        assertEquals(3, SourceRequestGuard.liveClaims());

        SourceRequestGuard.release("QQ Music", "track-1");
        assertTrue("once the first attempt reports, the slot is free again",
                SourceRequestGuard.claim("QQ Music", "track-1", 1002L));
        SourceRequestGuard.release("QQ Music", "track-1");
        SourceRequestGuard.release("QQ Music", "track-1");
        assertEquals("a provider that reports twice releases once", 2, SourceRequestGuard.liveClaims());
    }

    @Test
    public void aClaimWhoseCallbackNeverFiresExpires() {
        assertTrue(SourceRequestGuard.claim("QQ Music", "track-1", 1000L));
        assertFalse(SourceRequestGuard.claim("QQ Music", "track-1", 1000L + 1000L));
        assertTrue("past the stale window the slot is reused, so a lost callback cannot silence a"
                        + " source for the session",
                SourceRequestGuard.claim("QQ Music", "track-1",
                        1000L + SourceRequestGuard.STALE_MS + 1L));
    }

    @Test
    public void aRequestWithNothingToKeyOnIsNeverBlocked() {
        assertTrue(SourceRequestGuard.claim(null, "track-1", 1000L));
        assertTrue(SourceRequestGuard.claim("QQ Music", "", 1000L));
        assertTrue(SourceRequestGuard.claim("QQ Music", null, 1000L));
        assertEquals("and none of them takes a slot", 0, SourceRequestGuard.liveClaims());
    }
}
