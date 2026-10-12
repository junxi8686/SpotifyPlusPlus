package com.spotifyplusplus.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SettingsStore;
import com.spotifyplusplus.lyrics.ai.AiCredentialStore;
import com.spotifyplusplus.lyrics.ai.AiHttpTestControl;
import com.spotifyplusplus.lyrics.ai.AiPaidRecord;
import com.spotifyplusplus.lyrics.ai.AiRecordStore;
import com.spotifyplusplus.lyrics.ai.AiRecordStores;
import com.spotifyplusplus.lyrics.ai.AiRunConfig;
import com.spotifyplusplus.lyrics.ai.AiMeaningRun;
import com.spotifyplusplus.lyrics.ai.AiRequestLiveState;
import com.spotifyplusplus.lyrics.ai.AiSettings;
import com.spotifyplusplus.lyrics.ai.FakeAiRecordStore;
import com.spotifyplusplus.lyrics.session.CanonicalBase;
import com.spotifyplusplus.lyrics.session.CanonicalRow;
import com.spotifyplusplus.lyrics.session.DerivedLayerArtifact;
import com.spotifyplusplus.lyrics.session.LayerFailure;
import com.spotifyplusplus.lyrics.session.LayerKind;
import com.spotifyplusplus.lyrics.session.LayerRunCoalescer;
import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

/**
 * The Meaning lane's observable contracts, driven through {@code start(...)} on the JVM.
 *
 * <p>The lane is Android-coupled only by its surface dispatcher, its clock, its configuration
 * source, its paid store, and its executors; those are the test seam, so everything else — which
 * flow wins, who owns the coalescer key, what reaches a paid provider — is asserted as behaviour
 * rather than as source text.
 */
public final class LyricsMeaningLaneBehaviorTest {

    private static final String BACKEND = "google_unofficial";
    private static final String GOOGLE_DRAFT_TEXT = "T:";
    private static final long WAIT_MS = 5_000L;

    private FakeAndroidContext context;
    private SettingsStore settingsStore;
    private AiCredentialStore credentials;
    private AiSettings aiSettings;
    private FakeAiRecordStore recordStore;
    private GateExecutor aiExecutor;
    private ExecutorService laneExecutor;
    private ControllableProvider provider;
    private EventLog events;
    private final List<ExecutorService> spareExecutors = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = new FakeAndroidContext();
        settingsStore = new SettingsStore(context);
        credentials = new AiCredentialStore(context, new IdentityCipher());
        aiSettings = new AiSettings(settingsStore, credentials);
        recordStore = new FakeAiRecordStore();
        laneExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "lane-behavior"));
        aiExecutor = new GateExecutor("ai-behavior");
        provider = new ControllableProvider();
        events = new EventLog();
        AiRecordStores.installFactoryForTest((Context ignored) -> recordStore);
        AiHttpTestControl.install(401);
        AiRequestLiveState.clearForTest();
    }

    @After public void tearDown() throws Exception {
        aiExecutor.openGate();
        provider.release();
        provider.releaseParked();
        for (ExecutorService spare : spareExecutors) spare.shutdownNow();
        laneExecutor.shutdownNow();
        aiExecutor.shutdownNow();
        AiRecordStores.installFactoryForTest(null);
        AiHttpTestControl.restore();
        AiRequestLiveState.clearForTest();
        clearCoalescer();
    }

    // --- Google lane ------------------------------------------------------

    @Test public void googleLanePublishesEveryPendingTranslation() throws Exception {
        LyricsMeaningLane lane = newLane();
        Recorder callback = new Recorder("a");

        assertTrue(lane.start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), callback));

        assertTrue(events.await("a:complete", WAIT_MS));
        assertTrue(events.index("google-translate:lane-behavior") >= 0);
        assertTrue(events.index("google-translate:lane-behavior") < events.index("a:complete"));
    }

    /**
     * A 429 is the endpoint saying this network address is done for now. Every later batch, the
     * retry pass and the rescue pass would only be refused too, and each refused ask keeps the
     * limit in place - so the run stops asking instead of spending a song's worth of requests to
     * learn the same thing again.
     */
    @Test public void rateLimitedStopsTheRunInsteadOfAskingAgain() throws Exception {
        provider.rateLimited = true;
        Recorder callback = new Recorder("limited");

        assertTrue(newLane().start("rate-limited", 1, longPendingDocument(120), BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), callback));

        assertTrue("expected limited:complete, saw " + events.snapshot(),
                events.await("limited:complete", WAIT_MS));
        assertEquals("one refused request, not a run's worth of them", 1, provider.translateCalls());
    }

    /**
     * A backend refusal has to reach the surface in words.
     *
     * <p>The device showed the defect this covers: a Baidu run refused with errno 1022 settled in
     * half a second, left the original lyrics untouched, and handed the surface nothing to show -
     * so a reader who had just tapped translate saw no spinner, no message, and no translation.
     * The run is terminal either way; what must differ is that the surface is told.
     */
    @Test public void refusedRunTellsTheSurfaceWhyItProducedNothing() throws Exception {
        provider.refused = true;
        Recorder callback = new Recorder("refused-run");

        assertTrue(newLane().start("refused-track", 1, longPendingDocument(6), BACKEND,
                "en", "zh", "auto", false, alwaysCurrent(), callback));

        assertTrue("expected refused-run:complete, saw " + events.snapshot(),
                events.await("refused-run:complete", WAIT_MS));
        assertTrue("a refusal must be announced; messages were " + events.messages(),
                events.anyMessageContains("refused the request"));
        assertEquals("no translation was produced, so the notice is the only outcome",
                0, provider.translatedCount());
    }

    /**
     * A refusal the backend calls permanent ends the run after one request.
     *
     * <p>Wrong credentials are the case the official Baidu API made real: every later batch, the
     * retry pass and the rescue pass would be signed the same way and refused identically, so each
     * of them is a round trip spent to be told the same thing. The ladder exists for answers that
     * did not arrive - a timeout, a dropped connection - not for an answer that says no.
     */
    @Test public void permanentRefusalIsNotAskedAgain() throws Exception {
        provider.permanentlyRefused = true;
        Recorder callback = new Recorder("permanent-run");

        assertTrue(newLane().start("permanent-track", 1, longPendingDocument(6), BACKEND,
                "en", "zh", "auto", false, alwaysCurrent(), callback));

        assertTrue("expected permanent-run:complete, saw " + events.snapshot(),
                events.await("permanent-run:complete", WAIT_MS));
        assertEquals("one request, not one per pass", 1, provider.translateCalls());
        assertTrue("a refusal must still be announced; messages were " + events.messages(),
                events.anyMessageContains("refused the request"));
        assertEquals("no translation was produced, so the notice is the only outcome",
                0, provider.translatedCount());
    }

    /**
     * An unreadable answer that parses as nothing is still terminal, and says so: "the translation could
     * not be read" is a different problem from "the service refused", and the reader acts on it
     * differently.
     */
    @Test public void unreadableAnswerTellsTheSurfaceItCouldNotBeRead() throws Exception {
        provider.unreadable = true;
        Recorder callback = new Recorder("unreadable-run");

        assertTrue(newLane().start("unreadable-track", 1, longPendingDocument(6), BACKEND,
                "en", "zh", "auto", false, alwaysCurrent(), callback));

        assertTrue("expected unreadable-run:complete, saw " + events.snapshot(),
                events.await("unreadable-run:complete", WAIT_MS));
        assertTrue("an unreadable answer must be announced; messages were " + events.messages(),
                events.anyMessageContains("could not be read"));
    }

    /**
     * A lane executor that refuses the task must not strand the layer in PROCESSING.
     *
     * <p>The work key is claimed before dispatch. Releasing it only on the worker meant a refused
     * execution held the key with no owner, so every later request for that song and configuration
     * was told the work was already in flight and the layer stayed "running" with nothing behind it
     * - the toggle cannot offer a retry while it reads as running, so tapping it did nothing. The
     * machine-translation path had no guard here; the AI path always has.
     */
    @Test public void refusedTranslationDispatchReleasesTheCoalescerKey() throws Exception {
        // Only the lane executor refuses; the lane under test is otherwise the ordinary harness.
        GateExecutor refusingLane = new GateExecutor("lane-refusing");
        refusingLane.rejecting(true);
        spareExecutors.add(refusingLane);
        LyricsMeaningLane refusedLane = newLane(refusingLane, aiExecutor);
        Recorder refused = new Recorder("refused-dispatch");

        assertFalse("a run that never dispatched is not started",
                refusedLane.start("dispatch-track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                        false, alwaysCurrent(), refused));
        assertFalse(events.await("refused-dispatch:complete", 250));

        Recorder later = new Recorder("later-dispatch");
        assertTrue("a later request must claim the abandoned key",
                newLane().start("dispatch-track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                        false, alwaysCurrent(), later));
        assertTrue("expected later-dispatch:complete, saw " + events.snapshot(),
                events.await("later-dispatch:complete", WAIT_MS));
    }

    // --- Coalescer contracts ---------------------------------------------

    /**
     * Issue #5: the AI path used to count a metric for a deferred run and drop it, so a tap during
     * an automatic run reported "did not start" and never ran at all.
     */
    @Test public void deferredAiRunReplaysInsteadOfBeingDropped() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        LyricsMeaningLane owner = newLane();
        LyricsMeaningLane otherSurface = newLane(openAiExecutor("ai-other"));
        Recorder first = new Recorder("first");
        Recorder second = new Recorder("second");
        LyricsDocument document = notPendingDocument();

        assertTrue(owner.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), first));
        // The owner's job is parked behind a closed gate, so the key is still claimed.
        assertFalse(otherSurface.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), second));
        assertFalse(events.await("second:complete", 250));

        aiExecutor.openGate();

        assertTrue("expected first:complete, saw " + events.snapshot(),
                events.await("first:complete", WAIT_MS));
        assertTrue("the deferred run must replay and settle; saw " + events.snapshot(),
                events.await("second:complete", WAIT_MS));
    }

    /** A deferred explicit request is queued work, so the surface may honestly call it running. */
    @Test public void deferredExplicitAiRequestStillCountsAsStarted() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        LyricsMeaningLane owner = newLane();
        LyricsMeaningLane otherSurface = newLane(openAiExecutor("ai-other"));
        Recorder first = new Recorder("first");
        Recorder second = new Recorder("second");
        LyricsDocument document = notPendingDocument();

        assertTrue(owner.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), first));
        assertTrue("the queued tap is started even though it had to wait",
                otherSurface.start("track", 1, document, BACKEND, "en", "ko", "ko",
                        true, alwaysCurrent(), second));

        aiExecutor.openGate();

        assertTrue("expected first:complete, saw " + events.snapshot(),
                events.await("first:complete", WAIT_MS));
        assertTrue("expected second:complete, saw " + events.snapshot(),
                events.await("second:complete", WAIT_MS));
    }

    /**
     * The key is claimed before dispatch; a refused execution would otherwise hold it forever with
     * no owner to release it, and every later request for that song would be told work is in flight.
     */
    @Test public void refusedAiDispatchReleasesTheCoalescerKey() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        LyricsMeaningLane lane = newLane();
        Recorder refused = new Recorder("refused");
        Recorder later = new Recorder("later");
        LyricsDocument document = notPendingDocument();

        aiExecutor.rejecting(true);
        assertFalse(lane.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), refused));
        assertFalse(events.await("refused:complete", 250));

        aiExecutor.rejecting(false);
        aiExecutor.openGate();
        assertTrue(lane.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), later));
        assertTrue("a later request must claim the abandoned key",
                events.await("later:complete", WAIT_MS));
    }

    // --- Billing boundary -------------------------------------------------

    /**
     * Automatic Meaning must not bill for source lyrics already in the translation target: the
     * trigger is only a request when there is translation work to do for it.
     */
    @Test public void automaticAiRequestRequiresTranslationWork() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, true);
        giveCredential();
        aiExecutor.openGate();
        LyricsMeaningLane lane = newLane();

        Recorder idle = new Recorder("idle");
        assertTrue(lane.start("track", 1, notPendingDocument(), BACKEND, "en", "en", "en",
                false, alwaysCurrent(), idle));
        assertTrue("expected idle:complete, saw " + events.snapshot(),
                events.await("idle:complete", WAIT_MS));
        assertEquals("an automatic trigger with no translation work must not bill",
                0, AiHttpTestControl.count());
        assertEquals(AiRequestLiveState.Phase.NONE,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING).current.phase);

        // Control: with work pending the same configuration does ask, so the guard above is the
        // only reason the first run stayed quiet.
        Recorder busy = new Recorder("busy");
        assertTrue(lane.start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), busy));
        assertTrue(events.await("busy:complete", WAIT_MS));
        assertEquals("a pending translation does cross the billing boundary",
                1, AiHttpTestControl.count());
        assertNotEquals(AiRequestLiveState.Phase.NONE,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING).current.phase);
    }

    // --- Google preview flow ---------------------------------------------

    /**
     * Preview must be its own branch: Google displays first and the AI job replaces it, while
     * AI-only never asks Google for display at all.
     */
    @Test public void previewPublishesGoogleFirstAndAiOnlyNeverDoes() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();

        Recorder preview = new Recorder("preview");
        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertTrue("preview shows Google while AI works, saw " + events.snapshot(),
                events.index("preview:rerender") >= 0);

        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        Recorder aiOnly = new Recorder("aiOnly");
        assertTrue(newLane().start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), aiOnly));
        assertTrue("expected aiOnly:complete, saw " + events.snapshot(),
                events.await("aiOnly:complete", WAIT_MS));
        assertEquals("AI-only has no Google display job to publish first: " + events.snapshot(),
                -1, events.index("aiOnly:rerender"));
    }

    /**
     * Google display work belongs on the lane executor; only the AI job waits behind the AI
     * executor's queue, so a parked AI job can never hold up the display.
     */
    @Test public void googleChildRunsOnTheLaneExecutorWithoutWaitingForTheAiJob()
            throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        Recorder preview = new Recorder("preview");

        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));
        assertTrue("the display must publish while the AI job is still queued: "
                        + events.snapshot(),
                events.await("preview:rerender", WAIT_MS));
        assertTrue("Google must run on the lane executor: " + events.snapshot(),
                events.index("google-translate:lane-behavior") >= 0);
        assertEquals("the parked AI job must not have billed yet", 0, AiHttpTestControl.count());

        aiExecutor.openGate();
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertEquals(1, AiHttpTestControl.count());
    }

    /**
     * Google publication releases the AI job; until it publishes, no paid request may leave.
     */
    @Test public void previewHoldsThePaidRequestUntilGooglePublishes() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        provider.gate();
        Recorder preview = new Recorder("preview");

        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));

        Thread.sleep(300L);
        assertEquals("Google has not published, so nothing may be billed yet",
                0, AiHttpTestControl.count());
        assertEquals("the AI job must still be waiting on Google",
                AiRequestLiveState.Phase.PREPARING,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING).current.phase);
        assertEquals(-1, events.index("preview:rerender"));

        provider.release();
        assertTrue("expected preview:rerender, saw " + events.snapshot(),
                events.await("preview:rerender", WAIT_MS));
        assertTrue("the released AI job must reach the provider",
                events.await(() -> AiHttpTestControl.count() == 1, WAIT_MS));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
    }

    /**
     * Preview AI semantics are AI-only semantics: raw lyrics and the plain meaning identity, so
     * one paid answer serves both flows instead of buying the same lines twice.
     */
    @Test public void previewAndAiOnlyShareOnePaidRecordIdentity() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        Recorder preview = new Recorder("preview");
        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertEquals(1, AiHttpTestControl.count());
        Set<String> previewIdentity = recordStore.keys();
        assertEquals("preview wrote exactly one paid identity", 1, previewIdentity.size());
        String body = AiHttpTestControl.recorded().get(0).body;
        assertTrue("the request carries the raw lyrics: " + body,
                body.contains("안녕하세요"));
        assertFalse("the request carries no Google draft: " + body,
                body.contains(GOOGLE_DRAFT_TEXT));

        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        AiHttpTestControl.clear();
        Recorder aiOnly = new Recorder("aiOnly");
        assertTrue(newLane().start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), aiOnly));
        assertTrue("expected aiOnly:complete, saw " + events.snapshot(),
                events.await("aiOnly:complete", WAIT_MS));
        assertEquals(1, AiHttpTestControl.count());
        assertEquals("preview and AI-only must key one paid record, not two",
                previewIdentity, recordStore.keys());
    }

    /** A paid answer already bought must not skip the display flow the user selected. */
    @Test public void cachedAiAnswerStillShowsGoogleFirstInPreview() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        LyricsDocument document = pendingDocument();
        AiRecordStores.installFactoryForTest(ignored -> new CachedAnswerStore(document));
        Recorder preview = new Recorder("preview");

        assertTrue(newLane().start("track", 1, document, BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));

        assertTrue("a cached answer must not skip the Google display: " + events.snapshot(),
                events.await("preview:rerender", WAIT_MS));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertEquals("a reused answer is not billed again", 0, AiHttpTestControl.count());
    }

    /**
     * Exactly-once accounting: a refused AI dispatch cancels the run's calls and leaves the layer
     * claimable rather than busy forever.
     */
    @Test public void rejectedPreviewDispatchLeavesTheLayerClaimable() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        Recorder refused = new Recorder("refused");
        Recorder later = new Recorder("later");
        LyricsDocument document = pendingDocument();

        aiExecutor.rejecting(true);
        assertFalse(newLane().start("track", 1, document, BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), refused));

        assertTrue("a refused dispatch aborts the run's provider calls: " + events.snapshot(),
                events.await("cancel", WAIT_MS));

        aiExecutor.rejecting(false);
        aiExecutor.openGate();
        assertTrue(newLane().start("track", 2, document, BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), later));
        assertTrue("the refused run must not have left the layer busy: " + events.snapshot(),
                events.await("later:complete", WAIT_MS));
    }

    /** Retirement before AI dispatch wakes no paid request and releases the run. */
    @Test public void retiredPreviewRunReleasesTheLayerWithoutRequesting() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        LyricsSecondaryProcessor.CurrentGuard generationTwoIsRetired =
                (id, generation, snapshot) -> generation != 2;
        Recorder retired = new Recorder("retired");
        Recorder later = new Recorder("later");

        assertTrue(newLane().start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, generationTwoIsRetired, retired));
        assertTrue("the retired run must cancel rather than bill",
                events.await(() -> AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING)
                        .current.phase == AiRequestLiveState.Phase.CANCELLED, WAIT_MS));
        assertEquals(0, AiHttpTestControl.count());
        assertEquals(-1, events.index("retired:complete"));

        assertTrue(newLane().start("track", 3, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, generationTwoIsRetired, later));
        assertTrue("the released key must be claimable again: " + events.snapshot(),
                events.await("later:complete", WAIT_MS));
    }

    // --- Stale replay -----------------------------------------------------

    /**
     * F2: an A run, a deferred A refresh, a track switch to B, then A settlement must still
     * publish B. The deferred replay runs on A's finishing worker; while B is parked behind
     * the provider it must not advance the lane sequence or cancel B's provider calls.
     */
    @Test public void deferredStaleReplayDoesNotRetireTheNewTracksRun() throws Exception {
        LyricsMeaningLane lane = newLane();
        AtomicReference<String> currentTrack = new AtomicReference<>("f2-track-a");
        LyricsSecondaryProcessor.CurrentGuard guard =
                (id, generation, snapshot) -> id.equals(currentTrack.get());
        Recorder owner = new Recorder("owner");
        Recorder refresh = new Recorder("refresh");
        Recorder next = new Recorder("next");
        LyricsDocument trackA = trackDocument("f2-track-a", "안녕하세요", "반갑습니다");
        LyricsDocument trackB = trackDocument("f2-track-b", "여보세요", "감사합니다");

        provider.gate();
        provider.park("f2-track-b");

        // The A run owns its key and parks inside the provider.
        assertTrue(lane.start("f2-track-a", 1, trackA, BACKEND, "en", "ko", "ko",
                false, guard, owner));
        assertTrue(events.await(() -> provider.translateCalls() == 1, WAIT_MS));

        // The A refresh finds the key claimed and defers behind the owner.
        assertFalse(lane.start("f2-track-a", 1, trackA, BACKEND, "en", "ko", "ko",
                false, guard, refresh));

        // Track switch: B starts (retiring A here is legitimate). Its worker queues
        // behind A's on the single lane thread.
        currentTrack.set("f2-track-b");
        assertTrue(lane.start("f2-track-b", 2, trackB, BACKEND, "en", "ko", "ko",
                false, guard, next));
        assertEquals(1, provider.translateCalls());

        // A settles: the owner is stale so it publishes nothing, and its finish() runs the
        // deferred replay inline before B's queued worker starts. Awaiting B's translate
        // therefore proves the replay already ran.
        provider.release();
        assertTrue(events.await(() -> provider.translateCalls() == 2, WAIT_MS));

        provider.releaseParked();
        assertTrue("B must still publish after the stale replay: " + events.snapshot(),
                events.await("next:complete", WAIT_MS));
        assertEquals(-1, events.index("refresh:complete"));
        assertEquals(-1, events.index("owner:complete"));
        int cancels = 0;
        for (String event : events.snapshot()) {
            if (event.equals("cancel")) cancels++;
        }
        // Two legitimate same-track retirements: the A refresh supersedes the A owner, and B
        // supersedes the refresh. The stale replay itself must cancel nothing.
        assertEquals("only legitimate retirements may cancel", 2, cancels);
    }

    // --- Harness ----------------------------------------------------------

    private LyricsMeaningLane newLane() {
        return newLane(aiExecutor);
    }

    /** A second surface: its own lane instance, because each surface owns its own lane. */
    private LyricsMeaningLane newLane(ExecutorService otherAiExecutor) {
        return newLane(laneExecutor, otherAiExecutor);
    }

    /** A lane with both executors chosen by the caller, for the dispatch-refusal contract. */
    private LyricsMeaningLane newLane(ExecutorService otherLaneExecutor,
                                      ExecutorService otherAiExecutor) {
        LyricsMeaningLane lane = new LyricsMeaningLane(context, new OkHttpClient(), otherLaneExecutor,
                otherAiExecutor, 1, provider, Runnable::run, () -> 1_000L,
                context -> aiSettings);
        if (otherAiExecutor != aiExecutor) spareExecutors.add(otherAiExecutor);
        return lane;
    }

    private GateExecutor openAiExecutor(String name) {
        GateExecutor executor = new GateExecutor(name);
        executor.openGate();
        return executor;
    }

    private void configureAi(String flow, boolean automatic) {
        settingsStore.put(Settings.AI_ENABLED, true);
        settingsStore.put(Settings.TRANSLATION_ENABLED, true);
        settingsStore.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        settingsStore.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        settingsStore.put(Settings.AI_TRANSLATION_PIPELINE, flow);
        settingsStore.put(Settings.AI_TRANSLATION_MODE,
                automatic ? "Always use AI" : "On demand");
    }

    private void giveCredential() {
        assertTrue(credentials.save(aiSettings.credentialScope(), "sk-test-key"));
    }

    private static LyricsSecondaryProcessor.CurrentGuard alwaysCurrent() {
        return (id, generation, snapshot) -> true;
    }

    static LyricsDocument pendingDocument() {
        return document("ko", true);
    }

    /** Distinct lyrics per track so each song keys its own coalescer entry. */
    static LyricsDocument trackDocument(String trackId, String first, String second) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = trackId;
        document.language = "ko";
        document.translationPending = true;
        String[] texts = new String[]{first, second};
        for (int i = 0; i < texts.length; i++) {
            LyricsLine line = new LyricsLine();
            line.text = texts[i];
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    static LyricsDocument notPendingDocument() {
        return document("ko", false);
    }

    private static LyricsDocument document(String language, boolean translationPending) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "behavior-track";
        document.language = language;
        document.translationPending = translationPending;
        for (int i = 0; i < 2; i++) {
            LyricsLine line = new LyricsLine();
            line.text = i == 0 ? "안녕하세요" : "반갑습니다";
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    /** A song long enough to need more than one translation batch, so stopping early shows. */
    private static LyricsDocument longPendingDocument(int lines) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "rate-limited-track";
        document.language = "ko";
        document.translationPending = true;
        for (int i = 0; i < lines; i++) {
            LyricsLine line = new LyricsLine();
            line.text = "가사 " + i;
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    private static void clearCoalescer() throws Exception {
        Field field = LyricsMeaningLane.class.getDeclaredField("COALESCER");
        field.setAccessible(true);
        ((LayerRunCoalescer) field.get(null)).clear();
    }

    private final class Recorder implements LyricsSecondaryProcessor.Callback {
        private final String tag;

        Recorder(String tag) {
            this.tag = tag;
        }

        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial,
                                       String message) {
            events.add(tag + ":rerender");
        }

        @Override public void progress(String message) {
            events.add(tag + ":progress");
            events.recordMessage(tag, message);
        }

        @Override public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                                       LayerFailure failure, String message, int changed) {
            events.add(tag + ":complete");
            events.recordMessage(tag, message);
        }
    }

    /** Dispatch as the surface would: inline, on whatever thread the lane settles from. */
    static final class EventLog {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> messages = new CopyOnWriteArrayList<>();

        void add(String event) {
            events.add(event);
        }

        /** Every message a surface was handed, prefixed with the callback it arrived on. */
        void recordMessage(String tag, String message) {
            messages.add(tag + "|" + (message == null ? "" : message));
        }

        List<String> messages() {
            return new ArrayList<>(messages);
        }

        boolean anyMessageContains(String text) {
            for (String message : messages) {
                if (message.contains(text)) return true;
            }
            return false;
        }

        List<String> snapshot() {
            return new ArrayList<>(events);
        }

        int index(String event) {
            return events.indexOf(event);
        }

        boolean await(String event, long timeoutMs) {
            return await(() -> events.contains(event), timeoutMs);
        }

        boolean await(BooleanSupplier condition, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (condition.getAsBoolean()) return true;
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return condition.getAsBoolean();
        }
    }

    final class ControllableProvider implements LyricsMeaningLane.MeaningProvider {
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean gated;
        /** Answers 429 the way the endpoint does when the address is rate limited. */
        private volatile boolean rateLimited;
        /**
         * Answers 200 carrying a backend refusal, the shape a Baidu run takes when errno 1022
         * arrives from an address the endpoint will not serve.
         */
        private volatile boolean refused;
        /** Answers 200 with a body the parser reads as no translations at all. */
        private volatile boolean unreadable;
        /**
         * Answers 200 with a refusal the backend calls permanent - a wrong signature, an
         * unauthorised app id - which no later pass can turn into an answer.
         */
        private volatile boolean permanentlyRefused;
        private final AtomicInteger translateCalls = new AtomicInteger();
        private final AtomicInteger translatedTotal = new AtomicInteger();
        /** Holds one track's worker after the global gate opens, so a settlement can land first. */
        private volatile String parkedTrack;
        private final CountDownLatch parkRelease = new CountDownLatch(1);

        void gate() {
            gated = true;
        }

        void release() {
            release.countDown();
        }

        void park(String trackIdPart) {
            parkedTrack = trackIdPart;
        }

        void releaseParked() {
            parkRelease.countDown();
        }

        int translateCalls() {
            return translateCalls.get();
        }

        /** Lines this provider actually translated, so a refusal can prove it produced none. */
        int translatedCount() {
            return translatedTotal.get();
        }

        @Override public String backendId() {
            return "behavior";
        }

        @Override public boolean handles(String backendSetting) {
            return true;
        }

        @Override public GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http,
                int processingVersion, String trackId, String sourceLang, String targetLang,
                List<GoogleEnhancer.BatchLine> batch, String cancelTag) {
            translateCalls.incrementAndGet();
            events.add("google-translate:" + Thread.currentThread().getName());
            if (gated) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (parkedTrack != null && trackId != null && trackId.contains(parkedTrack)) {
                try {
                    parkRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            GoogleEnhancer.BatchResult result = new GoogleEnhancer.BatchResult();
            if (permanentlyRefused) {
                result.httpStatus = 200;
                result.failureReason = "baidu_error_54001";
                result.permanentFailure = true;
                return result;
            }
            if (rateLimited) {
                result.httpStatus = 429;
                result.failureReason = "http_429";
                return result;
            }
            if (refused) {
                // A 200 with nothing usable in it and the backend's own reason: exactly what the
                // device log shows for errno 1022, minus the wire call.
                result.httpStatus = 200;
                result.failureReason = "baidu_errno_1022";
                return result;
            }
            if (unreadable) {
                result.httpStatus = 200;
                result.failureReason = "parse_empty";
                return result;
            }
            for (GoogleEnhancer.BatchLine line : batch) {
                result.translations.put(line.index, "T:" + line.text);
                translatedTotal.incrementAndGet();
            }
            return result;
        }

        @Override public void cancel(OkHttpClient http, String cancelTag) {
            events.add("cancel");
        }

        @Override public boolean shouldDisplay(String sourceText, String translated) {
            return translated != null && !translated.isEmpty();
        }
    }

    /**
     * One AI worker whose next job waits at a gate, plus a switch that refuses dispatch entirely —
     * the two executor behaviours the lane's coalescer contract depends on.
     */
    static final class GateExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile boolean rejecting;

        GateExecutor(String name) {
            delegate = Executors.newSingleThreadExecutor(r -> new Thread(r, name));
        }

        void openGate() {
            gate.countDown();
        }

        void rejecting(boolean value) {
            rejecting = value;
        }

        @Override public void execute(Runnable command) {
            if (rejecting) throw new RejectedExecutionException("executor refusing dispatch");
            delegate.execute(() -> {
                try {
                    if (!gate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate never opened");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                command.run();
            });
        }

        @Override public void shutdown() {
            delegate.shutdown();
        }

        @Override public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    /**
     * A paid store that already holds a finished answer for whatever it is asked about, so a
     * preview run can meet the cache instead of a provider.
     */
    static final class CachedAnswerStore implements AiRecordStore {
        private final java.util.Map<String, String> answers = new java.util.LinkedHashMap<>();

        CachedAnswerStore(LyricsDocument document) {
            for (CanonicalRow row : CanonicalBase.fromDocument("", document).rows) {
                answers.put(row.rowId, "AI:" + row.text);
            }
        }

        @Override public AiPaidRecord read(AiRunConfig config) {
            AiPaidRecord record = AiPaidRecord.begin(config, 1L);
            for (java.util.Map.Entry<String, String> answer : answers.entrySet()) {
                record.putItem(answer.getKey(), answer.getValue());
            }
            record.status = AiPaidRecord.Status.COMPLETE;
            return record;
        }

        @Override public boolean commit(AiRunConfig config, AiPaidRecord record) {
            return true;
        }

        @Override public Reservation reserve(AiRunConfig config, long maxRecordBytes) {
            return Reservation.admitted();
        }

        @Override public void release(AiRunConfig config) {
        }

        @Override public void forget(AiRunConfig config) {
        }
    }

    /** The credential store with a cipher that is happy to live on a plain JVM. */
    static final class IdentityCipher implements AiCredentialStore.Cipher {
        @Override public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override public String decrypt(String ciphertext) {
            return ciphertext;
        }

        @Override public void clear() {
        }
    }
}
