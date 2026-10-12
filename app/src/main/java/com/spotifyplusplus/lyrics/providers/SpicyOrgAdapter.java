package com.spotifyplusplus.lyrics.providers;

import android.content.Context;
import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricUtils;
import com.spotifyplusplus.lyrics.catalog.CatalogAdapters;
import com.spotifyplusplus.lyrics.catalog.CatalogSource;
import com.spotifyplusplus.xposed.XpLog;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** One personal client key, one v1 request, and one catalog outcome. */
final class SpicyOrgAdapter {
    private static long cooldownUntilMs;
    private static long cooldownEpoch = -1;
    private final OkHttpClient http;
    private final LyricsRepository.Parser parser;

    SpicyOrgAdapter(OkHttpClient http, LyricsRepository.Parser parser) {
        // Never forward a credential to redirects or retry a limited request automatically.
        this.http = http.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build();
        this.parser = parser;
    }

    void fetch(Context context, SpotifyTrack track, LyricsRepository.ResultCallback callback) {
        fetch(context, track, callback, false);
    }

    void fetchForAccessCheck(Context context, SpotifyTrack track,
                             LyricsRepository.ResultCallback callback) {
        fetch(context, track, callback, true);
    }

    private void fetch(Context context, SpotifyTrack track,
                       LyricsRepository.ResultCallback callback, boolean explicitRecovery) {
        final String key;
        final long epoch;
        final SpicyOrgAccessState.RequestTicket ticket;
        synchronized (SpicyOrgKeyStore.class) {
            key = SpicyOrgKeyStore.load(context);
            epoch = SpicyOrgKeyStore.epoch(context);
            ticket = SpicyOrgAccessState.beginRequest(context, explicitRecovery);
        }
        if (ticket == null) {
            fail(context, track, callback, SpicyOrgProtocol.error(403));
            return;
        }
        String trackId = LyricUtils.trackIdFromUri(track == null ? "" : track.uri);
        Request request;
        try {
            request = SpicyOrgProtocol.request(trackId, key);
        } catch (IllegalArgumentException error) {
            fail(context, track, callback, error.getMessage());
            return;
        }
        // One line per attempt, before it goes out: which track, whether a key is stored (never the
        // key), which path issued the request, and whether a recorded rate limit will refuse it.
        // Without this a source that never reaches the wire is indistinguishable from one that
        // answered and lost, which is what made this take guesswork.
        boolean cooling = cooldownActive(epoch, System.currentTimeMillis());
        XpLog.log("[SpotifyPlusSpicyOrg] request track=" + (trackId.isEmpty() ? "none" : trackId)
                + " key=" + (key.isEmpty() ? "missing" : "present")
                + " path=" + (ticket.recovery ? "recovery" : "automatic")
                + " epoch=" + epoch
                + " terminated=" + SpicyOrgAccessState.isTerminated(context)
                + " cooldown=" + (cooling ? "active" : "none"));
        if (cooling) {
            fail(context, track, callback, "Spicy Lyrics rate limited; retry later");
            return;
        }
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                fail(context, track, callback, "Spicy Lyrics network unavailable");
            }

            @Override public void onResponse(Call call, Response response) {
                LyricsDocument document;
                boolean stored;
                try (Response ignored = response) {
                    long seconds = SpicyOrgProtocol.seconds(response.header("Retry-After"));
                    if ("0".equals(response.header("RateLimit-Remaining"))) {
                        seconds = Math.max(seconds,
                                SpicyOrgProtocol.seconds(response.header("RateLimit-Reset")));
                    }
                    if (response.code() == 429 || response.code() == 503) {
                        seconds = Math.max(seconds, 50);
                    }
                    if (seconds > 0) {
                        recordCooldown(context, epoch, seconds, System.currentTimeMillis());
                    }
                    String raw = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful() || response.body() == null) {
                        SpicyOrgAccessState.terminate(context, ticket,
                                SpicyOrgProtocol.terminationCode(response.code(), raw));
                        fail(context, track, callback, SpicyOrgProtocol.error(response.code()));
                        return;
                    }
                    SpicyOrgProtocol.body(raw, trackId);
                    document = parser.parseSpicyOrgLyrics(context, track, raw);
                    if (document == null || document.lines.isEmpty()
                            || LyricQualityRanker.score(document) == LyricQualityRanker.REJECT) {
                        throw new IllegalArgumentException("Invalid lyrics");
                    }
                    // A rotation retires an old key's result before it can enter the cache.
                    stored = false;
                    synchronized (SpicyOrgKeyStore.class) {
                        if (!SpicyOrgAccessState.acceptValidatedResponse(context, ticket)) {
                            fail(context, track, callback, "Spicy Lyrics key changed; retry");
                            return;
                        }
                        document.selectedSource = "Spicy Lyrics";
                        document.selectionMode = "strict";
                        document.selectionOverride = "Spicy Lyrics";
                        stored = CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.SPICY_ORG,
                                track, document, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID,
                                trackId, raw, 1);
                    }
                    // A document the catalogue refused is the third way this source can produce
                    // nothing, and the only one with no error to report: say so, or it reads as a
                    // stored result that simply never wins.
                    XpLog.log("[SpotifyPlusSpicyOrg] outcome track=" + trackId + " result="
                            + (stored ? "stored" : "refused-by-catalog")
                            + " lines=" + document.lines.size() + " type=" + document.type);
                } catch (Exception error) {
                    fail(context, track, callback, "Spicy Lyrics invalid response");
                    return;
                }
                callback.onSuccess(document);
            }
        });
    }

    static boolean cooldownActive(long epoch, long nowMs) {
        synchronized (SpicyOrgKeyStore.class) {
            return cooldownEpoch == epoch && nowMs < cooldownUntilMs;
        }
    }

    static void recordCooldown(Context context, long epoch, long seconds, long nowMs) {
        // Share the rotation lock so a retired response cannot replace the current key's deadline.
        synchronized (SpicyOrgKeyStore.class) {
            if (SpicyOrgKeyStore.epoch(context) != epoch) return;
            long deadline = nowMs + seconds * 1000;
            cooldownUntilMs = cooldownEpoch == epoch ? Math.max(cooldownUntilMs, deadline) : deadline;
            cooldownEpoch = epoch;
        }
    }

    private static void fail(Context context, SpotifyTrack track,
                             LyricsRepository.ResultCallback callback, String error) {
        // The outcome that keeps this source out of the seat, named in the log. The callback's
        // message only reaches a surface the owner may not be looking at.
        XpLog.log("[SpotifyPlusSpicyOrg] outcome track="
                + (track == null ? "none" : LyricUtils.trackIdFromUri(track.uri))
                + " result=none reason=" + error);
        CatalogAdapters.recordError(context, CatalogSource.SourceId.SPICY_ORG, track, error);
        callback.onError(error);
    }
}
