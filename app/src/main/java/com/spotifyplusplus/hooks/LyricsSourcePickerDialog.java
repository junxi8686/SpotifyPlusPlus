package com.spotifyplusplus.hooks;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.spotifyplusplus.ui.SettingsUiStrings;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.catalog.CatalogPickerModel;
import com.spotifyplusplus.lyrics.catalog.CatalogPickerState;
import com.spotifyplusplus.settings.RowSyncPlan;
import com.spotifyplusplus.ui.ActionIconDrawable;
import com.spotifyplusplus.ui.PanelDialog;
import com.spotifyplusplus.xposed.XpLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source picker in the panel's visual language. One {@link CatalogPickerState} owns what the picker
 * shows; every tap, load, and session tick becomes a transition on it, and the rows are patched in
 * place from its projection. The dialog is therefore never closed to show an outcome: a delete
 * confirmation, a pending check, and a loaded result all land on the rows that are already there.
 *
 * <p>Rows are still {@link CatalogPickerModel} output and every action still routes through
 * {@link LyricsHost}, so a selection change flows through the shared session and now playing
 * updates together with fullscreen. Selecting stored data makes zero requests; tapping an unchecked
 * source checks it.
 *
 * <p>Public so Settings can open the same picker scoped to the current song.
 */
public final class LyricsSourcePickerDialog implements LyricsSessionManager.Listener {
    private static final String TAG = "[SpotifyPlusSourcePicker]";
    /** How long a delete stays armed before it reverts on its own. */
    private static final long ARM_EXPIRY_MS = 4000L;

    /** How long a started command may hold its row before the row is released again. */
    private static final long RUN_TIMEOUT_MS = 20000L;

    /** Bumped per started command; used so a completed command stops its own watchdog early. */
    private long runSerial;
    /** A climb that shows no fetch within this window is treated as never started. */
    private static final long CLIMB_SETTLE_MS = 3000L;
    private static final String KEY_CHECK_ALL = CatalogPickerModel.RowKind.ACTION_CHECK_ALL.name();

    /** Where short confirmations land (the fullscreen status line). */
    public interface StatusSink {
        void show(String message);
    }

    /** One session command, run with the callback that reports its outcome. */
    private interface ActionStart {
        void run(LyricsHost.CatalogActionCallback callback);
    }

    private final Activity activity;
    private final LyricsHost host;
    private final SettingsUiStrings strings;
    private final StatusSink status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Mounted row views by state key, in mount order; the sync plan reads it as current keys. */
    private final Map<String, RowView> mounted = new LinkedHashMap<>();

    private PanelDialog dialog;
    private LinearLayout rows;
    private CatalogPickerState state;
    private LyricsSessionManager.SessionSubscription subscription;
    private LyricsSessionManager.PollingDemandLease lease;
    private boolean closed;
    private LyricsHost.CatalogActionCallback opening;
    /** The last session tick seen; ticks arrive every 200 ms, so only a change reloads. */
    private boolean tickPrimed;
    private String tickTrackUri = "";
    private int tickGeneration;
    private String tickStatus = "";
    private boolean tickFetchInFlight;

    private LyricsSourcePickerDialog(Activity activity, LyricsHost host, SettingsUiStrings strings,
                                     StatusSink status) {
        this.activity = activity;
        this.host = host;
        this.strings = strings;
        this.status = status;
    }

    public static void show(Activity activity, LyricsHost host, SettingsUiStrings strings,
                            StatusSink status) {
        if (activity == null || host == null) return;
        new LyricsSourcePickerDialog(activity, host, strings, status).open();
    }

    static LyricsSourcePickerDialog showForAgent(Activity activity, LyricsHost host,
            SettingsUiStrings strings, LyricsHost.CatalogActionCallback opening) {
        LyricsSourcePickerDialog picker = new LyricsSourcePickerDialog(activity, host, strings,
                message -> { });
        picker.opening = opening;
        picker.open();
        return picker;
    }

    boolean isOpen() {
        return !closed && dialog != null;
    }

    boolean isShowing() {
        return isOpen() && dialog.isShowing();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        close();
    }

    private void completeOpening(boolean success, String detail) {
        LyricsHost.CatalogActionCallback callback = opening;
        opening = null;
        if (callback != null) callback.onComplete(success, detail);
    }

    // --- Lifecycle ---

    /** Opens the picker for the session's current track; no track means nothing to choose. */
    private void open() {
        try {
            String uri = host.catalogTrackUri();
            if (uri == null || uri.isEmpty()) {
                completeOpening(false, "No current track");
                close();
                return;
            }
            state = CatalogPickerState.initial(uri);
            dialog = new PanelDialog(activity,
                    text(strings, "source_picker_title", "Choose lyrics source"));
            rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            dialog.add(rows);
            // Each row carries the panel's 8 dp gap; the container must not add a second one.
            ((LinearLayout.LayoutParams) rows.getLayoutParams()).bottomMargin = 0;
            dialog.onDismiss(this::close);
            reload();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker show failed: " + t);
            close();
        }
    }

    /** The picker is showing; ticks are what keep its rows honest, and the lease makes them come. */
    private void present() {
        dialog.show();
        completeOpening(true, "opened track=" + state.trackUri);
        XpLog.log(TAG + " picker opened rows=" + rows.getChildCount());
        try {
            subscription = host.subscribeLyricsSession(this);
            lease = host.acquireLyricsPollingDemand();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker watch failed: " + t);
        }
    }

    /** The user closed the picker, so nothing keeps ticking or loading on its behalf. */
    private void close() {
        completeOpening(false, "picker closed before opening");
        closed = true;
        handler.removeCallbacksAndMessages(null);
        release(subscription);
        release(lease);
        subscription = null;
        lease = null;
    }

    private static void release(AutoCloseable held) {
        if (held == null) return;
        try {
            held.close();
        } catch (Throwable ignored) {
        }
    }

    // --- Rows ---

    /** Asks for a fresh set of rows: a new serial, then the load that carries it. */
    private void reload() {
        if (closed) return;
        dispatch(state.requestLoad());
        load();
    }

    /** Fetches the rows the current serial asked for. The reducer drops stale and superseded ones. */
    private void load() {
        if (closed) return;
        final int serial = state.loadSerial();
        try {
            host.loadCatalogPickerRows((uri, loaded) -> {
                if (closed) return;
                try {
                    dispatch(state.withRows(uri, serial, loaded));
                } catch (Throwable t) {
                    XpLog.log(TAG + " picker render failed: " + t);
                    completeOpening(false, "picker render failed");
                    dismiss();
                }
            });
        } catch (Throwable t) {
            XpLog.log(TAG + " picker load failed: " + t);
            completeOpening(false, "picker load failed");
            dismiss();
        }
    }

    /**
     * Fetches rows for a serial a transition already requested. A tick that only observed fetch
     * activity raises no serial, so nothing is asked for there.
     */
    private void loadIfSerialRose(int before) {
        if (!closed && state.loadSerial() > before) load();
    }

    private void dispatch(CatalogPickerState next) {
        if (closed || next == state) return;
        state = next;
        render();
    }

    // --- Session ---

    @Override
    public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {
        try {
            if (closed || snapshot == null) return;
            String uri = snapshot.trackUri == null ? "" : snapshot.trackUri;
            boolean inFlight = host.catalogFetchInFlight();
            // The first tick only records the baseline: the opening load already read this state.
            boolean tickChanged = tickPrimed
                    && (!uri.equals(tickTrackUri) || snapshot.generation != tickGeneration
                    || !same(snapshot.status, tickStatus) || inFlight != tickFetchInFlight);
            tickPrimed = true;
            tickTrackUri = uri;
            tickGeneration = snapshot.generation;
            tickStatus = snapshot.status;
            tickFetchInFlight = inFlight;
            if (!uri.equals(state.trackUri)) {
                dispatch(state.trackChanged(uri));
                reload();
                return;
            }
            int before = state.loadSerial();
            dispatch(state.fetchObserved(inFlight));
            loadIfSerialRose(before);
            if (tickChanged) reload();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker session tick failed: " + t);
        }
    }

    @Override
    public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot,
                                  LyricsDocument document) {
        try {
            reload();
        } catch (Throwable t) {
            XpLog.log(TAG + " picker document change failed: " + t);
        }
    }

    // --- Rendering ---

    /** Keyed sync: stale views out, mounted views patched, missing ones built, order projected. */
    private void render() {
        if (closed || dialog == null || rows == null) return;
        List<CatalogPickerState.Shown> shown = state.project(checkingLabel(), confirmLabel());
        Map<String, CatalogPickerState.Shown> byKey = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(shown.size());
        for (CatalogPickerState.Shown row : shown) {
            byKey.put(row.key, row);
            keys.add(row.key);
        }
        RowSyncPlan plan = RowSyncPlan.of(keys, new ArrayList<>(mounted.keySet()));
        for (String dead : plan.removals) {
            RowView stale = mounted.remove(dead);
            if (stale != null) rows.removeView(stale.root);
        }
        for (int index = 0; index < plan.order.size(); index++) {
            String key = plan.order.get(index);
            CatalogPickerState.Shown row = byKey.get(key);
            if (row == null) continue;
            RowView view = mounted.get(key);
            if (view == null) {
                view = new RowView(key);
                mounted.put(key, view);
            }
            patch(view, row);
            // Move only a misplaced row: detaching drops accessibility focus and touch state.
            if (rows.indexOfChild(view.root) != index) {
                rows.removeView(view.root);
                rows.addView(view.root, Math.min(index, rows.getChildCount()), view.params);
            }
        }
        if (!dialog.isShowing() && !shown.isEmpty()) present();
        // Subtitles change height as rows move between states, so the card refits every render.
        if (dialog.isShowing()) dialog.refit();
    }

    private void patch(RowView view, CatalogPickerState.Shown shown) {
        CatalogPickerModel.Row row = shown.row;
        view.title.setText((row.selected ? "✓ " : "") + row.title);
        view.title.setTextColor(row.selected ? PanelDialog.COL_ACCENT : PanelDialog.COL_TITLE);
        view.subtitle.setText(row.subtitle);
        patchTrailing(view, row);
        view.root.setContentDescription(row.title + ", " + row.subtitle
                + (row.selected ? ", selected" : ""));
        view.root.setEnabled(!shown.pending);
        view.root.setAlpha(shown.pending ? 0.5f : 1f);
        // Holding a row is only meaningful for a stored candidate; every other row clears the menu.
        boolean holdable = shown.source.kind == CatalogPickerModel.RowKind.SOURCE
                && shown.source.stored;
        view.root.setOnLongClickListener(holdable ? v -> openMenu(shown.source) : null);
        view.root.setLongClickable(holdable);
    }

    /**
     * Right-aligned status glyph, created only for a row that needs one. Stored sources show a
     * green circle-tick, fetched-but-empty ones an X, and the delete action a trash icon; rows
     * with no outcome have none, so every row's text starts at the same inset.
     */
    private void patchTrailing(RowView view, CatalogPickerModel.Row row) {
        ActionIconDrawable.Kind kind;
        int color;
        if (row.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK) {
            kind = ActionIconDrawable.Kind.DELETE;
            color = PanelDialog.COL_SUMMARY;
        } else if (row.kind == CatalogPickerModel.RowKind.SOURCE
                && row.mark != CatalogPickerModel.DataMark.NONE) {
            boolean have = row.mark == CatalogPickerModel.DataMark.HAVE;
            kind = have ? ActionIconDrawable.Kind.CIRCLE_CHECK : ActionIconDrawable.Kind.CLOSE;
            color = have ? PanelDialog.COL_ACCENT : PanelDialog.COL_SUMMARY;
        } else {
            if (view.trailing != null) {
                view.root.removeView(view.trailing);
                view.trailing = null;
            }
            return;
        }
        if (view.trailing == null) {
            ImageView icon = new ImageView(activity);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconParams.leftMargin = dp(8);
            icon.setLayoutParams(iconParams);
            view.root.addView(icon);
            view.trailing = icon;
        }
        view.trailing.setImageDrawable(new ActionIconDrawable(kind, color, density()));
    }

    private String checkingLabel() {
        return text(strings, "source_picker_checking", "Checking") + "…";
    }

    private String confirmLabel() {
        return text(strings, "source_picker_confirm_delete", "Tap again to delete");
    }

    /**
     * The names of the sources the phrase will actually be sent to, as one line.
     *
     * <p>The phrase is not addressed to one provider, and which providers are asked is a setting
     * the owner may not have looked at recently - so the panel says both, rather than leaving them
     * to work out whose spelling to type and where it will go.
     */
    private String enabledSourceNames() {
        StringBuilder names = new StringBuilder();
        try {
            com.spotifyplusplus.lyrics.catalog.CatalogPickerModel.Text adapter =
                    (name, fallback) -> text(strings, name, fallback);
            for (com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId source
                    : com.spotifyplusplus.lyrics.catalog.CatalogPolicy.read(activity).enabledOrder) {
                if (names.length() > 0) names.append(" / ");
                names.append(com.spotifyplusplus.lyrics.catalog.CatalogPickerModel
                        .displaySource(source, adapter));
            }
        } catch (Throwable ignored) {
            return "";
        }
        return names.toString();
    }

    /**
     * The one place a model is never asked, and no threshold decides anything.
     *
     * <p>Every automatic path in this module answers "is this the same recording?" - by score, by
     * script folding, or by a model. All three can be wrong in the case that actually happens: a
     * release catalogued under a different name. "反乌托邦 - 拼接版" against "拼接乌托邦" is not a
     * spelling difference and no amount of folding reaches it.
     *
     * <p>So here the owner is simply shown what the provider returned - title and performers, as
     * the provider wrote them - and picks the row themselves. The choice is then fetched by id,
     * with no search, no ranking and no arbiter in between.
     */
    private void promptManualSearch(String key) {
        final com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable[] chosen = {
                com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable.NETEASE};
        final PanelDialog prompt = new PanelDialog(activity,
                text(strings, "source_picker_manual_title", "Search lyrics by name"));
        prompt.paragraph(text(strings, "source_picker_manual_help",
                "Searched exactly as typed, in the one source you pick, with no matching and no "
                        + "model involved: every result comes back and you choose. "
                        + "Applies to this track only."));
        prompt.paragraph(text(strings, "source_picker_manual_source", "Search in"));
        // All of them listed at once, each a row that selects on tap. The earlier shape hid them
        // behind a dropdown whose only feedback was its own label - so picking a second source
        // looked like nothing had happened, and the label went on naming the first one.
        final java.util.List<TextView> marks = new java.util.ArrayList<>();
        for (final com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable source
                : com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable.values()) {
            final int index = marks.size();
            marks.add(prompt.checkRow(source.zhName, "", () -> {
                chosen[0] = source;
                for (int i = 0; i < marks.size(); i++) {
                    marks.get(i).setText(i == index ? "✓" : "");
                }
            }));
        }
        marks.get(0).setText("✓");
        final android.widget.EditText field = prompt.field(false, "",
                text(strings, "source_picker_manual_hint", "Type the song title here"));
        prompt.primary(text(strings, "source_picker_manual_search", "Search"), () -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) return;
            runManualSearch(key, chosen[0], value, null, 0);
        });
        prompt.secondary(text(strings, "settings_ai_cancel", "Cancel"), null);
        prompt.show();
    }

    /** Runs the phrase against one provider and shows everything it returned, unranked. */
    private void runManualSearch(final String key,
                                 final com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable source,
                                 final String query, final TextView which, final int offset) {
        final okhttp3.OkHttpClient http = manualHttp();
        okhttp3.Request request;
        if (source == com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable.QQ) {
            request = new okhttp3.Request.Builder()
                    .url(source.urlTemplate)
                    .post(okhttp3.RequestBody.create(
                            com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.qqRequestBody(query),
                            okhttp3.MediaType.parse("application/json; charset=utf-8")))
                    .header("User-Agent", MANUAL_UA)
                    .header("Referer", "https://y.qq.com/")
                    .build();
        } else {
            // NetEase pages by offset and is the only one of the three that does; the template
            // carries a literal offset=0 that later pages replace.
            String url = String.format(source.urlTemplate, android.net.Uri.encode(query));
            if (offset > 0) url = url.replace("offset=0", "offset=" + offset);
            request = new okhttp3.Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", MANUAL_UA)
                    .header("Referer", source == com.spotifyplusplus.lyrics.providers
                            .ManualLyricsSearch.Searchable.NETEASE
                            ? "https://music.163.com/" : "https://lrclib.net/")
                    .build();
        }
        status(text(strings, "source_picker_manual_searching", "Searching…"));
        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                handler.post(() -> status(text(strings, "source_picker_manual_failed",
                        "Search failed")));
            }

            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                final String body;
                try {
                    body = response.body() == null ? "" : response.body().string();
                } catch (Throwable t) {
                    handler.post(() -> status(text(strings, "source_picker_manual_failed",
                            "Search failed")));
                    return;
                }
                final java.util.List<com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Hit> hits =
                        com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.parse(source, body);
                handler.post(() -> showManualResults(key, source, hits, which, 0, query));
            }
        });
    }

    /** Third step: the provider's own list, one tappable row per result. */
    private void showManualResults(final String key,
                                   final com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable source,
                                   final java.util.List<com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Hit> hits,
                                   final TextView which, final int offset, final String query) {
        if (closed) return;
        final PanelDialog results = new PanelDialog(activity, source.zhName);
        if (hits.isEmpty() && offset == 0) {
            results.paragraph(text(strings, "source_picker_manual_none",
                    "That source returned nothing for this phrase. Try how it spells the title."));
            results.secondary(text(strings, "lyrics_ai_close", "Close"), null);
            results.show();
            return;
        }
        results.paragraph(text(strings, "source_picker_manual_pick",
                "Everything it returned, in its own words. Pick the row that is this recording."));
        for (final com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Hit hit : hits) {
            results.resultRow(hit.title, hit.artists, () -> {
                results.dismiss();
                selectManualHit(key, source, hit, which);
            });
        }
        // A mistyped title usually still lands near the right answer, so the list is not treated as
        // final: more pages stay reachable as long as the provider keeps returning rows.
        if (source == com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable.NETEASE
                && hits.size() >= 5) {
            results.checkRow(text(strings, "source_picker_manual_more", "Load more…"), "",
                    () -> {
                        results.dismiss();
                        runManualSearch(key, source, query, which, offset + hits.size());
                    });
        }
        results.secondary(text(strings, "settings_ai_cancel", "Cancel"), null);
        results.show();
    }

    /** The owner's pick: fetch exactly that id, with no search and no matching in the way. */
    private void selectManualHit(String key,
                                 com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Searchable source,
                                 com.spotifyplusplus.lyrics.providers.ManualLyricsSearch.Hit hit,
                                 TextView which) {
        com.spotifyplusplus.lyrics.providers.ManualLyricsQuery.armExact(
                source.id, hit.itemId, state.trackUri);
        if (which != null) which.setText(source.zhName);
        com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId catalogId;
        switch (source) {
            case QQ:
                catalogId = com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId.QQ;
                break;
            case LRCLIB:
                catalogId = com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId.LRCLIB;
                break;
            default:
                catalogId = com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId.NETEASE;
                break;
        }
        final com.spotifyplusplus.lyrics.catalog.CatalogSource.SourceId target = catalogId;
        run(key, callback -> host.refreshCatalogSource(target, callback),
                text(strings, "source_picker_manual_applied", "Using the lyrics you picked"));
    }

    /** One client for the chooser; it runs at most a couple of requests per use. */
    private static volatile okhttp3.OkHttpClient manualClient;

    private static okhttp3.OkHttpClient manualHttp() {
        okhttp3.OkHttpClient client = manualClient;
        if (client == null) {
            synchronized (LyricsSourcePickerDialog.class) {
                client = manualClient;
                if (client == null) {
                    client = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    manualClient = client;
                }
            }
        }
        return client;
    }

    private static final String MANUAL_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    // --- Taps ---

    private void onTap(String key) {
        try {
            CatalogPickerState.Shown row = shown(key);
            if (row == null) return;
            if (row.source.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK) {
                if (key.equals(state.armedKey)) {
                    run(key, host::deleteCatalogTrack,
                            text(strings, "source_picker_deleted", "Deleted saved lyrics"));
                    return;
                }
                CatalogPickerState armedState = state.armed(key);
                if (armedState == state) return;
                dispatch(armedState);
                final int armSerial = state.armSerial();
                handler.postDelayed(() -> dispatch(state.armExpired(armSerial)), ARM_EXPIRY_MS);
                return;
            }
            dispatch(state.disarmed());
            CatalogPickerModel.Row source = row.source;
            switch (source.kind) {
                case AUTO:
                    run(key, host::resetCatalogToAuto,
                            text(strings, "source_picker_selected", "Source selected"));
                    break;
                case SOURCE:
                    // Seating a stored candidate is only meaningful when the source actually
                    // produced usable text. A fetch that failed can still leave a non-empty
                    // candidate behind, and seating one of those is how a screen ends up showing a
                    // failed source as green with something that is not lyrics in it. The row still
                    // works: it falls through to the re-check below.
                    if (source.stored && !source.candidateId.isEmpty()
                            && source.mark == CatalogPickerModel.DataMark.HAVE) {
                        run(key, callback -> host.selectCatalogCandidate(source.candidateId,
                                callback), text(strings, "source_picker_selected",
                                "Source selected"));
                    } else if (source.checkable && source.sourceId != null) {
                        run(key, callback -> host.refreshCatalogSource(source.sourceId, callback),
                                text(strings, "source_picker_checked", "Source checked"));
                    }
                    break;
                case ACTION_CHECK_ALL:
                    run(key, host::refreshAllCatalogSourcesInOrder,
                            text(strings, "source_picker_checked", "Source checked"));
                    break;
                case ACTION_MANUAL_SEARCH:
                    promptManualSearch(key);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " picker action failed: " + t);
        }
    }

    /**
     * Starts one session command for a row. The track a command acts on is the session's, so the
     * guard read and the call happen in one main-thread frame: a command can never be issued for
     * the track the rows belonged to after the user has already moved on.
     */
    private void run(String key, ActionStart action, String successMessage) {
        if (closed || !state.canStart(key)) return;
        try {
            String uri = host.catalogTrackUri();
            if (uri == null || !uri.equals(state.trackUri)) {
                dispatch(state.trackChanged(uri == null ? "" : uri));
                reload();
                status(text(strings, "source_picker_track_changed", "Track changed"));
                return;
            }
            dispatch(state.started(key));
            // Watchdog. The ordered climb walks every enabled source in turn, and one of them can
            // simply never call back - Apple Music's fetch has been seen to leave the whole chain
            // waiting forever, which parked the panel on "checking" with no way out but closing it.
            // A row that is still running after this long is released so the owner can try again;
            // the command itself is untouched and reports into a picker that is showing its rows
            // again, exactly as it does for a slow-but-successful climb.
            final long serial = ++runSerial;
            handler.postDelayed(() -> {
                // Only the closed check. The serial guard that used to stand here made every
                // earlier watchdog give up the moment any other command finished: each one
                // bumped the serial, so a row whose command never called back had nobody left
                // to release it and sat on "checking" for good. finished() already does the
                // right thing when it is called twice or for a row that is not pending - it
                // returns unchanged - so calling it unconditionally is the safe direction.
                if (closed) return;
                // finished() and not disarmed(): only finished() takes the key out of the
                // pending set, and until it is out canStart(key) stays false - so the row
                // would stay locked on "checking" and the retry this message offers
                // would do nothing. That is the exact case the watchdog was written for.
                dispatch(state.finished(key, false));
                load();
                status(text(strings, "source_picker_timed_out", "Timed out — tap to try again"));
            }, RUN_TIMEOUT_MS);
            action.run(new LyricsHost.CatalogActionCallback() {
                @Override public void onProgress(String detail) {
                    // The ordered climb reports each source as it lands; reread rows so the
                    // picker stays live instead of waiting for the final outcome.
                    handler.post(() -> {
                        if (!closed) load();
                    });
                }

                @Override public void onComplete(boolean success, String detail) {
                    if (serial == runSerial) runSerial++;
                    complete(key, success, detail, successMessage);
                }
            });
        } catch (Throwable error) {
            XpLog.log(TAG + " picker action failed: " + error);
            complete(key, false, error.getMessage(), successMessage);
        }
    }

    /** One command's outcome: report it, release the row, then read the store it wrote. */
    private void complete(String key, boolean success, String detail, String successMessage) {
        try {
            // The detail is a provider failure, which is reported in English because the fetch
            // classifiers match on those phrases. It is translated here, on the way to the toast.
            String failure = detail == null || detail.isEmpty()
                    ? text(strings, "source_picker_failed", "Could not update source")
                    : com.spotifyplusplus.ui.LyricsErrorText.localize(detail);
            // The outcome is reported even after the picker closed; only the rows stop updating.
            status(success ? successMessage : failure);
            if (closed) return;
            dispatch(state.finished(key, success));
            load();
            if (success && isCheckAll(key)) {
                handler.postDelayed(this::settleClimb, CLIMB_SETTLE_MS);
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " picker action callback failed: " + t);
        }
    }

    /** An ordered climb that never showed a fetch settled without one; clear it and reread rows. */
    private void settleClimb() {
        if (closed) return;
        int before = state.loadSerial();
        dispatch(state.climbNeverStarted());
        loadIfSerialRose(before);
    }

    private static boolean isCheckAll(String key) {
        return KEY_CHECK_ALL.equals(key);
    }

    private CatalogPickerState.Shown shown(String key) {
        if (key == null || key.isEmpty()) return null;
        for (CatalogPickerState.Shown row : state.project(checkingLabel(), confirmLabel())) {
            if (key.equals(row.key)) return row;
        }
        return null;
    }

    // --- Long press ---

    /** Per-candidate actions over one stored row; the picker itself stays open behind the menu. */
    private boolean openMenu(CatalogPickerModel.Row row) {
        try {
            final String key = CatalogPickerState.key(row);
            final String candidateId = row.candidateId;
            PanelDialog menu = new PanelDialog(activity, row.title);
            menu.add(menuRow(text(strings, "source_picker_reject_wrong_match",
                    "Reject wrong match"), () -> {
                menu.dismiss();
                run(key, callback -> host.rejectCatalogCandidate(candidateId, callback),
                        text(strings, "source_picker_rejected", "Rejected match"));
            }));
            menu.add(menuRow(text(strings, "source_picker_remove_saved_candidate",
                    "Remove saved candidate"), () -> {
                menu.dismiss();
                run(key, callback -> host.removeCatalogCandidate(candidateId, callback),
                        text(strings, "source_picker_removed", "Removed saved candidate"));
            }));
            menu.show();
            return true;
        } catch (Throwable t) {
            XpLog.log(TAG + " picker hold menu failed: " + t);
            return false;
        }
    }

    private LinearLayout menuRow(String label, Runnable action) {
        int pad = dp(12);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, dp(10), pad, dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(PanelDialog.COL_TITLE);
        title.setTextSize(16);
        row.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setContentDescription(label);
        row.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        return row;
    }

    // --- Rows as views ---

    /** One mounted row; the views are kept so a rerender patches them instead of rebuilding. */
    private final class RowView {
        final LinearLayout root;
        final TextView title;
        final TextView subtitle;
        final LinearLayout.LayoutParams params;
        ImageView trailing;

        RowView(String key) {
            int pad = dp(12);
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(pad, dp(10), pad, dp(10));
            root.setClickable(true);
            root.setFocusable(true);
            // The listener captures the stable key only; the row it means is read on tap.
            root.setOnClickListener(v -> onTap(key));

            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.rightMargin = dp(8);

            title = new TextView(activity);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            labels.addView(title);

            subtitle = new TextView(activity);
            subtitle.setTextColor(PanelDialog.COL_SUMMARY);
            subtitle.setTextSize(13);
            labels.addView(subtitle);

            root.addView(labels, labelParams);
            params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
        }
    }

    // --- Shared helpers ---

    private int dp(int value) {
        return Math.round(value * density());
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String text(SettingsUiStrings strings, String name, String fallback) {
        try {
            return strings == null ? fallback : strings.get(name, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void status(String message) {
        if (status == null) return;
        try {
            status.show(message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }
}
