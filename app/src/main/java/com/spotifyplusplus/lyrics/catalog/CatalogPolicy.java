package com.spotifyplusplus.lyrics.catalog;

import android.content.Context;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SpotifyPlusConfig;
import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;
import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Acquisition and selection policy snapshot: which sources are enabled, their order, and whether
 * Auto uses the primary stack or the user source order. Persisted settings are translated here
 * once; runtime code reads this snapshot and never consults the preference store itself.
 */
public final class CatalogPolicy {
    /** Enabled sources in user order. Disabled sources are absent. */
    public final List<SourceId> enabledOrder;
    public final boolean sourceOrderMode;
    public final boolean karaokeOriginalLyrics;
    public final long spicyOrgKeyEpoch;
    public final boolean syncUpgradeEnabled;
    public final boolean spicyOrgTerminated;
    public final long spicyOrgAccessRevision;
    /**
     * True when the owner asked for a Chinese translation to count as a language match even though
     * the track is not Chinese. Off means "prefer the original", the default.
     */
    public final boolean preferChineseTranslation;

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode) {
        this(enabledOrder, sourceOrderMode, false);
    }

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                         boolean karaokeOriginalLyrics) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, false);
    }

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                         boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled, 0L);
    }

    private CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                          boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled,
                          long spicyOrgKeyEpoch) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled,
                spicyOrgKeyEpoch, false, 0);
    }

    CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                  boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled,
                  long spicyOrgKeyEpoch, boolean spicyOrgTerminated, long accessRevision) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled,
                spicyOrgKeyEpoch, spicyOrgTerminated, accessRevision, false);
    }

    CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                  boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled,
                  long spicyOrgKeyEpoch, boolean spicyOrgTerminated, long accessRevision,
                  boolean preferChineseTranslation) {
        this.preferChineseTranslation = preferChineseTranslation;
        this.spicyOrgTerminated = spicyOrgTerminated;
        this.spicyOrgAccessRevision = accessRevision;
        this.syncUpgradeEnabled = syncUpgradeEnabled;
        this.spicyOrgKeyEpoch = spicyOrgKeyEpoch;
        List<SourceId> order = new ArrayList<>();
        if (enabledOrder != null) {
            for (SourceId source : enabledOrder) {
                if (source != null && !order.contains(source)) order.add(source);
            }
        }
        this.enabledOrder = Collections.unmodifiableList(order);
        this.sourceOrderMode = sourceOrderMode;
        this.karaokeOriginalLyrics = karaokeOriginalLyrics;
    }

    public static CatalogPolicy read(Context context) {
        List<SourceId> order = new ArrayList<>();
        for (LyricsSourcePreferences.Source source
                : LyricsSourcePreferences.enabledSourceOrder(context)) {
            SourceId mapped = sourceId(source);
            // SpicyLyrics.org is listed whenever the owner enabled it and a key is stored, even
            // after a recorded access loss.
            //
            // This used to skip it while SpicyOrgAccessState.canAcquire() was false, and that gate
            // takes the non-recovery path: it stays shut until a *manual* check clears the flag. So
            // one rejection silenced the source for automatic acquisition for good, which is
            // exactly the reported "SPICY never searches on its own, but tapping it in the picker
            // works". Automatic acquisition is a fresh request, not a cached replay, so it is
            // allowed to try and let the response decide; cached results stay hidden by
            // eligibleForDisplay() until the server confirms access again.
            //
            // Removing that skip was not enough on its own: sourceId() had no SPICY_ORG case, so
            // the source the owner actually enables mapped to null here and never entered
            // enabledOrder at all. See sourceId().
            if (mapped != null && !order.contains(mapped)) order.add(mapped);
        }
        return new CatalogPolicy(order, LyricsSourcePreferences.rankingMode(context)
                == LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                context != null && SpotifyPlusConfig.from(context)
                        .get(Settings.KARAOKE_ORIGINAL_LYRICS),
                // The cross-provider timing upgrade was removed from this build, so the flag is
                // always false. The parameter itself stays: the ranking files pass it through and
                // touching that call chain would reach into another member's scoring work. A device
                // that stored lyrics_sync_upgrade=true keeps an inert entry nothing reads.
                false,
                com.spotifyplusplus.lyrics.providers.SpicyOrgKeyStore.epoch(context),
                com.spotifyplusplus.lyrics.providers.SpicyOrgAccessState.isTerminated(context),
                com.spotifyplusplus.lyrics.providers.SpicyOrgAccessState.revision(context),
                context != null && "Prefer Chinese translation".equals(
                        SpotifyPlusConfig.from(context).get(Settings.LYRICS_LANGUAGE_PREFERENCE)));
    }

    public boolean enabled(SourceId source) {
        return source != null && enabledOrder.contains(source);
    }

    /** Automatic selection obeys the global karaoke substitution option; manual pins do not. */
    public boolean eligibleForAuto(CatalogCandidate candidate) {
        return eligibleForDisplay(candidate) && enabled(candidate.sourceId)
                && (karaokeOriginalLyrics
                || candidate.matchMethod != CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION);
    }

    /** Access termination also blocks manual pins without removing the stored decision. */
    public boolean eligibleForDisplay(CatalogCandidate candidate) {
        return candidate != null && candidate.hasValidProviderTiming()
                && (candidate.sourceId != SourceId.SPICY_ORG || !spicyOrgTerminated);
    }

    public boolean hasCurrentOrgAccess(Context context) {
        synchronized (com.spotifyplusplus.lyrics.providers.SpicyOrgKeyStore.class) {
            return spicyOrgKeyEpoch == com.spotifyplusplus.lyrics.providers.SpicyOrgKeyStore.epoch(context)
                    && spicyOrgAccessRevision == com.spotifyplusplus.lyrics.providers.SpicyOrgAccessState.revision(context)
                    && spicyOrgTerminated == com.spotifyplusplus.lyrics.providers.SpicyOrgAccessState.isTerminated(context);
        }
    }

    /** Auto keeps the router and direct primary stack before user-ordered fallbacks. */
    public List<SourceId> automaticOrder() {
        if (sourceOrderMode) return enabledOrder;
        List<SourceId> order = new ArrayList<>();
        for (SourceId source : new SourceId[]{SourceId.SPICY_ORG, SourceId.APPLE,
                SourceId.SPOTIFY_NATIVE}) {
            if (enabled(source)) order.add(source);
        }
        for (SourceId source : enabledOrder) if (!order.contains(source)) order.add(source);
        return Collections.unmodifiableList(order);
    }

    public static boolean primary(SourceId source) {
        return source == SourceId.SPICY_ORG || source == SourceId.APPLE
                || source == SourceId.SPOTIFY_NATIVE;
    }

    public static boolean syncDonor(SourceId source) {
        return source == SourceId.QQ || source == SourceId.NETEASE;
    }

    /** Settings-level source to catalog acquisition identity. */
    public static SourceId sourceId(LyricsSourcePreferences.Source source) {
        if (source == null) return null;
        switch (source) {
            case SPICY:
                // Retired desktop-token id that a stored order may still carry; it resolves to the
                // one SpicyLyrics.org source that replaced it.
                return SourceId.SPICY_ORG;
            case SPICY_ORG:
                // Was missing, so the only live SpicyLyrics.org preference fell through to null and
                // was dropped from enabledOrder. That is the whole defect: the planner builds its
                // source list from enabledOrder and eligibleForAuto() requires enabled(), so the
                // source was never probed automatically and a stored result could never be elected -
                // while the picker, which walks CatalogSource ids and never consults this mapping,
                // checked it on tap and the fetch worked. One silent null, five sources competing
                // and a sixth that only ever won a round it was alone in.
                return SourceId.SPICY_ORG;
            case APPLE_MUSIC:
                return SourceId.APPLE;
            case SPOTIFY:
                return SourceId.SPOTIFY_NATIVE;
            case AMLL:
                return SourceId.AMLL;
            case LRCLIB:
                return SourceId.LRCLIB;
            case QQ:
                return SourceId.QQ;
            case NETEASE:
                return SourceId.NETEASE;
            default:
                return null;
        }
    }

    /** Catalog identity to the repository's strict-source route. */
    public static LyricsSourcePreferences.Source preferenceSource(SourceId source) {
        if (source == null) return null;
        switch (source) {
            case SPICY_ORG:
                // Must be the live SPICY_ORG preference, not the retired SPICY alias.
                //
                // The repository turns this mapping back into a request label, and "Spicy" is the
                // retired Apple Music (Lenerd) route while "Spicy Lyrics" is the personal-key v1
                // request. With the alias in place, every automatic round that reached the ordered
                // fetch asked Apple Music for SpicyLyrics.org's slot and stored the answer under
                // APPLE - the source was in the plan and its result was attributed elsewhere.
                return LyricsSourcePreferences.Source.SPICY_ORG;
            case APPLE:
                return LyricsSourcePreferences.Source.APPLE_MUSIC;
            case SPOTIFY_NATIVE:
                return LyricsSourcePreferences.Source.SPOTIFY;
            case AMLL:
                return LyricsSourcePreferences.Source.AMLL;
            case LRCLIB:
                return LyricsSourcePreferences.Source.LRCLIB;
            case QQ:
                return LyricsSourcePreferences.Source.QQ;
            case NETEASE:
                return LyricsSourcePreferences.Source.NETEASE;
            default:
                return null;
        }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CatalogPolicy)) return false;
        CatalogPolicy o = (CatalogPolicy) other;
        return sourceOrderMode == o.sourceOrderMode
                && karaokeOriginalLyrics == o.karaokeOriginalLyrics
                && syncUpgradeEnabled == o.syncUpgradeEnabled
                && spicyOrgKeyEpoch == o.spicyOrgKeyEpoch
                && spicyOrgTerminated == o.spicyOrgTerminated
                && spicyOrgAccessRevision == o.spicyOrgAccessRevision
                && enabledOrder.equals(o.enabledOrder);
    }

    @Override public int hashCode() {
        return Objects.hash(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled,
                spicyOrgKeyEpoch, spicyOrgTerminated, spicyOrgAccessRevision);
    }

    @Override public String toString() {
        return (sourceOrderMode ? "source-order" : "auto") + enabledOrder
                + (karaokeOriginalLyrics ? "+karaoke-original" : "+karaoke-verbatim")
                + (syncUpgradeEnabled ? "+sync-upgrade" : "");
    }
}
