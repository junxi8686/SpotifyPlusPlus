package com.spotifyplusplus.lyrics.ai;

import com.spotifyplusplus.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything known about one paid document: what was asked, what came back, and what it cost.
 *
 * <p>This is the unit the owner paid for, so it is durable and it is never silently discarded.
 * Two properties follow from that and shape the whole class.
 *
 * <p><b>A partial record is worth keeping.</b> Chunks complete one at a time and each one was
 * billed on its own. A run interrupted by a track change has already spent money on the chunks it
 * finished, so those are stored and a later run resumes from them rather than paying twice. This is
 * why the record holds per-chunk state and not merely a finished/unfinished flag.
 *
 * <p><b>Accounting includes what failed.</b> A rejected response and a repair attempt both bill.
 * Tokens therefore accumulate across every attempt, and {@link #usageEstimated} records whether any
 * of those numbers had to be inferred rather than read from the provider — a cost display that
 * quietly rounded an unknown down to zero would be worse than one that admits it is approximate.
 */
public final class AiPaidRecord {

    public enum Status {
        /** Some chunks are done. Valid to resume; not valid to display. */
        PARTIAL,
        /** Every chunk validated. This is the only status that may be applied to a document. */
        COMPLETE,
        /** A chunk failed terminally. Retained for its accounting and its resumable chunks. */
        FAILED
    }

    public final LayerKind layer;
    public final String docDigest;
    public final String configId;
    public final String providerId;
    public final String modelName;
    /** Target language for Meaning, target orthography for Sound. */
    public final String targetLang;
    /** Sound only: the normalized source language the reading was produced for. */
    public final String sourceLanguage;
    /** Sound only: which pronunciation system produced it. Pinyin and Jyutping are not one answer. */
    public final String pronunciationSystem;
    public final int schemaVersion;
    public final int chunkPlanVersion;

    public Status status = Status.PARTIAL;
    public int inputTokens;
    public int outputTokens;
    public boolean usageEstimated;
    public long createdAtMs;
    public long lastAccessedAtMs;

    /** Accepted output by canonical row ID, in the order the rows were enumerated. */
    private final Map<String, String> items = new LinkedHashMap<>();
    /** Per-chunk state, keyed by the planner's stable chunk ID. */
    private final Map<String, AiChunkRecord> chunks = new LinkedHashMap<>();

    public AiPaidRecord(LayerKind layer, String docDigest, String configId, String providerId,
                        String modelName, String targetLang, String sourceLanguage,
                        String pronunciationSystem, int schemaVersion, int chunkPlanVersion) {
        this.layer = layer == null ? LayerKind.MEANING : layer;
        this.docDigest = AiText.nz(docDigest);
        this.configId = AiText.nz(configId);
        this.providerId = AiText.nz(providerId);
        this.modelName = AiText.nz(modelName);
        this.targetLang = AiText.nz(targetLang);
        this.sourceLanguage = sourceLanguage;
        this.pronunciationSystem = pronunciationSystem;
        this.schemaVersion = schemaVersion;
        this.chunkPlanVersion = chunkPlanVersion;
    }

    /** A fresh record for a plan about to run. */
    public static AiPaidRecord begin(AiRunConfig config, long nowMs) {
        AiPaidRecord record = new AiPaidRecord(config.layer, config.docDigest, config.configId,
                config.providerId, config.modelName, config.targetLang, config.sourceLanguage,
                config.pronunciationSystem, AiContract.schemaFor(config.layer),
                AiContract.CHUNK_PLAN_VERSION);
        record.createdAtMs = nowMs;
        record.lastAccessedAtMs = nowMs;
        return record;
    }

    public Map<String, String> items() {
        return Collections.unmodifiableMap(items);
    }

    public Map<String, AiChunkRecord> chunks() {
        return Collections.unmodifiableMap(chunks);
    }

    public String item(String rowId) {
        return items.get(AiText.nz(rowId));
    }

    public AiChunkRecord chunk(String chunkId) {
        return chunks.get(AiText.nz(chunkId));
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public void putItem(String rowId, String text) {
        if (AiText.nz(rowId).isEmpty()) return;
        items.put(rowId, AiText.nz(text));
    }

    public void putChunk(String chunkId, AiChunkRecord record) {
        if (AiText.nz(chunkId).isEmpty() || record == null) return;
        chunks.put(chunkId, record);
    }

    /**
     * Folds one execution's accounting in, counting only what this attempt added.
     *
     * <p>A resumed chunk arrives carrying the totals of its earlier attempts, so the deltas are
     * taken against the record already held. Adding the raw totals would bill the earlier attempts
     * a second time on every resume.
     */
    public void account(AiChunkRecord previous, AiChunkRecord next) {
        if (next == null) return;
        int priorIn = previous == null ? 0 : previous.inputTokens;
        int priorOut = previous == null ? 0 : previous.outputTokens;
        inputTokens += next.inputTokens - priorIn;
        outputTokens += next.outputTokens - priorOut;
        usageEstimated |= next.usageEstimated;
    }

    /**
     * Reopens failed chunks so an explicit retry can resend exactly them.
     *
     * <p>Completed chunks are untouched: they are paid for and validated, and resending one would
     * be a second charge for an answer already held.
     */
    public void reopenFailedChunks() {
        for (AiChunkRecord chunk : chunks.values()) {
            if (chunk.status != AiChunkRecord.Status.FAILED) continue;
            chunk.status = AiChunkRecord.Status.PENDING;
            chunk.attempts = 0;
            chunk.repairs = 0;
            chunk.failure = null;
        }
        if (status == Status.FAILED) status = Status.PARTIAL;
    }

    /** Chunk IDs still owing a call, in plan order. */
    public List<String> outstandingChunkIds(AiChunkPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan == null) return out;
        for (AiPlannedChunk chunk : plan.chunks) {
            AiChunkRecord held = chunks.get(chunk.id);
            if (held == null || !held.isComplete()) out.add(chunk.id);
        }
        return out;
    }

    /** True when every chunk of {@code plan} has validated output in this record. */
    public boolean satisfies(AiChunkPlan plan) {
        return plan != null && outstandingChunkIds(plan).isEmpty();
    }

    /**
     * Drops the retained chunk bookkeeping while keeping the paid output.
     *
     * <p>Used when a stored record's chunked shape no longer matches the current plan: those chunks
     * would skip work and map onto rows they were never asked about, so the run has to execute every
     * chunk of the current plan. What was already paid for stays in {@link #items()} - discarding it
     * for a plan change would charge the owner twice for the same rows.
     */
    public void dropRetainedChunks() {
        chunks.clear();
    }

    /**
     * True when every planned chunk ended terminally and the rows left without output are exactly
     * the ones a terminal chunk proved it could not satisfy.
     *
     * <p>This is the difference between "the run stopped" and "the run finished". A partial record
     * whose shortfall is unproven may simply be missing a row nobody asked about, so only the proven
     * form may be treated as done for the rows it covers - and only then may the display compose
     * those rows from the baseline layer without buying the same answer twice.
     */
    public boolean hasTerminalFallback(AiChunkPlan plan) {
        if (status != Status.PARTIAL || plan == null || plan.isEmpty() || items.isEmpty()) return false;
        boolean fallback = false;
        for (AiChunkRecord held : chunks.values()) {
            fallback |= held.status == AiChunkRecord.Status.COMPLETED_FALLBACK;
        }
        return fallback && hasTerminalOutput(plan);
    }

    /** True when this record's items are exactly the output a finished run of {@code plan} holds. */
    boolean hasTerminalOutput(AiChunkPlan plan) {
        if (plan == null || plan.isEmpty()) return false;
        if (chunkPlanVersion != plan.version) return false;
        Set<String> expectedOutput = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (AiPlannedChunk planned : plan.chunks) {
            Set<String> omitted = terminalFallbackRows(planned.id, planned.itemIds(), visited);
            if (omitted == null) return false;
            Set<String> requestedRows = rowIds(planned.itemIds());
            requestedRows.removeAll(omitted);
            expectedOutput.addAll(requestedRows);
        }
        return visited.equals(chunks.keySet()) && items.keySet().equals(expectedOutput);
    }

    /** True when every planned chunk holds a terminal record. */
    boolean hasTerminalRoots(AiChunkPlan plan) {
        for (AiPlannedChunk planned : plan.chunks) {
            AiChunkRecord held = chunk(planned.id);
            if (held == null || !held.isTerminal()) return false;
        }
        return true;
    }

    /**
     * True when a held record may be resumed against {@code plan} unchanged.
     *
     * <p>A resume rebuilds its requests from the current source under the same identities, so a
     * record whose chunked shape no longer matches the plan would carry completed chunks that map
     * onto rows they were never asked about. That is a silent wrong answer rather than a lost
     * saving, so a mismatched record is not resumed; the caller buys the document again.
     */
    boolean hasValidRetainedPlan(AiChunkPlan plan, AiChunkPlanner.Input input) {
        if (plan == null || chunkPlanVersion != plan.version) return false;
        Set<String> visited = new LinkedHashSet<>();
        Set<String> outputRows = new LinkedHashSet<>();
        for (AiPlannedChunk planned : plan.chunks) {
            if (!validRetainedChunk(planned, input, visited, outputRows)) return false;
        }
        return visited.equals(chunks.keySet()) && outputRows.equals(items.keySet());
    }

    private boolean validRetainedChunk(AiPlannedChunk planned, AiChunkPlanner.Input input,
                                       Set<String> visited, Set<String> outputRows) {
        AiChunkRecord held = chunk(planned.id);
        if (held == null) return true;
        if (held.isTerminal()) {
            Set<String> omitted = terminalFallbackRows(planned.id, planned.itemIds(), visited);
            if (omitted == null || !hasProvenTerminalChunk(planned)) return false;
            Set<String> rows = rowIds(planned.itemIds());
            rows.removeAll(omitted);
            outputRows.addAll(rows);
            return true;
        }
        if (!held.ids.equals(planned.itemIds()) || !held.fallbackRowIds.isEmpty()
                || !visited.add(planned.id)) return false;
        if (held.status == AiChunkRecord.Status.REPLANNED) {
            List<AiPlannedChunk> children = AiChunkPlanner.replan(input, planned);
            for (AiPlannedChunk child : children) {
                if (!validRetainedChunk(child, input, visited, outputRows)) return false;
            }
        }
        return true;
    }

    /** True when a terminal chunk's rows and its re-planned children agree with its omissions. */
    boolean hasProvenTerminalChunk(AiPlannedChunk planned) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> omitted = terminalFallbackRows(planned.id, planned.itemIds(), visited);
        if (omitted == null) return false;
        for (String id : chunks.keySet()) {
            if (id.startsWith(planned.id + ".") && !visited.contains(id)) return false;
        }
        for (String row : rowIds(planned.itemIds())) {
            if (items.containsKey(row) == omitted.contains(row)) return false;
        }
        return true;
    }

    /**
     * The rows {@code id} proved it could not satisfy, or null when it cannot be trusted as proof.
     *
     * <p>Every part of the claim is checked: the record's own ids against the plan's, no duplicate
     * ids, the fallback list inside those ids, the status matching whether any row was omitted, and
     * - for a re-planned parent - a complete, disjoint set of children whose omissions add up to the
     * parent's. A partial proof is not a proof.
     */
    private Set<String> terminalFallbackRows(String id, List<String> expectedIds,
                                             Set<String> visited) {
        AiChunkRecord held = chunk(id);
        if (held == null || !held.isTerminal() || !held.ids.equals(expectedIds)
                || held.ids.isEmpty() || new LinkedHashSet<>(held.ids).size() != held.ids.size()
                || !visited.add(id)) return null;
        Set<String> omitted = new LinkedHashSet<>(held.fallbackRowIds);
        if (omitted.size() != held.fallbackRowIds.size()
                || !rowIds(held.ids).containsAll(omitted)
                || (held.status == AiChunkRecord.Status.COMPLETED_FALLBACK) != !omitted.isEmpty()) {
            return null;
        }
        int directChildren = 0;
        String prefix = id + ".";
        for (String childId : chunks.keySet()) {
            if (childId.startsWith(prefix) && childId.indexOf('.', prefix.length()) < 0) {
                directChildren++;
            }
        }
        if (directChildren == 0) return omitted;
        if (directChildren < 2) return null;
        List<String> covered = new ArrayList<>();
        Set<String> coveredRows = new LinkedHashSet<>();
        Set<String> childOmissions = new LinkedHashSet<>();
        for (int i = 0; i < directChildren; i++) {
            String childId = prefix + i;
            AiChunkRecord child = chunk(childId);
            if (child == null || child.ids.size() >= held.ids.size()) return null;
            Set<String> childRows = rowIds(child.ids);
            if (!Collections.disjoint(coveredRows, childRows)) return null;
            coveredRows.addAll(childRows);
            covered.addAll(child.ids);
            Set<String> childFallback = terminalFallbackRows(childId, child.ids, visited);
            if (childFallback == null) return null;
            childOmissions.addAll(childFallback);
        }
        return covered.equals(held.ids) && childOmissions.equals(omitted) ? omitted : null;
    }

    private static Set<String> rowIds(List<String> ids) {
        Set<String> rows = new LinkedHashSet<>();
        for (String id : ids) rows.add(AiContract.rowIdOf(id));
        return rows;
    }

    /**
     * True when {@code rowId} is one this record structurally proved it could not translate.
     *
     * <p>The display uses this to compose a row from the baseline layer, so it must hold for that
     * exact row: a chunk that fell back for other rows proves nothing about this one.
     */
    boolean permitsFallback(String rowId) {
        if (status != Status.PARTIAL || items.containsKey(rowId) || chunks.isEmpty()) return false;
        boolean proven = false;
        for (AiChunkRecord held : chunks.values()) {
            if (!held.isTerminal()) return false;
            if (held.status != AiChunkRecord.Status.COMPLETED_FALLBACK
                    || !held.fallbackRowIds.contains(rowId)) continue;
            for (String id : held.ids) {
                if (AiContract.rowIdOf(id).equals(rowId)) proven = true;
            }
        }
        return proven;
    }

    /**
     * True when this record covers exactly the rows a finished run for {@code rows} must hold, with
     * chunk identity intact.
     */
    public boolean isCompleteForRows(List<AiLine> rows) {
        if (!hasCompleteItemsForRows(rows) || chunks.isEmpty()) return false;
        Set<String> expected = sentRowIds(rows);
        Set<String> covered = new LinkedHashSet<>();
        for (Map.Entry<String, AiChunkRecord> entry : chunks.entrySet()) {
            AiChunkRecord chunk = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isEmpty() || chunk == null
                    || !chunk.isComplete() || chunk.ids.isEmpty()) return false;
            Set<String> ids = new HashSet<>();
            for (String id : chunk.ids) {
                if (id == null || id.isEmpty() || !ids.add(id)) return false;
                covered.add(AiContract.rowIdOf(id));
            }
        }
        return expected.equals(covered);
    }

    /**
     * True when a saved record may be replayed for {@code rows} instead of bought again.
     *
     * <p>Stricter than {@link #isComplete()}: the stored status says what the run believed, and
     * this says whether the text actually held is usable now - the right rows, and each one still
     * passing the contract it was accepted under. A record that fails here is not a hit, so the
     * caller buys the document rather than displaying an answer that no longer meets the rules.
     */
    public boolean hasCompleteItemsForRows(List<AiLine> rows) {
        if (!isComplete() || rows == null) return false;
        return sentRowIds(rows).equals(items.keySet()) && hasValidStoredItemsForRows(rows);
    }

    /** Per-row validity of the stored text, independent of which rows are missing entirely. */
    boolean hasValidStoredItemsForRows(List<AiLine> rows) {
        if (rows == null || !sentRowIds(rows).containsAll(items.keySet())) return false;
        for (AiLine row : rows) {
            if (row == null || !row.isSent() || !items.containsKey(row.id)) continue;
            String text = items.get(row.id);
            if (AiText.containsForbiddenText(text)
                    || AiText.segmentCount(text) != AiText.segmentCount(row.sourceText)) return false;
            boolean allBlank = true;
            for (String segment : AiText.splitSegments(text)) {
                if (AiText.utf8Bytes(segment) > AiContract.MAX_TRANSLATED_ITEM_BYTES) return false;
                allBlank &= AiText.trim(segment).isEmpty();
            }
            if (allBlank && row.lineClass == AiLineClass.ORDINARY) return false;
        }
        return true;
    }

    private Set<String> sentRowIds(List<AiLine> rows) {
        Set<String> expected = new LinkedHashSet<>();
        for (AiLine row : rows) {
            if (row != null && row.isSent()) expected.add(row.id);
        }
        return expected;
    }
}
