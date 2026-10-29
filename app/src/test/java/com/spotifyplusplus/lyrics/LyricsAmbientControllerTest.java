package com.spotifyplusplus.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricsAmbientControllerTest {
    @Test
    public void darkeningAppliesToAnyBackgroundStyle() {
        // Upstream's linear map (owner round 3: keep upstream parity; the lightest end must stay
        // light). 0 = untouched, the default 35 = 0.65, 100 = black, out-of-range clamps.
        assertEquals(0.65f, LyricsAmbientController.backgroundBrightness(35), 0.0001f);
        assertEquals(1f, LyricsAmbientController.backgroundBrightness(0), 0.0001f);
        assertEquals(0f, LyricsAmbientController.backgroundBrightness(100), 0.0001f);
        assertEquals(0f, LyricsAmbientController.backgroundBrightness(150), 0.0001f);
        assertEquals(1f, LyricsAmbientController.backgroundBrightness(-10), 0.0001f);
    }
}
