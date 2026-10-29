package com.spotifyplusplus.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricsAmbientControllerTest {
    @Test
    public void darkeningAppliesToAnyBackgroundStyle() {
        // Owner-directed curve change (2026-10-29): the default reads deeper than the old linear
        // ramp (0.65 -> ~0.536) and the top stops near 0.22 instead of black.
        assertEquals(0.5359f, LyricsAmbientController.backgroundBrightness(35), 0.0001f);
        assertEquals(1f, LyricsAmbientController.backgroundBrightness(0), 0.0001f);
        assertEquals(0.22f, LyricsAmbientController.backgroundBrightness(100), 0.0001f);
        // Values above the setting's maximum still clamp rather than going negative.
        assertEquals(0.22f, LyricsAmbientController.backgroundBrightness(150), 0.0001f);
    }
}
