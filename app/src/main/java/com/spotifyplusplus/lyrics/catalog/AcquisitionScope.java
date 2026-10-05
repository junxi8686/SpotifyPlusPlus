package com.spotifyplusplus.lyrics.catalog;

import com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The sources one automatic fetch may ask, as decided by {@link AcquisitionPlanner}. The
 * repository consults this instead of the source settings, so a source the planner held back
 * (disabled, or answered recently) is never requested by the automatic chain.
 */
public final class AcquisitionScope {
    public static final AcquisitionScope NONE =
            new AcquisitionScope(Collections.<SourceId>emptyList(), false, false);

    /** Allowed sources, in ask order for source-order mode. */
    public final List<SourceId> sources;
    public final boolean sourceOrderMode;
    public final boolean karaokeOriginalLyrics;
    /**
     * Ask every source in {@link #sources} at the same time instead of one at a time.
     *
     * <p>The planner deliberately proposes a single source per round so each provider's outcome is
     * recorded before the next is tried. That is right for retry bookkeeping and wrong for the wait:
     * asked in sequence, the wait is the sum of every provider's latency, and a slow first provider
     * hides a fast second one. When this is set the repository races them instead, shows the first
     * usable answer, and upgrades it only for a strictly better one.
     */
    public final boolean raceAll;

    public AcquisitionScope(List<SourceId> sources, boolean sourceOrderMode) {
        this(sources, sourceOrderMode, false);
    }

    public AcquisitionScope(List<SourceId> sources, boolean sourceOrderMode,
                            boolean karaokeOriginalLyrics) {
        this(sources, sourceOrderMode, karaokeOriginalLyrics, false);
    }

    public AcquisitionScope(List<SourceId> sources, boolean sourceOrderMode,
                            boolean karaokeOriginalLyrics, boolean raceAll) {
        List<SourceId> copy = new ArrayList<>();
        if (sources != null) {
            for (SourceId source : sources) {
                if (source != null && !copy.contains(source)) copy.add(source);
            }
        }
        this.sources = Collections.unmodifiableList(copy);
        this.sourceOrderMode = sourceOrderMode;
        this.karaokeOriginalLyrics = karaokeOriginalLyrics;
        this.raceAll = raceAll;
    }

    /** Same scope, flagged to ask every listed source at once. */
    public AcquisitionScope racing() {
        return new AcquisitionScope(sources, sourceOrderMode, karaokeOriginalLyrics, true);
    }

    public boolean allows(SourceId source) {
        return source != null && sources.contains(source);
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Stable identity for in-flight de-duplication. */
    public String key() {
        StringBuilder out = new StringBuilder(sourceOrderMode ? "order:" : "auto:");
        for (SourceId source : sources) out.append(source.id).append(',');
        out.append(karaokeOriginalLyrics ? "karaoke-original" : "karaoke-verbatim");
        return out.toString();
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AcquisitionScope)) return false;
        AcquisitionScope o = (AcquisitionScope) other;
        return sourceOrderMode == o.sourceOrderMode
                && karaokeOriginalLyrics == o.karaokeOriginalLyrics
                && sources.equals(o.sources);
    }

    @Override public int hashCode() {
        return Objects.hash(sources, sourceOrderMode, karaokeOriginalLyrics);
    }
}
