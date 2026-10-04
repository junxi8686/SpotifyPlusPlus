package com.spotifyplusplus.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences.RankingMode;
import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences.Source;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Source-commit contract: values, order, and ordering of the two namespaces. */
public class SourcePreferencesAdapterTest {
    static final class RecordingSink implements SourcePreferencesAdapter.Sink {
        final List<String> calls = new ArrayList<>();
        RankingMode rankingMode;
        List<Source> order;
        final Map<Source, Boolean> enabled = new EnumMap<>(Source.class);

        @Override
        public void setRankingMode(RankingMode mode) {
            calls.add("rankingMode");
            rankingMode = mode;
        }

        @Override
        public void setSourceOrder(List<Source> order) {
            calls.add("sourceOrder");
            this.order = new ArrayList<>(order);
        }

        @Override
        public void setSourceEnabled(Source source, boolean enabled) {
            calls.add("enabled:" + source.id);
            this.enabled.put(source, enabled);
        }
    }

    @Test
    public void commitOrdersOrdinaryValuesBeforeSourceNamespace() {
        SettingsWriterTest.FakeStore store = new SettingsWriterTest.FakeStore();
        RecordingSink sink = new RecordingSink();
        Map<Source, Boolean> enabled = new EnumMap<>(Source.class);
        enabled.put(Source.APPLE_MUSIC, true);
        enabled.put(Source.SPOTIFY, false);
        enabled.put(Source.LRCLIB, true);
        new SourcePreferencesAdapter(sink).commit(new SettingsWriter(store),
                new SourcePreferencesAdapter.Commit("Source order",
                        Arrays.asList(Source.SPOTIFY, Source.APPLE_MUSIC, Source.LRCLIB),
                        enabled));
        assertEquals("Auto", store.values.get(Settings.LYRICS_SOURCE_OVERRIDE.key));
        assertEquals("Source order", store.values.get(Settings.LYRICS_SOURCE_MODE.key));
        assertEquals(RankingMode.SOURCE_ORDER, sink.rankingMode);
        assertEquals(Arrays.asList(Source.SPOTIFY, Source.APPLE_MUSIC, Source.LRCLIB), sink.order);
        assertEquals(Boolean.TRUE, sink.enabled.get(Source.APPLE_MUSIC));
        assertEquals(Boolean.FALSE, sink.enabled.get(Source.SPOTIFY));
        assertEquals(Boolean.TRUE, sink.enabled.get(Source.LRCLIB));
        // Absent means the panel never showed this source a switch, so nothing is written for it.
        //
        // This used to assert Boolean.FALSE, commented "missing entries default to disabled" - and
        // that is exactly the bug the owner kept reporting: the panel renders a switch for only
        // some sources, so every save wrote "off" for the rest, and a source turned on was silently
        // turned back off by the act of saving.
        assertNull(sink.enabled.get(Source.SPICY));
        assertNull(sink.enabled.get(Source.AMLL));
        assertEquals(Arrays.asList("rankingMode", "sourceOrder",
                "enabled:apple", "enabled:spotify", "enabled:lrclib"),
                sink.calls);
    }

    @Test
    public void commitParsesAutoRanking() {
        SettingsWriterTest.FakeStore store = new SettingsWriterTest.FakeStore();
        RecordingSink sink = new RecordingSink();
        new SourcePreferencesAdapter(sink).commit(new SettingsWriter(store),
                new SourcePreferencesAdapter.Commit("Auto",
                        Arrays.asList(Source.APPLE_MUSIC),
                        new EnumMap<Source, Boolean>(Source.class)));
        assertEquals(RankingMode.AUTO, sink.rankingMode);
    }
}
