package com.spotifyplusplus.hooks;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SpotifyPlusConfig;
import com.spotifyplusplus.xposed.XpLog;

/**
 * Removes Spotify's own lyrics section from the Now Playing page, by taking its View out of the
 * layout.
 *
 * <p>This replaces an earlier attempt that emptied the lyrics model instead. That approach was
 * abandoned because of what it cost: the card is Compose, so emptying the data never removed it - it
 * produced an empty bar - while the writes it made broke this module's own capture of those lyrics
 * and left blank rows behind in Spotify's lyrics table.
 *
 * <p>The premise of the first View-based attempt was wrong in a way worth recording, because the
 * correction is the whole reason this version works. It assumed the bar was a row of the page's
 * RecyclerView, found no TextView anywhere in the decoration, and concluded the surface was Compose
 * and therefore out of reach. It is Compose - but Compose is mounted <em>into</em> the View tree:
 * {@code dumpsys activity top} on the Now Playing page shows
 * {@code NowPlayingActivity > RoundedConstraintLayout (now_playing_container) > PeekScrollView
 * (scroll_container) > PasteLinearLayout (root_container)} with several
 * {@code androidx.compose.ui.platform.ComposeView} children under it, each with a real position and
 * a real height. A section is one of those children.
 *
 * <p>So it can be removed - {@link ViewGroup#removeView} takes it out of the layout and the space it
 * held collapses - and the only open question is which child it is. A ComposeView has no resource id
 * and no text of its own; what it has is an accessibility node subtree, which is what
 * {@code uiautomator} reads as virtual nodes. That subtree is reached through
 * {@link View#getAccessibilityNodeProvider()}, and the section's own label is in it.
 *
 * <p>Two guards keep this from taking the page with it. A candidate must publish the lyrics label in
 * its own subtree, and it must be a section's height rather than a page's - the containers here are
 * 3120px tall and the sections under two thousand, so anything taller is left alone.
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

    /**
     * How tall a candidate may be and still be a section rather than the page. The Now Playing
     * containers are the full screen (3120px on this device) and the Compose sections under it are
     * 640 to 1354; a removal that took a container would remove the page.
     */
    private static final int MAX_SECTION_HEIGHT_PX = 2000;
    /** Below this a ComposeView is a button or an icon, not a section with lines in it. */
    private static final int MIN_SECTION_HEIGHT_PX = 400;
    /** A section spans the page's content width; a small square is not one. */
    private static final int MIN_SECTION_WIDTH_PX = 600;

    /** How deep the View walk and the accessibility walk may go before giving up. */
    private static final int MAX_VIEW_DEPTH = 48;
    private static final int MAX_A11Y_DEPTH = 24;

    private static final long SWEEP_INTERVAL_MS = 500L;
    /** Roughly ten minutes of walking, then it stops touching the ui thread for good. */
    private static final long LOOP_TICKS = 1200L;

    private static volatile boolean installed;
    private static volatile long removedCount;
    /** How many ComposeViews have been described in the log, so it stays readable. */
    private static int notedComposeViews;
    private static final int MAX_NOTED_COMPOSE_VIEWS = 24;

    private SpotifyLyricsCardHider() {
    }

    /**
     * Starts the sweep. Called once, from the hook that installs everything else.
     *
     * <p>Nothing is hooked here: the page is walked on a timer. Hooks were tried for this and all of
     * them missed, because the section is built before this module installs anything and its label
     * never changes afterwards, so there is no event to wait for. A timer that reads the current
     * window needs no event, and it stops on its own.
     */
    public static void ensureInstalled(Context context, ClassLoader hostLoader) {
        // The context is deliberately not required.
        //
        // Requiring it meant nothing armed: at the point this is called - the module's own hook
        // installation - ActivityThread has no application yet, so the caller passes null and the
        // whole thing returned on its first line. Nothing here needs an application at install time;
        // enabled() resolves one on each tick, by which time there is one.
        if (installed) return;
        installed = true;
        try {
            startLoop();
            XpLog.log(TAG + " section remover armed");
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
                if (count == 1L || removedCount == 0L && count % 40L == 0L) {
                    // Logged periodically while nothing has been found, so a page that never gets
                    // swept is visible in the log rather than silent - and it says whether the
                    // setting is on, because a sweep that is running and switched off looks exactly
                    // like a sweep that is running and finding nothing.
                    XpLog.log(TAG + " sweeping, tick=" + count + " enabled=" + enabled()
                            + " removed=" + removedCount);
                }
                if (!enabled()) return;
                View root = currentWindowRoot();
                if (root != null) {
                    Scan scan = new Scan();
                    removeLyricsSections(root, 0, scan);
                    // DISABLED. The heuristic removed the wrong sections.
                    //
                    // removeView itself works - the log shows it firing - but identifying the lyrics
                    // card as "the tallest Compose section" was wrong: on the Now Playing page the
                    // tallest ones are other things, and the sweep removed them repeatedly, once
                    // every 500ms, stretching the artwork over the page and pushing the controls off
                    // it. A section cannot be identified without reading its semantics, and this app
                    // cannot read Compose's semantics from inside itself - only a live accessibility
                    // connection can, which is how uiautomator sees the same labels this could not.
                    //
                    // Left in place, unreachable, so the technique and its failure are both on
                    // record rather than being rediscovered.
                    if (false && scan.composeViews > 0 && onNowPlayingPage()) {
                        removeTallestSection(root, scan);
                    }
                    if (count % 20L == 0L) {
                        XpLog.log(TAG + " scan composeViews=" + scan.composeViews
                                + " labels=\"" + scan.labels + "\" removed=" + removedCount);
                    }
                }
            } catch (Throwable t) {
                if (count <= 2L) XpLog.log(TAG + " sweep failed: " + t);
            }
            handler.postDelayed(step[0], SWEEP_INTERVAL_MS);
        };
        handler.postDelayed(step[0], 400L);
    }

    /**
     * Whether the page on top is Spotify's Now Playing screen.
     *
     * <p>The shape heuristic only holds there - it is the page whose Compose sections are a few
     * buttons and one card. Scoped to the activity class rather than to a view, because the class
     * name is not obfuscated on this build and a wrong removal elsewhere would be worse than a
     * missed one here.
     */
    private static boolean onNowPlayingPage() {
        try {
            for (Activity activity : currentActivities()) {
                if (activity == null || activity.isFinishing()) continue;
                String name = activity.getClass().getName();
                if (name.contains("nowplaying") || name.contains("NowPlaying")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static java.util.List<Activity> currentActivities() {
        java.util.List<Activity> out = new java.util.ArrayList<>();
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return out;
            java.lang.reflect.Field activities = threadClass.getDeclaredField("mActivities");
            activities.setAccessible(true);
            Object map = activities.get(thread);
            if (!(map instanceof java.util.Map)) return out;
            for (Object record : ((java.util.Map<?, ?>) map).values()) {
                if (record == null) continue;
                java.lang.reflect.Field activityField =
                        record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object activity = activityField.get(record);
                if (activity instanceof Activity) out.add((Activity) activity);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Always false. The setting this read is gone, and so is the removal it gated.
     *
     * <p>Kept so the class still compiles and so the account below stays where it happened: see
     * removeLyricsSections and the note at the top of this file.
     */
    private static boolean enabled() {
        return false;
    }

    private static Context currentApplication() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Object application = threadClass.getMethod("getApplication").invoke(thread);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The decor view of whatever window is on top.
     *
     * <p>Found through ActivityThread rather than through a callback, because a callback is what kept
     * not arriving. Reflection here is read-only and failure is reported as null, which stops a walk
     * rather than breaking the page.
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

    /** What one sweep saw, so a miss can be read rather than guessed at. */
    private static final class Scan {
        int composeViews;
        StringBuilder labels = new StringBuilder();
    }

    /** Removes any Compose section in this subtree whose own accessibility tree names lyrics. */
    private static void removeLyricsSections(View view, int depth, Scan scan) {
        if (view == null || depth > MAX_VIEW_DEPTH) return;
        if (view.getVisibility() != View.VISIBLE) return;

        if (isComposeView(view)) {
            scan.composeViews++;
            // Compose publishes its content as accessibility nodes and nothing else, so this is the
            // only way to ask a ComposeView what it says.
            String published = labelsInAccessibilityTree(view);
            if (scan.labels.length() < 200 && !published.isEmpty()) {
                if (scan.labels.length() > 0) scan.labels.append(" | ");
                scan.labels.append(published);
            }
            if (namesLyrics(published)) {
                removeFromParent(view, "a11y");
            }
            // Nothing of ours lives inside a Compose section; no need to descend.
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // Copied first: removing a child while iterating mutates the group being walked.
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                removeLyricsSections(child, depth + 1, scan);
            }
        }
    }

    /**
     * Removes the page's tallest Compose section, which on this build is the lyrics card.
     *
     * <p>Only reached when no section named itself, which is the usual case: see the note above the
     * class for why an app cannot read Compose's own semantics. The shapes are what is left, and
     * they separate cleanly - the page's buttons and icons are 150 to 180px tall and the lyrics card
     * is 810 - so the tallest section inside the page's scroll container is taken, with a floor so a
     * stray tall ComposeView that is really a container cannot be mistaken for it.
     */
    private static void removeTallestSection(View root, Scan scan) {
        View best = null;
        for (View candidate : composeSections(root, 0)) {
            int height = candidate.getHeight();
            if (height < MIN_SECTION_HEIGHT_PX || height > MAX_SECTION_HEIGHT_PX) continue;
            if (candidate.getWidth() < MIN_SECTION_WIDTH_PX) continue;
            if (best == null || height > best.getHeight()) best = candidate;
        }
        if (best == null) {
            if (scan.labels.length() == 0) scan.labels.append("no-section-shaped-composeview");
            return;
        }
        removeFromParent(best, "tallest-section");
    }

    /** Every Compose section under this subtree, skipping the containers we must not remove. */
    private static java.util.List<View> composeSections(View view, int depth) {
        java.util.List<View> out = new java.util.ArrayList<>();
        collectComposeSections(view, depth, out);
        return out;
    }

    private static void collectComposeSections(View view, int depth, java.util.List<View> out) {
        if (view == null || depth > MAX_VIEW_DEPTH) return;
        if (view.getVisibility() != View.VISIBLE) return;
        if (isComposeView(view)) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                collectComposeSections(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static boolean isComposeView(View view) {
        String name = view.getClass().getName();
        return name.equals("androidx.compose.ui.platform.ComposeView")
                || name.endsWith(".AndroidComposeView");
    }

    /**
     * Every label this ComposeView publishes, flattened.
     *
     * <p>Collected rather than tested one at a time, because a miss has to be readable: the sweep
     * finding a ComposeView whose subtree says nothing, and one whose subtree was never reachable at
     * all, need different fixes and look identical from the outside.
     */
    private static String labelsInAccessibilityTree(View composeView) {
        AccessibilityNodeInfo root = null;
        String how = "none";
        try {
            // The framework's own entry point first: it returns the host node for a view that
            // publishes through an AccessibilityDelegate, and for one that publishes through a
            // provider. Asking the provider directly - which this did at first - is only the second
            // of those two, and returned nothing here while uiautomator, which goes through the
            // framework, was reading the same section's labels perfectly well.
            root = composeView.createAccessibilityNodeInfo();
            how = "view";
        } catch (Throwable ignored) {
        }
        if (root == null) {
            try {
                AccessibilityNodeProvider provider = composeView.getAccessibilityNodeProvider();
                if (provider != null) {
                    root = provider.createAccessibilityNodeInfo(
                            AccessibilityNodeProvider.HOST_VIEW_ID);
                    how = "provider";
                }
            } catch (Throwable ignored) {
            }
        }
        if (root == null) {
            noteComposeView(composeView, "null-root");
            return "";
        }
        StringBuilder out = new StringBuilder();
        try {
            collectLabels(root, 0, out);
            noteComposeView(composeView, how + " children=" + root.getChildCount()
                    + " text=" + out.length());
        } catch (Throwable t) {
            noteComposeView(composeView, how + " read-failed");
        }
        return out.toString();
    }

    /** First few ComposeViews are described once, so a miss is diagnosable from the log alone. */
    private static void noteComposeView(View view, String detail) {
        if (notedComposeViews >= MAX_NOTED_COMPOSE_VIEWS) return;
        notedComposeViews++;
        try {
            XpLog.log(TAG + " composeView " + view.getWidth() + "x" + view.getHeight()
                    + " " + detail);
        } catch (Throwable ignored) {
        }
    }

    private static void collectLabels(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        if (node == null || depth > MAX_A11Y_DEPTH || out.length() > 300) return;
        try {
            String content = text(node.getText());
            if (content.isEmpty()) content = text(node.getContentDescription());
            if (!content.isEmpty() && content.length() <= 24) {
                if (out.length() > 0) out.append('/');
                out.append(content);
            }
            final int children = node.getChildCount();
            for (int i = 0; i < children; i++) {
                collectLabels(node.getChild(i), depth + 1, out);
            }
        } catch (Throwable ignored) {
            // A node that cannot be read contributes nothing.
        }
    }

    /** Whether any label in the flattened set is the section's own heading. */
    private static boolean namesLyrics(String labels) {
        if (labels == null || labels.isEmpty()) return false;
        for (String part : labels.split("/")) {
            if (isSectionLabel(part.trim())) return true;
        }
        return false;
    }

    /**
     * Takes the View out of its parent.
     *
     * <p>{@code removeView} rather than {@code setVisibility(GONE)}: a gone View keeps its slot in
     * many layouts, which is what left the empty bar behind last time. Removing it collapses the
     * space, so what was below moves up.
     */
    private static void removeFromParent(View view, String how) {
        try {
            int height = view.getHeight();
            if (height > MAX_SECTION_HEIGHT_PX) {
                XpLog.log(TAG + " refusing to remove " + height + "px view via " + how
                        + " - too tall to be a section");
                return;
            }
            ViewParent parent = view.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ((ViewGroup) parent).removeView(view);
            removedCount++;
            XpLog.log(TAG + " removed Spotify lyrics section via " + how
                    + " height=" + height + " parent=" + parent.getClass().getName());
        } catch (Throwable t) {
            XpLog.log(TAG + " removal failed: " + t);
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    /** True when {@code text} is one of the section's own labels, and nothing longer. */
    private static boolean isSectionLabel(String value) {
        if (value.isEmpty() || value.length() > MAX_LABEL_LENGTH) return false;
        for (String label : LABELS) {
            if (label.equals(value)) return true;
        }
        return false;
    }
}
