package com.spotifyplusplus.lyrics.language;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/**
 * The two tiers, and the cache that keeps them out of the ranking's inner loops.
 *
 * <p>No model pack is installed in this JVM, so the tests drive tier 1 through the seam. That is the
 * point of the seam: the tier that runs on the owner's device is the model, and the tier that runs
 * here is a counter.
 */
public class DocumentLanguageTest {
    @After
    public void restore() {
        DocumentLanguage.installTier1ForTests(null);
        DocumentLanguage.clearCache();
    }

    @Test
    public void withoutThePackTheVerdictFallsBackToScriptFamilies() {
        assertEquals("fam:latin", DocumentLanguage.family("Take me hand"));
        assertEquals("fam:zh", DocumentLanguage.family("离开地球表面说好的幸福呢"));
        assertEquals("fam:ja", DocumentLanguage.family("君の名前を呼んでいる"));
        assertEquals("fam:ko", DocumentLanguage.family("오늘도 音樂을 들어요"));
        assertEquals("fam:cyrl", DocumentLanguage.family("Привет как дела"));
        assertEquals("fam:grek", DocumentLanguage.family("Καλημέρα σου"));
        assertEquals("fam:arab", DocumentLanguage.family("مرحبا بك في العالم"));
        assertEquals("fam:hebr", DocumentLanguage.family("שלום לך עולם"));
        assertEquals("fam:deva", DocumentLanguage.family("नमस्ते दुनिया"));
        assertEquals("fam:thai", DocumentLanguage.family("สวัสดีชาวโลก"));
        assertEquals("nothing to classify", DocumentLanguage.UNKNOWN, DocumentLanguage.family(""));
        assertEquals("punctuation is not a language", DocumentLanguage.UNKNOWN,
                DocumentLanguage.family("!!! ..."));
    }

    /**
     * Han is Chinese only when it passes the same density gate the script component uses: a Chinese
     * document with a line of Japanese stays Chinese, and an English line with a few Han words is
     * not Chinese at all.
     */
    @Test
    public void theFamilyForHanFollowsTheSameGateAsTheScriptComponent() {
        assertEquals("fam:zh", DocumentLanguage.family("我们都一样在人群中寻找自己"));
        assertEquals("one kana among fourteen characters does not make it Japanese",
                "fam:zh", DocumentLanguage.family("我们都一样在人群中寻找自己さ"));
        assertEquals("mostly Latin with a few Han words is Latin",
                "fam:latin",
                DocumentLanguage.family(
                        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz 我的未来不是梦啊我们都要"));
    }

    /**
     * The performance pin at the unit level: the same document is one verdict however many times it
     * is asked for, and a new document is one more.
     */
    @Test
    public void oneVerdictPerDocumentHoweverManyTimesItIsAskedFor() {
        final AtomicInteger detections = new AtomicInteger();
        DocumentLanguage.installTier1ForTests(text -> {
            detections.incrementAndGet();
            return "ja";
        });
        for (int i = 0; i < 5; i++) {
            assertEquals("ja", DocumentLanguage.verdict("candidate-1|digest-1", "君の名前"));
        }
        assertEquals("five asks, one detection", 1, detections.get());
        assertEquals("ja", DocumentLanguage.verdict("candidate-2|digest-2", "君の名前"));
        assertEquals("a different document is one more", 2, detections.get());
        assertEquals("ja", DocumentLanguage.verdict("candidate-1|digest-1", "君の名前"));
        assertEquals("and the first one is still cached", 2, detections.get());
    }

    /**
     * The chain, and the bug it fixes. The owner's device has the pack installed and the detector
     * declines on most documents - its own abstention thresholds, not this module's - so gating tier 2
     * on the pack being absent made the component inert: five of six candidates logged an empty
     * verdict. A decline now falls through to the family, and only a decline from both tiers ends
     * empty.
     */
    @Test
    public void aDecliningTierOneFallsThroughToTheFamilyAndBothDecliningEndsEmpty() {
        assertEquals("with no pack there is no tier-1 verdict, so tier 2 answers",
                "fam:latin", DocumentLanguage.verdict("k1", "Take me hand"));
        assertEquals("tier1-absent+tier2", DocumentLanguage.verdictOf("k1", "Take me hand").source);

        DocumentLanguage.installTier1ForTests(text -> DocumentLanguage.UNKNOWN);
        assertEquals("tier 1 declining is not a reason to abandon the classification",
                "fam:zh", DocumentLanguage.verdict("k2", "离开地球表面说好的幸福呢"));
        assertEquals("tier1-declined+tier2", DocumentLanguage.verdictOf("k2", "离").source);
        assertEquals("and the decline is visible in the trace field", "tier1-declined+tier2",
                DocumentLanguage.verdictOf("k3", "离开地球表面说好的幸福呢").source);

        assertEquals("punctuation: neither tier has anything to say", DocumentLanguage.UNKNOWN,
                DocumentLanguage.verdict("k4", "!!! ..."));
        assertEquals("tier1-declined+none", DocumentLanguage.verdictOf("k4", "!!! ...").source);

        // A tier that answers wins outright, whatever the family of the text would have been.
        DocumentLanguage.installTier1ForTests(text -> "fr");
        DocumentLanguage.clearCache();
        assertEquals("fr", DocumentLanguage.verdict("k5", "Take me hand"));
        assertEquals("tier1", DocumentLanguage.verdictOf("k5", "Take me hand").source);
    }

    /** A detector that throws costs tier 1 only; the family still answers, and nothing else breaks. */
    @Test
    public void aThrowingDetectorStillLeavesTheFamilyTier() {
        DocumentLanguage.installTier1ForTests(text -> {
            throw new IllegalStateException("pack removed mid-session");
        });
        assertEquals("fam:zh", DocumentLanguage.verdict("k6", "离开地球表面说好的幸福呢"));
        assertEquals("tier1-error+tier2", DocumentLanguage.verdictOf("k6", "离开").source);
        assertTrue("and a document with nothing to classify still ends empty",
                DocumentLanguage.verdict("k7", "!!! ...").isEmpty());
        assertEquals("tier1-error+none", DocumentLanguage.verdictOf("k7", "!!! ...").source);
    }
}
