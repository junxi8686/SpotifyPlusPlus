package com.spotifyplusplus.lyrics.language;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

import org.junit.Test;

/**
 * The display converter: phrase-aware conversion, the projection that keeps timings aligned, and the
 * guarantee that the off state changes nothing.
 */
public class ChineseCharacterConverterTest {
    /** Phrase-sensitive pair: the same first character converts differently by the word it is in. */
    private static final String TRADITIONAL_HAIR = "\u982d\u9aee";   // 頭髮
    private static final String TRADITIONAL_DEPART = "\u51fa\u767c"; // 出發
    private static final String SIMPLIFIED_HAIR = "\u5934\u53d1";    // 头发
    private static final String SIMPLIFIED_DEPART = "\u51fa\u53d1";  // 出发
    /** A Traditional line with no phrase entry, so it converts character by character. */
    private static final String TRADITIONAL_LINE = "\u96e2\u958b\u5730\u7403\u8868\u9762"; // 離開地球表面
    private static final String SIMPLIFIED_LINE = "\u79bb\u5f00\u5730\u7403\u8868\u9762"; // 离开地球表面
    /** U+F900 is the compatibility form of U+8C48; the normalisation step must fold it. */
    private static final String COMPATIBILITY = "\uf900";

    @Test
    public void theDictionariesAreBundledAndLoaded() {
        assertTrue("the three Simplified-direction dictionaries must ship with the module",
                ChineseCharacterConverter.available());
        assertEquals(SIMPLIFIED_LINE,
                ChineseCharacterConverter.convert(TRADITIONAL_LINE, ChineseCharacterForm.SIMPLIFIED));
    }

    /**
     * The whole reason this is a trie and not a character table: one character, two words, two
     * different conversions. Our existing character-level {@code ChineseScriptVariants} cannot do
     * this, which is why display conversion is a separate mechanism.
     */
    @Test
    public void phraseAwareConversionBeatsACharacterTable() {
        assertEquals(SIMPLIFIED_HAIR, ChineseCharacterConverter.convert(TRADITIONAL_HAIR,
                ChineseCharacterForm.SIMPLIFIED));
        assertEquals(SIMPLIFIED_DEPART, ChineseCharacterConverter.convert(TRADITIONAL_DEPART,
                ChineseCharacterForm.SIMPLIFIED));
        assertEquals("and the two differ exactly where the phrase dictionary says they do",
                SIMPLIFIED_HAIR.substring(1), SIMPLIFIED_DEPART.substring(1));
    }

    /**
     * A compatibility ideograph is folded to its unified form before the conversion step, so it
     * converts exactly as that unified character does. Asserted that way round on purpose: the value
     * the normalisation produces is data, and pinning the pair (U+F900 to whichever Simplified form
     * its unified character has) would test the dictionary rather than this step.
     */
    @Test
    public void compatibilityIdeographsAreNormalisedBeforeConversion() {
        String unified = "\u8c48";
        assertEquals("the compatibility form converts as its unified form does",
                ChineseCharacterConverter.convert(unified, ChineseCharacterForm.SIMPLIFIED),
                ChineseCharacterConverter.convert(COMPATIBILITY, ChineseCharacterForm.SIMPLIFIED));
        assertEquals("one source character stays one display character",
                1, ChineseCharacterConverter.project(COMPATIBILITY, ChineseCharacterForm.SIMPLIFIED)
                        .displayCodePointCount());
        assertFalse("and it is no longer the compatibility form",
                COMPATIBILITY.equals(ChineseCharacterConverter.convert(COMPATIBILITY,
                        ChineseCharacterForm.SIMPLIFIED)));
    }

    /**
     * The projection is what a per-character renderer needs: every source boundary maps to a display
     * boundary, and slicing by source range returns exactly the display text for that range. This is
     * asserted by walking a converted line character by character and rebuilding it from the slices.
     */
    @Test
    public void theProjectionMapsEverySourceCharacterToItsDisplayText() {
        String source = TRADITIONAL_HAIR + TRADITIONAL_DEPART + TRADITIONAL_LINE;
        ChineseCharacterConverter.Projection projection =
                ChineseCharacterConverter.project(source, ChineseCharacterForm.SIMPLIFIED);

        assertEquals(source.codePointCount(0, source.length()), projection.sourceCodePointCount());
        assertEquals(0, projection.mapBoundary(0));
        assertEquals(projection.displayCodePointCount(),
                projection.mapBoundary(projection.sourceCodePointCount()));
        StringBuilder rebuilt = new StringBuilder();
        int previous = 0;
        for (int cp = 0; cp < projection.sourceCodePointCount(); cp++) {
            assertTrue("boundaries never move backwards at " + cp,
                    projection.mapBoundary(cp) >= previous);
            previous = projection.mapBoundary(cp);
            rebuilt.append(projection.slice(cp, cp + 1));
        }
        assertEquals("the whole display text is the concatenation of its per-character slices",
                projection.text, rebuilt.toString());
        assertEquals(SIMPLIFIED_HAIR + SIMPLIFIED_DEPART + SIMPLIFIED_LINE, projection.text);
        assertEquals("and one slice covers the whole line", projection.text,
                projection.slice(0, projection.sourceCodePointCount()));
    }

    /** The off state returns the same instance and loads nothing. */
    @Test
    public void theOffStateIsAnIdentityProjectionAndTheSameDocument() {
        ChineseCharacterConverter.Projection identity =
                ChineseCharacterConverter.project(TRADITIONAL_LINE, ChineseCharacterForm.ORIGINAL);
        assertSame(TRADITIONAL_LINE, identity.text);
        assertEquals(TRADITIONAL_LINE, ChineseCharacterConverter.convert(TRADITIONAL_LINE, null));
        assertEquals(TRADITIONAL_LINE, ChineseCharacterConverter.convert(TRADITIONAL_LINE,
                ChineseCharacterForm.ORIGINAL));

        LyricsDocument document = document(TRADITIONAL_LINE, TRADITIONAL_HAIR);
        ChineseCharacterConverter.ProjectedDocument projected =
                ChineseCharacterConverter.project(document, ChineseCharacterForm.ORIGINAL);
        assertSame("nothing was copied, so the renderer draws exactly what it draws today",
                document, projected.document);
        assertFalse(projected.converted);
        assertTrue(projected.lines.isEmpty());
        assertEquals(TRADITIONAL_LINE, projected.document.lines.get(0).text);
    }

    /** On: a copy is converted, the input is untouched, and each line can be sliced for timing. */
    @Test
    public void conversionCopiesTheDocumentAndKeepsPerLineProjections() {
        LyricsDocument document = document(TRADITIONAL_LINE, TRADITIONAL_HAIR);
        ChineseCharacterConverter.ProjectedDocument projected =
                ChineseCharacterConverter.project(document, ChineseCharacterForm.SIMPLIFIED);

        assertTrue(projected.converted);
        assertTrue("the stored document is never modified", document != projected.document);
        assertEquals("the source document still holds its own text",
                TRADITIONAL_LINE, document.lines.get(0).text);
        assertEquals(SIMPLIFIED_LINE, projected.document.lines.get(0).text);
        assertEquals(SIMPLIFIED_HAIR, projected.document.lines.get(1).text);

        ChineseCharacterConverter.Projection first = projected.projectionFor(0);
        assertNotNull(first);
        assertEquals(SIMPLIFIED_LINE, first.text);
        assertEquals("a timing that covered the first character slices to its converted character",
                SIMPLIFIED_LINE.substring(0, 1), first.slice(0, 1));
        assertEquals("and a timing that covered the last two covers their converted text",
                SIMPLIFIED_LINE.substring(4), first.slice(4, 6));
        assertNull(projected.projectionFor(99));
    }

    @Test
    public void anAlreadySimplifiedDocumentConvertsToItself() {
        ChineseCharacterConverter.ProjectedDocument projected = ChineseCharacterConverter.project(
                document(SIMPLIFIED_LINE), ChineseCharacterForm.SIMPLIFIED);
        assertEquals(SIMPLIFIED_LINE, projected.document.lines.get(0).text);
        assertFalse("nothing needed converting, so nothing is reported as converted",
                projected.converted);
    }

    @Test
    public void nullsAndEmptyTextAreSafe() {
        assertTrue(ChineseCharacterConverter.convert(null, ChineseCharacterForm.SIMPLIFIED).isEmpty());
        assertTrue(ChineseCharacterConverter.convert("", ChineseCharacterForm.SIMPLIFIED).isEmpty());
        assertTrue(ChineseCharacterConverter.project((LyricsDocument) null,
                ChineseCharacterForm.SIMPLIFIED).document == null);
        ChineseCharacterConverter.ProjectedDocument none =
                ChineseCharacterConverter.project(document("Take me hand"),
                        ChineseCharacterForm.SIMPLIFIED);
        assertEquals("text in no Chinese script is unchanged", "Take me hand",
                none.document.lines.get(0).text);
    }

    private static LyricsDocument document(String... texts) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "track1";
        document.type = "Line";
        long start = 1000L;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = start;
            line.endMs = start + 900L;
            start += 1000L;
            document.lines.add(line);
        }
        return document;
    }
}
