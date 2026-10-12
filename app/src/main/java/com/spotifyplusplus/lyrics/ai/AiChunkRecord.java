package com.spotifyplusplus.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What is known about one chunk: what was asked, how often, at what cost, and how it ended.
 *
 * <p>Request text is diagnostic data. Resumes rebuild requests from the current source under the
 * same document and configuration hashes. Org-backed records omit stored input text. Explicit
 * {@link Status#REPLANNED} state preserves billed parent accounting while deterministic child
 * records own the remaining work.
 *
 * <p>Tokens accumulate across attempts including failed and repaired ones, because those bill too.
 */
public final class AiChunkRecord {
    public enum Status {
        PENDING,
        /**
         * The original request reached the true output ceiling and was replaced by deterministic
         * hierarchical child chunks. The billed parent attempt remains here for accounting; a
         * resume runs only unfinished children and never resends this request.
         */
        REPLANNED,
        /** Every requested row validated. */
        COMPLETE,
        /**
         * Finished with some rows unsatisfied after the targeted re-ask; the kept rows are valid
         * and stored, the rest fall back to the baseline layer. Terminal like {@link #COMPLETE} —
         * a retry must not re-bill the rows that succeeded — but it is not complete: a record
         * holding this status never satisfies a plan's reuse gate.
         */
        COMPLETED_FALLBACK,
        FAILED
    }

    public final List<String> ids;
    public final String requestJson;
    public Status status;
    public int attempts;
    public int repairs;
    public int inputTokens;
    public int outputTokens;
    /** True once any attempt's usage had to be estimated rather than read from the provider. */
    public boolean usageEstimated;
    public AiChunkFailure failure;
    /**
     * Rows this chunk proved it could not satisfy, after its targeted re-ask, so the run falls back
     * to the baseline layer for exactly them.
     *
     * <p>Recorded rather than inferred: "some chunk fell back" is not proof that a specific row may
     * be composed from the baseline, and only proof lets a later run treat a partial record as
     * finished for those rows instead of buying them again.
     */
    public List<String> fallbackRowIds = Collections.emptyList();

    public AiChunkRecord(List<String> ids, String requestJson) {
        this.ids = Collections.unmodifiableList(
                new ArrayList<>(ids == null ? Collections.<String>emptyList() : ids));
        this.requestJson = AiText.nz(requestJson);
        this.status = Status.PENDING;
    }

    public static AiChunkRecord forChunk(AiPlannedChunk chunk) {
        return new AiChunkRecord(chunk.itemIds(), chunk.requestJson);
    }

    /** A detached copy, so a run in flight never mutates the record a caller already holds. */
    public AiChunkRecord copy() {
        AiChunkRecord copy = new AiChunkRecord(ids, requestJson);
        copy.status = status;
        copy.attempts = attempts;
        copy.repairs = repairs;
        copy.inputTokens = inputTokens;
        copy.outputTokens = outputTokens;
        copy.usageEstimated = usageEstimated;
        copy.failure = failure;
        copy.fallbackRowIds = fallbackRowIds;
        return copy;
    }

    /** Records the rows proven to need the baseline layer; null and empty both mean none. */
    public void setFallbackRowIds(List<String> rows) {
        fallbackRowIds = Collections.unmodifiableList(
                new ArrayList<>(rows == null ? Collections.<String>emptyList() : rows));
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /** True when this chunk owes no further calls, whether fully or with fallback rows. */
    public boolean isTerminal() {
        return status == Status.COMPLETE || status == Status.COMPLETED_FALLBACK;
    }
}
