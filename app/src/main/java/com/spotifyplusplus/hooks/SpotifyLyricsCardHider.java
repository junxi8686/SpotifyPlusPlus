package com.spotifyplusplus.hooks;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.spotifyplusplus.lyrics.session.LyricsSourcePreferences;
import com.spotifyplusplus.xposed.XpHooks;
import com.spotifyplusplus.xposed.XpLog;

/**
 * Removes Spotify's own lyrics section from the Now Playing page.
 *
 * <p>Emptying the lyrics model (see {@link NativeLyricsCaptureHook}) took the text, the controls
 * and most of the card with it, and left a short bar reading "歌词". That bar turned out not to be
 * Compose - it is a row in the page's RecyclerView, so it is an ordinary View and can be hidden.
 *
 * <p>Its content is what identifies it: the row contains the section's own label. Matching on that
 * label rather than on a position or a class name is what keeps this working when Spotify reorders
 * the page, and it is also what keeps it from hiding anything else - the row is only hidden when it
 * literally says "Lyrics" or "歌词", and only when it is the short bar at the top of a section.
 *
 * <p>The hook is on RecyclerView layout rather than on a timer, because these rows are recycled:
 * one hidden once comes back the next time it is bound, and layout is exactly when it does.
 */
public final class SpotifyLyricsCardHider {
    private static final String TAG = "[SpotifyPlusLyricsRow]";

    /** Labels Spotify gives that section, in the languages this build could be running in. */
    private static final String[] LABELS = {"歌词", "Lyrics", "歌詞"};

    /**
     * A section label, not a lyric line. The label is a couple of characters; a lyric can be
     * anything, and this keeps a stray line from being mistaken for the heading.
     */
    private static final int MAX_LABEL_LENGTH = 8;

    private static volatile boolean installed;
    /** How often the visible tree is walked, and for how long after the page resumes. */
    private static final long SWEEP_INTERVAL_MS = 500L;
    private static final long SWEEP_WINDOW_MS = 120000L;

    /** Roughly ten minutes of walking, then it stops touching the ui thread for good. */
    private static final long LOOP_TICKS = 800L;

    /** How deep the walk may go on any one branch before giving up. */
    private static final int MAX_DEPTH = 48;

    private SpotifyLyricsCardHider() {
    }

    /**
     * Installs the layout hook once, using the host app's class loader.
     *
     * <p>{@code hostLoader} must be Spotify's, not this module's: RecyclerView is the host's
     * dependency, and asking this module's loader for it throws ClassNotFoundException - which is
     * exactly what happened, leaving the hider installed nowhere and the bar on screen. The caller
     * has a model object from the host in hand, so it passes that object's loader.
     */
    public static void ensureInstalled(Context context, ClassLoader hostLoader) {
        if (installed || context == null) return;
        installed = true;
        // A self-scheduling loop, started here and independent of every event.
        //
        // Three triggers were tried before this and all three produced no log line at all:
        // the label is set while the page is built - earlier than this module ever installs
        // anything - and never changes again, so waiting for setText to carry "歌词" can never
        // succeed; Activity.onResume had already happened by installation time; and hooking
        // setText as a clock never fired either. Each of those was a guess about timing. This is
        // not: the loop starts at installation and walks whatever the current window holds, on a
        // timer, whether or not anything else in the app decides to call back.
        // Retired. It cannot work, and it was not safe either.
        //
        // The premise was wrong: this file assumed the bar was a row of the page's RecyclerView and
        // therefore an ordinary View. Two other files in this module record the opposite from direct
        // observation - Spotify's Now Playing surface is Compose - and the dump that seemed to
        // contradict them is explained by accessibility: Compose paints into a single
        // AndroidComposeView and publishes *virtual* nodes, which is what uiautomator reports as
        // android.widget.TextView. There is no TextView in the real tree, so no walk over it could
        // ever match, whatever the depth or the trigger.
        //
        // It was also unsafe: this module's own Chinese strings contain the exact label being
        // matched, so a walk over the activity decoration would have hidden this module's own rows
        // in the layout editor and the share sheet.
        try {
            XpLog.log(TAG + " view-side hider retired: the bar is Compose, not a View");
        } catch (Throwable t) {
            XpLog.log(TAG + " install failed: " + t);
        }
    }

    /** Walks the current window's view tree on a timer. Safe to call once. */
    private static void startLoop() {
        final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        final long[] ticks = {0L};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            long count = ticks[0]++;
            try {
                if (count > LOOP_TICKS) return;
                View root = currentWindowRoot();
                if (root != null) hideLabelsIn(root, 0);
                if (count == 1) {
                    XpLog.log(TAG + " first walk root=" + (root == null ? "null"
                            : root.getClass().getName()));
                }
            } catch (Throwable t) {
                if (count == 1) XpLog.log(TAG + " walk failed: " + t);
            }
            handler.postDelayed(step[0], SWEEP_INTERVAL_MS);
        };
        handler.postDelayed(step[0], 300L);
    }

    /**
     * The decor view of whatever window is on top.
     *
     * <p>Found through ActivityThread rather than through a callback, because a callback is what
     * kept not arriving. Reflection here is read-only and failure is reported as null, which stops
     * a walk rather than breaking the page.
     */
    private static View currentWindowRoot() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            java.lang.reflect.Field activities = threadClass.getDeclaredField("mActivities");
            activities.setAccessible(true);
            Object map = activities.get(thread);
            if (!(map instanceof java.util.Map)) return null;
            for (Object record : ((java.util.Map<?, ?>) map).values()) {
                if (record == null) continue;
                java.lang.reflect.Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object activity = activityField.get(record);
                if (!(activity instanceof Activity)) continue;
                Activity candidate = (Activity) activity;
                if (candidate.isFinishing()) continue;
                if (candidate.getWindow() == null) continue;
                return candidate.getWindow().getDecorView();
            }
        } catch (Throwable ignored) {
            // Falls through to null: the next tick tries again.
        }
        return null;
    }

    /** Walks one activity's view tree on a timer until it stops being the foreground page. */
    private static void startSweeping(final Activity activity) {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final long started = android.os.SystemClock.uptimeMillis();
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                if (android.os.SystemClock.uptimeMillis() - started > SWEEP_WINDOW_MS) return;
                View root = activity.getWindow() == null ? null
                        : activity.getWindow().getDecorView();
                if (root != null) hideLabelsIn(root, 0);
            } catch (Throwable ignored) {
                // A sweep that throws must not take the ui thread down with it.
            }
            handler.postDelayed(step[0], SWEEP_INTERVAL_MS);
        };
        handler.postDelayed(step[0], SWEEP_INTERVAL_MS);
    }

    /** Hides the bar around any section label found in this subtree. */
    private static void hideLabelsIn(View view, int depth) {
        // Depth budget, not a guess: the label sits under DecorView > content frames > the page's
        // RecyclerView > a card > its header, past twenty levels on this build. The first version
        // stopped at 16, so the walk ran, reached nothing, and logged nothing - which is exactly
        // what the capture showed.
        if (view == null || depth > MAX_DEPTH) return;
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            CharSequence raw = label.getText();
            String text = raw == null ? "" : raw.toString().trim();
            if (isSectionLabel(text)) {
                try {
                    if (!LyricsSourcePreferences.ignoresSpotifyLyrics(label.getContext())) return;
                    View target = stripAround(label);
                    if (target.getVisibility() != View.GONE) {
                        target.setVisibility(View.GONE);
                        XpLog.log(TAG + " hid lyrics bar via sweep '" + text + "' depth=" + depth);
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideLabelsIn(group.getChildAt(i), depth + 1);
            }
        }
    }

    /** True when {@code text} is one of the section's own labels, and nothing longer. */
    private static boolean isSectionLabel(String text) {
        if (text.isEmpty() || text.length() > MAX_LABEL_LENGTH) return false;
        for (String label : LABELS) {
            if (label.equals(text)) return true;
        }
        return false;
    }

    /**
     * The smallest ancestor of {@code label} that still looks like a section header rather than a
     * whole page: climbed while the ancestor stays short, stopped before it grows into the content
     * below it.
     */
    private static View stripAround(TextView label) {
        View best = label;
        android.view.ViewParent parent = label.getParent();
        int guard = 0;
        while (parent instanceof View && guard++ < 8) {
            View candidate = (View) parent;
            if (candidate.getHeight() > 0 && candidate.getHeight() > 500) break;
            best = candidate;
            parent = candidate.getParent();
        }
        return best;
    }
}
