package com.spotifyplusplus.lyrics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The credit line no longer has a row on the now-playing surfaces, but it still travels with every
 * published form of the document - the share card, the HyperGlow bridge payload and the Android
 * Auto bundle all build their text from these credits - so its wording and link offsets stay a
 * contract.
 */
public class SpicyOrgAttributionTest {
    @Test
    public void restrictedResponseCreditsItsOriginAndContributors() {
        LyricsDocument document = restricted("spicy_lyrics");
        document.spicyOrgUploader = "spikerko";
        document.spicyOrgUploaderUrl = "https://spicylyrics.org/user/spikerko";
        document.spicyOrgMaker = "gc";
        document.spicyOrgMakerUrl = "https://spicylyrics.org/user/gc";

        List<SpicyOrgAttribution.Credit> credits = SpicyOrgAttribution.credits(document);

        assertEquals(3, credits.size());
        assertEquals("Lyrics from Spicy Lyrics", credits.get(0).label);
        assertEquals("uploaded by spikerko", credits.get(1).label);
        assertEquals("uploaded by ".length(), credits.get(1).nameStart);
        assertEquals("https://spicylyrics.org/user/spikerko", credits.get(1).url);
        assertEquals("made by gc", credits.get(2).label);
        assertEquals("made by ".length(), credits.get(2).nameStart);
    }

    /** The origin is what the payload declares, even when that names the withdrawn Apple route. */
    @Test
    public void restrictedResponseNamesTheOriginThePayloadDeclares() {
        LyricsDocument document = restricted("apple_music");
        document.provider = "Apple Music";

        assertEquals("Lyrics from Apple Music", SpicyOrgAttribution.credits(document).get(0).label);
    }

    /** A contributor-uploaded response with no uploader credit contributes nothing to name. */
    @Test
    public void contributorCreditNeedsAName() {
        LyricsDocument document = restricted("spicy_lyrics");
        document.spicyOrgUploaderUrl = "https://spicylyrics.org/user/ghost";

        assertEquals(1, SpicyOrgAttribution.credits(document).size());
    }

    @Test
    public void ordinaryDocumentHasNoResponseCredit() {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = "lrclib";
        document.provider = "LRCLIB";

        assertTrue(SpicyOrgAttribution.credits(document).isEmpty());
    }

    private static LyricsDocument restricted(String origin) {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = "spicy_org";
        document.spicyOrgSource = origin;
        document.provider = "spicy_lyrics".equals(origin) ? "Spicy Lyrics" : "Unknown";
        return document;
    }
}
