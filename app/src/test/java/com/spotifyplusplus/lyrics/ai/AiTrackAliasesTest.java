package com.spotifyplusplus.lyrics.ai;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AiTrackAliasesTest {
    @Test
    public void aJsonArrayIsTheReplyThePromptAsksFor() {
        List<String> names = AiTrackAliases.parseAliases(
                "[\"\u96e2\u958b\u5730\u7403\u8868\u9762\",\"Leaving the Earth's Surface\"]");
        assertEquals(2, names.size());
        assertEquals("\u96e2\u958b\u5730\u7403\u8868\u9762", names.get(0));
        assertEquals("Leaving the Earth's Surface", names.get(1));
    }

    @Test
    public void aFencedJsonArrayIsStillRead() {
        List<String> names = AiTrackAliases.parseAliases(
                "```json\n[\"one\", \"two\"]\n```");
        assertEquals(java.util.Arrays.asList("one", "two"), names);
    }

    @Test
    public void aBulletedListIsAcceptedRatherThanThrownAway() {
        // A model that ignored the format has still answered the question; discarding it would
        // cost a second paid call to learn the same thing.
        List<String> names = AiTrackAliases.parseAliases("- one\n- two\n* three");
        assertEquals(java.util.Arrays.asList("one", "two", "three"), names);
    }

    @Test
    public void aNumberedListIsAcceptedToo() {
        List<String> names = AiTrackAliases.parseAliases("1. one\n2) two");
        assertEquals(java.util.Arrays.asList("one", "two"), names);
    }

    @Test
    public void duplicatesAndBlankEntriesAreDropped() {
        List<String> names = AiTrackAliases.parseAliases("[\"one\",\"One\",\"\",\"  \",\"two\"]");
        assertEquals(java.util.Arrays.asList("one", "two"), names);
    }

    @Test
    public void aSentenceInsteadOfATitleIsRejected() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 40; i++) long_.append("word ");
        List<String> names = AiTrackAliases.parseAliases("[\"" + long_ + "\",\"ok\"]");
        assertEquals(java.util.Collections.singletonList("ok"), names);
    }

    @Test
    public void theListIsCappedSoOneAnswerCannotFloodASearch() {
        List<String> names = AiTrackAliases.parseAliases("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]");
        assertEquals(4, names.size());
    }

    @Test
    public void garbageIsAnEmptyAnswerNotAnException() {
        assertTrue(AiTrackAliases.parseAliases("").isEmpty());
        assertTrue(AiTrackAliases.parseAliases(null).isEmpty());
        assertTrue(AiTrackAliases.parseAliases("[ not json").isEmpty());
    }

    @Test
    public void openAiReplyIsUnwrapped() {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"[\\\"one\\\",\\\"two\\\"]\"}}]}";
        assertEquals("[\"one\",\"two\"]", AiTextCall.openAiText(body));
    }

    @Test
    public void geminiReplyIsUnwrapped() {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[\\\"one\\\"]\"}]}}]}";
        assertEquals("[\"one\"]", AiTextCall.geminiText(body));
    }

    @Test
    public void anErrorBodyUnwrapsToNothingRatherThanThrowing() {
        assertEquals("", AiTextCall.openAiText("{\"error\":{\"message\":\"quota\"}}"));
        assertEquals("", AiTextCall.geminiText("{\"error\":{\"message\":\"quota\"}}"));
        assertEquals("", AiTextCall.openAiText("not json at all"));
    }
}
