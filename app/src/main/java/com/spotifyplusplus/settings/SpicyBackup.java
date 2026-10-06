package com.spotifyplusplus.settings;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.spotifyplusplus.SpotifyPlusConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything this module stores, as one file, and the way back from it.
 *
 * <p>The module keeps its state in Spotify's own preferences, so clearing Spotify's data or moving
 * to a new install loses every setting, every source choice and the saved lyric cache - and the
 * owner rebuilds all of it by hand. This is the answer to that: one JSON file in Downloads, and a
 * restore that merges it back.
 *
 * <p>Two stores are covered. {@code SpotifyPlus} holds the settings and the catalogue's selection
 * state; {@code SpotifyPlusAiCredentials} holds the API key, encrypted - the ciphertext is copied
 * verbatim and stays readable, because the key that unwraps it lives in the Keystore under
 * Spotify's own UID on this device and is not part of the backup. That is the intended behaviour
 * and the reason restoring on a different device cannot leak the key to it.
 *
 * <p>The lyric cache is deliberately not included: it is a download, it is by far the largest
 * thing here, and the module refetches whatever the owner actually plays.
 *
 * <p>Android-free except for the two preference reads and the Downloads handoff, so the encoding is
 * testable off-device.
 */
public final class SpicyBackup {
    public static final String FILE_NAME = "SpotifyPlusPlus-backup.json";
    /** Where this build writes. */
    private static final String FOLDER = "Spotify++";
    /**
     * The name and folder earlier builds used, still read so an export made before the rename is
     * not orphaned - the restore-from-Downloads path looks files up by name.
     */
    private static final String LEGACY_FILE_NAME = "SpicyEX-backup.json";
    /** Written into every snapshot; a restore from a newer file is refused rather than guessed. */
    public static final int FORMAT_VERSION = 2;

    private static final String AI_PREFS = "SpotifyPlusAiCredentials";
    /** Source order, per-track override and ranking mode. */
    private static final String SOURCE_SELECTION_PREFS = "SpotifyPlusLyricsSourceSelection";
    /** The catalogue: which sources each track has candidates from, and how they matched. */
    private static final String CATALOG_PREFS = "SpotifyPlusCanonicalSourceCache";
    /** SpicyLyrics.org client-key revision, so a restored key invalidates its old results. */
    private static final String SPICY_KEY_STATE_PREFS = "SpotifyPlusSpicyOrgKeyState";
    /** SpicyLyrics.org access loss/restore record. */
    private static final String SPICY_ACCESS_STATE_PREFS = "SpotifyPlusSpicyOrgAccessState";
    /** Remote lyric responses, so a restore does not re-download every one of them. */
    private static final String RESPONSE_CACHE_PREFS = "SpotifyPlusLyricsResponseCache";
    /** How long SpicyLyrics.org results are kept. */
    private static final String ORG_RETENTION_PREFS = "spicy-org-retention";
    /** The Android Auto prototype switch. */
    private static final String AUTO_PROTOTYPE_PREFS = "auto-prototype";

    /**
     * Every preference file a backup carries.
     *
     * <p>Two were covered and that was not enough to restore an install: the source order and the
     * per-track override live in their own file, and the catalogue's prefs hold a legacy cache.
     * Restoring only the settings brought back a configured app with no source selection, so
     * everything had to be searched and chosen again.
     *
     * <p>Diagnostic drafts, capture buffers and the request breaker are deliberately absent: they
     * are transient state about something in progress, not something to move between installs.
     */
    private static final String[] STORES = {
            SpotifyPlusConfig.PREFS_NAME,
            AI_PREFS,
            SOURCE_SELECTION_PREFS,
            CATALOG_PREFS,
            SPICY_KEY_STATE_PREFS,
            SPICY_ACCESS_STATE_PREFS,
            RESPONSE_CACHE_PREFS,
            ORG_RETENTION_PREFS,
            AUTO_PROTOTYPE_PREFS,
    };

    /**
     * Whole databases, copied byte for byte.
     *
     * <p>These are application data, not cache, and none of them was covered before. The catalogue
     * owns the owner's per-track selections, the accepted lyric documents and every provider
     * outcome - its own documentation says it has no TTL and no eviction. The cache database holds
     * translations and readings, which cost money to produce through the AI providers. The ledger
     * records what was spent.
     *
     * <p>Copying the file is the only way to carry all of that without a table-by-table
     * serialiser that would silently drop any column added later.
     */
    private static final String[] DATABASES = {
            "LyricsCatalog.db",
            "SpicyLyricCaches.db",
            "SpicyPaidAiLedger.db",
    };

    /**
     * Owner data under {@code files/}.
     *
     * <p>The language model pack is deliberately absent: it is a re-downloadable asset many
     * megabytes wide, and the settings that point at it already travel with the backup.
     */
    private static final String[] FILE_DIRS = {
            "spicyex_fonts",
    };

    private SpicyBackup() {
    }

    /** Returned when the owner cancelled; distinct from -1, which means the file was unusable. */
    public static final int CANCELLED = -2;

    /**
     * Progress and cancellation for one export or restore.
     *
     * <p>Called from whatever thread runs the work, which is never the main thread for a real
     * export: the catalogue and the caches are copied byte for byte, and doing that on the UI
     * thread would freeze the panel for as long as it takes.
     */
    public interface Progress {
        /** @param done units finished so far, {@code total} the units expected in all */
        void onProgress(int done, int total);

        /** Polled between units; a cancelled run stops without writing anything further. */
        boolean isCancelled();
    }

    /** The whole backup as JSON text. Pure given the stores; no file or network involved. */
    public static String encode(Map<String, ?> settings, Map<String, ?> credentials) {
        JsonObject stores = new JsonObject();
        stores.add(SpotifyPlusConfig.PREFS_NAME, encodeStore(settings));
        stores.add(AI_PREFS, encodeStore(credentials));
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("exportedAt", System.currentTimeMillis());
        root.add("stores", stores);
        return root.toString();
    }

    /** Every store this module owns, as one document. */
    public static String encodeAll(Context context) {
        return encodeAll(context, null);
    }

    /**
     * Every store this module owns, as one document, reporting progress as it goes.
     *
     * @return null when the owner cancelled
     */
    public static String encodeAll(Context context, Progress progress) {
        JsonObject stores = new JsonObject();
        int total = STORES.length + DATABASES.length + countFiles(context);
        int done = 0;
        for (String name : STORES) {
            if (cancelled(progress)) return null;
            stores.add(name, encodeStore(context == null ? null : prefs(context, name).getAll()));
            report(progress, ++done, total);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("exportedAt", System.currentTimeMillis());
        root.add("stores", stores);
        JsonObject databases = new JsonObject();
        if (context != null) {
            for (String name : DATABASES) {
                if (cancelled(progress)) return null;
                String encoded = readDatabaseBase64(context, name);
                if (encoded != null && !encoded.isEmpty()) databases.addProperty(name, encoded);
                report(progress, ++done, total);
            }
        }
        root.add("databases", databases);
        JsonObject files = new JsonObject();
        if (context != null && !encodeFiles(context, files, progress, done, total)) return null;
        root.add("files", files);
        return root.toString();
    }

    private static boolean encodeFiles(Context context, JsonObject out, Progress progress,
                                       int doneBase, int total) {
        File root = context.getFilesDir();
        if (root == null) return true;
        int done = doneBase;
        for (String dirName : FILE_DIRS) {
            File[] children = new File(root, dirName).listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child == null || !child.isFile()) continue;
                if (cancelled(progress)) return false;
                byte[] bytes = readAllBytes(child);
                if (bytes != null && bytes.length > 0) {
                    out.addProperty(dirName + "/" + child.getName(),
                            Base64.encodeToString(bytes, Base64.NO_WRAP));
                }
                report(progress, ++done, total);
            }
        }
        return true;
    }

    private static int countFiles(Context context) {
        File root = context == null ? null : context.getFilesDir();
        if (root == null) return 0;
        int count = 0;
        for (String dirName : FILE_DIRS) {
            File[] children = new File(root, dirName).listFiles();
            if (children == null) continue;
            for (File child : children) if (child != null && child.isFile()) count++;
        }
        return count;
    }

    private static void report(Progress progress, int done, int total) {
        if (progress == null) return;
        try {
            progress.onProgress(done, total);
        } catch (Throwable ignored) {
        }
    }

    private static boolean cancelled(Progress progress) {
        if (progress == null) return false;
        try {
            return progress.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Restores every store the document carries.
     *
     * <p>A store the file omits is left untouched rather than cleared, so a backup written before
     * the catalogue was covered still restores everything it does contain. The same is true of the
     * databases and files: a version-1 document carries neither and simply restores its stores.
     *
     * @return how many entries were written, or -1 when the text is not a usable backup
     */
    public static int decodeAll(Context context, String json) {
        return decodeAll(context, json, null);
    }

    public static int decodeAll(Context context, String json, Progress progress) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) return -1;
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            return -1;
        }
        try {
            JsonElement version = root.get("version");
            if (version == null || version.getAsInt() < 1
                    || version.getAsInt() > FORMAT_VERSION) return -1;
        } catch (Throwable t) {
            return -1;
        }
        JsonElement stores = root.get("stores");
        if (stores == null || !stores.isJsonObject() || context == null) return -1;
        JsonElement databases = root.get("databases");
        JsonElement files = root.get("files");
        int total = STORES.length + DATABASES.length + countFileEntries(files);
        int done = 0;
        int written = 0;
        for (String name : STORES) {
            if (cancelled(progress)) return CANCELLED;
            written += decodeStore(stores.getAsJsonObject().get(name), prefs(context, name));
            report(progress, ++done, total);
        }
        if (cancelled(progress)) return CANCELLED;
        // Handles are released before anything is written: an open connection keeps the process
        // on the old pages of the file being replaced.
        closeDatabaseHandles();
        for (String name : DATABASES) {
            if (cancelled(progress)) return CANCELLED;
            if (databases != null && databases.isJsonObject()) {
                written += decodeOneDatabase(context, name, databases.getAsJsonObject().get(name));
            }
            report(progress, ++done, total);
        }
        if (files != null && files.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : files.getAsJsonObject().entrySet()) {
                if (cancelled(progress)) return CANCELLED;
                written += decodeOneFile(context, entry.getKey(), entry.getValue());
                report(progress, ++done, total);
            }
        }
        return written;
    }

    private static int countFileEntries(JsonElement files) {
        if (files == null || !files.isJsonObject()) return 0;
        return files.getAsJsonObject().size();
    }

    private static int decodeOneDatabase(Context context, String name, JsonElement cell) {
        if (cell == null || !cell.isJsonPrimitive()) return 0;
        byte[] bytes;
        try {
            bytes = Base64.decode(cell.getAsString(), Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return 0;
        }
        if (bytes == null || bytes.length == 0) return 0;
        return writeDatabase(context, name, bytes) ? 1 : 0;
    }

    private static int decodeOneFile(Context context, String relative, JsonElement cell) {
        File root = context.getFilesDir();
        // Only the declared directories, and never a name that climbs out of one.
        if (root == null || relative == null || relative.contains("..")
                || cell == null || !cell.isJsonPrimitive()) return 0;
        int slash = relative.indexOf('/');
        if (slash <= 0) return 0;
        String dirName = relative.substring(0, slash);
        String fileName = relative.substring(slash + 1);
        if (!declaredFileDir(dirName) || fileName.isEmpty() || fileName.contains("/")) return 0;
        byte[] bytes;
        try {
            bytes = Base64.decode(cell.getAsString(), Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return 0;
        }
        if (bytes == null || bytes.length == 0) return 0;
        try {
            File dir = new File(root, dirName);
            if (!dir.isDirectory() && !dir.mkdirs()) return 0;
            try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
                out.write(bytes);
            }
            return 1;
        } catch (Throwable ignored) {
            // One unwritable file must not abandon the rest of the restore.
            return 0;
        }
    }

    private static boolean declaredFileDir(String name) {
        for (String declared : FILE_DIRS) if (declared.equals(name)) return true;
        return false;
    }

    /** Releases the cached connections of every database a restore may overwrite. */
    private static void closeDatabaseHandles() {
        try {
            com.spotifyplusplus.lyrics.catalog.CatalogStore.closeForRestore();
        } catch (Throwable ignored) {
        }
        try {
            com.spotifyplusplus.lyrics.cache.SpicyCacheStore.closeForRestore();
        } catch (Throwable ignored) {
        }
        try {
            com.spotifyplusplus.lyrics.session.AIPaidArtifactCache.closeForRestore();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The database file as base64, or null when it does not exist yet.
     *
     * <p>The write-ahead log is folded in first: a database copied while it still has a WAL beside
     * it is missing whatever that log holds.
     */
    private static String readDatabaseBase64(Context context, String name) {
        try {
            File path = context.getDatabasePath(name);
            if (path == null || !path.isFile()) return null;
            try {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(path.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READWRITE);
                try {
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close();
                } finally {
                    db.close();
                }
            } catch (Throwable ignored) {
                // A checkpoint that fails still leaves a readable main file.
            }
            byte[] bytes = readAllBytes(path);
            return bytes == null || bytes.length == 0
                    ? null : Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeDatabase(Context context, String name, byte[] bytes) {
        try {
            File target = context.getDatabasePath(name);
            if (target == null) return false;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            // The sidecars describe the file being replaced. Left behind, SQLite would replay a
            // stale log over the restored bytes.
            new File(target.getPath() + "-wal").delete();
            new File(target.getPath() + "-shm").delete();
            new File(target.getPath() + "-journal").delete();
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static byte[] readAllBytes(File file) {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.min(file.length(), 1 << 20));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject encodeStore(Map<String, ?> values) {        JsonObject store = new JsonObject();
        if (values == null) return store;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            JsonObject cell = new JsonObject();
            if (value instanceof Boolean) {
                cell.addProperty("t", "b");
                cell.addProperty("v", (Boolean) value);
            } else if (value instanceof String) {
                cell.addProperty("t", "s");
                cell.addProperty("v", (String) value);
            } else if (value instanceof Integer) {
                cell.addProperty("t", "i");
                cell.addProperty("v", (Integer) value);
            } else if (value instanceof Long) {
                cell.addProperty("t", "l");
                cell.addProperty("v", (Long) value);
            } else if (value instanceof Float) {
                cell.addProperty("t", "f");
                cell.addProperty("v", (Float) value);
            } else if (value instanceof Set) {
                JsonArray list = new JsonArray();
                for (Object item : (Set<?>) value) list.add(String.valueOf(item));
                cell.addProperty("t", "set");
                cell.add("v", list);
            } else {
                // An unknown type is skipped rather than guessed at: a wrong write is worse than a
                // missing entry, because it silently changes a setting to something never chosen.
                continue;
            }
            store.add(entry.getKey(), cell);
        }
        return store;
    }

    /**
     * Writes {@code json}'s entries into both stores, replacing same-named keys and leaving every
     * other key alone.
     *
     * @return how many entries were written, or -1 when the text is not a usable backup
     */
    public static int decodeInto(String json, SharedPreferences settings, SharedPreferences credentials) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) return -1;
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            return -1;
        }
        // Inside the guard, and bounded below as well: this used to sit outside the try, so a
        // file whose "version" was a string or an object threw straight out of here and the
        // caller turned it into no feedback whatsoever - the owner tapped Import and saw
        // nothing happen. A version below 1 is not a format this code ever wrote.
        try {
            JsonElement version = root.get("version");
            if (version == null || version.getAsInt() < 1
                    || version.getAsInt() > FORMAT_VERSION) return -1;
        } catch (Throwable t) {
            return -1;
        }
        JsonElement stores = root.get("stores");
        if (stores == null || !stores.isJsonObject()) return -1;
        int written = 0;
        written += decodeStore(stores.getAsJsonObject().get(SpotifyPlusConfig.PREFS_NAME), settings);
        written += decodeStore(stores.getAsJsonObject().get(AI_PREFS), credentials);
        return written;
    }

    private static int decodeStore(JsonElement element, SharedPreferences target) {
        if (element == null || !element.isJsonObject() || target == null) return 0;
        SharedPreferences.Editor editor = target.edit();
        int written = 0;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement cellElement = entry.getValue();
            if (cellElement == null || !cellElement.isJsonObject()) continue;
            JsonObject cell = cellElement.getAsJsonObject();
            JsonElement typeElement = cell.get("t");
            JsonElement value = cell.get("v");
            if (typeElement == null || value == null) continue;
            String type = typeElement.getAsString();
            try {
                switch (type) {
                    case "b":
                        editor.putBoolean(entry.getKey(), value.getAsBoolean());
                        break;
                    case "s":
                        editor.putString(entry.getKey(), value.getAsString());
                        break;
                    case "i":
                        editor.putInt(entry.getKey(), value.getAsInt());
                        break;
                    case "l":
                        editor.putLong(entry.getKey(), value.getAsLong());
                        break;
                    case "f":
                        editor.putFloat(entry.getKey(), value.getAsFloat());
                        break;
                    case "set": {
                        Set<String> items = new HashSet<>();
                        if (value.isJsonArray()) {
                            for (JsonElement item : value.getAsJsonArray()) {
                                items.add(item.getAsString());
                            }
                        }
                        editor.putStringSet(entry.getKey(), items);
                        break;
                    }
                    default:
                        continue;
                }
                written++;
            } catch (Throwable ignored) {
                // One unreadable entry must not abandon the other few hundred.
            }
        }
        editor.apply();
        return written;
    }

    /** Snapshots this install and writes it to {@code Downloads/Spicy EX}. Null on failure. */
    public static String exportToDownloads(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String json = encodeAll(context);
        Uri uri = null;
        boolean saved = false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    context.getContentResolver().openOutputStream(uri), StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, ready, null, null);
            saved = true;
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + FILE_NAME;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (!saved && uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                    // Best-effort cleanup of a half-written export.
                }
            }
        }
    }

    /** Writes the snapshot to a document the owner picked. -1 when the write fails. */
    public static int writeTo(Context context, Uri target) {
        return writeTo(context, target, null);
    }

    /** As {@link #writeTo(Context, Uri)}, reporting progress; {@link #CANCELLED} if cancelled. */
    public static int writeTo(Context context, Uri target, Progress progress) {
        if (context == null || target == null) return -1;
        String json = encodeAll(context, progress);
        if (json == null) return CANCELLED;
        try (OutputStreamWriter writer = new OutputStreamWriter(
                context.getContentResolver().openOutputStream(target, "wt"),
                StandardCharsets.UTF_8)) {
            writer.write(json);
            return settingsCount(context);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Restores from a document the owner picked. -1 when it is not a usable backup. */
    public static int restoreFrom(Context context, Uri source) {
        return restoreFrom(context, source, null);
    }

    /** As {@link #restoreFrom(Context, Uri)}, reporting progress; {@link #CANCELLED} if cancelled. */
    public static int restoreFrom(Context context, Uri source, Progress progress) {
        if (context == null || source == null) return -1;
        String json;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) return -1;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return -1;
        }
        return decodeAll(context, json, progress);
    }

    /**
     * How many entries the export would carry, used only for the confirmation message.
     *
     * <p>Counts a database and a stored file as one entry each, so the number the owner sees moves
     * when the catalogue grows rather than only when a setting changes.
     */
    public static int settingsCount(Context context) {
        if (context == null) return 0;
        int total = 0;
        for (String name : STORES) total += prefs(context, name).getAll().size();
        for (String name : DATABASES) {
            try {
                File path = context.getDatabasePath(name);
                if (path != null && path.isFile()) total++;
            } catch (Throwable ignored) {
            }
        }
        File root = context.getFilesDir();
        if (root != null) {
            for (String dirName : FILE_DIRS) {
                File[] children = new File(root, dirName).listFiles();
                if (children == null) continue;
                for (File child : children) if (child != null && child.isFile()) total++;
            }
        }
        return total;
    }

    /**
     * Reads a label through the panel's own string table, for messages raised outside it.
     *
     * <p>The owner's chosen language is read rather than left to the default: passing null here
     * resolved to English every time, so a restore reported itself in English to someone whose
     * entire panel was in Chinese.
     */
    public static String text(Context context, String name, String fallback) {
        try {
            String language = null;
            try {
                language = com.spotifyplusplus.SpotifyPlusConfig.from(context)
                        .get(com.spotifyplusplus.Settings.UI_LANGUAGE);
            } catch (Throwable ignored) {
                // Falls through to the default language rather than failing the message.
            }
            return com.spotifyplusplus.ui.UiLanguage.strings(context, language).get(name, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * A formatted message. Counts go through {@code %1$d} rather than being concatenated with
     * fragments: Android trims leading and trailing spaces inside a string resource, so
     * "Restored " + n + " entries" arrives as "Restored102entries" in every language.
     */
    public static String format(Context context, String name, String fallback, Object... args) {
        try {
            return String.format(text(context, name, fallback), args);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Reads back {@link #FILE_NAME} from Downloads. Null when it is absent or unreadable.
     *
     * <p>The legacy name is tried too: the lookup is by file name, so an export written before the
     * rename would otherwise become invisible to this path.
     */
    public static String readFromDownloads(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String json = readDownloadByName(context, FILE_NAME);
        return json != null ? json : readDownloadByName(context, LEGACY_FILE_NAME);
    }

    private static String readDownloadByName(Context context, String name) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns._ID};
        try (android.database.Cursor cursor = context.getContentResolver().query(collection,
                projection, MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{name}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            Uri file = ContentUris.withAppendedId(collection, cursor.getLong(0));
            try (InputStream in = context.getContentResolver().openInputStream(file)) {
                if (in == null) return null;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Restores this install from the Downloads file. -1 when there is nothing usable there. */
    public static int restoreFromDownloads(Context context) {
        String json = readFromDownloads(context);
        if (json == null || json.isEmpty()) return -1;
        // decodeAll, not decodeInto: this path still wrote into two stores when the export grew to
        // six, so restoring from Downloads brought back the settings and keys but left the source
        // order and the catalogue behind, while importing the very same file restored them.
        return decodeAll(context, json);
    }

    private static SharedPreferences prefs(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
