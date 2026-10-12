package com.spotifyplusplus.settings;

import com.google.gson.JsonObject;
import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SpotifyPlusConfig;
import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the file carries that only made sense on the device that wrote it.
 *
 * <p>Two of those are pinned here: the custom font's absolute path, which travels as text while the
 * font file itself travels under {@code files/}, and the base64 of a database, which must be
 * produced without ever holding the whole file in memory twice. The credentials have their own test.
 */
public class SpicyBackupPortabilityTest {

    private final FakeAndroidContext context = new FakeAndroidContext();

    // --- the custom font path -----------------------------------------------------------------

    @Test
    public void aRestoredFontPathIsRepointedAtThisInstallsCopy() throws IOException {
        File fontDir = tempDir("fonts");
        File font = write(fontDir, "Inter-1700000000000.ttf", 64);
        String foreignUserPath =
                "/data/user/0/com.spotify.music/files/spicyex_fonts/Inter-1700000000000.ttf";

        assertEquals(font.getAbsolutePath(), SpicyBackup.repairedFontPath(foreignUserPath, fontDir));
    }

    @Test
    public void aFontPathThatStillResolvesIsLeftAlone() throws IOException {
        File fontDir = tempDir("fonts");
        File font = write(fontDir, "Inter.ttf", 16);

        assertEquals(font.getAbsolutePath(),
                SpicyBackup.repairedFontPath(font.getAbsolutePath(), fontDir));
    }

    @Test
    public void aMissingFontIsLeftAloneRatherThanPointedAtSomethingElse() throws IOException {
        File fontDir = tempDir("fonts");
        write(fontDir, "Other.ttf", 16);

        String absent = "/data/user/0/com.spotify.music/files/spicyex_fonts/Absent.ttf";
        assertEquals(absent, SpicyBackup.repairedFontPath(absent, fontDir));
    }

    @Test
    public void aFamilyNameOrForeignPathIsNeverRewritten() throws IOException {
        File fontDir = tempDir("fonts");
        write(fontDir, "serif", 8);

        assertEquals("sans-serif-medium",
                SpicyBackup.repairedFontPath("sans-serif-medium", fontDir));
        assertEquals("/system/fonts/Roboto.ttf",
                SpicyBackup.repairedFontPath("/system/fonts/Roboto.ttf", fontDir));
        // Only this module's own directory is understood, so nothing outside it is touched.
        assertEquals("/storage/emulated/0/Download/serif",
                SpicyBackup.repairedFontPath("/storage/emulated/0/Download/serif", fontDir));
    }

    @Test
    public void traversalOutOfTheFontDirectoryIsRefused() {
        assertNull(SpicyBackup.managedFontName("/data/user/0/pkg/files/spicyex_fonts/../other.ttf"));
        assertNull(SpicyBackup.managedFontName("/data/user/0/pkg/files/spicyex_fonts/deep/x.ttf"));
        assertNull(SpicyBackup.managedFontName("/data/user/0/pkg/files/spicyex_fonts/"));
        assertNull(SpicyBackup.managedFontName(""));
        assertEquals("x.ttf",
                SpicyBackup.managedFontName("/data/user/0/pkg/files/spicyex_fonts/x.ttf"));
    }

    @Test
    public void aRestoreRepairsTheStoredFontPath() throws IOException {
        // The font file is restored under this install's own files directory, as the archive
        // writes it; the setting that names it still carries the exporting device's path.
        File restored = write(new File(context.getFilesDir(), SpicyBackup.FONT_DIR),
                "Inter-1700000000000.ttf", 32);
        JsonObject settings = new JsonObject();
        settings.add(Settings.LYRICS_FONT_CUSTOM_PATH.key, cell("s",
                "/data/user/0/com.spotify.music/files/spicyex_fonts/Inter-1700000000000.ttf"));
        JsonObject stores = new JsonObject();
        stores.add(SpotifyPlusConfig.PREFS_NAME, settings);

        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.add("stores", stores);

        SpicyBackup.applyRestore(context, root.toString(), null, null);

        assertEquals(restored.getAbsolutePath(), context.file(SpotifyPlusConfig.PREFS_NAME)
                .getString(Settings.LYRICS_FONT_CUSTOM_PATH.key, ""));
    }

    @Test
    public void aFontRowDoesNotClaimCoverageForAFileThatIsNotThere() throws IOException {
        File present = write(tempDir("fonts"), "Here.ttf", 8);

        assertTrue(FontPathState.isMissingFile(
                "/data/user/0/com.spotify.music/files/spicyex_fonts/Absent.ttf"));
        assertFalse(FontPathState.isMissingFile(present.getAbsolutePath()));
        // A family name is a legitimate value and can never be missing.
        assertFalse(FontPathState.isMissingFile("sans-serif-medium"));
        assertFalse(FontPathState.isMissingFile("serif"));
        assertFalse(FontPathState.isMissingFile(""));
        assertFalse(FontPathState.isMissingFile(null));
    }

    // --- the streamed database encoding -------------------------------------------------------

    @Test
    public void streamedBase64IsIdenticalToEncodingTheWholeFile() throws IOException {
        assertEncodedEqualsWholeFile(0);
        assertEncodedEqualsWholeFile(3);
        assertEncodedEqualsWholeFile(48 * 1024);
        assertEncodedEqualsWholeFile(48 * 1024 + 1);
        assertEncodedEqualsWholeFile(100_000);
        assertEncodedEqualsWholeFile(200_001);
    }

    /** Nothing here is a format change: the version a release writes stays 2. */
    @Test
    public void theWrittenFormatVersionIsUnchanged() {
        assertEquals(2, SpicyBackup.FORMAT_VERSION);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static void assertEncodedEqualsWholeFile(int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random(size + 7L).nextBytes(bytes);
        File file = write(tempDir("db"), "sample-" + size + ".db", bytes);

        StringWriter out = new StringWriter();
        SpicyBackup.writeBase64(file, out);

        assertEquals("size " + size, Base64.getEncoder().encodeToString(bytes), out.toString());
    }

    private static JsonObject cell(String type, String value) {
        JsonObject cell = new JsonObject();
        cell.addProperty("t", type);
        cell.addProperty("v", value);
        return cell;
    }

    private static File tempDir(String prefix) throws IOException {
        File dir = Files.createTempDirectory("spicyex-" + prefix).toFile();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(dir)));
        return dir;
    }

    private static File write(File dir, String name, int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return write(dir, name, bytes);
    }

    private static File write(File dir, String name, byte[] bytes) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File file = new File(dir, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
    }
}
