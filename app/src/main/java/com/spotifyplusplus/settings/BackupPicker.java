package com.spotifyplusplus.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import com.spotifyplusplus.xposed.XpHooks;
import com.spotifyplusplus.xposed.XpLog;

/**
 * Android's own file pickers, on loan inside Spotify's process.
 *
 * <p>This module has no Activity of its own, so the two halves of a document-picker round trip are
 * not equally easy. Sending the {@code ACTION_CREATE_DOCUMENT} / {@code ACTION_OPEN_DOCUMENT}
 * intent is trivial - Spotify's Activity is right there. Receiving the answer is the hard half: it
 * arrives at {@code onActivityResult} on that Activity, and nothing in this process is listening
 * for it. So one hook is installed, catching {@code onActivityResult} everywhere and acting on it
 * only for the two request codes below.
 *
 * <p>Filtering is the whole safety story here. Spotify uses this same callback for permissions,
 * sign-in and sharing, and an unfiltered hook would eat its results. Anything that is not one of
 * our two codes returns immediately, having touched nothing.
 */
public final class BackupPicker {
    private static final String TAG = "[SpotifyPlusBackupPicker]";

    /**
     * Deliberately far outside the small integers apps pass to {@code startActivityForResult}:
     * the point is to never collide with one of Spotify's own request codes.
     */
    private static final int REQUEST_EXPORT = 10240;
    private static final int REQUEST_IMPORT = 10241;

    private static final String MIME_JSON = "application/json";

    private static volatile boolean installed;

    private BackupPicker() {
    }

    /** Installs the result hook once. Safe to call on every panel render. */
    public static void ensureInstalled(Context context) {
        if (installed) return;
        installed = true;
        try {
            XpHooks.hookAllMethods(Activity.class, "onActivityResult", "backup:picker",
                    (XpHooks.After) param -> {
                        Object[] args = param.args;
                        if (args == null || args.length < 3) return;
                        if (!(args[0] instanceof Integer) || !(args[1] instanceof Integer)) return;
                        int request = (Integer) args[0];
                        if (request != REQUEST_EXPORT && request != REQUEST_IMPORT) return;
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity activity = (Activity) param.thisObject;
                        int result = (Integer) args[1];
                        Object data = args[2];
                        Uri uri = data instanceof Intent ? ((Intent) data).getData() : null;
                        handler(activity, request, result, uri);
                    });
        } catch (Throwable t) {
            XpLog.log(TAG + " result hook failed: " + t);
        }
    }

    /** Opens the system's "save as" picker. False when this context cannot launch one. */
    public static boolean startExport(Context context) {
        Activity activity = activityOf(context);
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(MIME_JSON);
            intent.putExtra(Intent.EXTRA_TITLE, SpicyBackup.FILE_NAME);
            activity.startActivityForResult(intent, REQUEST_EXPORT);
            return true;
        } catch (Throwable t) {
            XpLog.log(TAG + " export picker failed: " + t);
            return false;
        }
    }

    /** Opens the system's "open file" picker. False when this context cannot launch one. */
    public static boolean startImport(Context context) {
        Activity activity = activityOf(context);
        if (activity == null) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, REQUEST_IMPORT);
            return true;
        } catch (Throwable t) {
            XpLog.log(TAG + " import picker failed: " + t);
            return false;
        }
    }

    private static void handler(Activity activity, int request, int result, Uri uri) {
        try {
            if (result != Activity.RESULT_OK || uri == null) return;
            if (request == REQUEST_EXPORT) {
                int written = SpicyBackup.writeTo(activity, uri);
                toast(activity, written >= 0
                        ? SpicyBackup.format(activity, "settings_backup_exported_to",
                                "Saved to %1$s", uri.getLastPathSegment())
                        : SpicyBackup.text(activity, "settings_backup_export_failed",
                                "Could not export"));
            } else {
                int restored = SpicyBackup.restoreFrom(activity, uri);
                toast(activity, restored < 0
                        ? SpicyBackup.text(activity, "settings_backup_unreadable",
                                "That file is not a Spicy EX backup")
                        : SpicyBackup.format(activity, "settings_backup_restored_count",
                                "Restored %1$d entries", restored));
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " result handling failed: " + t);
        }
    }

    private static void toast(Activity activity, String message) {
        try {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof android.content.ContextWrapper)) return null;
            current = ((android.content.ContextWrapper) current).getBaseContext();
        }
        return null;
    }
}
