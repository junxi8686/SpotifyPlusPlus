package com.spotifyplusplus.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

import com.spotifyplusplus.ui.PanelDialog;
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
 *
 * <p>An export or restore copies whole databases, so it runs off the main thread behind a
 * cancellable progress dialog: on the UI thread it would freeze the panel for as long as the
 * catalogue takes to read, and a backup that cannot be stopped is worse than one that is slow.
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
            if (request == REQUEST_EXPORT) runExport(activity, uri);
            else runImport(activity, uri);
        } catch (Throwable t) {
            XpLog.log(TAG + " result handling failed: " + t);
        }
    }

    private static void runExport(Activity activity, Uri uri) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final PanelStyle style = new PanelStyle(activity);
        final ProgressUi ui = ProgressUi.show(activity, style, "正在导出…", cancelled);
        new Thread(() -> {
            int written = SpicyBackup.writeTo(activity, uri, ui.progress());
            ui.close();
            // Every UI call goes back to the main thread. A Dialog or a Toast built on a worker
            // thread throws, and that is why the completion message never appeared.
            onUi(activity, () -> {
                if (written == SpicyBackup.CANCELLED) {
                    toast(activity, SpicyBackup.text(activity, "settings_backup_cancelled",
                            "已取消"));
                } else if (written < 0) {
                    toast(activity, SpicyBackup.text(activity, "settings_backup_export_failed",
                            "导出失败"));
                } else {
                    toast(activity, SpicyBackup.format(activity, "settings_backup_exported_to",
                            "已导出 %1$d 项", written));
                }
            });
        }, "spicy-backup-export").start();
    }

    private static void runImport(Activity activity, Uri uri) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final PanelStyle style = new PanelStyle(activity);
        final ProgressUi ui = ProgressUi.show(activity, style, "正在导入…", cancelled);
        new Thread(() -> {
            int restored = SpicyBackup.restoreFrom(activity, uri, ui.progress());
            ui.close();
            if (restored == SpicyBackup.CANCELLED) {
                onUi(activity, () -> toast(activity,
                        SpicyBackup.text(activity, "settings_backup_cancelled", "已取消")));
                return;
            }
            if (restored < 0) {
                onUi(activity, () -> toast(activity,
                        SpicyBackup.text(activity, "settings_backup_unreadable",
                                "这个文件不是 Spotify++ 的备份")));
                return;
            }
            // The catalogue and the caches are database files. The running process still holds the
            // pages it read, so what it shows now is the state from before the restore - which is
            // why the owner is asked whether to restart rather than told the restore is live.
            // Built on the main thread: this dialog is the whole point of the confirmation.
            onUi(activity, () -> askRestart(activity, style, restored));
        }, "spicy-backup-import").start();
    }

    /** Runs {@code action} on the main thread, immediately when already there. */
    private static void onUi(Activity activity, Runnable action) {
        if (activity == null || action == null) return;
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                action.run();
            } else {
                activity.runOnUiThread(action);
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " ui dispatch failed: " + t);
        }
    }

    /** Offers the restart the restored databases need, and lets the owner decline it. */
    private static void askRestart(Activity activity, PanelStyle style, int restored) {
        try {
            final PanelDialog dialog = new PanelDialog(activity, "导入完成");
            dialog.paragraph(SpicyBackup.format(activity, "settings_backup_restored_count",
                    "已恢复 %1$d 项", restored));
            dialog.paragraph("曲库和翻译缓存要等 Spotify 重启后才会生效。现在重启吗？");
            dialog.primary("强制停止 Spotify", BackupPicker::stopSpotify);
            dialog.secondary(SpicyBackup.text(activity, "settings_ai_cancel", "取消"), null);
            dialog.show();
        } catch (Throwable t) {
            XpLog.log(TAG + " restart prompt failed: " + t);
            toast(activity, SpicyBackup.format(activity, "settings_backup_restored_count",
                    "已恢复 %1$d 项", restored));
        }
    }

    /**
     * Stops Spotify by ending the process the module is injected into.
     *
     * <p>There is nothing gentler available from inside: this module has no Activity to finish and
     * cannot ask Spotify's own components to close. The owner chose this, and the next launch
     * reads the restored databases because it opens them fresh.
     */
    private static void stopSpotify() {
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable t) {
            XpLog.log(TAG + " stop failed: " + t);
        }
    }

    /**
     * The cancellable progress dialog both directions share.
     *
     * <p>Dismissing it counts as cancelling: closing the only affordance the owner has must not
     * leave a database copy running in the background with nothing left to stop it.
     */
    private static final class ProgressUi {
        private final ProgressBar bar;
        private final TextView label;
        private final PanelDialog dialog;
        private final Activity activity;
        private final AtomicBoolean cancelled;

        private ProgressUi(Activity activity, PanelDialog dialog, ProgressBar bar, TextView label,
                           AtomicBoolean cancelled) {
            this.activity = activity;
            this.dialog = dialog;
            this.bar = bar;
            this.label = label;
            this.cancelled = cancelled;
        }

        static ProgressUi show(Activity activity, PanelStyle style, String title,
                              AtomicBoolean cancelled) {
            PanelDialog dialog = new PanelDialog(activity, title);
            ProgressBar bar = new ProgressBar(activity, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            dialog.add(bar);
            TextView label = style.text("", 12, PanelDialog.COL_SUMMARY, false);
            label.setPadding(0, style.dp(6), 0, 0);
            dialog.add(label);
            dialog.secondary(SpicyBackup.text(activity, "settings_ai_cancel", "取消"),
                    () -> cancelled.set(true));
            dialog.onDismiss(() -> cancelled.set(true));
            ProgressUi ui = new ProgressUi(activity, dialog, bar, label, cancelled);
            dialog.show();
            return ui;
        }

        SpicyBackup.Progress progress() {
            return new SpicyBackup.Progress() {
                @Override public void onProgress(int done, int total) {
                    final int percent = total <= 0 ? 0 : Math.min(100, done * 100 / total);
                    final String text = total <= 0 ? "" : done + " / " + total;
                    activity.runOnUiThread(() -> {
                        try {
                            bar.setProgress(percent);
                            label.setText(text);
                        } catch (Throwable ignored) {
                        }
                    });
                }

                @Override public boolean isCancelled() {
                    return cancelled.get();
                }
            };
        }

        void close() {
            activity.runOnUiThread(() -> {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
            });
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
