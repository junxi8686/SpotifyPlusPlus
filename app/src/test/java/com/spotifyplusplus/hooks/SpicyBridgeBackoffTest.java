package com.spotifyplusplus.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the HyperGlow bridge retries, and how often it may log.
 *
 * <p>The device log had 37 pairs of bind/provider failures in one run: every publication attempted
 * the bind, the refused bind attempted the provider, and the failed provider call attempted the
 * bind again - no backoff anywhere in the cycle. These pin the policy that replaces it, and the
 * bound it puts on the log.
 */
public class SpicyBridgeBackoffTest {
    @Test
    public void theLadderStartsAtASecondAndStopsAtAMinute() {
        assertEquals(1_000L, SpicyBridgeBackoff.delayMs(0));
        assertEquals(2_000L, SpicyBridgeBackoff.delayMs(1));
        assertEquals(4_000L, SpicyBridgeBackoff.delayMs(2));
        assertEquals(8_000L, SpicyBridgeBackoff.delayMs(3));
        assertEquals(60_000L, SpicyBridgeBackoff.delayMs(10));
        // No shift overflow: a long-lived failure still waits a minute, not a negative delay.
        assertEquals(60_000L, SpicyBridgeBackoff.delayMs(64));
        assertEquals(60_000L, SpicyBridgeBackoff.delayMs(Integer.MAX_VALUE));
    }

    @Test
    public void anAttemptIsDueOnlyAfterItsDeadline() {
        assertTrue("never attempted", SpicyBridgeBackoff.attemptDue(1_000L, 0L));
        assertFalse(SpicyBridgeBackoff.attemptDue(1_000L, 2_000L));
        assertTrue(SpicyBridgeBackoff.attemptDue(2_000L, 2_000L));
    }

    @Test
    public void presenceIsProbedOncePerMinute() {
        assertTrue("never probed", SpicyBridgeBackoff.presenceCheckDue(1_000L, 0L));
        assertFalse(SpicyBridgeBackoff.presenceCheckDue(30_000L, 1_000L));
        assertTrue(SpicyBridgeBackoff.presenceCheckDue(61_000L, 1_000L));
        // A clock that went backwards must not freeze the probe forever.
        assertTrue(SpicyBridgeBackoff.presenceCheckDue(500L, 1_000L));
    }

    /**
     * The property that matters: an absent consumer costs a bounded number of attempts, not one per
     * publication. Attempts are counted the way the publisher makes them - only when one is due -
     * which puts them at 0s, 1s, 3s, 7s and 15s instead of one per state change.
     */
    @Test
    public void afailedConsumerIsAttemptedFiveTimesInHalfAMinuteNotThirtySeven() {
        int attempts = 0;
        long now = 0L;
        long retryAt = 0L;
        int failures = 0;
        while (now < 30_000L) {
            if (SpicyBridgeBackoff.attemptDue(now, retryAt)) {
                attempts++;
                failures++;
                retryAt = now + SpicyBridgeBackoff.delayMs(failures - 1);
            }
            now += 100L;
        }
        assertEquals(5, attempts);
        // Before the fix each of the ~37 publications in the same window produced two failures.
        assertTrue("bounded", attempts < 10);
    }
}
