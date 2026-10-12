package com.spotifyplusplus.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.testsupport.FakeAndroidContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The Baidu backend's wire shape, its request budget and its response handling, without a network.
 *
 * <p>Every case here is a real question about the official API: which fields and which signature
 * the request carries, how large one request may be, which refusals are permanent, and - the reason
 * the batching rules exist at all - how many round trips a song costs.
 *
 * <p>The documented parameters, the 6000-byte query ceiling and the signature example asserted in
 * {@link #signsBaidusOwnWorkedExample} came from Baidu's own documentation; error codes come from
 * its error-code table. The one thing no test here can settle is what the live service answers to a
 * correctly signed request, which needs the owner's credentials.
 *
 * <p>The per-line cache read that precedes every request in this backend cannot be asserted here:
 * {@code LyricCaches} is SQLite-backed, and OpenHelper is a stub off-device, so the store answers
 * nothing in a JVM test and a second identical batch always looks cold. What is asserted here is
 * everything after that read - which requests are built, which are not sent, and how many go out -
 * and the device log's {@code cacheHits=} on the settlement line is where a hit is observed.
 */
public class BaiduTranslateBackendTest {
    private static final String APP_ID = "2015063000000001";
    private static final String SECRET = "12345678";
    private static final BaiduTranslateBackend.Credentials CREDENTIALS =
            new BaiduTranslateBackend.Credentials(APP_ID, SECRET);

    private FakeAndroidContext context;
    private FakeClock clock;

    @Before public void setUp() {
        context = new FakeAndroidContext();
        clock = new FakeClock();
        BaiduTranslateBackend.clock = clock;
        // The limit state is process-wide on purpose (it is per account), so a test that earned a
        // cooldown would otherwise hold back every later test in this JVM.
        BaiduTranslateBackend.clearLimitState();
    }

    @After public void tearDown() {
        BaiduTranslateBackend.clearLimitState();
        BaiduTranslateBackend.clock = android.os.SystemClock::elapsedRealtime;
    }

    // --- The request the official API documents ---

    @Test public void signsTheDocumentedParametersWithTheDocumentedFormula() throws Exception {
        Request request = BaiduTranslateBackend.taggedRequest(
                "[[SPX_000]] 안녕하세요", "ko", "zh", CREDENTIALS, "MEANING#7");

        assertEquals("POST", request.method());
        assertEquals("https://fanyi-api.baidu.com/api/trans/vip/translate",
                request.url().toString());
        assertEquals("MEANING#7", request.tag(String.class));
        assertTrue(String.valueOf(request.body().contentType())
                .startsWith("application/x-www-form-urlencoded"));
        // The page endpoint's headers are gone with the page endpoint: a credentialled API call
        // does not have a referer, an origin, or an XHR marker.
        assertNull(request.header("Referer"));
        assertNull(request.header("Origin"));
        assertNull(request.header("X-Requested-With"));

        Map<String, String> form = decodeForm(bodyOf(request));
        assertEquals("[[SPX_000]] 안녕하세요", form.get("q"));
        assertEquals("ko", form.get("from"));
        assertEquals("zh", form.get("to"));
        assertEquals(APP_ID, form.get("appid"));
        assertFalse(form.get("salt").isEmpty());
        assertEquals("every parameter the API documents must be present",
                6, form.size());
        // Recomputed here from the raw values rather than through the production helper, so the
        // assertion is about the formula the API documents: MD5(appid + q + salt + secret), hex.
        assertEquals(md5Hex(APP_ID + form.get("q") + form.get("salt") + SECRET), form.get("sign"));
        assertEquals(32, form.get("sign").length());
        assertEquals(form.get("sign").toLowerCase(java.util.Locale.ROOT), form.get("sign"));
    }

    /**
     * Baidu's own worked example, from the parameter table of its documentation: appid
     * {@code 2015063000000001}, q {@code apple}, salt {@code 1435660288}, secret {@code 12345678}
     * is published as producing {@code f89f9594663708c1605f3d736d01d2d4}. If the concatenation
     * order or the hex encoding ever changes, this fails.
     */
    @Test public void signsBaidusOwnWorkedExample() {
        assertEquals("f89f9594663708c1605f3d736d01d2d4",
                BaiduTranslateBackend.sign("2015063000000001", "apple", "1435660288", "12345678"));
    }

    @Test public void everyRequestCarriesAFreshSalt() throws Exception {
        Request first = BaiduTranslateBackend.taggedRequest("a", "en", "zh", CREDENTIALS, "t");
        Request second = BaiduTranslateBackend.taggedRequest("a", "en", "zh", CREDENTIALS, "t");

        String firstSalt = decodeForm(bodyOf(first)).get("salt");
        String secondSalt = decodeForm(bodyOf(second)).get("salt");
        assertNotEquals("a reused salt makes two requests look like one", firstSalt, secondSalt);
        assertFalse(firstSalt.isEmpty());
        assertTrue(firstSalt, firstSalt.matches("[0-9a-f]+"));
    }

    // --- Batching under the documented 6000-byte query ceiling ---

    @Test public void keepsEveryRequestInsideTheDocumentedQueryCeiling() {
        List<GoogleEnhancer.BatchLine> lines = chineseLines(120, 40);

        List<List<BaiduTranslateBackend.WireLine>> chunks = chunksOf(lines);

        assertTrue("a 120-line Chinese batch must not travel as one request", chunks.size() > 1);
        int position = 0;
        for (List<BaiduTranslateBackend.WireLine> chunk : chunks) {
            int bytes = GoogleEnhancer.batchQuery(BaiduTranslateBackend.wireBatch(chunk))
                    .getBytes(StandardCharsets.UTF_8).length;
            assertTrue("chunk of " + bytes + " bytes exceeds the API's ceiling",
                    bytes <= BaiduTranslateBackend.QUERY_MAX_BYTES);
            assertTrue(chunk.size() <= BaiduTranslateBackend.CHUNK_MAX_LINES);
            for (BaiduTranslateBackend.WireLine line : chunk) {
                assertEquals("chunking must not reorder the batch", position++,
                        (int) line.indices.get(0));
            }
        }
        assertEquals(lines.size(), position);
    }

    @Test public void sendsEverythingUnderTheCeilingAsOneRequest() {
        List<List<BaiduTranslateBackend.WireLine>> chunks = chunksOf(chineseLines(4, 10));

        assertEquals("a short batch costs exactly one request", 1, chunks.size());
        assertEquals(4, chunks.get(0).size());
    }

    @Test public void aSingleLineOverTheCeilingIsSentAloneRatherThanSplit() {
        String huge = repeat("字", 3000);

        List<List<BaiduTranslateBackend.WireLine>> chunks = chunksOf(
                Arrays.asList(new GoogleEnhancer.BatchLine(7, huge),
                        new GoogleEnhancer.BatchLine(8, "short")));

        // Split lyrics would break the marker-to-row mapping, so the long line travels alone and
        // the API's own refusal for it is the honest outcome.
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).size());
        assertEquals(7, (int) chunks.get(0).get(0).indices.get(0));
        assertEquals(8, (int) chunks.get(1).get(0).indices.get(0));
    }

    // --- Response shapes ---

    @Test public void parsesTheOfficialMarkedAnswer() {
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"from\":\"en\",\"to\":\"zh\",\"trans_result\":["
                        + "{\"src\":\"first\",\"dst\":\"[[SPX_000]] 第一行\"},"
                        + "{\"src\":\"second\",\"dst\":\"[[SPX_001]] 第二行\"}]}", 2);

        // A success carries no code field at all - only a refusal does - so "no code" has to read as
        // success rather than as a shape this build does not know.
        assertFalse(parsed.hasCode);
        assertEquals(0, parsed.code);
        assertFalse(parsed.malformed);
        assertEquals(2, parsed.destinations);
        assertEquals("第一行", parsed.translations.get(0));
        assertEquals("第二行", parsed.translations.get(1));
    }

    @Test public void fallsBackToDestinationOrderWhenTheMarkersDoNotSurvive() {
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"trans_result\":[{\"src\":\"a\",\"dst\":\"one\"},{\"src\":\"b\",\"dst\":\"two\"}]}",
                2);

        assertEquals("one", parsed.translations.get(0));
        assertEquals("two", parsed.translations.get(1));
    }

    @Test public void readsARefusalWhoseCodeIsAString() {
        // The API sends error_code as a JSON string; the field is documented as a number.
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"error_code\":\"54003\",\"error_msg\":\"Access frequency limited\"}", 3);

        assertTrue(parsed.hasCode);
        assertEquals(54003, parsed.code);
        assertEquals("Access frequency limited", parsed.message);
        assertTrue(parsed.translations.isEmpty());
        assertFalse(parsed.malformed);
    }

    @Test public void readsARefusalWhoseCodeIsANumber() {
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"error_code\":54001,\"error_msg\":\"Invalid Sign\"}", 1);

        assertEquals(54001, parsed.code);
        assertEquals("Invalid Sign", parsed.message);
    }

    @Test public void aBodyWithoutACodeIsReportedAsMissingRatherThanAsZero() {
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"trans_result\":[{\"dst\":\"x\"}]}", 1);

        assertFalse(parsed.hasCode);
        assertEquals(0, parsed.code);
        assertEquals("x", parsed.translations.get(0));
    }

    @Test public void aDestinationCountThatDoesNotMatchTheBatchIsNotGuessed() {
        // A wrong line's translation on a row is worse than asking again.
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"trans_result\":[{\"dst\":\"one\\ntwo\\nthree\"}]}", 2);

        assertTrue(parsed.translations.isEmpty());
        assertEquals(1, parsed.destinations);
    }

    @Test public void oneDestinationIsUsedAsTheOnlyLineWhenTheMarkerIsDropped() {
        BaiduTranslateBackend.Parsed parsed = BaiduTranslateBackend.parseBatchTranslation(
                "{\"trans_result\":[{\"dst\":\"你好\"}]}", 1);

        assertEquals("你好", parsed.translations.get(0));
        assertEquals(1, parsed.translations.size());
    }

    @Test public void anUnexpectedShapeFailsInsteadOfThrowing() {
        BaiduTranslateBackend.Parsed notJson =
                BaiduTranslateBackend.parseBatchTranslation("<html>busy</html>", 2);
        BaiduTranslateBackend.Parsed notAnObject =
                BaiduTranslateBackend.parseBatchTranslation("[1,2,3]", 2);
        BaiduTranslateBackend.Parsed empty =
                BaiduTranslateBackend.parseBatchTranslation("", 2);

        assertTrue(notJson.malformed);
        assertTrue(notAnObject.malformed);
        assertTrue(empty.malformed);
        assertTrue(notJson.translations.isEmpty());
        assertTrue(notAnObject.translations.isEmpty());
        assertTrue(empty.translations.isEmpty());
        assertEquals(0, notJson.destinations);
    }

    // --- Which refusals are worth another request ---

    @Test public void theTwoFrequencyCodesAreTheRateLimit() {
        assertTrue(BaiduTranslateBackend.isRateLimited(54003));
        assertTrue(BaiduTranslateBackend.isRateLimited(54005));
        assertFalse(BaiduTranslateBackend.isRateLimited(52001));
        assertFalse(BaiduTranslateBackend.isPermanent(54003));
        assertFalse(BaiduTranslateBackend.isPermanent(54005));
    }

    @Test public void credentialAndAccountRefusalsArePermanent() {
        int[] permanent = {52003, 54000, 54001, 54004, 58000, 58001, 58002, 90107};
        for (int code : permanent) {
            assertTrue("code " + code + " cannot be fixed by asking again",
                    BaiduTranslateBackend.isPermanent(code));
        }
        // The two transient ones, and a code this build has never seen: one more try is cheap, and
        // an unknown refusal is at least as likely to be momentary as it is to be final.
        assertFalse(BaiduTranslateBackend.isPermanent(52001));
        assertFalse(BaiduTranslateBackend.isPermanent(52002));
        assertFalse(BaiduTranslateBackend.isPermanent(12345));
    }

    // --- The batch path: how many requests, and which ones are not sent ---

    @Test public void aBatchWithNothingPendingCostsNoRequest() {
        ScriptedHttp http = new ScriptedHttp(markedAnswer("hello"));
        List<GoogleEnhancer.BatchLine> lines = Arrays.asList(
                new GoogleEnhancer.BatchLine(0, "   "),
                new GoogleEnhancer.BatchLine(1, ""),
                new GoogleEnhancer.BatchLine(2, null));

        GoogleEnhancer.BatchResult result = call(http, lines);

        assertEquals(0, http.count());
        assertEquals(0, result.requestedCount);
        assertTrue(result.translations.isEmpty());
        assertEquals("", result.failureReason);
    }

    @Test public void withoutCredentialsNothingIsSentAndTheReasonNamesIt() {
        ScriptedHttp http = new ScriptedHttp(markedAnswer("hello"));
        List<GoogleEnhancer.BatchLine> lines =
                Arrays.asList(new GoogleEnhancer.BatchLine(0, "你好"));

        // The public entry point reads the stored credentials; none are stored on this context.
        GoogleEnhancer.BatchResult result = BaiduTranslateBackend.translateBatch(context, http.client(),
                1, "track", "zh", "en", lines, "MEANING#1");

        assertEquals("an unsigned request cannot be answered, so none may be sent",
                0, http.count());
        assertEquals("baidu_unconfigured", result.failureReason);
        assertEquals(0, result.httpStatus);
        assertEquals(0, result.networkAttempts);
        assertFalse(result.permanentFailure);
        assertTrue(result.translations.isEmpty());
    }

    @Test public void oneRequestPerChunkAndEveryLineComesBack() {
        List<GoogleEnhancer.BatchLine> lines = chineseLines(120, 40);
        List<String> replies = new ArrayList<>();
        for (List<BaiduTranslateBackend.WireLine> chunk : chunksOf(lines)) {
            replies.add(markedAnswerFor(BaiduTranslateBackend.wireBatch(chunk), "译"));
        }
        ScriptedHttp http = new ScriptedHttp(replies.toArray(new String[0]));

        GoogleEnhancer.BatchResult result = call(http, lines);

        assertEquals("one request per chunk and no more: " + http.urlList(),
                chunksOf(lines).size(), http.count());
        assertEquals(120, result.networkTranslatedCount);
        assertEquals(120, result.translations.size());
        for (int i = 0; i < 120; i++) {
            assertEquals("译", result.translations.get(i));
        }
        assertEquals(200, result.httpStatus);
        assertEquals("", result.failureReason);
    }

    /**
     * The owner's report: lines repeating one text came back untranslated while their distinct
     * neighbours were translated. One entry per distinct text on the wire, and every occurrence of
     * that text is filled from the single answer.
     */
    @Test public void repeatedLinesTravelOnceAndEveryOccurrenceTakesTheAnswer() throws Exception {
        List<GoogleEnhancer.BatchLine> lines = Arrays.asList(
                new GoogleEnhancer.BatchLine(0, "You are my symphony"),
                new GoogleEnhancer.BatchLine(1, "We are"),
                new GoogleEnhancer.BatchLine(2, "By your side"),
                new GoogleEnhancer.BatchLine(3, "We are"),
                new GoogleEnhancer.BatchLine(4, "We are unity"),
                new GoogleEnhancer.BatchLine(5, "We are"));
        ScriptedHttp http = new ScriptedHttp(
                markedAnswerFor(BaiduTranslateBackend.wireBatch(
                        BaiduTranslateBackend.wireLines(lines)), "译"));

        GoogleEnhancer.BatchResult result = call(http, lines);

        assertEquals("a repeated line must not cost a second destination", 1, http.count());
        String query = decodeForm(bodyOf(http.requests.get(0))).get("q");
        assertEquals("[[SPX_000]] You are my symphony"
                        + "\n[[SPX_001]] We are"
                        + "\n[[SPX_002]] By your side"
                        + "\n[[SPX_003]] We are unity",
                query);
        assertEquals(6, result.translations.size());
        assertEquals("译", result.translations.get(1));
        assertEquals("译", result.translations.get(3));
        assertEquals("译", result.translations.get(5));
        assertEquals(6, result.networkTranslatedCount);
    }

    @Test public void aPermanentRefusalEndsThePassWithoutAskingForTheNextChunk() {
        List<GoogleEnhancer.BatchLine> lines = chineseLines(120, 40);
        int chunks = chunksOf(lines).size();
        assertTrue(chunks > 1);
        ScriptedHttp http = new ScriptedHttp(
                "{\"error_code\":\"54001\",\"error_msg\":\"Invalid Sign\"}");

        GoogleEnhancer.BatchResult result = call(http, lines);

        // One request, not one per chunk: the signature is computed the same way for every chunk,
        // so every later chunk would be refused identically.
        assertEquals(1, http.count());
        assertTrue(result.permanentFailure);
        assertEquals("baidu_error_54001", result.failureReason);
    }

    @Test public void aTransientRefusalDoesNotStopTheNextChunk() {
        List<GoogleEnhancer.BatchLine> lines = chineseLines(120, 40);
        List<List<BaiduTranslateBackend.WireLine>> chunks = chunksOf(lines);
        assertTrue(chunks.size() > 1);
        List<String> replies = new ArrayList<>();
        replies.add("{\"error_code\":\"52001\",\"error_msg\":\"Request Timeout\"}");
        for (int i = 1; i < chunks.size(); i++) {
            replies.add(markedAnswerFor(BaiduTranslateBackend.wireBatch(chunks.get(i)), "译"));
        }
        ScriptedHttp http = new ScriptedHttp(replies.toArray(new String[0]));

        GoogleEnhancer.BatchResult result = call(http, lines);

        // The next chunk is a different query and is still worth sending; the first chunk's lines
        // stay missing so the lane's retry pass can re-ask for them in smaller batches.
        assertEquals(chunks.size(), http.count());
        assertFalse(result.permanentFailure);
        assertEquals("baidu_error_52001", result.failureReason);
        assertEquals(lines.size() - chunks.get(0).size(), result.networkTranslatedCount);
        assertEquals("译", result.translations.get(chunks.get(1).get(0).indices.get(0)));
        assertNull(result.translations.get(chunks.get(0).get(0).indices.get(0)));
    }

    @Test public void aRateLimitIsReportedAs429AndHoldsBackTheNextRun() {
        ScriptedHttp http = new ScriptedHttp(
                "{\"error_code\":\"54003\",\"error_msg\":\"Access frequency limited\"}");
        List<GoogleEnhancer.BatchLine> lines =
                Arrays.asList(new GoogleEnhancer.BatchLine(0, "你好"));

        GoogleEnhancer.BatchResult limited = call(http, lines);

        assertEquals(429, limited.httpStatus);
        assertEquals("baidu_error_54003", limited.failureReason);
        assertEquals(1, http.count());
        // The clock is frozen in this test, so the account-wide cooldown is still in force: the next
        // batch is answered the way the endpoint would, without being sent.
        clock.stepMs = 0L;
        GoogleEnhancer.BatchResult held = call(http, lines);
        assertEquals(429, held.httpStatus);
        assertEquals("cooldown", held.failureReason);
        assertEquals("a limited account must not be asked again", 1, http.count());
    }

    @Test public void aTransportRefusalIsReportedWithoutClaimingPermanence() {
        ScriptedHttp http = new ScriptedHttp(reply(400, "bad request"));
        List<GoogleEnhancer.BatchLine> lines =
                Arrays.asList(new GoogleEnhancer.BatchLine(0, "你好"));

        GoogleEnhancer.BatchResult result = call(http, lines);

        assertEquals(1, http.count());
        assertEquals(400, result.httpStatus);
        assertEquals("http_400", result.failureReason);
        assertFalse("a wire refusal is not an account verdict", result.permanentFailure);
    }

    @Test public void everyChunkOfALongBatchStaysUnderTheCeilingOnTheWire() throws Exception {
        List<GoogleEnhancer.BatchLine> lines = chineseLines(120, 40);
        List<String> replies = new ArrayList<>();
        for (List<BaiduTranslateBackend.WireLine> chunk : chunksOf(lines)) {
            replies.add(markedAnswerFor(BaiduTranslateBackend.wireBatch(chunk), "译"));
        }
        ScriptedHttp http = new ScriptedHttp(replies.toArray(new String[0]));

        call(http, lines);

        for (Request request : http.requests) {
            String query = decodeForm(bodyOf(request)).get("q");
            assertTrue("q of " + query.getBytes(StandardCharsets.UTF_8).length + " bytes",
                    query.getBytes(StandardCharsets.UTF_8).length
                            <= BaiduTranslateBackend.QUERY_MAX_BYTES);
        }
    }

    // --- The cooldown: how long it lasts and what clears it ---

    /**
     * The limit is a per-second window, so the ladder starts short and stops at a minute. The
     * Google-derived 30s-to-10min ladder held every song back for half a minute after one burst
     * and, because nothing is sent while a cooldown is in force, no success could shorten it.
     */
    @Test public void theCooldownStartsShortAndIsCappedAtAMinute() {
        assertEquals(5_000L, BaiduTranslateBackend.limitBackoffMs(0L, 0L));
        assertEquals(10_000L, BaiduTranslateBackend.limitBackoffMs(0L, 1L));
        assertEquals(20_000L, BaiduTranslateBackend.limitBackoffMs(0L, 2L));
        assertEquals(60_000L, BaiduTranslateBackend.limitBackoffMs(0L, 20L));
        // A documented Retry-After is honoured, and capped at the same ceiling.
        assertEquals(3_000L, BaiduTranslateBackend.limitBackoffMs(3_000L, 9L));
        assertEquals(60_000L, BaiduTranslateBackend.limitBackoffMs(600_000L, 0L));
    }

    @Test public void aCooldownClearsWhenItsWindowPassesAndTheNextBatchIsSent() {
        ScriptedHttp http = new ScriptedHttp(
                "{\"error_code\":\"54003\",\"error_msg\":\"Access frequency limited\"}",
                markedAnswer("你好"));
        List<GoogleEnhancer.BatchLine> lines =
                Arrays.asList(new GoogleEnhancer.BatchLine(0, "hello"));

        assertEquals(429, call(http, lines).httpStatus);

        // Past the base window: the next batch must be sent rather than answered from the cooldown.
        clock.stepMs = 20_000L;
        GoogleEnhancer.BatchResult after = call(http, lines);

        assertEquals("second call answered " + after.httpStatus + "/" + after.failureReason
                + " after " + http.count() + " request(s)", 2, http.count());
        assertEquals(200, after.httpStatus);
        assertEquals("你好", after.translations.get(0));
        assertEquals("", after.failureReason);
    }

    /**
     * The two states a refused run can be in, told apart on the same call: a cooldown is only ever
     * reported after the credentials check passed, so an unconfigured device must keep reporting
     * {@code baidu_unconfigured} with no request and no 429 even while the account is limited.
     */
    @Test public void anUnconfiguredBackendIsNeverReportedAsACooldown() {
        clock.stepMs = 0L;
        ScriptedHttp http = new ScriptedHttp(
                "{\"error_code\":\"54003\",\"error_msg\":\"Access frequency limited\"}");
        List<GoogleEnhancer.BatchLine> lines =
                Arrays.asList(new GoogleEnhancer.BatchLine(0, "你好"));
        assertEquals(429, call(http, lines).httpStatus);

        // The public entry point reads the stored credentials; none are stored on this context.
        GoogleEnhancer.BatchResult unconfigured = BaiduTranslateBackend.translateBatch(context,
                http.client(), 1, "track", "zh", "en", lines, "MEANING#1");

        assertEquals("baidu_unconfigured", unconfigured.failureReason);
        assertEquals(0, unconfigured.httpStatus);
        assertEquals(0, unconfigured.networkAttempts);
        assertEquals("a refused batch and no request: the cooldown path would have sent none either",
                1, http.count());
    }

    // --- Unchanged behaviour ---

    @Test public void mapsTheCodesBaiduSpellsDifferently() {
        assertEquals("zh", BaiduTranslateBackend.baiduLanguage("zh"));
        assertEquals("cht", BaiduTranslateBackend.baiduLanguage("zh-TW"));
        assertEquals("jp", BaiduTranslateBackend.baiduLanguage("ja"));
        assertEquals("kor", BaiduTranslateBackend.baiduLanguage("ko"));
        assertEquals("fra", BaiduTranslateBackend.baiduLanguage("fr"));
        assertEquals("spa", BaiduTranslateBackend.baiduLanguage("es"));
        assertEquals("auto", BaiduTranslateBackend.baiduLanguage("auto"));
        assertEquals("auto", BaiduTranslateBackend.baiduLanguage(""));
        assertEquals("auto", BaiduTranslateBackend.baiduLanguage(null));
        assertEquals("en", BaiduTranslateBackend.baiduLanguage("en"));
        // Unmapped codes travel unchanged: the API's own 58001 for a pair it does not serve is a
        // clearer answer than a code invented here.
        assertEquals("xx", BaiduTranslateBackend.baiduLanguage("xx"));
    }

    @Test public void anUncancellableTagCancelsNothing() {
        OkHttpClient http = new OkHttpClient();

        assertEquals(0, BaiduTranslateBackend.cancelTagged(null, "MEANING#1"));
        assertEquals(0, BaiduTranslateBackend.cancelTagged(http, ""));
        assertEquals(0, BaiduTranslateBackend.cancelTagged(http, null));
        assertEquals(0, BaiduTranslateBackend.cancelTagged(http, "MEANING#1"));
    }

    @Test public void hidesAnEchoOfTheSourceOrItsRomanization() {
        assertTrue(BaiduTranslateBackend.shouldDisplayTranslation("안녕하세요", "你好"));
        assertFalse(BaiduTranslateBackend.shouldDisplayTranslation("안녕하세요", "안녕하세요"));
        assertFalse(BaiduTranslateBackend.shouldDisplayTranslation("中国", "zhong guo"));
    }

    @Test public void markedBatchQueryKeepsOneMarkerPerLine() {
        Map<Integer, String> parsed = GoogleEnhancer.splitByMarkers(
                GoogleEnhancer.batchQuery(Arrays.asList(
                        new GoogleEnhancer.BatchLine(0, "first"),
                        new GoogleEnhancer.BatchLine(1, "second"))));

        assertEquals("first", parsed.get(0));
        assertEquals("second", parsed.get(1));
    }

    // --- Fixtures ---

    private GoogleEnhancer.BatchResult call(ScriptedHttp http, List<GoogleEnhancer.BatchLine> lines) {
        return BaiduTranslateBackend.translateBatch(context, http.client(), 1, "track", "zh", "en",
                lines, "MEANING#1", CREDENTIALS);
    }

    /** The wire chunks of a batch, i.e. after repeated texts have been collapsed to one entry. */
    private static List<List<BaiduTranslateBackend.WireLine>> chunksOf(
            List<GoogleEnhancer.BatchLine> lines) {
        return BaiduTranslateBackend.apiChunks(BaiduTranslateBackend.wireLines(lines));
    }

    /**
     * Distinct lines, so the batch is not collapsed by the wire's one-entry-per-text rule and the
     * chunking arithmetic is the only thing under test.
     */
    private static List<GoogleEnhancer.BatchLine> chineseLines(int count, int charsPerLine) {
        List<GoogleEnhancer.BatchLine> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(new GoogleEnhancer.BatchLine(i, i + repeat("中", charsPerLine)));
        }
        return lines;
    }

    /** One marked destination per line, numbered the way the batch query numbers them. */
    private static String markedAnswerFor(List<GoogleEnhancer.BatchLine> chunk, String translation) {
        StringBuilder body = new StringBuilder("{\"trans_result\":[");
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) body.append(',');
            body.append("{\"src\":\"s\",\"dst\":\"[[SPX_")
                    .append(String.format(java.util.Locale.US, "%03d", i))
                    .append("]] ").append(translation).append("\"}");
        }
        return body.append("]}").toString();
    }

    private static String markedAnswer(String translation) {
        return "{\"trans_result\":[{\"dst\":\"[[SPX_000]] " + translation + "\"}]}";
    }

    private static String reply(int status, String body) {
        return status + " " + body;
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static String bodyOf(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    private static Map<String, String> decodeForm(String body) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            form.put(java.net.URLDecoder.decode(pair.substring(0, equals), "UTF-8"),
                    java.net.URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
        return form;
    }

    private static String md5Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(java.util.Locale.US, "%02x", b));
        return hex.toString();
    }

    /**
     * The lane's clock, driven by the test.
     *
     * <p>It steps a shared monotonic base forward on every read, so the request spacing never makes
     * a test sleep and a clock never moves backwards between tests - the throttle is keyed per lane
     * and the state is static, so a clock that restarted lower would make the next test wait for
     * real. A test that wants to observe a cooldown sets {@link #stepMs} to zero and freezes it.
     *
     * <p>The step is applied before the value is returned. Subtracting it first - {@code getAndAdd}
     * - made the first read after a step report the instant before it, so a test that stepped past
     * a cooldown was still answered from it.
     */
    static final class FakeClock implements java.util.function.LongSupplier {
        private static final java.util.concurrent.atomic.AtomicLong NOW_MS =
                new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        long stepMs = 2_000L;

        @Override public long getAsLong() {
            return NOW_MS.addAndGet(stepMs);
        }
    }

    /**
     * An OkHttp client that answers from a script and records what was sent.
     *
     * <p>A reply is either {@code "<status> <body>"} or a bare body, which means HTTP 200. The last
     * reply repeats, so a test only writes the ones it cares about.
     */
    static final class ScriptedHttp {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        private final String[] replies;

        ScriptedHttp(String... replies) {
            this.replies = replies;
        }

        OkHttpClient client() {
            return new OkHttpClient.Builder().addInterceptor(new Interceptor() {
                @Override public Response intercept(Chain chain) throws IOException {
                    Request request = chain.request();
                    requests.add(request);
                    String reply = replies[Math.min(requests.size() - 1, replies.length - 1)];
                    int status = 200;
                    int space = reply.indexOf(' ');
                    if (space > 0 && reply.substring(0, space).matches("\\d{3}")) {
                        status = Integer.parseInt(reply.substring(0, space));
                        reply = reply.substring(space + 1);
                    }
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message("scripted")
                            .body(ResponseBody.create(reply, MediaType.get("application/json")))
                            .build();
                }
            }).build();
        }

        int count() {
            return requests.size();
        }

        List<String> urlList() {
            List<String> urls = new ArrayList<>();
            for (Request request : requests) urls.add(request.url().toString());
            return urls;
        }
    }
}
