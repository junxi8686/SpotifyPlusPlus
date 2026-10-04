package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.catalog.CatalogResolver.Resolution;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.ProviderStatus;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SelectionMode;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure view-model for the fullscreen source picker. One row per source: a stored best candidate
 * renders with its timing and capabilities, an unchecked source shows its status with a tap to
 * check it. The dialog renders rows; every action routes back through the shared session so
 * now playing updates together. No lyric bodies leave this model: titles carry providers,
 * timing, status, and capability labels only.
 */
public final class CatalogPickerModel {
    private CatalogPickerModel() {
    }

    /**
     * Text lookup for the rows this model builds.
     *
     * <p>Injected rather than read from a Context so the model stays a pure function that a JVM
     * test can drive without a device - and so the rows read in whatever language the panel is
     * showing. {@link #RAW} reproduces the authored English, which is what the tests and the
     * diagnostics paths use.
     */
    public interface Text {
        String get(String name, String fallback);

        /** The authored English, unchanged. */
        Text RAW = (name, fallback) -> fallback;
    }

    public enum RowKind {
        AUTO,
        SOURCE,
        ACTION_CHECK_ALL,
        /** Opens a field for a phrase to search with, in place of the track's own metadata. */
        ACTION_MANUAL_SEARCH,
        ACTION_DELETE_TRACK
    }

    /** Leading status glyph: fetched data usable, fetched but empty, or nothing to report. */
    public enum DataMark {
        NONE,
        HAVE,
        EMPTY
    }

    /** Source display order matches the automatic tie-break. */
    static final SourceId[] SOURCE_ORDER = {
            SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB,
            SourceId.QQ, SourceId.NETEASE,
    };

    public static final class Row {
        public final RowKind kind;
        public final String title;
        public final String subtitle;
        public final boolean selected;
        public final boolean stored;
        /** Checking starts a request; false for pure actions. */
        public final boolean checkable;
        public final DataMark mark;
        public final String candidateId;
        public final SourceId sourceId;

        Row(RowKind kind, String title, String subtitle, boolean selected, boolean stored,
            boolean checkable, DataMark mark, String candidateId, SourceId sourceId) {
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.selected = selected;
            this.stored = stored;
            this.checkable = checkable;
            this.mark = mark == null ? DataMark.NONE : mark;
            this.candidateId = candidateId == null ? "" : candidateId;
            this.sourceId = sourceId;
        }

        /** Copy with a replaced title (delete-arm confirmation); null keeps the title. */
        public Row withTitle(String title) {
            return new Row(kind, title == null ? this.title : title, subtitle, selected, stored,
                    checkable, mark, candidateId, sourceId);
        }

        public Row withSubtitle(String subtitle) {
            return new Row(kind, title, subtitle == null ? this.subtitle : subtitle, selected,
                    stored, checkable, mark, candidateId, sourceId);
        }
    }

    /**
     * @param candidates stored candidates for the track (any order; ranked here)
     * @param states persisted per-source states; absent means not checked
     * @param selection current selection (null behaves as Auto)
     * @param auto the resolver's current automatic outcome (null when nothing stored)
     */
    public static List<Row> build(List<CatalogCandidate> candidates,
                                  Map<SourceId, ProviderStatus> states,
                                  CatalogSelection selection, Resolution auto) {
        return build(candidates, states, selection, auto, null, Text.RAW);
    }

    public static List<Row> build(List<CatalogCandidate> candidates,
                                  Map<SourceId, ProviderStatus> states,
                                  CatalogSelection selection, Resolution auto,
                                  CatalogPolicy policy) {
        return build(candidates, states, selection, auto, policy, Text.RAW);
    }

    public static List<Row> build(List<CatalogCandidate> candidates,
                                  Map<SourceId, ProviderStatus> states,
                                  CatalogSelection selection, Resolution auto,
                                  CatalogPolicy policy, Text text) {
        final Text t = text == null ? Text.RAW : text;
        List<Row> rows = new ArrayList<>();
        boolean manual = selection != null && selection.mode == SelectionMode.MANUAL;
        SourceId winnerSource = !manual && auto != null && auto.winner != null
                ? auto.winner.sourceId : null;
        String winnerCandidate = !manual && auto != null && auto.winner != null
                ? auto.winner.candidateId : null;
        if (auto != null && auto.winner != null) {
            // No subtitle, in any state. "Best match" claimed a comparison across every source
            // when the automatic visit only asks the sources that are due, and "Fetching" was
            // state text the owner asked not to park in this menu. The title plus the
            // selected-green state is the whole claim.
            rows.add(new Row(RowKind.AUTO, t.get("picker_row_auto", "Auto"), "",
                    !manual, true, false, DataMark.NONE, auto.winner.candidateId,
                    auto.winner.sourceId));
        } else {
            rows.add(new Row(RowKind.AUTO, t.get("picker_row_auto_empty", "Auto · nothing stored yet"),
                    "", !manual, false, false, DataMark.NONE, "", null));
        }
        Map<SourceId, CatalogCandidate> bestBySource = bestBySource(candidates);
        if (manual && selection != null) {
            CatalogCandidate pinned = candidateById(candidates, selection.candidateId);
            if (pinned != null && pinned.sourceId != null) {
                // The selected revision remains visible even when a newer variant from the same
                // provider ranks higher. A manual pin identifies a candidate, not just a brand.
                bestBySource.put(pinned.sourceId, pinned);
            }
        }
        for (SourceId source : SOURCE_ORDER) {
            CatalogCandidate best = bestBySource.get(source);
            ProviderStatus status = stateFor(states, source);
            boolean selected = manual && selection != null && best != null
                    && selection.candidateId.equals(best.candidateId);
            if (!manual && winnerSource != null && best != null
                    && winnerCandidate != null && winnerCandidate.equals(best.candidateId)) {
                // Auto mode greens the winning candidate row alongside the Auto row itself.
                selected = true;
            }
            if (best != null) {
                rows.add(new Row(RowKind.SOURCE,
                        displaySource(source, t) + " · " + displayTiming(best, t),
                        statusLine(best, status, t),
                        selected, true, false, DataMark.HAVE, best.candidateId, source));
            } else {
                rows.add(new Row(RowKind.SOURCE, displaySource(source, t),
                        checkHint(status, t),
                        false, false, true, emptyMark(status), "", source));
            }
        }
        StringBuilder enabled = new StringBuilder();
        if (policy != null) {
            for (SourceId source : policy.enabledOrder) {
                if (enabled.length() > 0) enabled.append(", ");
                enabled.append(displaySource(source, t));
            }
        }
        rows.add(new Row(RowKind.ACTION_CHECK_ALL,
                t.get("picker_row_check_all", "Check all sources in order"),
                enabled.length() == 0
                        ? t.get("picker_row_check_all_none", "Check every enabled source")
                        : t.get("picker_row_check_all_prefix", "Check all: %1$s")
                                .replace("%1$s", enabled.toString()),
                false, false, false, DataMark.NONE, "", null));
        rows.add(new Row(RowKind.ACTION_MANUAL_SEARCH,
                t.get("picker_row_manual_search", "Search by name…"),
                t.get("picker_row_manual_search_detail",
                        "Type the title as the other app spells it"),
                false, false, false, DataMark.NONE, "", null));
        rows.add(new Row(RowKind.ACTION_DELETE_TRACK,
                t.get("picker_row_clear", "Clear saved lyrics"),
                t.get("picker_row_clear_detail", "stored candidates and states"),
                false, false, false, DataMark.NONE, "", null));
        return Collections.unmodifiableList(rows);
    }

    /** Fetched-but-empty outcomes earn an X glyph; anything else reports nothing. */
    static DataMark emptyMark(ProviderStatus status) {
        if (status == null) return DataMark.NONE;
        switch (status) {
            case NOT_FOUND:
            case TRANSIENT_ERROR:
            case REJECTED:
            case NEEDS_REFRESH:
                return DataMark.EMPTY;
            default:
                return DataMark.NONE;
        }
    }

    /** Short retry hint for an enabled source with no stored candidate. */
    static String checkHint(ProviderStatus status) {
        return checkHint(status, Text.RAW);
    }

    static String checkHint(ProviderStatus status, Text text) {
        final Text t = text == null ? Text.RAW : text;
        if (status == null || status == ProviderStatus.NOT_CHECKED) {
            return t.get("picker_hint_check", "Tap to check");
        }
        switch (status) {
            case AVAILABLE:
                return t.get("picker_hint_check", "Tap to check");
            case NOT_FOUND:
                return t.get("picker_hint_not_found", "Not found · tap to retry");
            case TRANSIENT_ERROR:
                return t.get("picker_hint_failed", "Failed · tap to retry");
            case NEEDS_REFRESH:
                return t.get("picker_hint_needs_refresh", "Needs refresh · tap to retry");
            case REJECTED:
                return t.get("picker_hint_rejected", "Rejected · tap to retry");
            default:
                return t.get("picker_hint_check", "Tap to check");
        }
    }

    public static String displaySource(SourceId source) {
        return displaySource(source, Text.RAW);
    }

    public static String displaySource(SourceId source, Text text) {
        final Text t = text == null ? Text.RAW : text;
        if (source == null) return t.get("picker_source_unknown", "Unknown");
        switch (source) {
            case APPLE: return t.get("lyrics_source_name_apple", "Apple Music");
            case SPOTIFY_NATIVE: return t.get("lyrics_source_name_spotify", "Spotify");
            case AMLL: return t.get("lyrics_source_name_amll", "AMLL");
            case LRCLIB: return t.get("lyrics_source_name_lrclib", "LRCLIB");
            case QQ: return t.get("lyrics_source_name_qq", "QQ Music");
            case NETEASE: return t.get("lyrics_source_name_netease", "NetEase");
            default: return source.id;
        }
    }

    static String displayTiming(CatalogCandidate candidate) {
        return displayTiming(candidate, Text.RAW);
    }

    static String displayTiming(CatalogCandidate candidate, Text text) {
        if (candidate == null) return displayTimingLevel(null, text);
        return displayTimingLevel(candidate.timingLevel, text);
    }

    /** Footer timing label for a rendered document type. */
    public static String displayTypeTiming(String documentType) {
        return displayTypeTiming(documentType, Text.RAW);
    }

    public static String displayTypeTiming(String documentType, Text text) {
        return displayTimingLevel(CatalogSource.TimingLevel.fromDocumentType(documentType), text);
    }

    private static String displayTimingLevel(CatalogSource.TimingLevel level) {
        return displayTimingLevel(level, Text.RAW);
    }

    private static String displayTimingLevel(CatalogSource.TimingLevel level, Text text) {
        final Text t = text == null ? Text.RAW : text;
        if (level == null) return t.get("picker_timing_unsynced", "Unsynced");
        switch (level) {
            case SYLLABLE: return t.get("picker_timing_syllable", "Syllable");
            case WORD: return t.get("picker_timing_word", "Word");
            case LINE: return t.get("picker_timing_line", "Line");
            default: return t.get("picker_timing_unsynced", "Unsynced");
        }
    }

    static String statusLine(CatalogCandidate candidate, ProviderStatus status) {
        return statusLine(candidate, status, Text.RAW);
    }

    static String statusLine(CatalogCandidate candidate, ProviderStatus status, Text text) {
        final Text t = text == null ? Text.RAW : text;
        StringBuilder out = new StringBuilder(displayStatus(status, t));
        List<String> caps = new ArrayList<>();
        if (candidate.hasProviderTranslation) caps.add(t.get("picker_cap_translation", "Translation"));
        if (candidate.hasProviderTransliteration) {
            caps.add(t.get("picker_cap_romanization", "Romanization"));
        }
        if (!caps.isEmpty()) out.append(" · ").append(join(caps));
        return out.toString();
    }

    static String displayStatus(ProviderStatus status) {
        return displayStatus(status, Text.RAW);
    }

    static String displayStatus(ProviderStatus status, Text text) {
        final Text t = text == null ? Text.RAW : text;
        if (status == null) return t.get("picker_status_not_checked", "Not checked");
        switch (status) {
            case AVAILABLE: return t.get("picker_status_available", "Available");
            case NOT_CHECKED: return t.get("picker_status_not_checked", "Not checked");
            case NOT_FOUND: return t.get("picker_status_not_found", "Not found");
            case TRANSIENT_ERROR: return t.get("picker_status_failed", "Failed");
            case REJECTED: return t.get("picker_status_rejected", "Rejected");
            case NEEDS_REFRESH: return t.get("picker_status_needs_refresh", "Needs refresh");
            case DISABLED: return t.get("picker_status_disabled", "Disabled");
            default: return t.get("picker_status_not_checked", "Not checked");
        }
    }

    private static Map<SourceId, CatalogCandidate> bestBySource(
            List<CatalogCandidate> candidates) {
        Map<SourceId, CatalogCandidate> best = new EnumMap<>(SourceId.class);
        for (CatalogCandidate candidate : CatalogResolver.ranked(candidates)) {
            if (candidate.sourceId != null && !best.containsKey(candidate.sourceId)) {
                best.put(candidate.sourceId, candidate);
            }
        }
        return best;
    }

    private static CatalogCandidate candidateById(List<CatalogCandidate> candidates, String id) {
        if (candidates == null || id == null || id.isEmpty()) return null;
        for (CatalogCandidate candidate : candidates) {
            if (candidate != null && id.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }

    private static ProviderStatus stateFor(Map<SourceId, ProviderStatus> states, SourceId source) {
        if (states == null || source == null) return null;
        return states.get(source);
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(", ");
            out.append(part);
        }
        return out.toString();
    }
}
