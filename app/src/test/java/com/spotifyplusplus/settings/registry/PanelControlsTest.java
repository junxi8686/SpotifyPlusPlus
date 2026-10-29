package com.spotifyplusplus.settings.registry;

import com.spotifyplusplus.Settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The panel's section order, row kinds and hand-built rows, stated as registry data. */
public class PanelControlsTest {
    @Test
    public void sectionOrderIsExplicit() {
        List<String> ids = new ArrayList<>();
        for (Settings.Section section : ControlRegistry.panelSections()) ids.add(section.id);
        assertEquals(Arrays.asList(
                "lyrics", "lyrics_sources", "lyrics_screen", "apple_music",
                "transliteration", "translation", "ai", "android_auto", "pip", "backup"), ids);
    }

    @Test
    public void kindsMatchRendererDispatch() {
        assertEquals(ControlSpec.Kind.TOGGLE, kindOf(Settings.TRANSLATION_ENABLED));
        assertEquals(ControlSpec.Kind.STEPPER, kindOf(Settings.SYNC_OFFSET_MS));
        // The translation size is the panel's drag slider: stepping it made every 2-point move
        // read the same on the old duration formatter.
        assertEquals(ControlSpec.Kind.SLIDER, kindOf(Settings.TRANSLATION_TEXT_SIZE));
        assertEquals(ControlSpec.Kind.CHOICE, kindOf(Settings.TAP_SEEK_MODE));
        assertEquals(ControlSpec.Kind.CHOICE, kindOf(Settings.UI_LANGUAGE));
    }

    @Test
    public void compositesStayExplicit() {
        assertEquals("source_row", compositeOf(Settings.LYRICS_SOURCE_MODE));
        assertEquals("spicy_key", compositeOf(Settings.SPICY_ORG_CLIENT_KEY));
        assertNull(compositeOf(Settings.CACHE_SIZE));
        assertNull(compositeOf(Settings.AI_PROVIDER));
    }

    /** The merged source row owns the override and the order: they never render on their own. */
    @Test
    public void theMergedSourceRowOwnsTheOverrideAndTheOrder() {
        assertEquals("source_row", compositeOf(Settings.LYRICS_SOURCE_OVERRIDE));
        assertEquals("source_row", compositeOf(Settings.LYRICS_SOURCE_ORDER));
        for (Settings.Setting<?> setting : new Settings.Setting<?>[]{
                Settings.LYRICS_SOURCE_OVERRIDE, Settings.LYRICS_SOURCE_ORDER}) {
            assertEquals(setting.key + " must not render a row of its own",
                    Visibility.NEVER, visibleWhen(setting));
        }
    }

    /** A setting the layout editor owns has no panel row at all. */
    @Test
    public void editorOwnedSettingsHaveNoPanelRow() {
        assertNull(kindOf(Settings.ANIMATION_STYLE));
        assertNull(kindOf(Settings.FULLSCREEN_CONTROLS));
        assertNull(kindOf(Settings.TRACK_INFO_POSITION));
        assertNull(kindOf(Settings.FOLLOW_CHIP_ICON));
    }

    /** The language pack download is an action row: it stores nothing and names itself. */
    @Test
    public void theLanguagePackDownloadIsAnActionRowWithoutAStoredKey() {
        ControlSpec download = null;
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if ("download_language_models".equals(entry.compositeId)) download = entry;
        }
        assertNotNull("the language pack download must have a panel entry", download);
        assertNull("the download action stores nothing", download.setting);
        assertEquals(Settings.TRANSLITERATION, download.section);
    }

    /** The export/import card is an action row too: it stores nothing and owns its own section. */
    @Test
    public void theBackupCardIsAnActionRowWithoutAStoredKey() {
        ControlSpec backup = null;
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if ("backup_row".equals(entry.compositeId)) backup = entry;
        }
        assertNotNull("the backup card must have a panel entry", backup);
        assertNull("the backup card stores nothing", backup.setting);
        assertEquals(Settings.BACKUP, backup.section);
        assertEquals(Visibility.ALWAYS, backup.visibleWhen);
    }

    /**
     * A panel composite the registry names but the panel cannot render throws
     * {@code IllegalStateException("No panel renderer for composite …")} while the section is being
     * built - that is a crash, not a missing row. A JVM test cannot build the Android views, so the
     * agreement between the two surfaces is pinned at the source level instead: every composite id
     * in the panel inventory must have a dispatch branch in the panel's renderer.
     */
    @Test
    public void everyRegisteredPanelCompositeHasARenderer() throws Exception {
        java.io.File source = new java.io.File(
                "src/main/java/com/spotifyplusplus/settings/panel/SettingsPanel.java");
        if (!source.isFile()) source = new java.io.File("app/" + source.getPath());
        assertTrue("SettingsPanel source not found from "
                        + new java.io.File(".").getAbsolutePath(),
                source.isFile());
        String sourceText = new String(java.nio.file.Files.readAllBytes(source.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        int composites = 0;
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if (entry.kind != ControlSpec.Kind.COMPOSITE) continue;
            composites++;
            assertTrue("no panel renderer branch for composite " + entry.compositeId,
                    sourceText.contains("\"" + entry.compositeId + "\".equals(id)"));
        }
        assertTrue("the panel inventory must contain composites", composites > 0);
    }

    private static ControlSpec entry(Settings.Setting<?> setting) {
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if (entry.setting == setting) return entry;
        }
        return null;
    }

    private static ControlSpec.Kind kindOf(Settings.Setting<?> setting) {
        ControlSpec entry = entry(setting);
        return entry == null ? null : entry.kind;
    }

    private static String compositeOf(Settings.Setting<?> setting) {
        ControlSpec entry = entry(setting);
        assertNotNull(setting.key + " must have a panel entry", entry);
        return entry.compositeId;
    }

    private static Visibility visibleWhen(Settings.Setting<?> setting) {
        ControlSpec entry = entry(setting);
        assertNotNull(setting.key + " must have a panel entry", entry);
        return entry.visibleWhen;
    }
}
