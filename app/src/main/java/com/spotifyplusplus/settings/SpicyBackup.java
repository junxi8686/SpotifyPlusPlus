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

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SpotifyPlusConfig;
import com.spotifyplusplus.lyrics.ai.AiCredentialStore;
import com.spotifyplusplus.lyrics.ai.AiSettings;
import com.spotifyplusplus.lyrics.processing.BaiduTranslateCredentials;
import com.spotifyplusplus.lyrics.providers.SpicyManualTokenStore;
import com.spotifyplusplus.lyrics.providers.SpicyOrgKeyStore;
import com.spotifyplusplus.ui.SettingsUiStrings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything this module stores, as one file, and the way back from it.
 *
 * <p>The module keeps its state in Spotify's own preferences, so clearing Spotify's data or moving
 * to a new install loses every setting, every source choice and the saved lyric cache - and the
 * owner rebuilds all of it by hand. This is the answer to that: one JSON file, and a restore that
 * merges it back. What is covered is the {@link #STORES} list, the {@link #DATABASES} list and the
 * {@link #FILE_DIRS} list; nothing else in the module's storage travels.
 *
 * <p>{@code SpotifyPlusAiCredentials} holds the provider keys, the Baidu app id and secret, the
 * SpicyLyrics.org client key and the desktop token, encrypted - the ciphertext is copied verbatim.
 * That is the intended behaviour, and it has two sides worth stating plainly:
 *
 * <ul>
 *   <li>It keeps those credentials working on <b>this install</b> after a module update, a Spotify
 *       update or a module uninstall/reinstall, because the key that unwraps them lives in the
 *       Keystore under Spotify's own UID on this device and is not part of the backup.
 *   <li>It cannot work anywhere else. After a Spotify uninstall or clear-data the platform
 *       destroys the Keystore key, and another device or another Android user has a different one,
 *       so a restore there reads those credentials as absent. The module never imports or migrates
 *       a key, and no message may claim it does.
 * </ul>
 *
 * <p>Two rules follow, and both are deliberate. A restore never replaces a stored credential that
 * this device can still read with one it cannot ({@code AiCredentialStore.decryptability}), and the
 * owner is told which credentials the file carried that cannot be read here, in the same dialog
 * that offers the restart.
 *
 * <p>The lyric cache <em>database</em> is included: it holds translations and readings that cost
 * money to produce through the AI providers. The language model pack under {@code files/} is
 * deliberately absent - it is a re-downloadable asset of tens of megabytes, and no setting stores
 * a path to it.
 *
 * <p>Android-free except for the preference reads, the Keystore decryptability probe and the
 * Downloads handoff, so the encoding and the merge rules are testable off-device.
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

    private static final String AI_PREFS = AiCredentialStore.PREFS;
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
     *
     * <p>{@link #CATALOG_PREFS} and {@link #RESPONSE_CACHE_PREFS} stay on the list even though both
     * caches now live in {@code SpicyLyricCaches.db} and are normally empty here: they are still
     * the migration source {@code SpicyCacheStore.importLegacyOnce} reads when the database has no
     * import marker, which is exactly the state a restore of a version-1 file (stores only, no
     * databases) produces. Dropping them would stop that one file from bringing its caches back.
     * {@link #AUTO_PROTOTYPE_PREFS} is written by the module's own process rather than Spotify's,
     * so under a plain LSPosed install there is normally nothing in it to carry; it costs an empty
     * object and would carry the value under a deployment that merges the provider into Spotify.
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

    /** The directory under {@code files/} this module copies fonts into. */
    static final String FONT_DIR = "spicyex_fonts";

    /** The result of one restore: the entry count plus what happened to the credentials. */
    public static final class RestoreOutcome {
        /** Entries written, or {@link #CANCELLED}, or -1 when the document was not usable. */
        public final int written;
        /**
         * Scopes whose value was already working on this device and was kept, because the value
         * the file carried cannot be read here. Reported, never silent: the owner has to be told
         * which keys stayed, or the restore looks like it changed nothing.
         */
        public final List<String> keptScopes;
        /**
         * Scopes the file carried that cannot be read here. Nothing was imported and nothing can
         * be: the owner enters these again, or the module keeps reporting them absent.
         */
        public final List<String> reentryScopes;

        RestoreOutcome(int written, List<String> keptScopes, List<String> reentryScopes) {
            this.written = written;
            this.keptScopes = keptScopes == null ? new ArrayList<>() : keptScopes;
            this.reentryScopes = reentryScopes == null ? new ArrayList<>() : reentryScopes;
        }

        /** True when there is something about credentials to say to the owner. */
        public boolean hasCredentialNotice() {
            return !keptScopes.isEmpty() || !reentryScopes.isEmpty();
        }
    }

    /** What one credential-store restore decided. Storage-free, so the rule is testable. */
    static final class CredentialPlan {
        /** The entries that may be applied: everything except a kept local value. */
        final JsonObject write = new JsonObject();
        final List<String> keptScopes = new ArrayList<>();
        final List<String> reentryScopes = new ArrayList<>();
    }

    /**
     * Decides which entries of an incoming credential store may be applied.
     *
     * <p>The rule is narrow on purpose: <b>a value that cannot be read on this device never
     * replaces one that can</b>. A readable incoming value is still applied - a restore is a
     * restore, and the owner asked for the file's state - so the only entry skipped is one that
     * would leave a working install with no key at all. Ciphertext written against another device's
     * Keystore is indistinguishable from a usable value until it is tried, which is why the local
     * value is tried first.
     *
     * @param local          the destination's current values, as {@code getAll()} would return them
     * @param incoming       the file's credential store, or null when the file has none
     * @param decryptability this device's ability to read a stored value; null writes through
     */
    static CredentialPlan planCredentialRestore(Map<String, ?> local, JsonObject incoming,
                                                AiCredentialStore.Decryptability decryptability) {
        CredentialPlan plan = new CredentialPlan();
        if (incoming == null) return plan;
        Set<String> keptKeys = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : incoming.entrySet()) {
            String key = entry.getKey();
            JsonElement cell = entry.getValue();
            if (!AiCredentialStore.isCredentialKey(key) || decryptability == null) {
                plan.write.add(key, cell);
                continue;
            }
            String localValue = storedString(local, key);
            String incomingValue = cellString(cell);
            boolean localUsable = !localValue.isEmpty() && decryptability.canDecrypt(localValue);
            boolean incomingUsable = incomingValue != null && !incomingValue.isEmpty()
                    && decryptability.canDecrypt(incomingValue);
            if (localUsable && !incomingUsable && !localValue.equals(incomingValue)) {
                // The file's value was written against a Keystore this device does not have, and
                // applying it would leave the owner with no key at all. The local one still works,
                // so it wins, and the owner is told which key was kept.
                keptKeys.add(key);
                plan.keptScopes.add(AiCredentialStore.scopeOf(key));
                continue;
            }
            plan.write.add(key, cell);
        }
        for (Map.Entry<String, JsonElement> entry : incoming.entrySet()) {
            String key = entry.getKey();
            if (!AiCredentialStore.isCredentialKey(key) || decryptability == null) continue;
            String finalValue = keptKeys.contains(key)
                    ? storedString(local, key) : cellString(entry.getValue());
            if (finalValue == null || finalValue.isEmpty()) continue;
            if (!decryptability.canDecrypt(finalValue)) {
                plan.reentryScopes.add(AiCredentialStore.scopeOf(key));
            }
        }
        return plan;
    }

    /** One raw stored string, or empty when the key is absent or does not hold a string. */
    private static String storedString(Map<String, ?> values, String key) {
        if (values == null || key == null) return "";
        Object value = values.get(key);
        return value instanceof String ? (String) value : "";
    }

    /** The string inside one encoded cell, or null when the cell is not a string. */
    private static String cellString(JsonElement cell) {
        if (cell == null || !cell.isJsonObject()) return null;
        JsonObject object = cell.getAsJsonObject();
        JsonElement type = object.get("t");
        JsonElement value = object.get("v");
        if (type == null || value == null || !"s".equals(type.getAsString())) return null;
        try {
            return value.getAsString();
        } catch (Throwable notAString) {
            return null;
        }
    }

    /**
     * The owner-facing names of credential scopes, in order and without duplicates.
     *
     * <p>Named through the same resources the settings rows use, so the dialog that reports a
     * missing key and the row that fixes it read alike in every language.
     */
    public static String credentialNames(Context context, List<String> scopes) {
        if (context == null || scopes == null || scopes.isEmpty()) return "";
        SettingsUiStrings strings = strings(context);
        if (strings == null) return "";
        List<String> names = new ArrayList<>();
        for (String scope : scopes) {
            if (scope == null || scope.isEmpty()) continue;
            String name = credentialName(strings, scope);
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return String.join(", ", names);
    }

    private static String credentialName(SettingsUiStrings strings, String scope) {
        if (AiSettings.PROVIDER_GEMINI.equals(scope) || AiSettings.PROVIDER_OPENAI.equals(scope)
                || AiSettings.PROVIDER_OPENROUTER.equals(scope)
                || AiSettings.PROVIDER_DEEPSEEK.equals(scope)
                || AiSettings.PROVIDER_CUSTOM.equals(scope)) {
            return strings.get("settings_ai_api_key", "API key");
        }
        if (BaiduTranslateCredentials.APP_ID_SCOPE.equals(scope)) {
            return strings.setting(Settings.BAIDU_TRANSLATE_APPID);
        }
        if (BaiduTranslateCredentials.SECRET_SCOPE.equals(scope)) {
            return strings.setting(Settings.BAIDU_TRANSLATE_SECRET);
        }
        if (SpicyOrgKeyStore.SCOPE.equals(scope)) {
            return strings.setting(Settings.SPICY_ORG_CLIENT_KEY);
        }
        if (SpicyManualTokenStore.SCOPE.equals(scope)) {
            return strings.setting(Settings.SPICY_MANUAL_TOKEN);
        }
        return strings.get("settings_backup_credential_other", "Stored credential");
    }

    /**
     * The whole result of a restore as one message, for the surface that has only a toast.
     *
     * <p>Never the word import: the file restores settings and data, and a credential it cannot
     * read has to be entered again.
     */
    public static String restoreMessage(Context context, RestoreOutcome outcome) {
        if (outcome == null) {
            return text(context, "settings_backup_unreadable",
                    "That file is not a Spicy EX backup");
        }
        if (outcome.written == CANCELLED) {
            return text(context, "settings_backup_cancelled", "Cancelled");
        }
        if (outcome.written < 0) {
            return text(context, "settings_backup_missing", "No backup found in Downloads");
        }
        String message = format(context, "settings_backup_restored_count", "Restored %1$d entries",
                outcome.written);
        if (!outcome.reentryScopes.isEmpty()) {
            message = message + "\n" + format(context, "settings_backup_credentials_reenter",
                    "Enter these again - they were encrypted on the device that wrote this backup"
                            + " and cannot be read here: %1$s",
                    credentialNames(context, outcome.reentryScopes));
        }
        if (!outcome.keptScopes.isEmpty()) {
            message = message + "\n" + format(context, "settings_backup_credentials_kept",
                    "Kept the keys already working on this device: %1$s",
                    credentialNames(context, outcome.keptScopes));
        }
        // This surface has no dialog to ask in, so it advises rather than offers the restart.
        return message + "\n" + text(context, "settings_backup_restart_advice",
                "Restart Spotify for the restored catalogue and caches to take effect.");
    }

    /**
     * Points a restored custom-font setting at this install's copy of the same file.
     *
     * <p>The setting stores an absolute path written by the exporting device, and that path carries
     * the device's own user id. The font file itself travels under {@code files/spicyex_fonts}, so
     * when the stored path no longer resolves but a file of the same name is here, the setting is
     * rewritten to the path that does. Without this the row keeps a path that cannot work, the
     * lyrics silently render in a fallback face, and the coverage check measures that fallback
     * instead of the owner's font. A repair is not counted as a restored entry: it corrects one.
     */
    private static void repairRestoredFontPath(Context context, SharedPreferences settings) {
        if (context == null || settings == null) return;
        String stored = storedString(settings.getAll(), Settings.LYRICS_FONT_CUSTOM_PATH.key);
        if (stored.isEmpty()) return;
        String repaired = repairedFontPath(stored, new File(context.getFilesDir(), FONT_DIR));
        if (repaired.equals(stored)) return;
        settings.edit().putString(Settings.LYRICS_FONT_CUSTOM_PATH.key, repaired).apply();
    }

    /**
     * The font path this install can use, given the value the file carried.
     *
     * <p>Pure but for the file system, so it is testable off-device. A value that already resolves,
     * or that does not name a file this module manages, is returned unchanged.
     */
    static String repairedFontPath(String storedPath, File fontDir) {
        if (storedPath == null) return "";
        if (storedPath.isEmpty() || new File(storedPath).isFile() || fontDir == null) {
            return storedPath;
        }
        String name = managedFontName(storedPath);
        if (name == null) return storedPath;
        File candidate = new File(fontDir, name);
        return candidate.isFile() ? candidate.getAbsolutePath() : storedPath;
    }

    /** The file name inside a path this module wrote under {@code files/spicyex_fonts}, or null. */
    static String managedFontName(String path) {
        if (path == null || path.isEmpty()) return null;
        String marker = "/" + FONT_DIR + "/";
        String normalized = path.replace('\\', '/');
        int at = normalized.lastIndexOf(marker);
        if (at < 0) return null;
        String name = normalized.substring(at + marker.length());
        if (name.isEmpty() || name.contains("/") || name.contains("..")) return null;
        return name;
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
     * <p>This builds the whole document in memory, which is what the pure tests and any caller that
     * wants the text need. The two paths that write a file ({@link #writeTo}, and the Downloads
     * fallback) stream through {@link #writeSnapshot} instead: a snapshot with a large cache in it
     * is not something a low-memory device should have to hold twice.
     *
     * @return null when the owner cancelled or the snapshot could not be built
     */
    public static String encodeAll(Context context, Progress progress) {
        StringWriter out = new StringWriter();
        try {
            if (writeSnapshot(context, out, progress) == CANCELLED) return null;
        } catch (Throwable failure) {
            return null;
        }
        return out.toString();
    }

    /**
     * Writes the snapshot straight to {@code out}, databases and files included.
     *
     * <p>Nothing bigger than one chunk is ever held: each database is base64-encoded as it is read,
     * so the document never exists in memory as a whole. The shape is exactly what
     * {@link #encodeAll} produces, and what every reader of this format already parses.
     *
     * @return the entry count the confirmation message reports, or {@link #CANCELLED}
     */
    static int writeSnapshot(Context context, Writer out, Progress progress) throws java.io.IOException {
        int total = STORES.length + DATABASES.length + countFiles(context);
        int done = 0;
        out.write("{\"version\":");
        out.write(Integer.toString(FORMAT_VERSION));
        out.write(",\"exportedAt\":");
        out.write(Long.toString(System.currentTimeMillis()));
        out.write(",\"stores\":{");
        boolean first = true;
        for (String name : STORES) {
            if (cancelled(progress)) return CANCELLED;
            if (!first) out.write(',');
            first = false;
            out.write(quote(name));
            out.write(':');
            out.write(encodeStore(context == null ? null : prefs(context, name).getAll()).toString());
            report(progress, ++done, total);
        }
        out.write("},\"databases\":{");
        first = true;
        if (context == null) {
            done += DATABASES.length;
        } else {
            for (String name : DATABASES) {
                if (cancelled(progress)) return CANCELLED;
                File path = preparedDatabaseFile(context, name);
                if (path != null) {
                    if (!first) out.write(',');
                    first = false;
                    out.write(quote(name));
                    out.write(":\"");
                    writeBase64(path, out);
                    out.write('"');
                }
                report(progress, ++done, total);
            }
        }
        out.write("},\"files\":{");
        if (context != null && !writeFiles(context, out, progress, done, total)) return CANCELLED;
        out.write("}}");
        return settingsCount(context);
    }

    /**
     * One file as base64, written in chunks.
     *
     * <p>The chunk size is a multiple of three bytes, so no chunk ever carries padding and the
     * concatenation is byte-for-byte the base64 of the whole file. That is what keeps the largest
     * database from having to exist as bytes and as text at the same time. {@link java.util.Base64}
     * rather than {@code android.util.Base64} for the same reason the lyrics parser uses it:
     * identical output, and the JVM suite can cover it.
     */
    static void writeBase64(File file, Appendable out) throws java.io.IOException {
        byte[] buffer = new byte[48 * 1024];
        java.util.Base64.Encoder encoder = java.util.Base64.getEncoder();
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.append(encoder.encodeToString(read == buffer.length
                        ? buffer : java.util.Arrays.copyOf(buffer, read)));
            }
        }
    }

    /** Every copied file under {@code files/}, streamed the same way the databases are. */
    private static boolean writeFiles(Context context, Writer out, Progress progress,
                                      int doneBase, int total) throws java.io.IOException {
        File root = context.getFilesDir();
        if (root == null) return true;
        int done = doneBase;
        boolean first = true;
        for (String dirName : FILE_DIRS) {
            File[] children = new File(root, dirName).listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child == null || !child.isFile()) continue;
                if (cancelled(progress)) return false;
                if (child.length() > 0) {
                    if (!first) out.write(',');
                    first = false;
                    out.write(quote(dirName + "/" + child.getName()));
                    out.write(":\"");
                    writeBase64(child, out);
                    out.write('"');
                }
                report(progress, ++done, total);
            }
        }
        return true;
    }

    /** A JSON string literal for one name, escaped by the same library the rest of the codec uses. */
    private static String quote(String value) {
        return new com.google.gson.JsonPrimitive(value == null ? "" : value).toString();
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
        return applyRestore(context, json, progress,
                AiCredentialStore.decryptability(context)).written;
    }

    /**
     * Restores every store the document carries and reports what happened to the credentials.
     *
     * <p>The credential store is merged rather than overwritten: a value this device can still read
     * is never replaced by one it cannot, and both outcomes are reported so the owner can be told
     * which keys were kept and which have to be entered again. Nothing here imports or migrates a
     * key; a value that cannot be decrypted on this device is simply absent.
     *
     * @return the entry count plus the credential outcome; {@code written} is -1 for an unusable
     *         document and {@link #CANCELLED} for a cancelled one
     */
    static RestoreOutcome applyRestore(Context context, String json, Progress progress,
                                       AiCredentialStore.Decryptability decryptability) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) return new RestoreOutcome(-1, null, null);
            root = parsed.getAsJsonObject();
        } catch (Throwable t) {
            return new RestoreOutcome(-1, null, null);
        }
        try {
            JsonElement version = root.get("version");
            if (version == null || version.getAsInt() < 1
                    || version.getAsInt() > FORMAT_VERSION) return new RestoreOutcome(-1, null, null);
        } catch (Throwable t) {
            return new RestoreOutcome(-1, null, null);
        }
        JsonElement stores = root.get("stores");
        if (stores == null || !stores.isJsonObject() || context == null) {
            return new RestoreOutcome(-1, null, null);
        }
        JsonElement databases = root.get("databases");
        JsonElement files = root.get("files");
        int total = STORES.length + DATABASES.length + countFileEntries(files);
        int done = 0;
        int written = 0;
        List<String> keptScopes = new ArrayList<>();
        List<String> reentryScopes = new ArrayList<>();
        for (String name : STORES) {
            if (cancelled(progress)) {
                return new RestoreOutcome(CANCELLED, keptScopes, reentryScopes);
            }
            JsonElement element = stores.getAsJsonObject().get(name);
            if (AI_PREFS.equals(name)) {
                JsonObject incoming = element != null && element.isJsonObject()
                        ? element.getAsJsonObject() : null;
                CredentialPlan plan = planCredentialRestore(
                        prefs(context, name).getAll(), incoming, decryptability);
                written += decodeStore(plan.write, prefs(context, name));
                keptScopes.addAll(plan.keptScopes);
                reentryScopes.addAll(plan.reentryScopes);
            } else {
                written += decodeStore(element, prefs(context, name));
            }
            report(progress, ++done, total);
        }
        if (cancelled(progress)) {
            return new RestoreOutcome(CANCELLED, keptScopes, reentryScopes);
        }
        // Handles are released before anything is written: an open connection keeps the process
        // on the old pages of the file being replaced.
        closeDatabaseHandles();
        for (String name : DATABASES) {
            if (cancelled(progress)) {
                return new RestoreOutcome(CANCELLED, keptScopes, reentryScopes);
            }
            if (databases != null && databases.isJsonObject()) {
                written += decodeOneDatabase(context, name, databases.getAsJsonObject().get(name));
            }
            report(progress, ++done, total);
        }
        if (files != null && files.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : files.getAsJsonObject().entrySet()) {
                if (cancelled(progress)) {
                    return new RestoreOutcome(CANCELLED, keptScopes, reentryScopes);
                }
                written += decodeOneFile(context, entry.getKey(), entry.getValue());
                report(progress, ++done, total);
            }
        }
        // Last, because the font file it looks for is restored by the loop above.
        repairRestoredFontPath(context, prefs(context, SpotifyPlusConfig.PREFS_NAME));
        return new RestoreOutcome(written, keptScopes, reentryScopes);
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
     * The database file to copy, or null when there is nothing to copy.
     *
     * <p>The write-ahead log is folded in first: a database copied while it still has a WAL beside
     * it is missing whatever that log holds. The file is returned rather than read, so the caller
     * can base64 it in chunks instead of holding it in memory twice.
     */
    private static File preparedDatabaseFile(Context context, String name) {
        try {
            File path = context.getDatabasePath(name);
            if (path == null || !path.isFile() || path.length() <= 0L) return null;
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
            return path;
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
     * <p>No decryptability is available here because there is no Context to reach a Keystore from,
     * so a credential entry is written exactly as the file has it. Callers that do have a Context
     * should use {@link #decodeInto(String, SharedPreferences, SharedPreferences, AiCredentialStore.Decryptability)},
     * so a value that cannot be read on this device never replaces one that can.
     *
     * @return how many entries were written, or -1 when the text is not a usable backup
     */
    public static int decodeInto(String json, SharedPreferences settings, SharedPreferences credentials) {
        return decodeInto(json, settings, credentials, null);
    }

    /** As {@link #decodeInto(String, SharedPreferences, SharedPreferences)}, with a decryptability. */
    public static int decodeInto(String json, SharedPreferences settings, SharedPreferences credentials,
                                 AiCredentialStore.Decryptability decryptability) {
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
        JsonElement element = stores.getAsJsonObject().get(AI_PREFS);
        JsonObject incoming = element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        CredentialPlan plan = planCredentialRestore(
                credentials == null ? null : credentials.getAll(), incoming, decryptability);
        written += decodeStore(plan.write, credentials);
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

    /**
     * Snapshots this install and writes it to {@code Downloads/Spicy EX}. Null on failure.
     *
     * <p>Streamed rather than built as one string, and a failed or cancelled write is removed
     * instead of being left behind as a document that looks like a backup.
     */
    public static String exportToDownloads(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
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
                if (writeSnapshot(context, writer, null) == CANCELLED) return null;
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

    /**
     * As {@link #writeTo(Context, Uri)}, reporting progress; {@link #CANCELLED} if cancelled.
     *
     * <p>The document is streamed straight to the owner's file, so nothing is assembled in memory
     * and an OutOfMemoryError or a full disk surfaces as a failed export rather than as a dead
     * process. Because the file is being written as it is produced, a failure or a cancellation
     * mid-way would leave a partial document: it is deleted instead, so what remains in the
     * picker's location is either a complete backup or nothing.
     */
    public static int writeTo(Context context, Uri target, Progress progress) {
        if (context == null || target == null) return -1;
        if (cancelled(progress)) return CANCELLED;
        boolean saved = false;
        try (OutputStreamWriter writer = new OutputStreamWriter(
                context.getContentResolver().openOutputStream(target, "wt"),
                StandardCharsets.UTF_8)) {
            int written = writeSnapshot(context, writer, progress);
            if (written == CANCELLED) return CANCELLED;
            saved = true;
            return written;
        } catch (Throwable failure) {
            return -1;
        } finally {
            if (!saved) discard(context, target);
        }
    }

    /** Best-effort removal of a document this code created and did not finish. */
    private static void discard(Context context, Uri target) {
        try {
            context.getContentResolver().delete(target, null, null);
        } catch (Throwable ignored) {
        }
    }

    /** Restores from a document the owner picked. -1 when it is not a usable backup. */
    public static int restoreFrom(Context context, Uri source) {
        return restoreFrom(context, source, null);
    }

    /** As {@link #restoreFrom(Context, Uri)}, reporting progress; {@link #CANCELLED} if cancelled. */
    public static int restoreFrom(Context context, Uri source, Progress progress) {
        return restore(context, source, progress).written;
    }

    /**
     * Restores from a document the owner picked, reporting what happened to the credentials.
     *
     * @return the entry count plus the credential outcome; {@code written} is -1 when the document
     *         is not a usable backup
     */
    public static RestoreOutcome restore(Context context, Uri source, Progress progress) {
        if (context == null || source == null) return new RestoreOutcome(-1, null, null);
        String json;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) return new RestoreOutcome(-1, null, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return new RestoreOutcome(-1, null, null);
        }
        return applyRestore(context, json, progress, AiCredentialStore.decryptability(context));
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
            return strings(context).get(name, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** The panel's string table for the owner's chosen language, or null when none is reachable. */
    private static SettingsUiStrings strings(Context context) {
        String language = null;
        try {
            language = SpotifyPlusConfig.from(context).get(Settings.UI_LANGUAGE);
        } catch (Throwable ignored) {
            // Falls through to the default language rather than failing the message.
        }
        return com.spotifyplusplus.ui.UiLanguage.strings(context, language);
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

    /**
     * Restores this install from the Downloads file.
     *
     * <p>Reads through {@link #applyRestore}, not the two-store path: restoring from Downloads once
     * brought back the settings and keys but left the source order and the catalogue behind, while
     * the very same file restored them when it was picked by hand.
     *
     * @return the entry count plus what happened to the credentials; {@code written} is -1 when
     *         there is nothing usable in Downloads
     */
    public static RestoreOutcome restoreDownloads(Context context, Progress progress) {
        String json = readFromDownloads(context);
        if (json == null || json.isEmpty()) return new RestoreOutcome(-1, null, null);
        return applyRestore(context, json, progress, AiCredentialStore.decryptability(context));
    }

    /** Restores this install from the Downloads file. -1 when there is nothing usable there. */
    public static int restoreFromDownloads(Context context) {
        return restoreDownloads(context, null).written;
    }

    private static SharedPreferences prefs(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
