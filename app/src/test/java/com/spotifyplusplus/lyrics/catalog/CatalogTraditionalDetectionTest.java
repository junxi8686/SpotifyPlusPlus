package com.spotifyplusplus.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.MatchMethod;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.TimingLevel;
import com.spotifyplusplus.lyrics.providers.LyricsParser;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The Traditional share the automatic ranking reads is measured on the document the reader gets.
 *
 * <p>This exists because a decision trace showed {@code trad=0%} beside every source in a round
 * whose lyrics were plainly Traditional, and the question was whether the measurement or the
 * documents were at fault. A measurement that never happened reads exactly like a document with no
 * script to prefer, so the only way to tell the two apart is to run the real path: the
 * SpicyLyrics.org parser that builds the document, the catalog adapter that encodes the candidate,
 * and the codec the resolver decodes before measuring. A regression that makes a Traditional
 * document measure zero fails here instead of being re-diagnosed from a device log.
 */
public class CatalogTraditionalDetectionTest {

    private static final String TRACK_ID = "4CP3p2wc4F1XIWJ6C3w2KH";
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "離開地球表面", "五月天", "離開地球表面", "spotify:track:" + TRACK_ID,
            0, "", 0, null, 259000, false);

    private static int nextId;

    /**
     * The real SpicyLyrics.org route: the public envelope, the Apple-Music-shaped body, and a
     * Syllable document - the word-by-word shape the owner was looking at when he reported the
     * measure.
     */
    private static String spicyOrgPayload(String type, String... lines) {
        JsonArray content = new JsonArray();
        double start = 1.0;
        for (String text : lines) {
            JsonArray syllables = new JsonArray();
            int half = text.length() / 2;
            syllables.add(syllable(text.substring(0, half), true, start, start + 0.5));
            syllables.add(syllable(text.substring(half), false, start + 0.5, start + 1.0));
            JsonObject lead = new JsonObject();
            lead.addProperty("Text", text);
            lead.addProperty("StartTime", start);
            lead.addProperty("EndTime", start + 1.0);
            lead.add("Syllables", syllables);
            JsonObject item = new JsonObject();
            item.addProperty("Type", "Vocal");
            item.add("Lead", lead);
            content.add(item);
            start += 1.0;
        }
        JsonObject lyrics = new JsonObject();
        lyrics.addProperty("Type", type);
        lyrics.add("Content", content);

        JsonObject body = lyrics;
        body.addProperty("id", TRACK_ID);
        body.addProperty("source", "apple_music");
        JsonObject envelope = new JsonObject();
        envelope.addProperty("Status", 200);
        envelope.add("Body", body);
        return envelope.toString();
    }

    private static JsonObject syllable(String text, boolean partOfWord, double start, double end) {
        JsonObject object = new JsonObject();
        object.addProperty("Text", text);
        object.addProperty("IsPartOfWord", partOfWord);
        object.addProperty("StartTime", start);
        object.addProperty("EndTime", end);
        return object;
    }

    /** The candidate the store would hold for this document, built by the adapter that stores it. */
    private static CatalogCandidate storedSpicyOrgCandidate(String... lines) {
        String raw = spicyOrgPayload("Syllable", lines);
        LyricsDocument document = new LyricsParser(null).parseSpicyOrgLyrics(null, TRACK, raw);
        assertEquals("Syllable", document.type);
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(SourceId.SPICY_ORG, TRACK,
                document, MatchMethod.EXACT_SPOTIFY_ID, TRACK_ID, raw, 1,
                System.currentTimeMillis());
        assertNotNull("the adapter must accept a well-formed Syllable document", candidate);
        return candidate;
    }

    @Test
    public void traditionalSpicyOrgDocumentIsMeasuredAsTraditional() {
        CatalogCandidate traditional = storedSpicyOrgCandidate(
                "離開地球表面", "為什麼這個世界", "說好的幸福呢", "後來我們都哭了");
        double share = CatalogResolver.lyricsTraditionalShare(traditional);
        // Not 1.0: the share counts only Han characters that differ from their Simplified form,
        // and 地球表面 / 世界 / 我們 are written the same in both scripts.
        assertTrue("a Traditional document must not measure zero, measured " + share,
                share > 0.2d);

        CatalogCandidate simplified = storedSpicyOrgCandidate(
                "离开地球表面", "为什么这个世界", "说好的幸福呢", "后来我们都哭了");
        assertEquals("the Simplified edition of the same lines has no Traditional share",
                0.0d, CatalogResolver.lyricsTraditionalShare(simplified), 0.0001d);
    }

    @Test
    public void aLatinDocumentReadsAsNoPreferenceNotAsTraditional() {
        // The other half of the measurement: 0 means "nothing to prefer" for an English document,
        // which must not be pushed below a Chinese one as though it were fully Traditional.
        assertEquals(0.0d, CatalogResolver.lyricsTraditionalShare(
                storedSpicyOrgCandidate("Take me hand", "Darling won't you break my heart")), 0.0001d);
    }

    @Test
    public void theSimplifiedEditionWinsOnTheScriptGrade() {
        // Two candidates that agree on every other component - timing, completeness, translation
        // coverage, duration, match - and differ only in script. The script grade is then the only
        // thing between them, and it is paid ahead of the provider tie-break, which prefers
        // SpicyLyrics.org.
        CatalogCandidate traditional = simpleCandidate(SourceId.SPICY_ORG, "離開地球表面", "說好的幸福呢");
        CatalogCandidate simplified = simpleCandidate(SourceId.NETEASE, "离开地球表面", "说好的幸福呢");
        assertEquals("nothing else separates the pair",
                CatalogResolver.score(traditional) + CatalogResolver.POINTS_SIMPLIFIED_MAX,
                CatalogResolver.score(simplified), 0.0001d);
        assertEquals("the Simplified edition must be elected",
                simplified.candidateId, CatalogResolver.resolve(
                        Arrays.asList(traditional, simplified), null,
                        Collections.<SourceId>emptyList()).winner.candidateId);
        assertEquals("and symmetrically", simplified.candidateId, CatalogResolver.resolve(
                Arrays.asList(simplified, traditional), null,
                Collections.<SourceId>emptyList()).winner.candidateId);
        assertTrue("though the provider tie-break still prefers SpicyLyrics.org",
                CatalogResolver.sourceRank(SourceId.SPICY_ORG,
                        CatalogResolver.effectivePriority(null))
                        < CatalogResolver.sourceRank(SourceId.NETEASE,
                        CatalogResolver.effectivePriority(null)));
    }

    @Test
    public void aScriptNeutralPrefixIsRecordedAsZero() {
        // A recorded boundary of the measurement, not a preference: the sample is the first few
        // hundred characters of the document's own line text, so a document whose opening is
        // script-neutral measures zero even when its later lines are Traditional. Pinned here so
        // that the difference between "measured zero" and "never measured" stays visible.
        StringBuilder opening = new StringBuilder();
        while (opening.length() < 400) opening.append("la la la ");
        CatalogCandidate lateTraditional = simpleCandidate(SourceId.SPICY_ORG,
                opening.toString(), "離開地球表面");
        assertEquals(0.0d, CatalogResolver.lyricsTraditionalShare(lateTraditional), 0.0001d);
    }

    /** A candidate encoded exactly as the store encodes it, for the tiers this test holds equal. */
    private static CatalogCandidate simpleCandidate(SourceId source, String... lines) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK_ID;
        for (String text : lines) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = 1000L * document.lines.size();
            line.endMs = line.startMs + 1000L;
            document.lines.add(line);
        }
        String id = source.id + "|" + TRACK_ID + "|d" + (nextId++) + "#" + nextId;
        return new CatalogCandidate(id, TRACK_ID, source, TRACK_ID, MatchMethod.STRONG_SEARCH, 0.8,
                0L, TimingLevel.WORD, true, true, false, false, false, false, false,
                "digest" + nextId, CanonicalSourceCodec.encode(document, 1, "digest", 0L), "[]",
                new byte[0], 1, 1, 0L);
    }
}
