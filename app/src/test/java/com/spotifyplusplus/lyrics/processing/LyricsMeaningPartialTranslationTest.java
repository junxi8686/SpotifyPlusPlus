package com.spotifyplusplus.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SettingsStore;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;
import com.spotifyplusplus.lyrics.ai.AiCredentialStore;
import com.spotifyplusplus.lyrics.ai.AiSettings;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * A document that is only partly translated must have its remaining lines translated.
 *
 * <p>This is the owner's report: a chorus repeating {@code We are} stayed in English while the
 * distinct lines around it were translated, and nothing asked again - "it has a little translation,
 * so it stops translating". The lane is driven end to end here, with the real Baidu backend behind
 * a scripted endpoint, because the two rules that decide it (one wire entry per distinct text, and
 * re-asking every row a pass left behind) live in different classes and only meet in a run.
 */
public final class LyricsMeaningPartialTranslationTest {
    private static final String TRACK = "unity-track";
    private static final BaiduTranslateBackend.Credentials CREDENTIALS =
            new BaiduTranslateBackend.Credentials("2015063000000001", "12345678");
    private static final long WAIT_MS = 10_000L;

    private FakeAndroidContext context;
    private SettingsStore settingsStore;
    private AiSettings aiSettings;
    private ExecutorService laneExecutor;
    private ExecutorService aiExecutor;
    private BaiduProvider provider;

    @Before public void setUp() {
        context = new FakeAndroidContext();
        settingsStore = new SettingsStore(context);
        settingsStore.put(Settings.TRANSLATION_ENABLED, true);
        settingsStore.put(Settings.TRANSLATION_TARGET, "zh");
        aiSettings = new AiSettings(settingsStore,
                new AiCredentialStore(context, new IdentityCipher()));
        laneExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "meaning-partial"));
        aiExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "meaning-partial-ai"));
        provider = new BaiduProvider();
        BaiduTranslateBackend.clearLimitState();
        BaiduTranslateBackend.clock = new BaiduTranslateBackendTest.FakeClock();
    }

    @After public void tearDown() throws Exception {
        BaiduTranslateBackend.clearLimitState();
        BaiduTranslateBackend.clock = SystemClock::elapsedRealtime;
        laneExecutor.shutdownNow();
        aiExecutor.shutdownNow();
        clearCoalescer();
    }

    /**
     * The strong form of the guarantee: whatever the first answer left behind, the run does not end
     * until every line has a translation.
     */
    @Test public void everyLineIsTranslatedWhenTheFirstAnswerSkipsTheRepeatedOne() throws Exception {
        Scripted script = Scripted.withRepeats();
        // The first answer carries every distinct line except the repeated "We are", which is ten
        // rows in this document: more than the eight-row cap the old rescue pass silently refused.
        ScriptedEndpoint http = new ScriptedEndpoint(
                script.markedAnswerMissing(1),
                script.markedAnswerForSingleLine("我们是"));
        Recorder callback = new Recorder();

        assertTrue(newLane(http).start(TRACK, 1, repeatedDocument(), "baidu_unofficial", "zh",
                "auto", "auto", false, alwaysCurrent(), callback));

        assertTrue("the run must settle", callback.await(WAIT_MS));
        assertEquals("every line must be translated, repeats included", 20, callback.changed);
        assertEquals(20, callback.translatedRowIds);
        assertNotNull(callback.artifact);
        assertFalse("nothing may be left partial when every row is covered",
                callback.artifact.partial);
        assertFalse(callback.failure.isFailure());
        // Two requests: the batch, then the pass that re-asked the rows it left behind.
        assertEquals(2, http.count());
    }

    /** With an answer that covers everything, the repeated rows are filled from one request. */
    @Test public void repeatedLinesCostOneRequestAndAllOccurrencesAreFilled() throws Exception {
        ScriptedEndpoint http = new ScriptedEndpoint(Scripted.withRepeats().markedAnswerAll());
        Recorder callback = new Recorder();

        assertTrue(newLane(http).start(TRACK, 2, repeatedDocument(), "baidu_unofficial", "zh",
                "auto", "auto", false, alwaysCurrent(), callback));

        assertTrue(callback.await(WAIT_MS));
        assertEquals("every occurrence of a repeated line takes the one answer", 20, callback.changed);
        assertFalse(callback.failure.isFailure());
        assertEquals("a repeated line must not cost a second request", 1, http.count());
    }

    /**
     * A run the endpoint refuses with its frequency limit is a rate limit, not a generic failure:
     * that is the wording the reader has to see, and the run must translate nothing rather than
     * claim success.
     */
    @Test public void aCooldownRefusalIsReportedAsARateLimitWithNothingTranslated() throws Exception {
        ScriptedEndpoint http = new ScriptedEndpoint(
                "{\"error_code\":\"54003\",\"error_msg\":\"Access frequency limited\"}");
        Recorder callback = new Recorder();

        assertTrue(newLane(http).start(TRACK, 3, repeatedDocument(), "baidu_unofficial", "zh",
                "auto", "auto", false, alwaysCurrent(), callback));

        assertTrue(callback.await(WAIT_MS));
        assertEquals(0, callback.changed);
        assertTrue("a refused run is a failure, not a success", callback.failure.isFailure());
        assertEquals(429, callback.failure.httpStatus);
        TranslationFailureNotice notice =
                TranslationFailureNotice.of(callback.failure, "baidu");
        assertNotNull(notice);
        assertEquals(TranslationFailureNotice.Kind.RATE_LIMITED, notice.kind);
        assertEquals("Baidu translation rate limited; retry later", notice.describe());
        // The refusal is answered without a request of its own: the limit is in force, so asking
        // again is what would keep it in place.
        assertEquals(1, http.count());
    }

    // --- Harness ------------------------------------------------------------------

    /** 20 lines, 10 of them repeats of "We are", the shape the owner reported. */
    private static LyricsDocument repeatedDocument() {
        String[] texts = {
                "You are my symphony", "We are", "By your side", "We are, we are unity",
                "You are my energy", "We are", "My guiding light", "We are",
                "We are unity", "We are", "You are my symphony", "We are",
                "By your side", "We are", "You are my energy", "We are",
                "My guiding light", "We are", "We are unity", "We are"};
        LyricsDocument document = new LyricsDocument();
        document.trackId = TRACK;
        document.language = "";
        document.translationPending = true;
        for (int i = 0; i < texts.length; i++) {
            LyricsLine line = new LyricsLine();
            line.text = texts[i];
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    private LyricsMeaningLane newLane(ScriptedEndpoint http) {
        return new LyricsMeaningLane(context, http.client(), laneExecutor, aiExecutor, 1, provider,
                Runnable::run, () -> 1_000L, ignored -> aiSettings);
    }

    private static LyricsSecondaryProcessor.CurrentGuard alwaysCurrent() {
        return (id, generation, snapshot) -> true;
    }

    private static void clearCoalescer() throws Exception {
        Field field = LyricsMeaningLane.class.getDeclaredField("COALESCER");
        field.setAccessible(true);
        ((LayerRunCoalescer) field.get(null)).clear();
    }

    /** The backend with explicit credentials, so the run needs no encrypted store off-device. */
    private final class BaiduProvider implements LyricsMeaningLane.MeaningProvider {
        @Override public String backendId() {
            return "baidu_unofficial";
        }

        @Override public boolean handles(String backendSetting) {
            return true;
        }

        @Override public GoogleEnhancer.BatchResult translate(Context ignored, OkHttpClient http,
                int processingVersion, String trackId, String sourceLang, String targetLang,
                List<GoogleEnhancer.BatchLine> batch, String cancelTag) {
            return BaiduTranslateBackend.translateBatch(ignored, http, processingVersion, trackId,
                    sourceLang, targetLang, batch, cancelTag, CREDENTIALS);
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

    /** The document's distinct texts in wire order, and the marked answer for them. */
    private static final class Scripted {
        private final List<String> distinct = new ArrayList<>();

        static Scripted withRepeats() {
            Scripted script = new Scripted();
            for (LyricsLine line : repeatedDocument().lines) {
                if (!script.distinct.contains(line.text)) script.distinct.add(line.text);
            }
            return script;
        }

        String markedAnswer(int skipMarker) {
            StringBuilder body = new StringBuilder("{\"trans_result\":[");
            boolean first = true;
            for (int i = 0; i < distinct.size(); i++) {
                if (i == skipMarker) continue;
                if (!first) body.append(',');
                first = false;
                body.append("{\"dst\":\"[[SPX_")
                        .append(String.format(java.util.Locale.US, "%03d", i))
                        .append("]] 译").append(i).append("\"}");
            }
            return body.append("]}").toString();
        }

        /** Every distinct line answered, which is the well-behaved endpoint. */
        String markedAnswerAll() {
            return markedAnswer(-1);
        }

        String markedAnswerMissing(int marker) {
            return markedAnswer(marker);
        }

        String markedAnswerForSingleLine(String translation) {
            return "{\"trans_result\":[{\"dst\":\"[[SPX_000]] " + translation + "\"}]}";
        }
    }

    /** An OkHttp client answering from a script; the last reply repeats, as the sibling test's does. */
    private static final class ScriptedEndpoint {
        private final List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final String[] replies;

        ScriptedEndpoint(String... replies) {
            this.replies = replies;
        }

        OkHttpClient client() {
            return new OkHttpClient.Builder().addInterceptor(chain -> {
                Request request = chain.request();
                requests.add(request);
                String reply = replies[Math.min(requests.size() - 1, replies.length - 1)];
                int status = 200;
                int space = reply.indexOf(' ');
                if (space > 0 && reply.substring(0, space).matches("\\d{3}")) {
                    status = Integer.parseInt(reply.substring(0, space));
                    reply = reply.substring(space + 1);
                }
                return new okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(status)
                        .message("scripted")
                        .body(okhttp3.ResponseBody.create(reply,
                                okhttp3.MediaType.get("application/json")))
                        .build();
            }).build();
        }

        int count() {
            return requests.size();
        }
    }

    private static final class Recorder implements LyricsSecondaryProcessor.Callback {
        private final CountDownLatch done = new CountDownLatch(1);
        DerivedLayerArtifact artifact;
        LayerFailure failure = LayerFailure.NONE;
        int changed;
        int translatedRowIds;

        boolean await(long timeoutMs) throws InterruptedException {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial, String message) {
        }

        @Override public void progress(String message) {
        }

        @Override public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                                       LayerFailure failure, String message, int changed) {
            this.artifact = artifact;
            this.failure = failure == null ? LayerFailure.NONE : failure;
            this.changed = changed;
            this.translatedRowIds = artifact == null ? 0 : artifact.rowIds().size();
            done.countDown();
        }
    }

    /** The credential store with a cipher that is happy to live on a plain JVM. */
    private static final class IdentityCipher implements AiCredentialStore.Cipher {
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
