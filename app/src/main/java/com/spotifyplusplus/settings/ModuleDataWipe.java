package com.spotifyplusplus.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.spotifyplusplus.SpotifyPlusConfig;
import com.spotifyplusplus.diagnostics.DiagnosticCaptureStore;
import com.spotifyplusplus.diagnostics.DiagnosticDraftStore;
import com.spotifyplusplus.lyrics.ai.AiCredentialStore;
import com.spotifyplusplus.lyrics.cache.SpicyCacheStore;
import com.spotifyplusplus.lyrics.catalog.CatalogStore;
import com.spotifyplusplus.lyrics.session.AIPaidArtifactCache;

import java.io.File;

/**
 * The module's whole local footprint, deleted on the owner's explicit request.
 *
 * <p>Why this exists: a backup made by one build and restored into another is not downgrade-safe
 * (the databases travel as whole files with their own schema versions), so a bad cross-version
 * restore can leave the module in a state no setting can repair. Deleting everything the module
 * wrote and starting over is the way back.
 *
 * <p>Scope is deliberately exact. Only the module's own stores are touched: its three SQLite
 * databases, the preference files whose names are listed below, the folders and files it creates
 * under Spotify's private files/cache/no-backup directories, and the encrypted credentials
 * (including the Keystore key behind them). Spotify's own databases, downloaded songs, account
 * data, and the backups the owner exported to Downloads are never touched.
 *
 * <p>A wipe cannot unload what is already in memory inside the running process - the lyrics
 * session, the artwork cache, the shell's applied settings. Nothing pretends otherwise: the caller
 * tells the owner to restart Spotify, and {@link Result#restartRequired} is always true.
 */
public final class ModuleDataWipe {

    /** What one wipe removed, and whether a restart is needed to make the empty state live. */
    public static final class Result {
        /** Stores, databases, directories and files this wipe actually removed. */
        public final int clearedItems;
        /** Entries that existed but could not be removed (locked or unwritable). */
        public final int failedItems;
        /** Always true: in-process state cannot be reloaded from the emptied stores. */
        public final boolean restartRequired = true;

        Result(int clearedItems, int failedItems) {
            this.clearedItems = clearedItems;
            this.failedItems = failedItems;
        }
    }

    /**
     * Whole database files, copied verbatim in backups and deleted verbatim here.
     *
     * <p>The owning classes keep their names private ({@code CatalogSchema.DATABASE} is the only
     * public one), and two of those packages are frozen, so the names are restated with their
     * owners named in the comment rather than made public just for this list.
     */
    private static final String[] DATABASES = {
            "LyricsCatalog.db",        // lyrics/catalog/CatalogSchema.DATABASE
            "SpicyLyricCaches.db",     // lyrics/cache/SpicyCacheStore.DATABASE
            "SpicyPaidAiLedger.db",    // lyrics/session/AIPaidArtifactDatabase.DATABASE
    };

    /**
     * Every preference file this module writes inside Spotify's process.
     *
     * <p>Most owners keep the name private, so the list is restated rather than widening ten APIs.
     * It is the same set {@link SpicyBackup} carries, plus the transient stores a backup leaves out
     * (diagnostics are cleared through their owners below, not by name).
     */
    private static final String[] PREFS = {
            SpotifyPlusConfig.PREFS_NAME,          // settings, shell layout, captured token
            AiCredentialStore.PREFS,               // encrypted secrets (also cleared below)
            "SpotifyPlusLyricsSourceSelection",    // session/LyricsSourcePreferences
            "SpotifyPlusCanonicalSourceCache",     // session/CanonicalSourceCache (legacy prefs)
            "SpotifyPlusLyricsResponseCache",      // providers/LyricsResponseCache (legacy prefs)
            "SpotifyPlusSpicyOrgKeyState",         // providers/SpicyOrgKeyStore epoch
            "SpotifyPlusSpicyOrgAccessState",      // providers/SpicyOrgAccessState
            "spicy-org-retention",                 // providers/SpicyOrgRetention
            "SpicyLyrics_QueryBreaker_g1",         // providers/SpicyTransport breaker
            "SpotifyPlusAIPaidArtifactCache",      // session/AIPaidArtifactCache legacy prefs
            "spicyex_share_hint",                  // sharecard/LyricsShareCardController hint
            // Written by the module's own process (auto/AutoPrototypeProvider). From Spotify's
            // process this clears the unused Spotify-side copy only; the module APK's own data
            // directory belongs to a different UID and cannot be reached from here.
            "auto-prototype",
    };

    /** Owner data under {@code files/}: copied fonts, the model pack, the agent channel. */
    private static final String[] FILES_DIRS = {
            "spicyex_fonts",
            "language-models-v1",
            "spicy-agent",
    };

    /** {@code files/} files that are not inside one of the directories above. */
    private static final String[] FILES_NAMES = {
            "language-models.v1.zip.part",   // LanguageModelPack download in progress
            "language-models-v1.partial",    // LanguageModelPack unzip staging
    };

    /** Module-owned directories under Spotify's cache directory. */
    private static final String[] CACHE_DIRS = {
            "kuromoji-mmap",   // language/MappedKuromojiDictionaries extracted tables
            "shareablesdir",   // sharecard/LyricsShareCardController share staging
    };

    /** Module-owned files under Spotify's cache directory. */
    private static final String[] CACHE_NAMES = {
            "spicy-diagnostic-events-v1.jsonl",          // DiagnosticCaptureStore
            "spicy-diagnostic-report-pending-v1.json",   // DiagnosticDraftStore
            "spicyex-share-preview.png",                 // LyricsShareCardController preview
    };

    /** Prefixes of cache files written with a variable suffix. */
    private static final String[] CACHE_PREFIXES = {
            "spicyex-share-preview-",  // share preview temporaries
    };

    /** Module-owned files under {@code noBackupFilesDir}. */
    private static final String[] NO_BACKUP_NAMES = {
            "spicy-diagnostic-description-v1.txt",  // DiagnosticCaptureStore
    };

    /** Prefix of the resolved-symbol caches, one per process identity. */
    private static final String NO_BACKUP_PREFIX = "spicy-symbols-";

    private ModuleDataWipe() {
    }

    /**
     * Deletes everything this module stores inside Spotify's data directory.
     *
     * <p>Runs off the main thread by contract: it deletes files and clears a dozen preference
     * files. Best effort per store - one locked file does not abandon the rest - and the caller
     * reports how many items were removed and how many could not be.
     */
    public static Result wipe(Context context) {
        if (context == null) return new Result(0, 0);
        int[] counters = new int[2]; // [cleared, failed]

        // Owners that keep their own capture/draft state, so their prefs and files go together.
        // Their clear/cancel is called either way - stale files may outlive an inactive state -
        // but only real state counts as an item the owner would recognise as deleted.
        try {
            boolean active = DiagnosticCaptureStore.state(context).active;
            DiagnosticCaptureStore.cancel(context);
            if (active) counters[0]++;
        } catch (Throwable failed) {
            counters[1]++;
        }
        try {
            boolean pending = DiagnosticDraftStore.load(context) != null;
            DiagnosticDraftStore.clear(context);
            if (pending) counters[0]++;
        } catch (Throwable failed) {
            counters[1]++;
        }

        // Handles first, exactly as a restore does: deleting under an open SQLite connection leaves
        // the process writing stale pages back into a file that no longer exists.
        closeDatabaseHandles();
        for (String name : DATABASES) removeDatabase(context, name, counters);
        for (String name : PREFS) clearPreferenceFile(context, name, counters);

        // The Keystore key goes with the ciphertext; without the key the stored values are useless.
        try {
            AiCredentialStore.create(context).deleteAll();
            counters[0]++;
        } catch (Throwable failed) {
            counters[1]++;
        }

        File files = context.getFilesDir();
        if (files != null) {
            for (String dir : FILES_DIRS) removeTree(new File(files, dir), counters);
            for (String name : FILES_NAMES) removeFile(new File(files, name), counters);
        }
        File cache = context.getCacheDir();
        if (cache != null) {
            for (String dir : CACHE_DIRS) removeTree(new File(cache, dir), counters);
            for (String name : CACHE_NAMES) removeFile(new File(cache, name), counters);
            removePrefixed(cache, CACHE_PREFIXES, null, counters);
            // Hot-reload dumps of the module APK: the same rule SpicyEXModule uses at startup.
            removePrefixed(cache, null, ".apk", counters);
        }
        File noBackup = context.getNoBackupFilesDir();
        if (noBackup != null) {
            for (String name : NO_BACKUP_NAMES) removeFile(new File(noBackup, name), counters);
            removePrefixed(noBackup, new String[]{NO_BACKUP_PREFIX}, null, counters);
        }
        return new Result(counters[0], counters[1]);
    }

    private static void closeDatabaseHandles() {
        try {
            CatalogStore.closeForRestore();
        } catch (Throwable ignored) {
        }
        try {
            SpicyCacheStore.closeForRestore();
        } catch (Throwable ignored) {
        }
        try {
            AIPaidArtifactCache.closeForRestore();
        } catch (Throwable ignored) {
        }
    }

    /** One database file plus the sidecars SQLite may have written beside it. */
    private static void removeDatabase(Context context, String name, int[] counters) {
        try {
            File database = context.getDatabasePath(name);
            if (database == null) return;
            boolean existed = false;
            boolean failed = false;
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                File file = new File(database.getPath() + suffix);
                if (!file.exists()) continue;
                existed = true;
                if (!file.delete() && file.exists()) failed = true;
            }
            if (!existed) return;
            if (failed) counters[1]++;
            else counters[0]++;
        } catch (Throwable failed) {
            counters[1]++;
        }
    }

    private static void clearPreferenceFile(Context context, String name, int[] counters) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            boolean hadValues = !prefs.getAll().isEmpty();
            // Nothing stored under this name: leave the file alone rather than asking Android to
            // create an empty one, and claim nothing. Reading never writes, so this stays a no-op.
            if (!hadValues) return;
            // commit, not apply: the result is reported to the owner immediately, so the write has
            // to be on disk before the completion message claims it happened.
            boolean committed = prefs.edit().clear().commit();
            if (!committed) {
                counters[1]++;
            } else {
                counters[0]++;
            }
        } catch (Throwable failed) {
            counters[1]++;
        }
    }

    private static void removeFile(File file, int[] counters) {
        try {
            if (file == null || !file.exists()) return;
            if (file.delete() || !file.exists()) counters[0]++;
            else counters[1]++;
        } catch (Throwable failed) {
            counters[1]++;
        }
    }

    /** Deletes a directory tree; counts the tree once when anything in it went away. */
    private static void removeTree(File root, int[] counters) {
        try {
            if (root == null || !root.exists()) return;
            boolean[] removed = new boolean[1];
            boolean[] failed = new boolean[1];
            deleteRecursively(root, removed, failed);
            if (failed[0]) counters[1]++;
            else if (removed[0]) counters[0]++;
        } catch (Throwable failed) {
            counters[1]++;
        }
    }

    private static void deleteRecursively(File file, boolean[] removed, boolean[] failed) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child, removed, failed);
        }
        boolean deleted = file.delete();
        if (deleted) removed[0] = true;
        else if (file.exists()) failed[0] = true;
    }

    /** Deletes direct children of {@code dir} whose name starts with a prefix or ends with a suffix. */
    private static void removePrefixed(File dir, String[] prefixes, String suffix, int[] counters) {
        try {
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child == null || !child.isFile()) continue;
                String name = child.getName();
                boolean match = suffix != null && name.endsWith(suffix);
                if (!match && prefixes != null) {
                    for (String prefix : prefixes) {
                        if (name.startsWith(prefix)) {
                            match = true;
                            break;
                        }
                    }
                }
                if (match) removeFile(child, counters);
            }
        } catch (Throwable failed) {
            counters[1]++;
        }
    }
}
