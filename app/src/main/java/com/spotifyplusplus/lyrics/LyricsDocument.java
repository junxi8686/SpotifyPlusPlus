package com.spotifyplusplus.lyrics;

import com.spotifyplusplus.lyrics.language.ChineseCharacterForm;
import com.spotifyplusplus.lyrics.language.SpicyTextDetection;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed lyrics document, normalized across all sources (Spicy API, Spotify native DB/model,
 * LRCLIB). {@code lines} is the source-faithful parse result; {@code appliedLines} is the
 * renderer row plan produced by {@link LyricTimeline#applySyncedRows(LyricsDocument)}.
 */
public class LyricsDocument {
    public String trackId = "";
    public String provider = "Spicy Lyrics";
    public String songWriters = ""; // "Written by" credits from the lyrics response, if any
    public String type = "Unknown";
    public String language = "";
    public String fetchSource = "unknown";
    /**
     * Attribution and provenance for the SpicyLyrics.org source. Kept on the document because the
     * response is subject to origin rules (refresh deadline and hard retention) that are evaluated
     * long after the fetch, including for documents restored from cache.
     */
    public String spicyOrgSource = "";
    public String spicyOrgUploader = "";
    public String spicyOrgUploaderUrl = "";
    public String spicyOrgMaker = "";
    public String spicyOrgMakerUrl = "";
    public String spicyOrgRawPayload = "";
    public long spicyOrgFetchedAtMs;
    /** Source-selection metadata shared by fullscreen and now-playing surfaces. */
    public String selectedSource = "";
    public String selectionMode = "smart";
    public String selectionOverride = "auto";
    /**
     * Catalog candidate this delivery was committed as; empty until a catalog transaction stored
     * it. The session renders catalog seats, so a delivery without an ID is committed first.
     */
    public String catalogCandidateId = "";
    /** Provider identity and persistence outcome; copied across in-flight deliveries only. */
    public com.spotifyplusplus.lyrics.catalog.CatalogDelivery catalogDelivery;
    public boolean spicyPackedPayload;
    public boolean spicyEnvelopeNoticePresent;
    public Integer spicyQueryStatus;
    public String spicyFormat = "";
    public boolean spicyPoisoned;
    public String spicyQualityReason;
    public long durationMs;
    public long startTimeMs;
    public int generation;
    public int processingVersion;
    public boolean processingPending;
    public boolean romanizationPending;
    public boolean translationPending;
    /** The translation run failed and nothing is shown for it: the toggle offers a retry. */
    public boolean translationFailed;
    /**
     * Why that run failed, as the backend spelled it - "baidu_errno_1022", "parse_empty", a socket
     * exception name. Empty unless {@link #translationFailed}.
     *
     * <p>The flag alone reaches a chip, which is a mark the reader has to interpret. This is what
     * lets the surface name the failure in words, so a refusal is not mistaken for a run that is
     * still working.
     */
    public String translationFailureReason = "";
    public boolean includesRomanization;
    public boolean includesTranslation;
    /**
     * Whether the reading and translation currently shown came from a model.
     *
     * <p>Provenance lives on the artifact, but the controls that need it read the document, so the
     * composer stamps it here on the way through. Display state only: nothing decides what to run
     * from these.
     */
    public boolean readingFromAi;
    public boolean translationFromAi;
    /** True only while the corresponding layer is actively running under AI authority. */
    public boolean readingAiPending;
    public boolean translationAiPending;
    /** True when the displayed AI translation used Google Translate output as request input. */
    public boolean translationAiRefinedFromGoogle;
    /** Model that produced the displayed AI output, from its provenance. Empty when not AI. */
    public String readingAiModel = "";
    public String translationAiModel = "";
    /** Exact privacy-safe AI failure tokens for the current layer settlement, or empty. */
    public String readingAiFailureToken = "";
    public String translationAiFailureToken = "";
    public boolean detectedChinese;
    /**
     * Which script the displayed lyrics are shown in. Display only: the source lines stay exactly
     * what the provider returned, so the picker preview, the cache and the next ranking never see a
     * converted document. ORIGINAL means nothing was converted and nothing was even loaded.
     */
    public ChineseCharacterForm chineseCharacterForm = ChineseCharacterForm.ORIGINAL;
    public final List<SpicyTextDetection.Script> detectedScripts = new ArrayList<>();
    public final List<LyricsLine> lines = new ArrayList<>();
    public final List<AppliedLine> appliedLines = new ArrayList<>();

    /** Deep copy of the parse model (applied rows / view state are intentionally not copied). */
    public static LyricsDocument copyOf(LyricsDocument source) {
        if (source == null) return null;
        LyricsDocument copy = new LyricsDocument();
        copy.trackId = safe(source.trackId);
        copy.provider = safe(source.provider);
        copy.songWriters = safe(source.songWriters);
        copy.type = safe(source.type);
        copy.language = safe(source.language);
        copy.fetchSource = safe(source.fetchSource);
        copy.spicyOrgSource = safe(source.spicyOrgSource);
        copy.spicyOrgUploader = safe(source.spicyOrgUploader);
        copy.spicyOrgUploaderUrl = safe(source.spicyOrgUploaderUrl);
        copy.spicyOrgMaker = safe(source.spicyOrgMaker);
        copy.spicyOrgMakerUrl = safe(source.spicyOrgMakerUrl);
        copy.spicyOrgRawPayload = safe(source.spicyOrgRawPayload);
        copy.spicyOrgFetchedAtMs = source.spicyOrgFetchedAtMs;
        copy.selectedSource = safe(source.selectedSource);
        copy.selectionMode = safe(source.selectionMode);
        copy.selectionOverride = safe(source.selectionOverride);
        copy.catalogCandidateId = safe(source.catalogCandidateId);
        copy.catalogDelivery = source.catalogDelivery;
        copy.spicyPackedPayload = source.spicyPackedPayload;
        copy.spicyEnvelopeNoticePresent = source.spicyEnvelopeNoticePresent;
        copy.spicyQueryStatus = source.spicyQueryStatus;
        copy.spicyFormat = safe(source.spicyFormat);
        copy.spicyPoisoned = source.spicyPoisoned;
        copy.spicyQualityReason = source.spicyQualityReason;
        copy.durationMs = source.durationMs;
        copy.startTimeMs = source.startTimeMs;
        copy.generation = source.generation;
        copy.processingVersion = source.processingVersion;
        copy.processingPending = source.processingPending;
        copy.romanizationPending = source.romanizationPending;
        copy.translationPending = source.translationPending;
        copy.translationFailed = source.translationFailed;
        copy.translationFailureReason = safe(source.translationFailureReason);
        copy.includesRomanization = source.includesRomanization;
        copy.includesTranslation = source.includesTranslation;
        copy.readingFromAi = source.readingFromAi;
        copy.translationFromAi = source.translationFromAi;
        copy.readingAiPending = source.readingAiPending;
        copy.translationAiPending = source.translationAiPending;
        copy.translationAiRefinedFromGoogle = source.translationAiRefinedFromGoogle;
        copy.readingAiModel = source.readingAiModel;
        copy.translationAiModel = source.translationAiModel;
        copy.readingAiFailureToken = safe(source.readingAiFailureToken);
        copy.translationAiFailureToken = safe(source.translationAiFailureToken);
        copy.detectedChinese = source.detectedChinese;
        copy.chineseCharacterForm = source.chineseCharacterForm;
        copy.detectedScripts.addAll(source.detectedScripts);
        for (LyricsLine line : source.lines) copy.lines.add(LyricsLine.copyOf(line));
        return copy;
    }

    static String safe(String value) {
        return LyricUtils.safe(value);
    }
}
