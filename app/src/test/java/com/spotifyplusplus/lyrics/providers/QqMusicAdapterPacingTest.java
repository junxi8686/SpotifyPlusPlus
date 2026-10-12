package com.spotifyplusplus.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Test;

/**
 * The per-source pacing, and the absence of a cooldown.
 *
 * <p>The owner's requirement, verbatim: pace QQ so it is never hit many times in one second, and do
 * not answer a refusal with half a minute of silence. The pacing is a slot claim; the cooldown is
 * gone, and the source-level assertion below is what stops one being reintroduced quietly.
 */
public class QqMusicAdapterPacingTest {
    @After
    public void tearDown() {
        QqMusicAdapter.resetPacing();
    }

    @Test
    public void aSecondRequestInsideTheIntervalIsNotGrantedASlot() {
        QqMusicAdapter.resetPacing();
        long now = 1_000_000L;
        assertEquals("the first request goes out", 0L, QqMusicAdapter.waitForRequestSlot(now));
        long wait = QqMusicAdapter.waitForRequestSlot(now + 10L);
        assertTrue("one 10 ms later does not: it is paced, not refused", wait > 0L);
        assertEquals("and the wait is the rest of the interval",
                QqMusicAdapter.MIN_REQUEST_INTERVAL_MS - 10L, wait);
        assertEquals("once the interval has passed, the slot is free again", 0L,
                QqMusicAdapter.waitForRequestSlot(now + QqMusicAdapter.MIN_REQUEST_INTERVAL_MS));
    }

    /**
     * The numbers themselves, because they are the whole fix: too tight and the source is hammered,
     * too wide and the walk is needlessly slow.
     */
    @Test
    public void thePacingNumbersAreTheOnesReported() {
        assertEquals("minimum interval between any two QQ requests", 1500L,
                QqMusicAdapter.MIN_REQUEST_INTERVAL_MS);
        assertEquals("spellings tried per attempt", 3, QqMusicAdapter.MAX_SPELLINGS_PER_ATTEMPT);
    }

    /**
     * No cooldown field, no ladder, no fast-fail path: a refusal ends its own attempt and the source
     * is available again as soon as the pacing slot passes. Checked against the source because the
     * behaviour being pinned is an absence.
     */
    @Test
    public void thereIsNoCooldownSilencingTheSource() throws Exception {
        File source = new File("src/main/java/com/spotifyplusplus/lyrics/providers/QqMusicAdapter.java");
        if (!source.isFile()) source = new File("app/" + source.getPath());
        assertTrue(source.isFile());
        String java = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertFalse("no cooldown window", java.contains("cooldownUntilMs"));
        assertFalse("no doubling ladder", java.contains("RATE_LIMIT_BASE_COOLDOWN_MS"));
        assertFalse("no escalating cap", java.contains("RATE_LIMIT_MAX_COOLDOWN_MS"));
        assertFalse("no fast-fail retry-later path", java.contains("QQ Music rate limited; retry later"));
        assertFalse("and no 'cooldown engaged' line", java.contains("qq cooldown engaged"));
        assertTrue("the limiter is the bound that replaced it",
                java.contains("waitForRequestSlot"));
        assertTrue("and a refusal still ends its attempt", java.contains("qq refused code="));
    }
}
