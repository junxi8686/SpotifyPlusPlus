package com.spotifyplusplus.lyrics.language;

import com.spotifyplusplus.lyrics.session.DetectionResult;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The language verdict for one stored lyrics document, computed once and reused.
 *
 * <p>Two tiers, because the owner may or may not have the language-model pack installed.
 *
 * <p><b>Tier 1 - the pack is present.</b> {@link LanguageModelPack#isReady()} is the test, and the
 * verdict comes from the existing detector through {@link LanguageDetectorManager}: the ISO-639-1
 * language, its confidence, and the script it was routed by. This is the tier that earns its keep,
 * because the model separates languages that share a script - English from French from German from
 * Vietnamese - which no amount of character inspection can do. Script-certain languages (Japanese,
 * Korean, Greek) come back from the detector with full confidence without touching the model.
 *
 * <p><b>Tier 2 - tier 1 declined.</b> The verdict degrades to the script family, prefixed
 * {@code fam:}: kana above the Japanese ratio is {@code fam:ja}, Hangul anywhere is {@code fam:ko},
 * Han that passes the Chinese gate is {@code fam:zh}, and otherwise the family is the script most of
 * the text's letters are written in - {@code fam:latin}, {@code fam:cyrl}, {@code fam:grek},
 * {@code fam:arab}, {@code fam:hebr}, {@code fam:deva} and the other Indic scripts, {@code fam:thai}
 * and the other South-East Asian scripts, {@code fam:ethiopic}, {@code fam:georgian},
 * {@code fam:armenian}, or the script's own name for anything else, so two different unmapped
 * scripts never match each other. <b>Latin is deliberately one family</b>, covering roughly a
 * hundred and fifty languages: English and French are not separable without the model, and a family
 * that admits it is more honest than a guess dressed up as a language.
 *
 * <p><b>The tiers are a chain, not alternatives.</b> Tier 2 runs whenever tier 1 yields no language,
 * including when the pack is installed. The first version keyed tier 2 on the pack being absent, so
 * on the owner's device - pack installed - tier 1 was the only tier ever tried, and the detector
 * declines by design: {@code CharSoupHandle.analyze} abstains below 0.80 confidence, below 0.95 for
 * Han text, and when the best label leads the runner-up by under 0.20, returning {@code SCRIPT_ONLY}
 * with no language at all. Five of six candidates then logged an empty {@code fam=} and the component
 * paid nobody - which is the bug this chain fixes. Declining is still correct, because the model must
 * not guess, but a decline has to fall through to the coarser answer instead of abandoning the
 * classification: {@code fam:latin} against a {@code fam:zh} track is exactly the mismatch the owner
 * was chasing. Tier 1 stays strictly better when it answers, because it separates languages the
 * family cannot.
 *
 * <p><b>Every tier fails closed, and the chain ends closed.</b> Too little text, no verdict from
 * either tier, a throwing detector: the verdict is empty, which the ranking scores as zero points and
 * otherwise ignores. Nothing here guesses a language to force a match, and nothing here can promote a
 * document: the component this feeds can only withhold its own three points.
 *
 * <p><b>Computed once per document.</b> The verdict is cached on the document's identity, because
 * the ranking runs about nineteen times a second while nothing about the candidate set changes. A
 * character n-gram model run over six candidates on every pass is exactly the cost this cache exists
 * to prevent, and the {@code detect} call is never made from a comparator or any other
 * per-comparison path: an uncached verdict scores zero for that pass and the progressive re-ranking
 * picks it up on a later one.
 */
public final class DocumentLanguage {
    /**
     * How sure tier 1 has to be. Deliberately conservative: a wrong verdict cannot promote a
     * document, but it can still withhold the three points from a correct one, and the owner's rule
     * is that withholding is better than guessing.
     *
     * <p>Not the reason the owner's device saw empty verdicts: the detector abstains on its own well
     * before this - {@code CharSoupHandle.analyze} returns null below 0.80 confidence, below 0.95 for
     * Han, and under a 0.20 lead over the runner-up, and that null arrives here as {@code SCRIPT_ONLY}
     * with no language. This floor only guards against a detector that reports a language weakly.
     */
    public static final double MIN_CONFIDENCE = 0.5d;

    /** The empty verdict: nothing was determined, so nothing matches. */
    public static final String UNKNOWN = "";

    /** Verdicts are a few bytes each; past this the map is dropped rather than trimmed per entry. */
    private static final int MAX_ENTRIES = 512;

    /**
     * What was decided, and which tier decided it. The source is the trace's {@code langsrc=} token:
     * the owner reads these logs, and a silently empty verdict is what made the chain bug take a round
     * trip to find.
     */
    public static final class Verdict {
        /** An ISO-639-1 code, {@code fam:<script>}, or {@link #UNKNOWN}. */
        public final String token;
        /** {@code tier1}, {@code tier1-declined+tier2}, {@code tier1-absent+tier2}, {@code none}... */
        public final String source;

        Verdict(String token, String source) {
            this.token = token;
            this.source = source;
        }
    }

    private static final Map<String, Verdict> CACHE = new ConcurrentHashMap<String, Verdict>();

    /** Test seam: replaces tier 1 entirely; see {@link #installTier1ForTests}. */
    public interface Tier1 {
        /** A language code, {@code ""} for an honest failure, or null when tier 2 should decide. */
        String detect(String text);
    }

    private static final Tier1 PACK = DocumentLanguage::detectWithPack;

    private static volatile Tier1 tier1 = PACK;
    private static final java.util.concurrent.atomic.AtomicInteger DETECTIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    private DocumentLanguage() {
    }

    /**
     * The verdict for one document: an ISO-639-1 language code, {@code fam:<script>} when only the
     * script could be settled, or {@link #UNKNOWN}.
     *
     * @param documentKey identifies the stored document (its candidate id and digest), so the same
     *                    document is never inspected twice
     * @param text        the text to classify; the caller passes the script sample, which is bounded
     */
    public static String verdict(String documentKey, String text) {
        return verdictOf(documentKey, text).token;
    }

    /** The verdict with the tier that produced it, for the trace's {@code langsrc=} field. */
    public static Verdict verdictOf(String documentKey, String text) {
        if (text == null || text.isEmpty()) return new Verdict(UNKNOWN, "none");
        String key = documentKey == null ? "" : documentKey;
        if (!key.isEmpty()) {
            Verdict cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Verdict computed = compute(text);
        if (!key.isEmpty()) {
            if (CACHE.size() >= MAX_ENTRIES) CACHE.clear();
            CACHE.put(key, computed);
        }
        return computed;
    }

    /**
     * The chain: tier 1, then tier 2 whatever tier 1 did, and only then nothing.
     *
     * <p>Tier 2 used to be gated on the pack being absent, which made the two tiers alternatives and
     * left the component inert on the one device that mattered - the pack was installed there, and
     * the detector declines more often than not. A decline is not a reason to stop classifying; it is
     * a reason to answer at the coarser granularity.
     */
    private static Verdict compute(String text) {
        String source;
        try {
            Tier1 detector = tier1;
            String fromPack = detector == null ? null : detector.detect(text);
            if (fromPack != null && !fromPack.isEmpty()) {
                return new Verdict(fromPack, "tier1");
            }
            source = fromPack == null ? "tier1-absent" : "tier1-declined";
        } catch (Throwable ignored) {
            // A verdict is a scoring hint. A detector that throws - a pack removed mid-session, a
            // native failure, a caller in a process with no model at all - must cost this component
            // its points and nothing else, never the ranking that asked for it. Tier 2 still answers.
            source = "tier1-error";
        }
        String family = family(text);
        if (family.isEmpty()) return new Verdict(UNKNOWN, source + "+none");
        return new Verdict(family, source + "+tier2");
    }

    /**
     * Tier 1 through the installed pack. Null means "the pack is not ready, use tier 2"; the empty
     * string means the pack answered but not well enough to be trusted.
     */
    private static String detectWithPack(String text) {
        if (!LanguageModelPack.isReady()) return null;
        DETECTIONS.incrementAndGet();
        DetectionResult result = LanguageDetectorManager.shared().detect(text);
        if (result == null || !result.hasLanguage() || result.confidence < MIN_CONFIDENCE) {
            return UNKNOWN;
        }
        return LanguageDetectorManager.normalizeHanLabel(result.language);
    }

    /** True when a verdict is Chinese, by either tier's spelling of it. */
    public static boolean isChinese(String verdict) {
        return "zh".equals(verdict) || "fam:zh".equals(verdict);
    }

    /**
     * The script family of a text, as {@code fam:<script>}: tier 2, and the reason a document in a
     * script nobody taught the ranking about still fails closed rather than matching by accident.
     */
    public static String family(String text) {
        if (text == null || text.isEmpty()) return UNKNOWN;
        int counted = 0;
        int kana = 0;
        int han = 0;
        Map<Character.UnicodeScript, Integer> letters =
                new java.util.EnumMap<Character.UnicodeScript, Integer>(
                        Character.UnicodeScript.class);
        boolean hangul = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) counted++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                kana++;
            }
            if (!Character.isLetter(cp)) continue;
            if (script == Character.UnicodeScript.HAN) han++;
            if (script == Character.UnicodeScript.HANGUL) hangul = true;
            Integer seen = letters.get(script);
            letters.put(script, seen == null ? 1 : seen + 1);
        }
        if (counted == 0) return UNKNOWN;
        // The same kana ratio, Han floor and Han density the Chinese gate uses: a Chinese song that
        // quotes one Japanese line stays Chinese, and an English track with a few Han words does not
        // become a Chinese document.
        if (kana > 0 && (double) kana / counted > KANA_RATIO) return "fam:ja";
        if (hangul) return "fam:ko";
        if (han >= MIN_HAN && (double) han / counted >= MIN_HAN_DENSITY) return "fam:zh";
        Character.UnicodeScript dominant = null;
        int most = 0;
        for (Map.Entry<Character.UnicodeScript, Integer> entry : letters.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                dominant = entry.getKey();
            }
        }
        if (dominant == null) return UNKNOWN;
        switch (dominant) {
            case LATIN: return "fam:latin";
            case HAN: return "fam:zh";
            case HIRAGANA: case KATAKANA: return "fam:ja";
            case HANGUL: return "fam:ko";
            case CYRILLIC: return "fam:cyrl";
            case GREEK: return "fam:grek";
            case ARABIC: return "fam:arab";
            case HEBREW: return "fam:hebr";
            case DEVANAGARI: return "fam:deva";
            case THAI: return "fam:thai";
            default: return "fam:" + dominant.name().toLowerCase(Locale.ROOT);
        }
    }

    /** The same threshold the Chinese gate uses; see {@code CatalogResolver.KANA_JAPANESE_RATIO}. */
    static final double KANA_RATIO = 0.10d;
    static final int MIN_HAN = 10;
    static final double MIN_HAN_DENSITY = 0.25d;

    // --- test seams -------------------------------------------------------------------------

    /** Installs a counting or fixed tier 1. Pass {@code null} to use the pack again. */
    public static void installTier1ForTests(Tier1 detector) {
        tier1 = detector == null ? PACK : detector;
    }

    /** How many tier-1 detections have run: the pin that a document is inspected once. */
    public static int detectionCount() {
        return DETECTIONS.get();
    }

    public static void clearCache() {
        CACHE.clear();
        DETECTIONS.set(0);
    }
}
