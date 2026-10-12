package com.spotifyplusplus.lyrics.processing;

import android.content.Context;
import android.os.SystemClock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.spotifyplusplus.lyrics.cache.LyricCaches;
import com.spotifyplusplus.lyrics.ai.AiText;
import com.spotifyplusplus.xposed.XpLog;

import static com.spotifyplusplus.lyrics.LyricUtils.isBlank;
import static com.spotifyplusplus.lyrics.LyricUtils.safe;

/**
 * Baidu Translate-backed Meaning backend: the second peer behind the lane's provider contract.
 *
 * <p>Google's unofficial endpoint is unreachable from mainland China, which is the whole reason
 * this backend exists. It talks to Baidu's <b>official</b> translate API
 * ({@code https://fanyi-api.baidu.com/api/trans/vip/translate}), the credentialled one: every
 * request carries the owner's {@code appid} and a {@code sign} that only their secret can produce.
 *
 * <p>It used to talk to the keyless endpoint the fanyi.baidu.com web page serves itself
 * ({@code POST /transapi}), chosen so a device needed no account. That endpoint stopped accepting
 * unsigned requests: it answers HTTP 200 carrying {@code errno 1022} ("访问出现异常，请刷新后重试")
 * for every request, from a datacenter address and from a residential address in mainland China
 * alike, so the refusal is not IP filtering. There is no keyless path left, and the credentialled
 * one is the supported contract rather than a reverse-engineered page call.
 *
 * <p>What the official API imposes, all of which shapes this file:
 *
 * <ul>
 *   <li>{@code q} (the whole batch) is capped at <b>6000 bytes</b> of UTF-8. The lane's own batch
 *       limit is 4500 <i>characters</i>, which is 13500 bytes for Chinese lyrics - over the cap, so
 *       a song in Chinese was guaranteed to be refused before it was ever sent. Batches are cut to
 *       {@link #QUERY_MAX_BYTES} here, measured in the bytes the API counts.</li>
 *   <li>Refusals carry a non-zero {@code error_code}. Some are permanent (a wrong signature or an
 *       unauthorised app id is permanent until the owner changes something) and some are not (a
 *       timeout, a system error). Only the non-permanent ones are worth a second ask, so
 *       {@link #isPermanent} stops the rest.</li>
 *   <li>{@code 54003} (and its long-query sibling {@code 54005}) is the frequency limit. It is
 *       reported as a 429 so the lane stops the remaining batches the way it already does for
 *       Google, and the account-wide cooldown below holds back the next run.</li>
 *   <li>The published rate for the free tier is 1 request per second. Requests are therefore spaced
 *       by {@link #REQUEST_MIN_INTERVAL_MS} instead of 150ms, and they are sent one at a time:
 *       firing them concurrently would earn the very {@code 54003} the spacing exists to avoid, and
 *       a limited run stops asking entirely, which costs more than the wait it would have saved.</li>
 * </ul>
 *
 * <p>Marked batching is unchanged: the batch goes out as {@code [[SPX_000]] line} markers, the
 * answer is split back by marker first and by destination order second, and every line that comes
 * back is written to the same per-line cache the Google path reads.
 *
 * <p>The provider id and the stored setting value keep their historical {@code baidu_unofficial}
 * spelling. They are persisted in owner settings and recorded in artifact provenance, so renaming
 * one here would silently reset an owner's stored choice to Google; the endpoint behind the name is
 * the official one.
 */
public final class BaiduTranslateBackend {
    /**
     * The lane's tag. The Meaning lane, the Google backend, and this one share it on purpose: a
     * device log is grepped once, and the settlement line names which backend answered.
     */
    private static final String TAG = "[SpotifyPlusMeaningLane]";
    private static final String ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate";
    private static final String ENDPOINT_PATH = "/api/trans/vip/translate";

    /**
     * {@code q} is capped at 6000 bytes by the API. The margin covers counting differences nobody
     * documents (whether the cap includes the newlines the batch is joined with), and the cost of
     * being 100 bytes under is one more request per 59KB of lyrics.
     */
    static final int QUERY_MAX_BYTES = 5900;
    /** One request's line count is also bounded, so the three-digit batch markers never overflow. */
    static final int CHUNK_MAX_LINES = 100;
    /** Bytes of "[[SPX_000]] " in front of every line, plus the newline that joins them. */
    private static final int MARKER_BYTES = 12;
    private static final int JOIN_BYTES = 1;

    /** Reported when no app id or no secret is stored: no request is built at all. */
    static final String REASON_UNCONFIGURED = "baidu_unconfigured";

    private static final int REQUEST_RETRIES = 1;
    /**
     * Minimum spacing between two requests on this lane.
     *
     * <p>Baidu publishes 1 request per second for the standard tier, and a {@code 54003} does not
     * cost one request: the lane stops the rest of the pass and the reader is told to retry, so
     * being under the interval is far more expensive than waiting it out. The spacing is 1200ms
     * rather than a literal 1000ms because the account's limit is counted in the endpoint's own
     * seconds, and a gap that is exactly one second on this device's monotonic clock has no margin
     * for the two clocks disagreeing by a few milliseconds. One extra 200ms per request is
     * invisible next to a refused pass; most songs need exactly one request. The first request of
     * a run waits for nothing.
     */
    private static final long REQUEST_MIN_INTERVAL_MS = 1200L;
    private static final long REQUEST_RETRY_DELAY_MS = 1000L;

    // --- Baidu's error codes, as its own error-code table defines them. ---

    /** 请求超时 (request timed out): transient, the same query may succeed later. */
    static final int ERROR_TIMEOUT = 52001;
    /** 系统错误 (system error): transient. */
    static final int ERROR_SYSTEM = 52002;
    /** Both transient codes, as the reason strings a notice may have to classify. */
    static final String REASON_TIMEOUT = "baidu_error_" + ERROR_TIMEOUT;
    static final String REASON_SYSTEM_ERROR = "baidu_error_" + ERROR_SYSTEM;
    /** 未授权用户 (unauthorised user): the app id and secret do not match. Permanent. */
    static final int ERROR_UNAUTHORIZED_USER = 52003;
    /** 必填参数为空 (required parameter missing): a build defect, not a transient state. Permanent. */
    static final int ERROR_MISSING_PARAMETER = 54000;
    /** 签名错误 (invalid sign): the secret is wrong or the concatenation order changed. Permanent. */
    static final int ERROR_INVALID_SIGN = 54001;
    /** 访问频率受限 (request frequency limited): the rate limit. */
    static final int ERROR_FREQUENCY_LIMITED = 54003;
    /** 账户余额不足 (insufficient balance): permanent until the account is topped up. */
    static final int ERROR_INSUFFICIENT_BALANCE = 54004;
    /** 长query请求频繁 (long queries too frequent): the rate limit for large batches. */
    static final int ERROR_LONG_QUERY_FREQUENCY_LIMITED = 54005;
    /** 客户端IP非法 (illegal client IP): permanent for this network. */
    static final int ERROR_ILLEGAL_IP = 58000;
    /** 翻译方向不支持 (unsupported translation direction): permanent for this language pair. */
    static final int ERROR_UNSUPPORTED_DIRECTION = 58001;
    /** 服务已关闭 (service closed): permanent. */
    static final int ERROR_SERVICE_CLOSED = 58002;
    /** 认证未通过 (certification not passed): permanent until the account is certified. */
    static final int ERROR_NOT_CERTIFIED = 90107;

    private static final long LIMIT_BASE_MS = 5_000L;
    private static final long LIMIT_MAX_MS = 60_000L;

    /**
     * Quiet time after which the backoff ladder forgets its strikes.
     *
     * <p>The limit is a per-second window, so a {@code 54003} says nothing about a request made a
     * minute later. Without this, strikes earned during one burst kept the cap in place for every
     * later song in the session - and since nothing is sent while a cooldown is in force, no later
     * success could reset them.
     */
    private static final long LIMIT_STRIKE_DECAY_MS = 60_000L;

    /**
     * One throttle per lane, keyed the way the Google path keys its own: a lane's batches stay
     * polite to the endpoint without making the two lanes queue behind each other.
     */
    private static final Map<String, long[]> LANE_THROTTLES = new ConcurrentHashMap<>();

    /**
     * Process-wide, because the limit is per account: every lane instance and every song shares it,
     * so one refusal holds the next batch back instead of earning another refusal.
     *
     * <p>Neither value is persisted: a fresh process starts with no cooldown, which is what makes
     * a restart a way out of a limit the account has already served.
     */
    private static final AtomicLong LIMITED_UNTIL_MS = new AtomicLong();
    private static final AtomicLong LIMIT_STRIKES = new AtomicLong();
    /** When the last frequency limit was seen, for {@link #LIMIT_STRIKE_DECAY_MS}. */
    private static final AtomicLong LIMIT_LAST_STRIKE_MS = new AtomicLong(Long.MIN_VALUE);
    /**
     * Monotonic milliseconds for pacing and for the frequency-limit cooldown.
     *
     * <p>A field rather than a direct {@code SystemClock} call so a JVM test can drive it:
     * {@code android.os.SystemClock} is a stub off-device, and the two rules this clock exists for -
     * the request spacing and what a {@code 54003} holds back - are exactly the rules that decide
     * how many requests a song costs, so they cannot be the untested part.
     */
    static java.util.function.LongSupplier clock = SystemClock::elapsedRealtime;

    /** Makes a salt unique even when two requests are built inside the same millisecond. */
    private static final AtomicLong SALT_SEQUENCE = new AtomicLong();

    private BaiduTranslateBackend() {
    }

    /**
     * The credential pair a request is signed with. A value type so the request path can be
     * exercised without an Android Keystore, exactly as {@code AiCredentialStore} takes an injected
     * cipher for the same reason.
     */
    static final class Credentials {
        final String appId;
        final String secret;

        Credentials(String appId, String secret) {
            this.appId = safe(appId).trim();
            this.secret = safe(secret).trim();
        }

        boolean configured() {
            return !appId.isEmpty() && !secret.isEmpty();
        }
    }

    /**
     * Translates one batch, mirroring {@link GoogleEnhancer#translateBatch} step for step: cached
     * lines are answered from the per-line cache, the rest go out as requests, the responses are
     * parsed back into batch positions, and anything that does not come back is reported as a
     * failure reason rather than thrown.
     */
    public static GoogleEnhancer.BatchResult translateBatch(
            Context context,
            OkHttpClient http,
            int processingVersion,
            String trackId,
            String sourceLang,
            String targetLang,
            List<GoogleEnhancer.BatchLine> lines,
            String cancelTag
    ) {
        return translateBatch(context, http, processingVersion, trackId, sourceLang, targetLang,
                lines, cancelTag, new Credentials(BaiduTranslateCredentials.appId(context),
                        BaiduTranslateCredentials.secret(context)));
    }

    /** The work behind {@link #translateBatch}, with the credentials supplied rather than read. */
    static GoogleEnhancer.BatchResult translateBatch(
            Context context,
            OkHttpClient http,
            int processingVersion,
            String trackId,
            String sourceLang,
            String targetLang,
            List<GoogleEnhancer.BatchLine> lines,
            String cancelTag,
            Credentials credentials
    ) {
        GoogleEnhancer.BatchResult result = new GoogleEnhancer.BatchResult();
        if (lines == null || lines.isEmpty()) return result;

        String target = isBlank(targetLang) ? "en" : targetLang;
        List<GoogleEnhancer.BatchLine> pending = new ArrayList<>();
        for (GoogleEnhancer.BatchLine line : lines) {
            if (line == null || isBlank(line.text)) continue;
            result.requestedCount++;
            // The per-line cache is deliberately the same one the Google path reads: its key is
            // track, source, target, and text, and it holds a translation of that text either way.
            // Sharing it means switching backends - or switching back - reuses lines already paid
            // for instead of asking again for the same sentence. It is also read before anything
            // else: a fully cached batch costs no request, no credentials check, and no failure.
            String cached = LyricCaches.getProcessingValue(context, processingVersion,
                    LyricCaches.translationKey(trackId, sourceLang, target, line.text));
            if (!isBlank(cached) && GoogleEnhancer.shouldDisplayTranslation(line.text, cached)) {
                result.translations.put(line.index, cached);
                result.cachedIndices.add(line.index);
            } else {
                pending.add(line);
            }
        }
        if (pending.isEmpty()) return result;

        if (credentials == null || !credentials.configured()) {
            // Nothing is built and nothing is sent. The official API cannot answer an unsigned
            // request at all, so a request here would cost a round trip to learn what is already
            // known, and the lane is handed the one reason that names what the owner has to do.
            result.failureReason = REASON_UNCONFIGURED;
            logFailure("", "", pending.size(), result, null);
            return result;
        }

        String from = baiduLanguage(LyricCaches.sourceLanguageForCache(sourceLang));
        String to = baiduLanguage(target);
        Map<String, String> cacheWrites = new LinkedHashMap<>();
        for (List<WireLine> chunk : apiChunks(wireLines(pending))) {
            if (chunk.isEmpty()) continue;
            HttpResult response = sendRequest(
                    taggedRequest(GoogleEnhancer.batchQuery(wireBatch(chunk)), from, to, credentials, cancelTag),
                    http);
            result.networkAttempts += response.attempts;
            result.httpStatus = response.status;
            if (isBlank(response.body)) {
                // No answer at all. The remaining chunks would go out over the same dead socket,
                // and the lane's retry pass re-asks for these lines in smaller batches anyway.
                if (isBlank(result.failureReason)) {
                    result.failureReason = isBlank(response.failureReason)
                            ? "empty_body" : response.failureReason;
                }
                logFailure(from, to, chunk.size(), result, null);
                break;
            }

            Parsed parsed = parseBatchTranslation(response.body, chunk.size());
            if (isRateLimited(parsed.code)) {
                // The endpoint reports its frequency limit inside a 200. Reporting that as 429 is
                // what makes the lane stop the rest of the pass, exactly as it does for Google's
                // 429, instead of asking once per remaining chunk and keeping the limit in place.
                result.httpStatus = 429;
                result.failureReason = errorReason(parsed);
                noteFrequencyLimited(clock.getAsLong(), 0L);
                logFailure(from, to, chunk.size(), result, parsed);
                break;
            }
            if (parsed.translations.isEmpty()) {
                result.failureReason = parsed.code != 0 ? errorReason(parsed) : "parse_empty";
                if (isPermanent(parsed.code)) {
                    // Every later chunk is signed the same way and refused the same way, so the
                    // pass ends here and the lane must not re-ask either.
                    result.permanentFailure = true;
                    logFailure(from, to, chunk.size(), result, parsed);
                    break;
                }
                // A transient refusal (a timeout on a large query, a system error) says nothing
                // about the next chunk, which is a different query.
                logFailure(from, to, chunk.size(), result, parsed);
                continue;
            }

            noteSuccess();
            for (int i = 0; i < chunk.size(); i++) {
                WireLine line = chunk.get(i);
                String translated = parsed.translations.get(i);
                if (isBlank(translated)) continue;
                translated = translated.trim();
                if (!GoogleEnhancer.shouldDisplayTranslation(line.text, translated)) continue;
                // Every occurrence of this text gets the answer. The per-line cache is keyed by
                // text, so one request line can only ever produce one translation for it, and a
                // repeated chorus line is the row that used to come back empty while its distinct
                // neighbours were translated.
                for (int index : line.indices) {
                    result.translations.put(index, translated);
                    result.networkTranslatedCount++;
                }
                cacheWrites.put(
                        LyricCaches.translationKey(trackId, sourceLang, target, line.text), translated);
            }
            if (response.cancelled) {
                // The run that asked was retired. Its answer is still cached - that is what the
                // cache write above is for - but no further chunk goes out on its behalf.
                result.failureReason = "cancelled";
                break;
            }
        }
        if (result.networkTranslatedCount > 0
                && result.networkTranslatedCount < pending.size() && isBlank(result.failureReason)) {
            result.failureReason = "partial_parse";
        }
        LyricCaches.putProcessingValues(context, processingVersion, cacheWrites);
        return result;
    }

    /**
     * Cancels the calls carrying {@code cancelTag} that have not been sent yet, on the same terms
     * as the Google path: a call already on the wire is left to finish, because its answer is what
     * writes the per-line cache the run that replaced this one will read.
     */
    public static int cancelTagged(OkHttpClient http, String cancelTag) {
        if (http == null || cancelTag == null || cancelTag.isEmpty()) return 0;
        int cancelled = 0;
        for (Call call : http.dispatcher().queuedCalls()) {
            if (cancelTag.equals(call.request().tag(String.class))) {
                call.cancel();
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * Display filter for a Baidu answer. The rules are about the text, not about who translated
     * it - an echo of the source line, or of its romanization, is not a translation whichever
     * endpoint sent it - so the Google path's filter is reused rather than re-derived, and both
     * backends agree on what is worth showing.
     */
    public static boolean shouldDisplayTranslation(String source, String translated) {
        return GoogleEnhancer.shouldDisplayTranslation(source, translated);
    }

    /**
     * The app's language code as Baidu spells it. Only the codes Baidu spells differently are
     * mapped; anything else is passed through unchanged, because the API's own {@code 58001} for a
     * pair it does not serve is a clearer answer than a guess made here - and the log line carries
     * the codes that were sent.
     */
    static String baiduLanguage(String code) {
        String value = safe(code).trim();
        if (value.isEmpty()) return "auto";
        switch (value.toLowerCase(Locale.ROOT)) {
            case "auto":
            case "unknown":
                return "auto";
            case "zh":
            case "zh-cn":
            case "zh-hans":
                return "zh";
            case "zh-tw":
            case "zh-hant":
                return "cht";
            case "ja":
                return "jp";
            case "ko":
                return "kor";
            case "fr":
                return "fra";
            case "es":
                return "spa";
            case "sv":
                return "swe";
            case "da":
                return "dan";
            case "fi":
                return "fin";
            case "sk":
                return "slo";
            case "ro":
                return "rom";
            case "uk":
                return "ukr";
            case "vi":
                return "vie";
            case "ar":
                return "ara";
            default:
                return value;
        }
    }

    /**
     * The request this backend sends: a form POST to the official endpoint carrying the six
     * documented parameters. Package-visible so a JVM test can assert the exact wire shape, the
     * signature included, without a network.
     *
     * <p>No browser headers: the page's {@code Referer}, {@code Origin} and {@code X-Requested-With}
     * were what the retired page endpoint wanted, and none of them mean anything to a credentialled
     * API call. The content type is the one Baidu documents for POST
     * ({@code application/x-www-form-urlencoded}), and OkHttp sets it from the form body.
     */
    static Request taggedRequest(String query, String from, String to, Credentials credentials,
                                 String cancelTag) {
        String appId = credentials == null ? "" : credentials.appId;
        String secret = credentials == null ? "" : credentials.secret;
        String salt = freshSalt();
        RequestBody body = new FormBody.Builder()
                .add("q", safe(query))
                .add("from", from)
                .add("to", to)
                .add("appid", appId)
                .add("salt", salt)
                .add("sign", sign(appId, safe(query), salt, secret))
                .build();
        return new Request.Builder()
                .url(ENDPOINT)
                .post(body)
                .header("Accept", "application/json")
                .tag(String.class, cancelTag == null ? "" : cancelTag)
                .build();
    }

    /**
     * The documented signature: {@code MD5(appid + q + salt + secret)}, lowercase hex.
     *
     * <p>MD5 is not a choice made here - it is the algorithm the API defines, and it is a request
     * checksum, not a password hash: the wire carries the secret's digest, never the secret.
     * Verified against Baidu's own worked example, whose {@code appid=2015063000000001},
     * {@code q=apple}, {@code salt=1435660288}, secret {@code 12345678} is documented to produce
     * {@code f89f9594663708c1605f3d736d01d2d4} and does.
     */
    static String sign(String appId, String query, String salt, String secret) {
        return md5Hex(safe(appId) + safe(query) + safe(salt) + safe(secret));
    }

    static String md5Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // MD5 is required by the platform; a JVM without it cannot sign anything.
            return "";
        }
    }

    /** A fresh salt per request: a timestamp plus a process counter, both hex. */
    static String freshSalt() {
        return Long.toHexString(System.currentTimeMillis())
                + Long.toHexString(SALT_SEQUENCE.incrementAndGet())
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
    }

    /**
     * One line as it travels on the wire: a distinct text plus every batch line that carries it.
     *
     * <p>A song's chorus repeats, and two identical lines can only ever hold the same translation -
     * the per-line cache is keyed by text, so the endpoint is being asked the same question twice.
     * Collapsing them costs one request line instead of one per occurrence, and it removes the one
     * shape that loses a row: an answer carrying no destination for a line leaves every occurrence
     * of that text empty, which is how a repeated chorus line stayed in the source language while
     * its distinct neighbours were translated.
     */
    static final class WireLine {
        final String text;
        final List<Integer> indices;

        WireLine(String text, List<Integer> indices) {
            this.text = text;
            this.indices = indices;
        }
    }

    /**
     * The batch with repeated texts collapsed, in first-seen order. Every occurrence keeps its
     * original batch index in {@link WireLine#indices}, which is what the answer is fanned back to.
     */
    static List<WireLine> wireLines(List<GoogleEnhancer.BatchLine> lines) {
        Map<String, List<Integer>> indicesByText = new LinkedHashMap<>();
        if (lines != null) {
            for (GoogleEnhancer.BatchLine line : lines) {
                if (line == null || isBlank(line.text)) continue;
                List<Integer> indices = indicesByText.get(line.text);
                if (indices == null) {
                    indices = new ArrayList<>();
                    indicesByText.put(line.text, indices);
                }
                indices.add(line.index);
            }
        }
        List<WireLine> wire = new ArrayList<>(indicesByText.size());
        for (Map.Entry<String, List<Integer>> entry : indicesByText.entrySet()) {
            wire.add(new WireLine(entry.getKey(), entry.getValue()));
        }
        return wire;
    }

    /** The marked batch the wire lines are sent as, one marker per distinct text. */
    static List<GoogleEnhancer.BatchLine> wireBatch(List<WireLine> lines) {
        List<GoogleEnhancer.BatchLine> batch = new ArrayList<>();
        if (lines == null) return batch;
        for (WireLine line : lines) {
            batch.add(new GoogleEnhancer.BatchLine(batch.size(), line.text));
        }
        return batch;
    }

    /**
     * The distinct batch cut into requests the API will accept: each one's marked query is at most
     * {@link #QUERY_MAX_BYTES} bytes and at most {@link #CHUNK_MAX_LINES} lines.
     *
     * <p>Byte-accurate on purpose. The lane's own 4500-character batch limit is 13500 UTF-8 bytes
     * of Chinese, and a request over the API's cap is refused outright - the old code discovered
     * that by sending it, retrying smaller, and paying a round trip per failed attempt.
     *
     * <p>A single line longer than the cap is sent alone rather than split: splitting a lyric line
     * would break the marker-to-row mapping the answer is put back through, and one long line is
     * rare enough that the API's own refusal for it is the honest outcome.
     */
    static List<List<WireLine>> apiChunks(List<WireLine> lines) {
        List<List<WireLine>> chunks = new ArrayList<>();
        if (lines == null || lines.isEmpty()) return chunks;
        List<WireLine> current = new ArrayList<>();
        int bytes = 0;
        for (WireLine line : lines) {
            if (line == null || isBlank(line.text)) continue;
            int lineBytes = MARKER_BYTES + AiText.utf8Bytes(line.text)
                    + (current.isEmpty() ? 0 : JOIN_BYTES);
            if (!current.isEmpty() && (current.size() >= CHUNK_MAX_LINES
                    || bytes + lineBytes > QUERY_MAX_BYTES)) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0;
                lineBytes = MARKER_BYTES + AiText.utf8Bytes(line.text);
            }
            current.add(line);
            bytes += lineBytes;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    /**
     * One response, decoded: the API's own status fields plus the destinations by batch position.
     *
     * <p>{@code code} and {@code message} are kept as the service sent them, so the failure log can
     * name the refusal instead of only its consequence. {@code hasCode} separates "the field said
     * 0" from "there was no such field", which is the difference between a shape change and a
     * refusal when a log is all there is to go on.
     */
    static final class Parsed {
        boolean hasCode;
        int code;
        String message = "";
        boolean malformed;
        int destinations;
        final Map<Integer, String> translations = new LinkedHashMap<>();
    }

    /**
     * Destinations by batch position.
     *
     * <p>Marked first: the batch went out as {@code [[SPX_000]] line} markers, and a marker is the
     * only thing that survives the service joining or re-flowing lines. When no marker comes back,
     * the destinations are mapped by position instead, which is exact for the one-line rescue pass
     * and for the documented shape - the API answers {@code trans_result} with one entry per line
     * of {@code q}, in order. Any other shape is reported as empty rather than guessed: one row
     * showing another row's translation is worse than asking again.
     */
    static Parsed parseBatchTranslation(String body, int lineCount) {
        Parsed parsed = new Parsed();
        if (isBlank(body)) {
            parsed.malformed = true;
            return parsed;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Throwable malformed) {
            parsed.malformed = true;
            return parsed;
        }
        if (root == null || !root.isJsonObject()) {
            parsed.malformed = true;
            return parsed;
        }

        JsonObject object = root.getAsJsonObject();
        JsonElement code = object.get("error_code");
        if (code == null || code.isJsonNull()) code = object.get("errno");
        parsed.hasCode = code != null && !code.isJsonNull();
        parsed.code = intValue(code);
        JsonElement message = object.get("error_msg");
        if (message == null || message.isJsonNull()) message = object.get("errmsg");
        parsed.message = stringValue(message);
        if (parsed.code != 0) return parsed;

        List<String> destinations = destinationsOf(object);
        parsed.destinations = destinations.size();
        if (destinations.isEmpty()) return parsed;

        StringBuilder joined = new StringBuilder();
        for (String destination : destinations) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(destination);
        }
        for (Map.Entry<Integer, String> entry
                : GoogleEnhancer.splitByMarkers(joined.toString()).entrySet()) {
            String value = GoogleEnhancer.stripMarkerEcho(entry.getValue()).trim();
            if (!isBlank(value)) parsed.translations.put(entry.getKey(), value);
        }
        if (!parsed.translations.isEmpty()) return parsed;

        List<String> positional = new ArrayList<>();
        for (String destination : destinations) {
            positional.addAll(Arrays.asList(destination.split("\r?\n")));
        }
        if (lineCount <= 0 || positional.size() != lineCount) return parsed;
        for (int i = 0; i < positional.size(); i++) {
            String value = GoogleEnhancer.stripMarkerEcho(positional.get(i)).trim();
            if (!isBlank(value)) parsed.translations.put(i, value);
        }
        return parsed;
    }

    /**
     * Destination texts in the order the response carries them. {@code trans_result[].dst} is the
     * documented shape; {@code data[].dst} is what the retired page endpoint answered, and a single
     * flattened {@code dst} is tolerated as well, because a parser's job is to read what arrives.
     * An unrecognized shape yields none, which the caller reports instead of throwing.
     */
    private static List<String> destinationsOf(JsonObject root) {
        List<String> out = new ArrayList<>();
        collectDestinations(root.get("trans_result"), out);
        if (out.isEmpty()) collectDestinations(root.get("data"), out);
        if (out.isEmpty()) {
            JsonElement nested = root.get("result");
            if (nested != null && nested.isJsonObject()) {
                collectDestinations(nested.getAsJsonObject().get("trans_result"), out);
                if (out.isEmpty()) collectDestinations(nested.getAsJsonObject().get("data"), out);
            }
        }
        if (out.isEmpty()) {
            String direct = stringValue(root.get("dst"));
            if (!direct.isEmpty()) out.add(direct);
        }
        return out;
    }

    private static void collectDestinations(JsonElement element, List<String> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) collectDestinations(item, out);
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String dst = stringValue(object.get("dst"));
            if (!dst.isEmpty()) {
                out.add(dst);
                return;
            }
            collectDestinations(object.get("data"), out);
            return;
        }
        String value = stringValue(element);
        if (!value.isEmpty()) out.add(value);
    }

    /** True for the two frequency-limit codes, which are answered as a 429. */
    static boolean isRateLimited(int code) {
        return code == ERROR_FREQUENCY_LIMITED || code == ERROR_LONG_QUERY_FREQUENCY_LIMITED;
    }

    /**
     * True for a refusal that no retry can fix: a wrong app id or secret, a bad signature, a
     * missing parameter, an empty balance, a blocked client address, an unsupported direction, a
     * closed service, an uncertified account. Every one of them needs the owner or the account to
     * change before the same request can succeed, so re-sending it is pure latency - and on the
     * lane it also ends the retry and rescue passes for the rest of the run.
     *
     * <p>A code this build has never seen is not called permanent: an unknown refusal is at least
     * as likely to be transient as it is to be final, and the cost of being wrong is one retry.
     */
    static boolean isPermanent(int code) {
        return code == ERROR_UNAUTHORIZED_USER
                || code == ERROR_MISSING_PARAMETER
                || code == ERROR_INVALID_SIGN
                || code == ERROR_INSUFFICIENT_BALANCE
                || code == ERROR_ILLEGAL_IP
                || code == ERROR_UNSUPPORTED_DIRECTION
                || code == ERROR_SERVICE_CLOSED
                || code == ERROR_NOT_CERTIFIED;
    }

    private static String errorReason(Parsed parsed) {
        return parsed.hasCode ? "baidu_error_" + parsed.code : "baidu_error_missing";
    }

    /**
     * One line per failed request, carrying the only evidence that separates the failure modes:
     * the wire status, Baidu's error code and message, how many destinations came back, the
     * language pair that was asked for, and how long a cooldown still has to run. No lyric text, no
     * query, and no signature travel with it.
     *
     * <p>{@code reason=cooldown} is only ever produced after the credentials check passed, so a
     * line carrying it proves the credentials are stored; {@code reason=baidu_unconfigured} with
     * {@code http=0} is the other state, and the two are never the same line.
     */
    private static void logFailure(String from, String to, int lineCount,
                                   GoogleEnhancer.BatchResult result, Parsed parsed) {
        XpLog.log(TAG + " baidu failed endpoint=" + ENDPOINT_PATH + " from=" + from + " to=" + to
                + " lines=" + lineCount
                + " http=" + result.httpStatus
                + " attempts=" + result.networkAttempts
                + " code=" + codeText(parsed)
                + " destinations=" + (parsed == null ? 0 : parsed.destinations)
                + " reason=" + safe(result.failureReason)
                + " cooldownMs=" + cooldownRemainingMs(clock.getAsLong())
                + " msg=" + (parsed == null ? "" : safe(parsed.message)));
    }

    private static String codeText(Parsed parsed) {
        if (parsed == null) return "none";
        return parsed.hasCode ? String.valueOf(parsed.code) : "missing";
    }

    private static int intValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return 0;
        try {
            // The API sends error_code as a JSON string ("54003") and the field is documented as a
            // number; Gson parses either, so both spellings of the same refusal are read the same.
            return element.getAsInt();
        } catch (Throwable notANumber) {
            return 0;
        }
    }

    private static String stringValue(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            return element.getAsString().trim();
        } catch (Throwable notAString) {
            return "";
        }
    }

    private static HttpResult sendRequest(Request request, OkHttpClient http) {
        HttpResult result = new HttpResult();
        if (http == null || request == null) {
            result.failureReason = "client_unavailable";
            return result;
        }
        String lane = GoogleEnhancer.laneOf(request);
        for (int attempt = 0; attempt <= REQUEST_RETRIES; attempt++) {
            if (cooldownRemainingMs(clock.getAsLong()) > 0L) {
                // Frequency limited a moment ago: answer the way the endpoint would, without
                // asking, so a queued batch or a retry pass costs nothing.
                result.status = 429;
                result.failureReason = "cooldown";
                return result;
            }
            throttleRequest(lane);
            result.attempts++;
            Call call = http.newCall(request);
            try (Response response = call.execute()) {
                result.status = response.code();
                if (response.isSuccessful() && response.body() != null) {
                    result.body = response.body().string();
                    if (isBlank(result.body)) result.failureReason = "empty_body";
                    return result;
                }
                result.failureReason = "http_" + response.code();
                if (response.code() == 429) {
                    // A second attempt a moment later would only extend the limit.
                    noteFrequencyLimited(clock.getAsLong(),
                            GoogleCooldown.parseRetryAfterMs(response.header("Retry-After")));
                    return result;
                }
                if (response.code() < 500) return result;
            } catch (IOException failure) {
                result.failureReason = failure.getClass().getSimpleName();
                // Cancelled on purpose: its run was retired, and sending it again is exactly the
                // request that cancel was meant to save.
                if (call.isCanceled()) {
                    result.failureReason = "cancelled";
                    result.cancelled = true;
                    return result;
                }
            }
            if (attempt < REQUEST_RETRIES) quietSleep(REQUEST_RETRY_DELAY_MS);
        }
        return result;
    }

    private static long cooldownRemainingMs(long nowMs) {
        return Math.max(0L, LIMITED_UNTIL_MS.get() - nowMs);
    }

    /**
     * How long a frequency limit holds requests back.
     *
     * <p>Long enough that the retry is not what earned the next limit, short enough that a false
     * positive cannot kill translation for the session: Baidu's own table defines {@code 54003}
     * and {@code 54005} as "too frequent", not "quota exhausted", and the published window is one
     * second. A documented {@code Retry-After} is honoured, capped, because the endpoint knows its
     * own window better than this build does.
     */
    static long limitBackoffMs(long retryAfterMs, long strikes) {
        if (retryAfterMs > 0L) return Math.min(LIMIT_MAX_MS, retryAfterMs);
        return Math.min(LIMIT_MAX_MS, LIMIT_BASE_MS << Math.min(Math.max(0L, strikes), 10L));
    }

    private static void noteFrequencyLimited(long nowMs, long retryAfterMs) {
        long lastStrike = LIMIT_LAST_STRIKE_MS.getAndSet(nowMs);
        if (lastStrike == Long.MIN_VALUE || nowMs < lastStrike
                || nowMs - lastStrike > LIMIT_STRIKE_DECAY_MS) {
            // The account has been quiet since the last refusal, so this one is a fresh first
            // strike: carrying the old ladder forward is what turned one burst into a session-long
            // blackout, because no request is sent while a cooldown is in force and so no success
            // could ever reset it.
            LIMIT_STRIKES.set(0L);
        }
        long backoff = limitBackoffMs(retryAfterMs, LIMIT_STRIKES.get());
        LIMIT_STRIKES.incrementAndGet();
        LIMITED_UNTIL_MS.accumulateAndGet(nowMs + backoff, Math::max);
    }

    private static void noteSuccess() {
        clearLimitState();
    }

    /**
     * Clears the account-wide limit state. A request that came back proves the limit is over, so
     * this is what a success does; it is package-visible because the state is process-wide and
     * static, so a test that earned a cooldown would otherwise hold back every later test.
     */
    static void clearLimitState() {
        LIMIT_STRIKES.set(0L);
        LIMITED_UNTIL_MS.set(0L);
        LIMIT_LAST_STRIKE_MS.set(Long.MIN_VALUE);
    }

    private static void throttleRequest(String lane) {
        long[] last = LANE_THROTTLES.computeIfAbsent(lane == null ? "default" : lane,
                key -> new long[] {0L});
        synchronized (last) {
            long nowMs = clock.getAsLong();
            long waitMs = last[0] + REQUEST_MIN_INTERVAL_MS - nowMs;
            if (waitMs > 0L) quietSleep(waitMs);
            last[0] = clock.getAsLong();
        }
    }

    private static void quietSleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static final class HttpResult {
        String body = "";
        int attempts;
        int status;
        String failureReason = "";
        /** True when the call was cancelled rather than answered. */
        boolean cancelled;
    }
}
