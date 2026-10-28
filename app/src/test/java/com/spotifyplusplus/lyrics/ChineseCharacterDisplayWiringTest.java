package com.spotifyplusplus.lyrics;

import com.spotifyplusplus.settings.registry.ControlRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.lyrics.language.ChineseCharacterConverter;
import com.spotifyplusplus.lyrics.language.ChineseCharacterForm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The display-text wiring, not the converter.
 *
 * <p>Two properties matter here and neither is about which characters the dictionaries map: the off
 * state must be inert and draw byte for byte what the source provided, and a conversion must leave
 * every timed span on the characters it started on, so the karaoke fill and the word highlight do
 * not drift. Whether the conversion itself is right is ChineseCharacterConverterTest's job.
 */
public class ChineseCharacterDisplayWiringTest {
    // migrated from the deleted SettingsUiSchema in the v2.1.4 merge
    /** Traditional, and phrase-sensitive: 這裡 and 頭髮 both go through the phrase table. */
    private static final String TRADITIONAL = "這裡的頭髮很亂";

    private static LyricsDocument document(String text) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Line";
        doc.language = "zh";
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = 0L;
        line.endMs = 4000L;
        int count = text.codePointCount(0, text.length());
        for (int i = 0; i < count; i++) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = text.substring(text.offsetByCodePoints(0, i),
                    text.offsetByCodePoints(0, i + 1));
            segment.canonicalStartCp = i;
            segment.canonicalEndCp = i + 1;
            segment.startMs = i * 400L;
            segment.endMs = segment.startMs + 400L;
            line.syllables.add(segment);
        }
        doc.lines.add(line);
        return doc;
    }

    private static AppliedLine firstVocalRow(LyricsDocument doc) {
        for (AppliedLine row : doc.appliedLines) {
            if (row != null && !row.dotLine && !row.bgLine) return row;
        }
        throw new AssertionError("no vocal row was planned");
    }

    private static List<String> rowTexts(LyricsDocument doc) {
        List<String> texts = new ArrayList<>();
        for (AppliedLine row : doc.appliedLines) texts.add(row == null ? null : row.text);
        return texts;
    }

    @Test
    public void theOffStateIsInertAndDrawsTheSourceExactly() {
        LyricsDocument doc = document(TRADITIONAL);
        doc.chineseCharacterForm = ChineseCharacterForm.ORIGINAL;
        LyricTimeline.applySyncedRows(doc);

        AppliedLine row = firstVocalRow(doc);
        assertEquals(TRADITIONAL, row.text);
        assertEquals(TRADITIONAL.length(), row.words.size());
        for (int i = 0; i < row.words.size(); i++) {
            SyllableSegment word = row.words.get(i);
            assertEquals(String.valueOf(TRADITIONAL.charAt(i)), word.text);
            assertEquals(i, word.canonicalStartCp);
            assertEquals(i + 1, word.canonicalEndCp);
            assertEquals(i * 400L, word.startMs);
            assertEquals(i * 400L + 400L, word.endMs);
        }

        // The document-level entry point hands back the same instance and reports no conversion, so
        // no path exists by which the off state could have changed a drawn character.
        ChineseCharacterConverter.ProjectedDocument projected =
                ChineseCharacterConverter.project(doc, ChineseCharacterForm.ORIGINAL);
        assertSame(doc, projected.document);
        assertFalse(projected.converted);
        assertTrue(projected.lines.isEmpty());

        // Asking for the state the document is already in replans nothing at all.
        List<String> before = rowTexts(doc);
        assertFalse(LyricTimeline.setChineseCharacterForm(doc, ChineseCharacterForm.ORIGINAL));
        assertEquals(before, rowTexts(doc));
    }

    @Test
    public void aConvertedRowKeepsEverySpanOnItsCharacters() {
        assertTrue("the Simplified dictionaries must be on the unit-test classpath",
                ChineseCharacterConverter.available());
        ChineseCharacterConverter.Projection projection =
                ChineseCharacterConverter.project(TRADITIONAL, ChineseCharacterForm.SIMPLIFIED);
        assertFalse("the fixture must be Traditional, or this test proves nothing",
                TRADITIONAL.equals(projection.text));

        LyricsDocument doc = document(TRADITIONAL);
        doc.chineseCharacterForm = ChineseCharacterForm.SIMPLIFIED;
        LyricTimeline.applySyncedRows(doc);
        AppliedLine row = firstVocalRow(doc);

        // The row draws the converted text, produced through the same entry point the wire calls.
        assertEquals(projection.text, row.text);
        assertEquals(projection.mapBoundary(0), 0);

        StringBuilder rebuilt = new StringBuilder();
        int previous = 0;
        for (int i = 0; i < row.words.size(); i++) {
            SyllableSegment word = row.words.get(i);
            // A span draws the display slice its original range owns, and nothing else: both
            // boundaries are remapped through the projection rather than kept as source offsets.
            assertEquals(projection.slice(i, i + 1), word.text);
            rebuilt.append(word.text);
            assertTrue("boundaries never move backwards", word.canonicalStartCp >= previous);
            assertTrue(word.canonicalEndCp >= word.canonicalStartCp);
            previous = word.canonicalEndCp;
            // A display conversion does not touch timing.
            assertEquals(i * 400L, word.startMs);
            assertEquals(i * 400L + 400L, word.endMs);
        }
        // In span order the slices rebuild the whole converted row, and the last boundary is the
        // display length: the renderer's fill, which indexes display code points, lands on the same
        // character the source timing named.
        assertEquals(projection.text, rebuilt.toString());
        assertEquals(projection.displayCodePointCount(), previous);
    }

    @Test
    public void theRowIsRegisteredWithTwoValuesAndDefaultsToNoConversion() {
        assertEquals("original", Settings.CHINESE_CHARACTER_FORM.defaultValue);
        assertEquals(Arrays.asList("original", "simplified"),
                Settings.CHINESE_CHARACTER_FORM.allowedValues);
        // SettingsUiSchema was deleted in the v2.1.4 merge; the panel's row order now lives in
        // ControlRegistry, and ControlSpec carries the setting it renders - so the exact-row
        // assertion is back at full strength.
        boolean registered = false;
        for (com.spotifyplusplus.settings.registry.ControlSpec spec
                : ControlRegistry.panelControls(Settings.TRANSLITERATION)) {
            if (spec.setting == Settings.CHINESE_CHARACTER_FORM) { registered = true; break; }
        }
        assertTrue("the row must be registered in the reading section's panel order", registered);
        // There is no Traditional direction to offer, so nothing may coerce towards one.
        assertEquals("original", Settings.CHINESE_CHARACTER_FORM.coerce("traditional"));
    }
}
