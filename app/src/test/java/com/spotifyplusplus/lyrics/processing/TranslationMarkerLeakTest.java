package com.spotifyplusplus.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire markers are protocol and must never reach a reader.
 *
 * <p>The owner's case: a batch whose last rows were not translated came back carrying the markers
 * they were sent behind, so the screen showed {@code [[SPX_008]]On and On and On} instead of the
 * line's own text. Wherever a marker leaked, that row had no translation - one defect, because the
 * marker is exactly what was left when no translation replaced it.
 *
 * <p>These pin the two halves of the fix: the shared definition of what a marker looks like (used
 * by the stripper and by the read-path rejection, so they cannot drift), and the batch outcome for
 * a response in which some rows translate and some come back untouched.
 *
 * <p>The per-line cache itself cannot be driven here - {@code SpicyCacheStore} is SQLite-backed and
 * answers nothing off-device - so what is asserted below is the value each row is given, which is
 * the same value that would be written to the cache; the purge on a rejected read is observed on
 * device.
 */
public class TranslationMarkerLeakTest {
    private static final String TRACK = "life-goes-on";
    private static final BaiduTranslateBackend.Credentials CREDENTIALS =
            new BaiduTranslateBackend.Credentials("2015063000000001", "12345678");

    private FakeAndroidContext context;

    @Before public void setUp() {
        context = new FakeAndroidContext();
        BaiduTranslateBackend.clearLimitState();
        BaiduTranslateBackend.clock = new BaiduTranslateBackendTest.FakeClock();
    }

    @After public void tearDown() {
        BaiduTranslateBackend.clearLimitState();
        BaiduTranslateBackend.clock = android.os.SystemClock::elapsedRealtime;
    }

    // --- The one definition of "what a marker looks like" ---

    /**
     * Every shape a translator may hand back, including the one that looks identical on screen: a
     * non-breaking space renders like an ordinary one but is not matched by {@code \s}.
     */
    @Test public void everyMarkerShapeIsRecognizedAndStripped() {
        String[] markers = {
                "[[SPX_008]]",                  // as sent
                "[[SPX 008]]",                  // stray space instead of the separator
                "[[ SPX_008 ]]",                // spaces inside the brackets
                "[SPX_008]",                    // single brackets
                "[[SPX_8]]",                    // unpadded number
                "[[SPX_0008]]",                 // over-padded number
                "[[SPX-008]]",                  // hyphen separator
                "[[spx_008]]",                  // lower case
                "[[SPX008]]",                   // no separator
                "[[S PX_008]]",                 // space inside the token
                "\u3010SPX_008\u3011",          // CJK bracket family
                "[[SPX\u00A0008]]",             // non-breaking space
                "[[SPX\u3000008]]",             // full-width space
                "[[\uFF33\uFF30\uFF38_\uFF10\uFF10\uFF18]]", // full-width everything
                "SPX_008]]",                    // opening brackets lost
                "[[SPX_\u96F6\u96F6\u516B]]"    // digits translated away: still wire text
        };
        for (String marker : markers) {
            String text = "你好 " + marker + "On and On and On";
            assertTrue("not recognized: " + marker, GoogleEnhancer.hasMarkerResidue(text));
            String stripped = GoogleEnhancer.stripMarkerEcho(text);
            assertFalse("survived the strip: " + marker, GoogleEnhancer.hasMarkerResidue(stripped));
            assertFalse("SPX survived: " + marker, stripped.contains("SPX"));
            assertEquals("left the line's own text: " + marker, "你好 On and On and On", stripped);
            assertNull("a value carrying wire text is not a translation: " + marker,
                    GoogleEnhancer.sanitizeTranslation(text));
        }
    }

    @Test public void cleanTextIsLeftAlone() {
        assertEquals("你是我的交响乐", GoogleEnhancer.sanitizeTranslation("你是我的交响乐"));
        assertFalse(GoogleEnhancer.hasMarkerResidue("你是我的交响乐"));
        // The bare token is content, not wire text: without brackets it is a lyric, not a marker.
        assertEquals("SPX 500 期权", GoogleEnhancer.sanitizeTranslation("SPX 500 期权"));
    }

    // --- The screenshot's batch ---

    /**
     * The owner's case, with the markers the service mangled: eight rows translate, the repeated
     * hook rows come back as their own text behind a marker, and the outcome must be a translation
     * for the eight and the row's own plain text for the two - never a marker.
     */
    @Test public void rowsWithoutATranslationShowTheirOwnTextAndNoMarker() {
        List<GoogleEnhancer.BatchLine> lines = screenshotBatch();
        ScriptedBaidu http = new ScriptedBaidu(screenshotAnswer(true));

        GoogleEnhancer.BatchResult result = translate(http, lines);

        assertNoMarkersAnywhere(result);
        for (int row = 0; row < 8; row++) {
            assertEquals("row " + row + " keeps its own translation", "译" + row,
                    result.translations.get(row));
        }
        assertNull("an untranslated row has no translation to show",
                result.translations.get(8));
        assertNull("an untranslated row has no translation to show",
                result.translations.get(9));
        assertEquals("the request carried one marker per row it asked about",
                10, countIn(http.lastQuery(), "[[SPX_"));
    }

    /** The same batch when the service answered without the markers at all. */
    @Test public void rowsAnsweredWithTheirOwnTextShowNoMarker() {
        List<GoogleEnhancer.BatchLine> lines = screenshotBatch();
        ScriptedBaidu http = new ScriptedBaidu(screenshotAnswer(false));

        GoogleEnhancer.BatchResult result = translate(http, lines);

        assertNoMarkersAnywhere(result);
        assertEquals(8, result.translations.size());
        assertNull(result.translations.get(8));
        assertNull(result.translations.get(9));
    }

    /**
     * The service merged the untranslated tail into the row before it - the shape that put three
     * lines of text under one lyric line. The merged tail's markers identify their own rows, so the
     * translated row keeps its translation and the two hook rows stay untranslated with no marker.
     */
    @Test public void aMergedAnswerStillGivesEveryRowItsOwnText() {
        List<GoogleEnhancer.BatchLine> lines = screenshotBatch();
        ScriptedBaidu http = new ScriptedBaidu(mergedAnswer());

        GoogleEnhancer.BatchResult result = translate(http, lines);

        assertNoMarkersAnywhere(result);
        assertEquals("the translated row keeps its translation", "译7", result.translations.get(7));
        assertNull("a hook row is not given the blob", result.translations.get(8));
        assertNull("a hook row is not given the blob", result.translations.get(9));
    }

    /** Whatever the row ends up with, the display filter can never accept wire text. */
    @Test public void wireTextIsNotDisplayable() {
        String source = "On and On and On";

        assertNull("a marker-prefixed value is refused before any filter sees it",
                GoogleEnhancer.sanitizeTranslation("[[SPX_008]]" + source));
        // The stripped echo is rejected on its own merits: it says nothing the source does not.
        assertFalse(GoogleEnhancer.shouldDisplayTranslation(source, source));
        // And once refused, the row is left with its own text, which is what the screen shows.
        assertFalse(GoogleEnhancer.hasMarkerResidue(source));
    }

    // --- Fixtures ---

    /** Ten rows: rows 8 and 9 are the repeated hook the owner saw, in two spellings. */
    private static List<GoogleEnhancer.BatchLine> screenshotBatch() {
        List<GoogleEnhancer.BatchLine> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) lines.add(new GoogleEnhancer.BatchLine(i, "Verse line " + i));
        lines.add(new GoogleEnhancer.BatchLine(8, "On and On and On"));
        lines.add(new GoogleEnhancer.BatchLine(9, "On and On On On"));
        return lines;
    }

    /**
     * Eight real translations, then the hook rows as the service left them. {@code mangled} marks
     * them with a non-breaking space, which looks identical on screen and is what the old pattern
     * could not see.
     */
    private static String screenshotAnswer(boolean mangled) {
        StringBuilder body = new StringBuilder("{\"trans_result\":[");
        for (int i = 0; i < 8; i++) {
            body.append(i > 0 ? "," : "")
                    .append("{\"src\":\"s\",\"dst\":\"[[SPX_")
                    .append(pad(i)).append("]] 译").append(i).append("\"}");
        }
        body.append(",{\"dst\":\"[[SPX_").append(mangled ? "\u00A0" : "").append(pad(8))
                .append("]]On and On and On\"}");
        body.append(",{\"dst\":\"[[SPX_").append(mangled ? "\u00A0" : "").append(pad(9))
                .append("]]On and On On On\"}");
        return body.append("]}").toString();
    }

    /** Nine destinations: the service put the two hook rows inside row 7's answer. */
    private static String mergedAnswer() {
        StringBuilder body = new StringBuilder("{\"trans_result\":[");
        for (int i = 0; i < 7; i++) {
            body.append(i > 0 ? "," : "")
                    .append("{\"dst\":\"[[SPX_").append(pad(i)).append("]] 译").append(i).append("\"}");
        }
        body.append(",{\"dst\":\"[[SPX_").append(pad(7)).append("]] 译7\\n[[SPX_")
                .append(pad(8)).append("]]On and On and On\\n[[SPX_")
                .append(pad(9)).append("]]On and On On On\"}");
        return body.append("]}").toString();
    }

    private static String pad(int index) {
        return String.format(java.util.Locale.US, "%03d", index);
    }

    private static int countIn(String text, String needle) {
        int count = 0;
        int at = text == null ? -1 : text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** Nothing a reader could be shown may carry wire text, on any row. */
    private static void assertNoMarkersAnywhere(GoogleEnhancer.BatchResult result) {
        for (Map.Entry<Integer, String> entry : result.translations.entrySet()) {
            String value = entry.getValue();
            assertFalse("row " + entry.getKey() + " carries wire text: " + value,
                    GoogleEnhancer.hasMarkerResidue(value));
            assertFalse("row " + entry.getKey() + " carries SPX: " + value, value.contains("SPX"));
        }
    }

    private GoogleEnhancer.BatchResult translate(ScriptedBaidu http,
                                                List<GoogleEnhancer.BatchLine> lines) {
        return BaiduTranslateBackend.translateBatch(context, http.client(), 1, TRACK, "en", "zh",
                lines, "MEANING#1", CREDENTIALS);
    }

    /** A scripted Baidu endpoint that records the query it was sent. */
    private static final class ScriptedBaidu {
        private final BaiduTranslateBackendTest.ScriptedHttp http;

        ScriptedBaidu(String body) {
            this.http = new BaiduTranslateBackendTest.ScriptedHttp(body);
        }

        okhttp3.OkHttpClient client() {
            return http.client();
        }

        /** The last query sent, decoded from the form body. */
        String lastQuery() {
            Map<String, String> form = new LinkedHashMap<>();
            if (http.requests.isEmpty()) return "";
            try {
                okio.Buffer buffer = new okio.Buffer();
                http.requests.get(http.requests.size() - 1).body().writeTo(buffer);
                for (String pair : buffer.readUtf8().split("&")) {
                    int equals = pair.indexOf('=');
                    form.put(java.net.URLDecoder.decode(pair.substring(0, equals), "UTF-8"),
                            java.net.URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
                }
            } catch (Throwable unreadable) {
                return "";
            }
            return form.get("q") == null ? "" : form.get("q");
        }
    }
}
