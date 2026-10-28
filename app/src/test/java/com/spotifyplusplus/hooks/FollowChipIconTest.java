package com.spotifyplusplus.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.settings.registry.ControlRegistry;
import com.spotifyplusplus.settings.registry.ControlSpec;
import com.spotifyplusplus.settings.values.FollowChipIcon;

import org.junit.Test;

import java.util.Arrays;

/** Follow chip icon choice: stable stored values, adaptive direction, editor-only policy. */
public class FollowChipIconTest {
    @Test
    public void iconDefaultsToAdaptiveArrow() {
        assertEquals("Adaptive arrow", Settings.FOLLOW_CHIP_ICON.defaultValue);
        assertEquals(Arrays.asList("Adaptive arrow", "Static arrow", "Waveform"),
                Settings.FOLLOW_CHIP_ICON.allowedValues);
        assertEquals("Adaptive arrow", Settings.FOLLOW_CHIP_ICON.coerce("bogus"));
        assertEquals("Waveform", Settings.FOLLOW_CHIP_ICON.coerce("Waveform"));
        assertEquals(Settings.INTERNAL, Settings.FOLLOW_CHIP_ICON.section);
    }

    @Test
    public void adaptivePointsDownWhenViewportIsAboveTarget() {
        // Viewport above the desired target: the song is below, so the arrow points down.
        assertFalse(LyricsJumpToCurrentController.shouldPointUp(0, 500));
        assertFalse(LyricsJumpToCurrentController.shouldPointUp(100, 102));
        assertFalse(LyricsJumpToCurrentController.shouldPointUp(200, 200));
    }

    @Test
    public void adaptivePointsUpWhenViewportIsBelowTarget() {
        assertTrue(LyricsJumpToCurrentController.shouldPointUp(500, 0));
        assertTrue(LyricsJumpToCurrentController.shouldPointUp(105, 100));
    }

    @Test
    public void arrowGlyphsReuseDownArrowAndUpPair() {
        assertEquals("\u2193", LyricsJumpToCurrentController.arrowGlyph(false));
        assertEquals("\u2191", LyricsJumpToCurrentController.arrowGlyph(true));
    }

    @Test
    public void iconKindHelpers() {
        assertTrue(LyricsJumpToCurrentController.isAdaptiveIcon(FollowChipIcon.ADAPTIVE_ARROW));
        assertFalse(LyricsJumpToCurrentController.isAdaptiveIcon(FollowChipIcon.STATIC_ARROW));
        assertFalse(LyricsJumpToCurrentController.isAdaptiveIcon(FollowChipIcon.WAVEFORM));
        assertTrue(LyricsJumpToCurrentController.isWaveformIcon(FollowChipIcon.WAVEFORM));
        assertFalse(LyricsJumpToCurrentController.isWaveformIcon(FollowChipIcon.ADAPTIVE_ARROW));
    }

    @Test
    public void iconStaysOutOfTheNormalSettingsPanel() {
        // The layout editor owns the follow chip, so the panel has no row for it.
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            assertFalse("the panel must not own " + entry.rowId(),
                    entry.setting == Settings.FOLLOW_CHIP_ICON);
        }
    }
}
