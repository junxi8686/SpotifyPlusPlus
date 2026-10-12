package com.spotifyplusplus.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What a stored paid answer has to prove before it is replayed, resumed or trusted.
 *
 * <p>The record's own status says what an earlier run believed. These pin the checks that decide
 * whether that belief still holds now: the text passes the per-row contract, the chunk coverage is
 * exactly the rows that were sent, a retained plan still matches the current one, and a fallback is
 * only honoured for a row some terminal chunk actually proved it could not satisfy. Every one of
 * them is about not displaying - or paying for - an answer that no longer belongs to this document.
 */
public class AiPaidRecordRecoveryTest {
    private static List<AiLine> rows() {
        List<AiLine> rows = new ArrayList<>();
        rows.add(AiLine.of("r0#a", "Hello", null, false));
        rows.add(AiLine.of("r1#b", "Hello / World", null, false));
        return rows;
    }

    private static AiPaidRecord completeRecord() {
        AiPaidRecord record = new AiPaidRecord(LayerKind.MEANING, "digest", "cfg", "openai",
                "model", "zh", "", "", AiContract.schemaFor(LayerKind.MEANING),
                AiContract.CHUNK_PLAN_VERSION);
        record.status = AiPaidRecord.Status.COMPLETE;
        record.putItem("r0#a", "你好");
        record.putItem("r1#b", "你好 / 世界");
        AiChunkRecord chunk = new AiChunkRecord(
                Arrays.asList("r0#a~0", "r1#b~0"), "{}");
        chunk.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0", chunk);
        return record;
    }

    @Test
    public void aSavedResultIsReusedOnlyWhileItsTextStillPasses() {
        AiPaidRecord record = completeRecord();
        List<AiLine> rows = rows();

        assertTrue(record.hasCompleteItemsForRows(rows));
        assertTrue(record.isCompleteForRows(rows));

        // A control character can never be part of a one-row item.
        record.putItem("r0#a", "你好\n");
        assertFalse("forbidden text", record.hasCompleteItemsForRows(rows));
        record.putItem("r0#a", "你好");

        // The stored text must still have the source's segment shape: one row, one segment here.
        record.putItem("r0#a", "你好 / 再见");
        assertFalse("segment mismatch", record.hasCompleteItemsForRows(rows));
        record.putItem("r0#a", "你好");

        // A blank answer is only acceptable for a row that is not ordinary.
        record.putItem("r0#a", "");
        assertFalse("blank ordinary row", record.hasCompleteItemsForRows(rows));
        record.putItem("r0#a", "你好");

        // An item for a row that was not sent is not this document's answer.
        record.putItem("r9#z", "别的");
        assertFalse("unknown row", record.hasCompleteItemsForRows(rows));
    }

    @Test
    public void chunkCoverageMustMatchTheRowsThatWereSent() {
        AiPaidRecord record = completeRecord();
        List<AiLine> rows = rows();

        record.putChunk("C0", new AiChunkRecord(Collections.singletonList("r0#a~0"), "{}"));
        record.chunk("C0").status = AiChunkRecord.Status.COMPLETE;
        assertFalse("a row with no chunk behind it is not complete",
                record.isCompleteForRows(rows));

        AiChunkRecord pending = new AiChunkRecord(Arrays.asList("r0#a~0", "r1#b~0"), "{}");
        record.putChunk("C0", pending);
        assertFalse("a pending chunk cannot back a completed record", record.isCompleteForRows(rows));

        AiChunkRecord duplicated = new AiChunkRecord(Arrays.asList("r0#a~0", "r0#a~0"), "{}");
        duplicated.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0", duplicated);
        assertFalse("duplicate ids prove nothing", record.isCompleteForRows(rows));
    }

    @Test
    public void aFallbackIsHonouredOnlyForARowItsChunkProved() {
        AiPaidRecord record = new AiPaidRecord(LayerKind.MEANING, "digest", "cfg", "openai",
                "model", "zh", "", "", AiContract.schemaFor(LayerKind.MEANING),
                AiContract.CHUNK_PLAN_VERSION);
        record.status = AiPaidRecord.Status.PARTIAL;
        record.putItem("r0#a", "你好");
        AiChunkRecord chunk = new AiChunkRecord(Arrays.asList("r0#a~0", "r1#b~0"), "{}");
        chunk.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
        chunk.setFallbackRowIds(Collections.singletonList("r1#b"));
        record.putChunk("C0", chunk);
        AiChunkPlan plan = plan(chunkIds("C0"), "r0#a~0", "r1#b~0");

        assertTrue(record.hasTerminalFallback(plan));
        assertTrue(record.hasTerminalOutput(plan));
        assertTrue(record.hasTerminalRoots(plan));
        assertTrue("the row the chunk proved it could not satisfy", record.permitsFallback("r1#b"));
        assertFalse("a row it did satisfy is not a fallback", record.permitsFallback("r0#a"));

        // The same status without the omission it claims is an unfinished claim, not a proof.
        chunk.setFallbackRowIds(null);
        assertFalse("unproven fallback", record.hasTerminalOutput(plan));
        assertFalse(record.permitsFallback("r1#b"));

        // And a fallback row outside the chunk's own ids proves nothing about any row.
        chunk.setFallbackRowIds(Collections.singletonList("r9#z"));
        assertFalse(record.hasTerminalOutput(plan));
    }

    @Test
    public void aRetainedPlanThatNoLongerMatchesIsNotResumed() {
        AiPaidRecord record = completeRecord();
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = rows();

        assertTrue(record.hasValidRetainedPlan(plan(chunkIds("C0"), "r0#a~0", "r1#b~0"), input));
        assertFalse("another planner version plans different boundaries",
                record.hasValidRetainedPlan(plan(AiContract.CHUNK_PLAN_VERSION + 1, chunkIds("C0"),
                        "r0#a~0", "r1#b~0"), input));
        assertFalse("a chunk holding other ids would map onto rows it never saw",
                record.hasValidRetainedPlan(plan(chunkIds("C1"), "r0#a~0", "r1#b~0"), input));
    }

    private static List<String> chunkIds(String... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    private static AiChunkPlan plan(List<String> chunkIds, String... itemIds) {
        return plan(AiContract.CHUNK_PLAN_VERSION, chunkIds, itemIds);
    }

    private static AiChunkPlan plan(int version, List<String> chunkIds, String... itemIds) {
        List<AiRequestItem> items = new ArrayList<>();
        for (String id : itemIds) {
            items.add(new AiRequestItem(id, AiLineClass.ORDINARY, null, "source", null));
        }
        List<AiPlannedChunk> chunks = new ArrayList<>();
        for (String id : chunkIds) {
            chunks.add(new AiPlannedChunk(id, null, "", items, Collections.<String>emptyList(),
                    "{}", 10, 10, 10));
        }
        return new AiChunkPlan(version, chunks, itemIds.length, 20);
    }
}
