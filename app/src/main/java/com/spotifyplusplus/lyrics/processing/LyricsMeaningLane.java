package com.spotifyplusplus.lyrics.processing;

import com.spotifyplusplus.lyrics.language.ProviderTextDetectionStore;
import com.spotifyplusplus.lyrics.language.TextDetectionLookup;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.spotifyplusplus.Diagnostics;
import com.spotifyplusplus.lyrics.ai.AiCancelledException;
import com.spotifyplusplus.lyrics.ai.AiMeaningRun;
import com.spotifyplusplus.lyrics.ai.AiLiveMonitor;
import com.spotifyplusplus.lyrics.ai.AiRunOutcome;
import com.spotifyplusplus.lyrics.ai.AiRunMonitor;
import com.spotifyplusplus.lyrics.ai.AiRequestLiveState;
import com.spotifyplusplus.lyrics.ai.AiRuntimeFailureLog;
import com.spotifyplusplus.lyrics.ai.AiSettings;
import com.spotifyplusplus.lyrics.ai.AiSignal;
import com.spotifyplusplus.lyrics.ai.AiText;
import com.spotifyplusplus.lyrics.session.CanonicalBase;
import com.spotifyplusplus.lyrics.session.CanonicalRow;
import com.spotifyplusplus.lyrics.session.LayerAuthority;
import com.spotifyplusplus.lyrics.session.LayerFailure;
import com.spotifyplusplus.lyrics.session.LayerKind;
import com.spotifyplusplus.lyrics.session.LayerProvenance;
import com.spotifyplusplus.lyrics.session.MeaningArtifact;
import com.spotifyplusplus.lyrics.session.MeaningEntry;
import com.spotifyplusplus.lyrics.session.LayerRunCoalescer;
import com.spotifyplusplus.lyrics.session.LayerRunIdentity;
import com.spotifyplusplus.lyrics.session.LyricPipelineMetrics;

import com.spotifyplusplus.xposed.XpLog;
import okhttp3.OkHttpClient;
import static com.spotifyplusplus.lyrics.LyricUtils.isBlank;
import static com.spotifyplusplus.lyrics.LyricUtils.safe;
import com.spotifyplusplus.lyrics.LyricUtils;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

/**
 * The Meaning lane: machine translation behind a backend contract.
 *
 * <p>Owns its own executor, run bookkeeping, cache identity, and completion. Original lyrics and
 * the Sound layer never wait for it, and a failure here leaves both untouched and usable.
 */
public final class LyricsMeaningLane {
    private static final String TAG = "[SpotifyPlusMeaningLane]";
    /** Diagnostic-capture component for the AI side of this lane. */
    private static final String AI_COMPONENT = "ai_meaning";
    private static final int TRANSLATION_BATCH_MAX_LINES = 100;
    private static final int TRANSLATION_BATCH_MAX_CHARS = 4500;
    private static final int TRANSLATION_RETRY_MAX_LINES = 12;
    private static final int TRANSLATION_RETRY_MAX_CHARS = 1200;

    /**
     * A machine Meaning backend. Google is one peer behind this contract; nothing above the lane
     * knows which backend answered.
     */
    public interface MeaningProvider {
        /** Stable backend identity for provenance and cache config. */
        String backendId();

        boolean handles(String backendSetting);

        /** @param cancelTag identifies this run's calls so a retired run can cancel them */
        GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http, int processingVersion,
                                             String trackId, String sourceLang, String targetLang,
                                             List<GoogleEnhancer.BatchLine> batch, String cancelTag);

        /** Aborts every in-flight call this backend started under {@code cancelTag}. */
        void cancel(OkHttpClient http, String cancelTag);

        boolean shouldDisplay(String sourceText, String translated);

        /**
         * Lowercase token naming this backend in diagnostics and in user-facing status text.
         *
         * <p>Defaults to the backend id so a test provider needs nothing; the two shipped backends
         * spell it the way their device logs already do.
         */
        default String logToken() {
            return backendId();
        }
    }

    /** The unofficial Google endpoint, kept behind the provider contract. */
    public static final class GoogleMeaningProvider implements MeaningProvider {
        @Override public String backendId() {
            return "google_unofficial";
        }

        @Override public boolean handles(String backendSetting) {
            return "google_unofficial".equalsIgnoreCase(backendSetting);
        }

        @Override public GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http,
                                                              int processingVersion, String trackId,
                                                              String sourceLang, String targetLang,
                                                              List<GoogleEnhancer.BatchLine> batch,
                                                              String cancelTag) {
            return GoogleEnhancer.translateBatch(context, http, processingVersion, trackId,
                    sourceLang, targetLang, batch, cancelTag);
        }

        @Override public void cancel(OkHttpClient http, String cancelTag) {
            GoogleEnhancer.cancelTagged(http, cancelTag);
        }

        @Override public boolean shouldDisplay(String sourceText, String translated) {
            return GoogleEnhancer.shouldDisplayTranslation(sourceText, translated);
        }

        @Override public String logToken() {
            return "google";
        }
    }

    /** The unofficial Baidu endpoint, the peer that answers where Google is unreachable. */
    public static final class BaiduMeaningProvider implements MeaningProvider {
        @Override public String backendId() {
            return "baidu_unofficial";
        }

        @Override public boolean handles(String backendSetting) {
            return "baidu_unofficial".equalsIgnoreCase(backendSetting);
        }

        @Override public GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http,
                                                              int processingVersion, String trackId,
                                                              String sourceLang, String targetLang,
                                                              List<GoogleEnhancer.BatchLine> batch,
                                                              String cancelTag) {
            return BaiduTranslateBackend.translateBatch(context, http, processingVersion, trackId,
                    sourceLang, targetLang, batch, cancelTag);
        }

        @Override public void cancel(OkHttpClient http, String cancelTag) {
            BaiduTranslateBackend.cancelTagged(http, cancelTag);
        }

        @Override public boolean shouldDisplay(String sourceText, String translated) {
            return BaiduTranslateBackend.shouldDisplayTranslation(sourceText, translated);
        }

        @Override public String logToken() {
            return "baidu";
        }
    }

    /**
     * Every backend behind the contract, in the order a stored setting is matched against them.
     * Google stays first: it is the shipped default, so an install that never touches the setting
     * resolves to exactly the provider it used before a second one existed.
     */
    private static final List<MeaningProvider> REGISTERED = Collections.unmodifiableList(Arrays.asList(
            new GoogleMeaningProvider(), new BaiduMeaningProvider()));

    /** The registered backend that answers {@code backendSetting}, or null when none claims it. */
    public static MeaningProvider providerFor(String backendSetting) {
        return providerFor(REGISTERED, backendSetting);
    }

    /**
     * True when {@code backendSetting} names a backend this lane can run. The document processor
     * needs that answer before the lane is reached: it decides there whether a document has
     * machine translation pending at all, and "provider" or "disabled" must keep reading as no.
     */
    public static boolean isMachineBackend(String backendSetting) {
        return providerFor(REGISTERED, backendSetting) != null;
    }

    private static MeaningProvider providerFor(List<MeaningProvider> providers, String backendSetting) {
        if (backendSetting == null) return null;
        for (MeaningProvider candidate : providers) {
            if (candidate != null && candidate.handles(backendSetting)) return candidate;
        }
        return null;
    }

    /**
     * Process-wide, because fullscreen, now-playing, and HyperGlow each hold their own lane
     * instance. Identical Meaning work must cost one provider run no matter how many surfaces ask.
     */
    private static final LayerRunCoalescer COALESCER = new LayerRunCoalescer();

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService laneExecutor;
    /** AI generation runs here so a 60s call never occupies a translation worker. */
    private final ExecutorService aiExecutor;
    private final Poster poster;
    /** Elapsed-realtime source for run metrics; injectable so JVM tests control time. */
    private final LongSupplier clock;
    private final int processingVersion;
    /** The backends this lane may use; a run resolves the stored setting against this list. */
    private final List<MeaningProvider> providers;
    private final AiSettingsSource settingsSource;
    /** Retires earlier runs of this lane: only the newest sequence may publish. */
    private final AtomicLong laneSequence = new AtomicLong();
    /** Call tag of the run currently allowed to publish; empty when the lane is idle. */
    private volatile String activeTag = "";
    /** Cancels the AI run in flight, if this lane started one. */
    private volatile AiSignal aiSignal;

    public LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                             ExecutorService aiExecutor, Handler handler, int processingVersion) {
        this(context, http, laneExecutor, aiExecutor, processingVersion, REGISTERED,
                handler == null ? null : handler::post, SystemClock::elapsedRealtime,
                AiSettings::new);
    }

    public LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                             ExecutorService aiExecutor, Handler handler, int processingVersion,
                             MeaningProvider provider) {
        this(context, http, laneExecutor, aiExecutor, processingVersion,
                provider == null ? Collections.<MeaningProvider>emptyList()
                        : Collections.singletonList(provider),
                handler == null ? null : handler::post, SystemClock::elapsedRealtime,
                AiSettings::new);
    }

    /**
     * Test seam: an explicit surface dispatcher and clock, so a JVM test can publish callbacks
     * without a Looper and read deterministic durations, plus a configuration source so a JVM test
     * can offer a credential the Android Keystore cannot mint off-device. Production uses the two
     * constructors above, which supply the real Handler, {@link SystemClock}, and the device's
     * {@link AiSettings}.
     */
    LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                      ExecutorService aiExecutor, int processingVersion,
                      MeaningProvider provider, Poster poster, LongSupplier clock,
                      AiSettingsSource settingsSource) {
        this(context, http, laneExecutor, aiExecutor, processingVersion,
                provider == null ? Collections.<MeaningProvider>emptyList()
                        : Collections.singletonList(provider),
                poster, clock, settingsSource);
    }

    private LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                              ExecutorService aiExecutor, int processingVersion,
                              List<MeaningProvider> providers, Poster poster, LongSupplier clock,
                              AiSettingsSource settingsSource) {
        this.context = context;
        this.http = http;
        this.laneExecutor = laneExecutor;
        this.aiExecutor = aiExecutor == null ? laneExecutor : aiExecutor;
        this.poster = poster;
        this.clock = clock;
        this.processingVersion = processingVersion;
        this.providers = providers;
        this.settingsSource = settingsSource;
    }

    /** Dispatches a lane callback onto the owning surface. */
    interface Poster {
        void post(Runnable action);
    }

    /** Where the lane reads AI configuration from; one source per lane instance. */
    interface AiSettingsSource {
        AiSettings create(Context context);
    }

    /** @return true when a run was started; false when the layer is disabled or already satisfied */
    public boolean start(
            String id,
            int generation,
            LyricsDocument snapshot,
            String backend,
            String targetLang,
            String sourceLang,
            String effectiveSourceLang,
            boolean explicitAiRequest,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            LyricsSecondaryProcessor.Callback callback
    ) {
        if (snapshot == null || snapshot.lines.isEmpty()) return false;
        final AiSettings aiSettings = settingsSource.create(context);
        final boolean aiAutomatic = aiSettings.translationAutomatic() && aiSettings.canRequest();
        // The backend setting decides which registered provider owns this run. An install that
        // never touched the setting resolves to Google, exactly as it did when Google was the only
        // peer; a setting no backend claims leaves the lane with nothing to do, as before.
        final MeaningProvider provider = providerFor(providers, backend);
        final boolean wanted = snapshot.translationPending && provider != null;
        LyricsDocument workerSnapshot = LyricsDocument.copyOf(snapshot);
        if (workerSnapshot == null || workerSnapshot.lines.isEmpty()) return false;

        List<Integer> work = new ArrayList<>();
        if (wanted) {
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (isBlank(line.translatedText)
                        && SpicyProcessing.flagsFor(line.text, sourceLang, targetLang,
                                line.detection).translationPending) {
                    work.add(i);
                }
            }
        }

        final DerivedLayerRun run = DerivedLayerRun.begin(context, LayerKind.MEANING, workerSnapshot, laneSequence);
        String retired = activeTag;
        activeTag = run.tag;
        if (!retired.isEmpty()) cancelTagged(retired);

        // AI remains the display authority. The composer may optionally ask for a Google draft as
        // request input; otherwise Meaning reads canonical source directly. Google preview is the
        // third flow: raw-lyrics AI semantics plus an independent Google display job.
        final AiSettings.MeaningFlow meaningFlow = aiSettings.meaningFlow();
        boolean aiConfigured = aiSettings.meaningLayerEnabled() && aiSettings.isEnabled()
                && !aiSettings.modelName().isEmpty();
        boolean aiAutomaticRequest = aiAutomatic && snapshot.translationPending;
        // Pending machine-translation work must stay on the selected backend's lane unless the
        // user explicitly requested AI or selected the automatic AI trigger. The old `wanted` term
        // made every configured AI install bypass Google, even in On-demand mode; disabling AI
        // then exposed the broken assumption because no Google result had been fetched for that
        // document.
        if (shouldUseAi(aiConfigured, explicitAiRequest, aiAutomatic, snapshot.translationPending)) {
            if (meaningFlow == AiSettings.MeaningFlow.GOOGLE_PREVIEW) {
                return startPreviewRun(run, id, generation, snapshot, workerSnapshot, work,
                        provider, backend, targetLang, sourceLang, effectiveSourceLang,
                        explicitAiRequest, explicitAiRequest || aiAutomaticRequest, currentGuard,
                        callback);
            }
            return startAiRun(run, id, generation, snapshot, workerSnapshot, work, provider,
                    backend, targetLang, sourceLang, effectiveSourceLang, explicitAiRequest,
                    explicitAiRequest || aiAutomaticRequest, currentGuard, callback);
        }

        if (work.isEmpty()) {
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.MEANING, null, LayerFailure.NONE, "", 0));
            return false;
        }

        // One provider run per (canonical base, Meaning config), whichever surface asks first. A
        // second surface waits for the owner and then fills from cache instead of re-billing.
        LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, () -> replayIfCurrent(run, id, generation,
                snapshot, currentGuard, () -> start(id, generation, snapshot, backend, targetLang,
                        sourceLang, effectiveSourceLang, explicitAiRequest, currentGuard, callback)))) {
            return false;
        }

        final long startedAtMs = clock.getAsLong();
        // The work key was claimed above, on this thread. If the executor refuses the task nothing
        // would ever release it, and every later request for this song and configuration would be
        // told the work is already in flight - forever, with no owner. The layer would sit in
        // PROCESSING with nothing behind it, which is exactly the "tapping does nothing" state:
        // the toggle cannot offer a retry while it still reads as running. The AI path below has
        // always had this guard; the machine-translation path did not.
        try {
            laneExecutor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            Set<Integer> translated = new HashSet<>();
            ProviderRunStats stats = new ProviderRunStats(work == null ? 0 : work.size(),
                    clock.getAsLong(), provider);
            // Collected as the lane works rather than read back off the document. Provider-supplied
            // translations are deliberately absent: they are canonical source data carried by the
            // base, not something this layer produced.
            List<MeaningEntry> entries = new ArrayList<>();
            try {
                if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                List<Integer> retry = translateBatchPass(provider, id, effectiveSourceLang,
                        targetLang, workerSnapshot, work, entries, translated, changed, true,
                        run.tag, run, stats);
                // A permanent refusal already ended the pass with an empty retry list; the guard is
                // the second half of that: no later pass may ask a backend that cannot answer.
                if (!retry.isEmpty() && !stats.permanent
                        && run.accepts(currentGuard, id, generation, snapshot)) {
                    post(run, id, generation, snapshot, currentGuard,
                            () -> callback.progress("Retrying echoed translations... " + retry.size()));
                    translateBatchPass(provider, id, effectiveSourceLang, targetLang, workerSnapshot,
                            retry, entries, translated, changed, false, run.tag, run, stats);
                }
                retryUntranslatedRows(provider, id, effectiveSourceLang, targetLang, workerSnapshot,
                        work, entries, translated, changed, run.tag, run, stats,
                        () -> run.accepts(currentGuard, id, generation, snapshot));
            } catch (Throwable t) {
                // The pass escaped before recording anything, so nothing else in this run knows a
                // request was even made. Without this the settlement reported LayerFailure.NONE and
                // the reader was told nothing at all about a run that produced no translation.
                stats.recordTransportFailure(t);
                XpLog.log(TAG + " translation pass failed: " + t.getClass().getSimpleName());
            } finally {
                // Release before publishing so a deferred surface re-runs against the fresh cache.
                COALESCER.finish(runIdentity);
            }

            boolean complete = translated.containsAll(work);
            logSettlement(stats, translated.size(), complete,
                    clock.getAsLong() - startedAtMs);
            boolean includes = complete
                    && (LyricsDocumentProcessor.hasDisplayedTranslation(workerSnapshot) || !translated.isEmpty());
            int finalChanged = changed.get();
            // A run that ended with every row translated is not a failure. An earlier pass's reason
            // is history by then - a partial answer and a transient refusal are both what the later
            // passes exist to recover from - and reporting it showed "the service's answer could not
            // be read" for a song that is fully translated on screen.
            final LayerFailure settledFailure = complete ? LayerFailure.NONE : stats.failure();
            final String settledMessage = complete
                    ? "Enhanced " + finalChanged + " translation fields"
                    : stats.message(finalChanged);
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
            LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                    clock.getAsLong() - startedAtMs);
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.MEANING, artifactOf(provider, run, entries, complete),
                            settledFailure, settledMessage, finalChanged));
            });
        } catch (java.util.concurrent.RejectedExecutionException notDispatched) {
            COALESCER.finish(runIdentity);
            XpLog.log(TAG + " translation not dispatched: "
                    + notDispatched.getClass().getSimpleName());
            // Nothing was attempted, so nothing was translated and no layer state changed. The
            // caller reads false and a later request for this song is free to try again.
            return false;
        }
        return true;
    }

    /** AI routing contract: pending work stays Google-owned until an AI trigger is present. */
    static boolean shouldUseAi(boolean aiConfigured, boolean explicitAiRequest,
                               boolean aiAutomatic, boolean translationPending) {
        return aiConfigured && (explicitAiRequest || aiAutomatic || !translationPending);
    }

    /**
     * The AI path for this layer.
     *
     * <p>Shaped like the Google path deliberately — same run guard, same coalescer, same completion
     * callback — so nothing above the lane has to know which authority answered. What differs is
     * underneath: one request for the whole document rather than provider-tuned batches, and a
     * cancellation that reaches the socket rather than only the callback.
     */
    private boolean startAiRun(final DerivedLayerRun run, final String id, final int generation,
                               final LyricsDocument snapshot, final LyricsDocument workerSnapshot,
                               final List<Integer> googleWork, final MeaningProvider provider,
                               final String backend,
                               final String targetLang, final String sourceLang,
                               final String effectiveSourceLang,
                               final boolean explicitAiRequest,
                               final boolean allowProviderRequest,
                               final LyricsSecondaryProcessor.CurrentGuard currentGuard,
                               final LyricsSecondaryProcessor.Callback callback) {
        final LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, () -> replayIfCurrent(run, id, generation,
                snapshot, currentGuard, () -> start(id, generation, snapshot, backend, targetLang,
                        sourceLang, effectiveSourceLang, explicitAiRequest, currentGuard, callback)))) {
            // Another surface — or the automatic run for this same song — already owns this exact
            // work. An explicit request still counts as started: it is queued above and will
            // settle when the owner finishes. Reporting it as "not started" is what told the user
            // their configuration was broken when it was not.
            return explicitAiRequest;
        }

        final AiSignal signal = new AiSignal();
        aiSignal = signal;
        if (allowProviderRequest) {
            AiRequestLiveState.begin(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            Diagnostics.event(AI_COMPONENT, "request_started",
                    Diagnostics.context("provider", aiProviderId()));
        }
        final AiRunMonitor monitor = allowProviderRequest
                ? new AiLiveMonitor(LayerKind.MEANING, run.canonicalDigest(), run.tag)
                : null;
        final long startedAtMs = clock.getAsLong();
        // The coalescer key is claimed above, on this thread. If the executor refuses the task
        // nothing would ever release it, and every later request for this song would be told the
        // work is already in flight — forever, with no owner.
        try {
            aiExecutor.execute(new Runnable() {
            @Override public void run() {
                AiMeaningRun.Result result = null;
                LayerFailure aiFailure = LayerFailure.NONE;
                MeaningArtifact googleBaseline = null;
                boolean refineGoogle = false;
                try {
                    if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                    AiSettings settings = settingsSource.create(context);
                    // Only Google draft refines. AI-only runs raw by contract; the preview flow
                    // never enters this path.
                    refineGoogle = settings.meaningFlow() == AiSettings.MeaningFlow.GOOGLE_DRAFT;
                    if (refineGoogle) {
                        ProviderFallbackResult googleResult = providerFallback(provider, run, id,
                                workerSnapshot, googleWork, effectiveSourceLang, targetLang);
                        googleBaseline = googleResult.artifact;
                        postProviderFailureIfNeeded(provider, run, id, generation, snapshot,
                                currentGuard, callback, googleResult.failure);
                    }
                    if (googleBaseline != null) {
                        final MeaningArtifact preliminary = googleBaseline;
                        post(run, id, generation, snapshot, currentGuard, new Runnable() {
                            @Override public void run() {
                                callback.rerender(LayerKind.MEANING, preliminary,
                                        "Showing " + backendName(provider)
                                                + " translation while AI works");
                            }
                        });
                    }
                    result = AiMeaningRun.run(context, settings, run.base,
                            workerSnapshot, googleBaseline, refineGoogle, targetLang,
                            allowProviderRequest, signal, monitor);
                    if (result != null && result.outcome != null
                            && result.outcome.kind == AiRunOutcome.Kind.FAILED) {
                        aiFailure = result.outcome.failure;
                        AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(), run.tag,
                                result.outcome.failureToken, result.outcome.failure.httpStatus,
                                result.outcome.failureDetail);
                        recordAiOutcome("request_failed", "failed",
                                result.outcome.failureToken,
                                result.outcome.failure.httpStatus);
                        XpLog.log(TAG + " ai translation outcome=failed token="
                                + result.outcome.failureToken + " status="
                                + result.outcome.failure.httpStatus + " rule="
                                + result.outcome.failureDetail);
                    } else if (result != null && result.outcome != null
                            && (result.outcome.kind == AiRunOutcome.Kind.COMPLETED
                            || result.outcome.kind == AiRunOutcome.Kind.REUSED)) {
                        recordAiOutcome("request_settled",
                                result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT),
                                "", 0);
                        XpLog.log(TAG + " ai translation outcome="
                                + result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT)
                                + " durable=" + result.outcome.durable);
                    }
                } catch (AiCancelledException cancelled) {
                    // Expected on a track change; the run already stored whatever it had finished.
                    AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(), run.tag);
                    recordAiOutcome("request_settled", "cancelled", "", 0);
                    return;
                } catch (Throwable failure) {
                    XpLog.log(TAG + " ai translation failed: "
                            + AiRuntimeFailureLog.describe(failure));
                    aiFailure = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                            "runtime_unavailable", 0);
                    AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(), run.tag,
                            "runtime_unavailable", 0, failure.getClass().getSimpleName());
                    recordAiOutcome("request_failed", "failed", "runtime_unavailable", 0);
                } finally {
                    COALESCER.finish(runIdentity);
                }
                AiRequestLiveState.complete(LayerKind.MEANING, run.canonicalDigest(), run.tag);

                MeaningArtifact aiArtifact = result == null ? null : result.artifact;
                if (aiArtifact != null && googleBaseline != null) {
                    aiArtifact = aiArtifact.withGoogleBaseline(googleBaseline, refineGoogle);
                }
                // Every AI Meaning mode uses the same document-level display selector. AI-only
                // has no Google candidate; Google-draft may fall back only to a complete baseline.
                if (googleBaseline == null && refineGoogle) {
                    ProviderFallbackResult googleResult = providerFallback(provider, run, id,
                            workerSnapshot, googleWork, effectiveSourceLang, targetLang);
                    googleBaseline = googleResult.artifact;
                    postProviderFailureIfNeeded(provider, run, id, generation, snapshot,
                            currentGuard, callback, googleResult.failure);
                }
                Set<String> requiredRows = requiredRowIds(
                        run.base, workerSnapshot, sourceLang, targetLang,
                        ProviderTextDetectionStore.lookup(context));
                MeaningArtifact artifact = refineGoogle
                        ? MeaningDisplaySelector.selectWithFallback(
                                run.base, requiredRows, aiArtifact, googleBaseline)
                        : MeaningDisplaySelector.select(
                                run.base, requiredRows, aiArtifact, null);
                final MeaningArtifact finalArtifact = artifact;
                final LayerFailure finalFailure = aiFailure;
                final int changed = finalArtifact == null ? 0 : finalArtifact.size();
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
                LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                        clock.getAsLong() - startedAtMs);
                post(run, id, generation, snapshot, currentGuard, new Runnable() {
                    @Override public void run() {
                        callback.complete(LayerKind.MEANING, finalArtifact, finalFailure,
                                "AI translated " + changed + " lines", changed);
                    }
                });
            }
            });
        } catch (RuntimeException notDispatched) {
            COALESCER.finish(runIdentity);
            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            XpLog.log(TAG + " ai translation not dispatched: "
                    + notDispatched.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    /**
     * The Google preview flow: two independent children under one parent run contract.
     *
     * <p>Google is display-only work on the lane executor, so it can never queue behind an AI
     * request. The AI request carries canonical raw lyrics only — {@code googleBaseline=null},
     * {@code refineGoogle=false} — which makes it share the plain Meaning paid-record identity
     * and prompt version with AI-only mode. {@link MeaningPreviewRace} owns which settlement may
     * reach the screen; this method owns exactly-once coalescer release, at-most-one preliminary
     * rerender, and the single completion.
     *
     * <p>The selected preview flow always resolves Google first. Google owns its own line cache,
     * so a repeat visit can reuse that result without letting a paid AI artifact bypass the
     * requested display flow.
     */
    private boolean startPreviewRun(final DerivedLayerRun run, final String id, final int generation,
                                    final LyricsDocument snapshot, final LyricsDocument workerSnapshot,
                                    final List<Integer> googleWork, final MeaningProvider provider,
                                    final String backend,
                                    final String targetLang, final String sourceLang,
                                    final String effectiveSourceLang,
                                    final boolean explicitAiRequest,
                                    final boolean allowProviderRequest,
                                    final LyricsSecondaryProcessor.CurrentGuard currentGuard,
                                    final LyricsSecondaryProcessor.Callback callback) {
        final LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, () -> replayIfCurrent(run, id, generation,
                snapshot, currentGuard, () -> start(id, generation, snapshot, backend, targetLang,
                        sourceLang, effectiveSourceLang, explicitAiRequest, currentGuard, callback)))) {
            return explicitAiRequest;
        }

        final MeaningPreviewRace race = new MeaningPreviewRace(
                run.base, requiredRowIds(run.base, workerSnapshot, sourceLang, targetLang,
                        ProviderTextDetectionStore.lookup(context)));
        final AiSignal signal = new AiSignal();
        aiSignal = signal;
        if (allowProviderRequest) {
            AiRequestLiveState.begin(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            Diagnostics.event(AI_COMPONENT, "request_started",
                    Diagnostics.context("provider", aiProviderId()));
        }
        final AiRunMonitor monitor = allowProviderRequest
                ? new AiLiveMonitor(LayerKind.MEANING, run.canonicalDigest(), run.tag)
                : null;
        final long startedAtMs = clock.getAsLong();
        // Preview means Google reaches the display before a new or cached AI answer can replace it.
        final CountDownLatch previewReadyForAi = new CountDownLatch(1);

        // Google display child. Lane executor, immediately; it shares the parent's run guard and
        // cancellation tag but never the AI executor's queue.
        try {
            laneExecutor.execute(new Runnable() {
                @Override public void run() {
                        MeaningArtifact google = null;
                        LayerFailure googleFailure = LayerFailure.NONE;
                    try {
                        if (!run.accepts(currentGuard, id, generation, snapshot)) {
                            race.abandonAi();
                            previewReadyForAi.countDown();
                            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag);
                            COALESCER.finish(runIdentity);
                            return;
                        }
                        ProviderFallbackResult googleResult = providerFallback(provider, run, id,
                                workerSnapshot, googleWork, effectiveSourceLang, targetLang);
                        google = googleResult.artifact;
                        googleFailure = googleResult.failure;
                    } catch (Throwable failure) {
                        XpLog.log(TAG + " preview google failed: "
                                + failure.getClass().getSimpleName());
                    }
                    final MeaningPreviewRace.Outcome googleOutcome = race.onGoogleSettled(google);
                    if (googleFailure.isFailure()) {
                        postProviderFailureIfNeeded(provider, run, id, generation, snapshot,
                                currentGuard, callback, googleFailure);
                    }
                    if (googleOutcome.preliminary) {
                        final MeaningArtifact preliminary = googleOutcome.artifact;
                        poster.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (run.accepts(currentGuard, id, generation, snapshot)
                                            && race.mayPublishPreliminary(preliminary)) {
                                        callback.rerender(LayerKind.MEANING, preliminary,
                                                "Showing " + backendName(provider)
                                                        + " translation while AI works");
                                    }
                                } finally {
                                    previewReadyForAi.countDown();
                                }
                            }
                        });
                    } else {
                        previewReadyForAi.countDown();
                        completePreviewRun(run, id, generation, snapshot, currentGuard, callback,
                                runIdentity, googleOutcome);
                    }
                }
            });
        } catch (RuntimeException notDispatched) {
            previewReadyForAi.countDown();
            // No Google child will settle: the race falls through to whatever the raw-AI child
            // decides, exactly as if Google had failed.
            MeaningPreviewRace.Outcome googleOutcome = race.onGoogleSettled(null);
            completePreviewRun(run, id, generation, snapshot, currentGuard, callback,
                    runIdentity, googleOutcome);
            XpLog.log(TAG + " preview google not dispatched: "
                    + notDispatched.getClass().getSimpleName());
        }

        // The coalescer key is claimed above, on this thread. The child that produces the race's
        // terminal outcome releases it; cancellation and dispatch rejection release explicitly.
        try {
            aiExecutor.execute(new Runnable() {
                @Override public void run() {
                    AiMeaningRun.Result result = null;
                    LayerFailure aiFailure = LayerFailure.NONE;
                    try {
                        try {
                            previewReadyForAi.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            race.abandonAi();
                            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag);
                            COALESCER.finish(runIdentity);
                            return;
                        }
                        if (!run.accepts(currentGuard, id, generation, snapshot)) {
                            race.abandonAi();
                            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag);
                            COALESCER.finish(runIdentity);
                            return;
                        }
                        // Raw lyrics only. Preview never sends a baseline and never reports
                        // baseline_unavailable: Google here is display, not request input.
                        result = AiMeaningRun.run(context, settingsSource.create(context), run.base,
                                workerSnapshot, null, false, targetLang,
                                allowProviderRequest, signal, monitor);
                        if (result != null && result.outcome != null
                                && result.outcome.kind == AiRunOutcome.Kind.FAILED) {
                            aiFailure = result.outcome.failure;
                            AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag, result.outcome.failureToken,
                                    result.outcome.failure.httpStatus,
                                    result.outcome.failureDetail);
                            recordAiOutcome("request_failed", "failed",
                                    result.outcome.failureToken,
                                    result.outcome.failure.httpStatus);
                            XpLog.log(TAG + " ai translation outcome=failed token="
                                    + result.outcome.failureToken + " status="
                                    + result.outcome.failure.httpStatus + " rule="
                                    + result.outcome.failureDetail);
                        } else if (result != null && result.outcome != null
                                && (result.outcome.kind == AiRunOutcome.Kind.COMPLETED
                                || result.outcome.kind == AiRunOutcome.Kind.REUSED)) {
                            recordAiOutcome("request_settled",
                                    result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT),
                                    "", 0);
                            XpLog.log(TAG + " ai translation outcome="
                                    + result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT)
                                    + " durable=" + result.outcome.durable);
                        }
                    } catch (AiCancelledException cancelled) {
                        // Expected on a track change; the run already stored whatever it had.
                        AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                run.tag);
                        recordAiOutcome("request_settled", "cancelled", "", 0);
                        COALESCER.finish(runIdentity);
                        return;
                    } catch (Throwable failure) {
                        XpLog.log(TAG + " ai translation failed: "
                                + AiRuntimeFailureLog.describe(failure));
                        aiFailure = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                                "runtime_unavailable", 0);
                        AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(), run.tag,
                                "runtime_unavailable", 0, failure.getClass().getSimpleName());
                        recordAiOutcome("request_failed", "failed", "runtime_unavailable", 0);
                    }
                    AiRequestLiveState.complete(LayerKind.MEANING, run.canonicalDigest(), run.tag);

                    MeaningArtifact artifact = result == null ? null : result.artifact;
                    // The preliminary was display-only: the paid record stays keyed to raw
                    // lyrics, so no Google baseline is folded into the final artifact.
                    final MeaningPreviewRace.Outcome outcome = race.onAiSettled(artifact,
                            aiFailure);
                    LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
                    LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                            clock.getAsLong() - startedAtMs);
                    completePreviewRun(run, id, generation, snapshot, currentGuard, callback,
                            runIdentity, outcome);
                }
            });
        } catch (RuntimeException notDispatched) {
            race.abandonAi();
            COALESCER.finish(runIdentity);
            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            // The same tag owns both children's calls, so this aborts the Google child too.
            cancelTagged(run.tag);
            XpLog.log(TAG + " preview ai translation not dispatched: "
                    + notDispatched.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    /** Publishes the preview race's single terminal outcome; non-terminal settlements are no-ops. */
    private void completePreviewRun(DerivedLayerRun run, String id, int generation,
                                    LyricsDocument snapshot,
                                    LyricsSecondaryProcessor.CurrentGuard currentGuard,
                                    LyricsSecondaryProcessor.Callback callback,
                                    LayerRunIdentity runIdentity,
                                    MeaningPreviewRace.Outcome outcome) {
        if (outcome == null || !outcome.terminal) return;
        COALESCER.finish(runIdentity);
        final int changed = outcome.artifact == null ? 0 : outcome.artifact.size();
        post(run, id, generation, snapshot, currentGuard, new Runnable() {
            @Override public void run() {
                callback.complete(LayerKind.MEANING, outcome.artifact, outcome.failure,
                        "AI translated " + changed + " lines", changed);
            }
        });
    }

    /**
     * One allowlisted diagnostic event for the AI side of this lane.
     *
     * <p>Only context keys already on the capture filter are used — {@code provider}, {@code
     * result}, {@code reason}, and {@code status} — so nothing here can silently drop. Tokens name
     * the failure; no lyric text, payload, or URL travels with them.
     */
    private void recordAiOutcome(String operation, String result, String reason, int httpStatus) {
        Diagnostics.event(AI_COMPONENT, operation, Diagnostics.context(
                "provider", aiProviderId(),
                "result", result,
                "reason", AiText.nz(reason),
                "status", httpStatus > 0 ? String.valueOf(httpStatus) : ""));
    }

    private String aiProviderId() {
        return settingsSource.create(context).providerId();
    }

    /**
     * Aborts the in-flight calls every registered backend started under {@code cancelTag}. Called
     * when a run is retired. The tag names one run, so a backend that never sent anything under it
     * matches nothing - which is why the tag is broadcast rather than tracked per backend.
     */
    private void cancelTagged(String cancelTag) {
        for (MeaningProvider candidate : providers) {
            if (candidate != null) candidate.cancel(http, cancelTag);
        }
    }

    /** Capitalized backend name for user-facing status text: "Google", "Baidu". */
    private static String backendName(MeaningProvider provider) {
        String token = provider == null ? "" : safe(provider.logToken());
        if (token.isEmpty()) return "";
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    private ProviderFallbackResult providerFallback(MeaningProvider provider, DerivedLayerRun run,
                                           String id, LyricsDocument workerSnapshot, List<Integer> work,
                                           String effectiveSourceLang, String targetLang) {
        List<MeaningEntry> entries = new ArrayList<>();
        for (int i = 0; workerSnapshot != null && i < workerSnapshot.lines.size(); i++) {
            LyricsLine line = workerSnapshot.lines.get(i);
            if (line == null || isBlank(line.translatedText)) continue;
            CanonicalRow row = run.base.rowAt(i);
            if (row != null && provider.shouldDisplay(line.text, line.translatedText)) {
                entries.add(new MeaningEntry(row.rowId, line.translatedText, targetLang));
            }
        }

        List<Integer> missing = work == null ? Collections.<Integer>emptyList()
                : new ArrayList<>(work);
        Set<Integer> translated = new HashSet<>();
        AtomicInteger changed = new AtomicInteger();
        ProviderRunStats stats = new ProviderRunStats(missing.size(), clock.getAsLong(), provider);
        if (!missing.isEmpty()) {
            try {
                List<Integer> retry = translateBatchPass(provider, id, effectiveSourceLang,
                        targetLang, workerSnapshot, missing, entries, translated, changed, true,
                        run.tag, run, stats);
                if (!retry.isEmpty() && !stats.permanent && run.isNewest()) {
                    translateBatchPass(provider, id, effectiveSourceLang, targetLang, workerSnapshot,
                            retry, entries, translated, changed, false, run.tag, run, stats);
                }
                retryUntranslatedRows(provider, id, effectiveSourceLang, targetLang, workerSnapshot,
                        missing, entries, translated, changed, run.tag, run, stats, run::isNewest);
            } catch (Throwable failure) {
                XpLog.log(TAG + " " + safe(provider.logToken()) + " fallback failed: "
                        + failure.getClass().getSimpleName());
            }
        }
        boolean complete = missing.isEmpty() || translated.containsAll(missing);
        logSettlement(stats, translated.size(), complete,
                clock.getAsLong() - stats.startedAtMs);
        MeaningArtifact artifact = entries.isEmpty() ? null : new MeaningArtifact(
                run.canonicalDigest(), run.configId(),
                new LayerProvenance(LayerAuthority.MACHINE, provider.backendId(), run.configId(),
                        System.currentTimeMillis()), entries, !complete);
        // The full classification, not only the frequency limit. A refusal the endpoint reports in
        // its own error number is a terminal outcome of a paid call and has to reach the reader by
        // the same route the limit already did; reporting the limit alone is what left a refused
        // Baidu run silent. A recovered run reports no failure, for the reason the machine path
        // does: every row it was asked for came back translated.
        LayerFailure failure = complete ? LayerFailure.NONE : stats.failure();
        return new ProviderFallbackResult(artifact, failure);
    }

    /**
     * Tells the surface a translation run ended without producing one.
     *
     * <p>Nothing else carries this: the artifact is empty, the layer settles FAILED with no text,
     * and the progress channel recorded only the reason. Rate limiting keeps the wording it already
     * had; every other outcome is named by kind, so a refusal, an unreadable answer and a dead
     * network do not read as the same event.
     */
    private void postProviderFailureIfNeeded(MeaningProvider provider, DerivedLayerRun run, String id,
                                            int generation, LyricsDocument snapshot,
                                            LyricsSecondaryProcessor.CurrentGuard guard,
                                            LyricsSecondaryProcessor.Callback callback,
                                            LayerFailure failure) {
        TranslationFailureNotice notice = TranslationFailureNotice.of(failure, provider.logToken());
        if (notice == null) return;
        post(run, id, generation, snapshot, guard,
                () -> callback.progress(notice.describe()));
    }

    private static final class ProviderFallbackResult {
        final MeaningArtifact artifact;
        final LayerFailure failure;

        ProviderFallbackResult(MeaningArtifact artifact, LayerFailure failure) {
            this.artifact = artifact;
            this.failure = failure == null ? LayerFailure.NONE : failure;
        }
    }

    private List<Integer> translateBatchPass(
            MeaningProvider provider, String id, String sourceLang, String targetLang,
            LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run
    ) {
        return translateBatchPass(provider, id, sourceLang, targetLang, workerSnapshot, work,
                entries, translatedIndices, changed, collectRetry, cancelTag, run, null);
    }

    private List<Integer> translateBatchPass(
            MeaningProvider provider, String id, String sourceLang, String targetLang,
            LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run,
            ProviderRunStats stats
    ) {
        int maxLines = collectRetry ? TRANSLATION_BATCH_MAX_LINES : TRANSLATION_RETRY_MAX_LINES;
        int maxChars = collectRetry ? TRANSLATION_BATCH_MAX_CHARS : TRANSLATION_RETRY_MAX_CHARS;
        return translateBatchPass(provider, id, sourceLang, targetLang, workerSnapshot, work,
                entries, translatedIndices, changed, collectRetry, cancelTag, run, stats,
                maxLines, maxChars);
    }

    private List<Integer> translateBatchPass(
            MeaningProvider provider, String id, String sourceLang, String targetLang,
            LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run,
            ProviderRunStats stats, int maxLines, int maxChars
    ) {
        List<Integer> retry = new ArrayList<>();
        if (workerSnapshot == null || work == null || work.isEmpty()) return retry;
        for (List<GoogleEnhancer.BatchLine> batch
                : translationBatches(workerSnapshot, work, maxLines, maxChars)) {
            if (batch.isEmpty()) continue;
            // Stop between batches as soon as this run is retired; the current call is cancelled
            // separately by whoever retired it.
            if (!run.isNewest()) break;
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROVIDER_CALL);
            GoogleEnhancer.BatchResult result = provider.translate(context, http, processingVersion,
                    id, sourceLang, targetLang, batch, cancelTag);
            if (stats != null) stats.record(result);
            // Rate-limited: the remaining batches (and the retry passes after this one) would
            // only be refused too, and every refused ask keeps the limit in place. A permanent
            // refusal is the same conclusion for a different reason: the backend has already said
            // that no retry can succeed, so re-asking costs a round trip and changes nothing.
            boolean rateLimited = result.httpStatus == 429;
            for (GoogleEnhancer.BatchLine item : batch) {
                if (item == null || item.index < 0 || item.index >= workerSnapshot.lines.size()) continue;
                LyricsLine line = workerSnapshot.lines.get(item.index);
                if (line == null) continue;
                String value = result.translations.get(item.index);
                boolean fromCache = result.cachedIndices.contains(item.index);
                if (!provider.shouldDisplay(line.text, value)) {
                    if (collectRetry && !fromCache) retry.add(item.index);
                    continue;
                }
                CanonicalRow row = run.base.rowAt(item.index);
                if (row != null) entries.add(new MeaningEntry(row.rowId, value, targetLang));
                translatedIndices.add(item.index);
                changed.incrementAndGet();
            }
            if (rateLimited || result.permanentFailure) {
                retry.clear();
                break;
            }
        }
        return retry;
    }

    static boolean translationPassComplete(boolean requested, List<Integer> work, Set<Integer> translatedIndices) {
        return !requested || work == null || work.isEmpty()
                || (translatedIndices != null && translatedIndices.containsAll(work));
    }

    /**
     * Re-asks every row the passes above left untranslated, until a pass makes no progress or the
     * backend says stop.
     *
     * <p>The old form asked once, and only when at most eight rows were missing. A chorus that
     * repeats one line is exactly the document that exceeds that cap: the run settled with a
     * translation on screen and the rest of the rows still in their source language, and nothing
     * ever asked for them again - "it has a little translation, so it stops translating". Every
     * line that carries no translation must be attempted, so the loop is bounded by progress (a
     * pass that translates nothing ends it) rather than by a row count, and it stops on the two
     * verdicts that make more asking pointless: a permanent refusal, and the endpoint's frequency
     * limit, which more requests are what keeps in place.
     */
    private void retryUntranslatedRows(
            MeaningProvider provider, String id, String sourceLang, String targetLang,
            LyricsDocument workerSnapshot, List<Integer> work, List<MeaningEntry> entries,
            Set<Integer> translatedIndices, AtomicInteger changed, String cancelTag,
            DerivedLayerRun run, ProviderRunStats stats, BooleanSupplier stillWanted
    ) {
        List<Integer> missing = untranslated(work, translatedIndices);
        while (!missing.isEmpty() && !stats.permanent && !stats.rateLimited
                && stillWanted.getAsBoolean()) {
            List<Integer> window = new ArrayList<>(
                    missing.subList(0, Math.min(TRANSLATION_RETRY_MAX_LINES, missing.size())));
            translateBatchPass(provider, id, sourceLang, targetLang, workerSnapshot, window,
                    entries, translatedIndices, changed, false, cancelTag, run, stats);
            List<Integer> next = untranslated(missing, translatedIndices);
            if (next.size() >= missing.size()) break;
            missing = next;
        }
    }

    private List<List<GoogleEnhancer.BatchLine>> translationBatches(LyricsDocument doc, List<Integer> work) {
        return translationBatches(doc, work, TRANSLATION_BATCH_MAX_LINES,
                TRANSLATION_BATCH_MAX_CHARS);
    }

    private List<List<GoogleEnhancer.BatchLine>> translationBatches(
            LyricsDocument doc, List<Integer> work, int maxLines, int maxChars) {
        List<List<GoogleEnhancer.BatchLine>> batches = new ArrayList<>();
        List<GoogleEnhancer.BatchLine> current = new ArrayList<>();
        int currentChars = 0;
        for (int index : work) {
            if (doc == null || index < 0 || index >= doc.lines.size()) continue;
            LyricsLine line = doc.lines.get(index);
            if (line == null || isBlank(line.text)) continue;
            int lineChars = safe(line.text).length() + 14;
            if (!current.isEmpty()
                    && (current.size() >= Math.max(1, maxLines)
                    || currentChars + lineChars > Math.max(128, maxChars))) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(new GoogleEnhancer.BatchLine(index, line.text));
            currentChars += lineChars;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches.isEmpty() ? Collections.<List<GoogleEnhancer.BatchLine>>emptyList() : batches;
    }

    static List<Integer> untranslated(List<Integer> requested, Set<Integer> translated) {
        List<Integer> missing = new ArrayList<>();
        if (requested == null) return missing;
        for (Integer index : requested) {
            if (index != null && (translated == null || !translated.contains(index))) {
                missing.add(index);
            }
        }
        return missing;
    }

    private void logSettlement(ProviderRunStats stats, int translated, boolean complete,
                               long durationMs) {
        if (stats == null) return;
        String result = complete ? "complete" : translated > 0 ? "partial" : "empty";
        String reason = safe(stats.lastReason);
        if (complete && !reason.isEmpty()) reason = "recovered_" + reason;
        XpLog.log(TAG + " " + safe(stats.logToken) + " settled result=" + result
                + " requested=" + stats.requested
                + " translated=" + translated
                + " cacheHits=" + stats.cacheHits.size()
                + " networkAttempts=" + stats.networkAttempts
                + " status=" + stats.lastStatus
                + " reason=" + reason
                + " durationMs=" + Math.max(0L, durationMs));
    }

    private static final class ProviderRunStats {
        final int requested;
        final long startedAtMs;
        /** Names the backend that answered, so the settled line says which one it was. */
        final String logToken;
        final Set<Integer> cacheHits = new HashSet<>();
        int networkAttempts;
        int lastStatus;
        String lastReason = "";
        /**
         * A backend refusal no retry can change, reported by the backend itself.
         *
         * <p>The retry and rescue passes exist for answers that did not arrive or did not parse.
         * Running them against a refusal - a wrong credential is the case this was added for -
         * spends one request per pass to be told the same thing again.
         */
        boolean permanent;
        /**
         * The endpoint's frequency limit, reported by the backend as a 429.
         *
         * <p>Kept apart from {@link #permanent} because it is temporary: it stops the re-ask loop
         * only while it is in force, and more requests are what keeps it in place.
         */
        boolean rateLimited;

        ProviderRunStats(int requested, long startedAtMs, MeaningProvider provider) {
            this.requested = Math.max(0, requested);
            this.startedAtMs = startedAtMs;
            this.logToken = provider == null ? "" : safe(provider.logToken());
        }

        void record(GoogleEnhancer.BatchResult result) {
            if (result == null) {
                lastReason = "null_result";
                return;
            }
            cacheHits.addAll(result.cachedIndices);
            networkAttempts += Math.max(0, result.networkAttempts);
            if (result.httpStatus > 0) lastStatus = result.httpStatus;
            if (result.httpStatus == 429) rateLimited = true;
            if (!isBlank(result.failureReason)) lastReason = result.failureReason;
            if (result.permanentFailure) permanent = true;
        }

        /**
         * Records a pass that threw before it could record a provider result. The exception type is
         * the only evidence of why, and a socket failure is the ordinary case here, so it is kept in
         * the same slot a provider-reported reason uses.
         */
        void recordTransportFailure(Throwable failure) {
            if (failure == null) return;
            networkAttempts++;
            lastReason = failure.getClass().getSimpleName();
        }

        LayerFailure failure() {
            if (lastStatus > 0 && lastStatus != 200) {
                // The reason code travels with the status. Dropping it here is what made a refused
                // Baidu run - HTTP 200 carrying errno 1022 - unnameable at the settlement.
                return new LayerFailure(LayerFailure.Reason.UNAVAILABLE, lastReason, lastStatus);
            }
            if ("parse_empty".equals(lastReason) || "partial_parse".equals(lastReason)) {
                return new LayerFailure(LayerFailure.Reason.MALFORMED, lastReason, 0);
            }
            if ("SocketTimeoutException".equals(lastReason)
                    || "InterruptedIOException".equals(lastReason)) {
                return new LayerFailure(LayerFailure.Reason.TIMEOUT, lastReason, 0);
            }
            if (!isBlank(lastReason)) {
                return new LayerFailure(LayerFailure.Reason.UNAVAILABLE, lastReason, 0);
            }
            return LayerFailure.NONE;
        }

        String message(int changed) {
            LayerFailure failure = failure();
            if (!failure.isFailure()) return "Enhanced " + changed + " translation fields";
            TranslationFailureNotice notice =
                    TranslationFailureNotice.of(failure, logToken);
            // The same wording the reader is shown, so a diagnostic report reading the settlement
            // line and the notice on screen cannot describe two different outcomes.
            return notice == null ? "Enhanced " + changed + " translation fields"
                    : notice.describe();
        }
    }


    /**
     * Retires the current run and aborts its in-flight requests. Called on a track change, so a
     * skipped track stops costing provider calls instead of merely having its callbacks ignored.
     */
    public void cancelActive() {
        laneSequence.incrementAndGet();
        String retired = activeTag;
        activeTag = "";
        if (!retired.isEmpty()) cancelTagged(retired);
        AiSignal signal = aiSignal;
        aiSignal = null;
        // Aborts the socket as well as the callback, so skipping a track stops the transfer. It
        // still cannot promise the provider did not bill for what it had already served.
        if (signal != null) signal.abort("track_change");
    }

    /**
     * The artifact for what this lane translated, built from entries collected while it worked.
     * Provenance names the backend that actually answered, so a document shows which provider its
     * translations came from.
     */
    private MeaningArtifact artifactOf(MeaningProvider provider, DerivedLayerRun run,
                                       List<MeaningEntry> entries, boolean complete) {
        if (entries.isEmpty()) return null;
        return new MeaningArtifact(run.canonicalDigest(), run.configId(),
                new LayerProvenance(LayerAuthority.MACHINE, provider.backendId(), run.configId(),
                        System.currentTimeMillis()),
                new ArrayList<>(entries), !complete);
    }

    static Set<String> requiredRowIds(CanonicalBase base, LyricsDocument document,
                                      String sourceLang, String targetLang) {
        return requiredRowIds(base, document, sourceLang, targetLang, TextDetectionLookup.NONE);
    }

    static Set<String> requiredRowIds(CanonicalBase base, LyricsDocument document,
                                      String sourceLang, String targetLang,
                                      TextDetectionLookup providerDetection) {
        Set<String> rows = new LinkedHashSet<>();
        if (base == null || document == null) return rows;
        for (CanonicalRow row : base.rows) {
            if (row == null || row.index < 0 || row.index >= document.lines.size()) continue;
            LyricsLine line = document.lines.get(row.index);
            if (line == null || line.interlude || isBlank(line.text)) continue;
            String provider = ProviderTranslationResolver.resolve(line.text,
                    line.providerTranslatedText, line.providerTranslationLanguage, targetLang,
                    providerDetection);
            if (!isBlank(provider)) continue;
            if (SpicyProcessing.flagsFor(line.text, sourceLang, targetLang,
                    line.detection).translationPending) {
                rows.add(row.rowId);
            }
        }
        return rows;
    }

    private static boolean isCurrent(LyricsSecondaryProcessor.CurrentGuard guard, String id,
                                     int generation, LyricsDocument snapshot) {
        return guard == null || guard.isCurrent(id, generation, snapshot);
    }

    /** Replays captured work only while its session and configuration still match. */
    private void replayIfCurrent(DerivedLayerRun run, String id, int generation,
                                 LyricsDocument snapshot,
                                 LyricsSecondaryProcessor.CurrentGuard guard, Runnable replay) {
        // finish() runs on a worker. Check and re-enter on the session dispatcher, so a track
        // or settings change cannot interleave between acceptance and retiring the active run.
        poster.post(() -> {
            if (!run.isNewest()) return;
            if (guard != null && !guard.isCurrent(id, generation, snapshot)) return;
            if (!run.configId().equals(LyricsDocumentProcessor.meaningConfigId(context))) return;
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COALESCED_RUN_JOINED);
            replay.run();
        });
    }

    private void post(DerivedLayerRun run, String id, int generation, LyricsDocument snapshot,
                      LyricsSecondaryProcessor.CurrentGuard guard, Runnable action) {
        poster.post(() -> {
            if (!run.accepts(guard, id, generation, snapshot)) return;
            action.run();
        });
    }
}
