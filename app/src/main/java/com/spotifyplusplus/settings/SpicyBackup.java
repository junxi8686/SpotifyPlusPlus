package com.spotifyplusplus.settings;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.spotifyplusplus.SpotifyPlusConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
    public static final String FILE_NAME = "SpicyEX-backup.json";
    /** Written into every snapshot; a restore from a newer file is refused rather than guessed. */
    public static final int FORMAT_VERSION = 1;

    private static final String AI_PREFS = "SpotifyPlusAiCredentials";
    private static final String[] STORES = {SpotifyPlusConfig.PREFS_NAME, AI_PREFS};

    private SpicyBackup() {
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

    private static JsonObject encodeStore(Map<String, ?> values) {
        JsonObject store = new JsonObject();
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
        String json = encode(prefs(context, SpotifyPlusConfig.PREFS_NAME).getAll(),
                prefs(context, AI_PREFS).getAll());
        Uri uri = null;
        boolean saved = false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Spicy EX");
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
            return Environment.DIRECTORY_DOWNLOADS + "/Spicy EX/" + FILE_NAME;
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
        if (context == null || target == null) return -1;
        String json = encode(prefs(context, SpotifyPlusConfig.PREFS_NAME).getAll(),
                prefs(context, AI_PREFS).getAll());
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
        return decodeInto(json, prefs(context, SpotifyPlusConfig.PREFS_NAME), prefs(context, AI_PREFS));
    }

    /** How many entries the export carried, used only for the confirmation message. */
    public static int settingsCount(Context context) {
        if (context == null) return 0;
        return prefs(context, SpotifyPlusConfig.PREFS_NAME).getAll().size()
                + prefs(context, AI_PREFS).getAll().size();
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

    /** Reads back {@link #FILE_NAME} from Downloads. Null when it is absent or unreadable. */    public static String readFromDownloads(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns._ID};
        try (android.database.Cursor cursor = context.getContentResolver().query(collection,
                projection, MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{FILE_NAME}, null)) {
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
        return decodeInto(json, prefs(context, SpotifyPlusConfig.PREFS_NAME), prefs(context, AI_PREFS));
    }

    private static SharedPreferences prefs(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
