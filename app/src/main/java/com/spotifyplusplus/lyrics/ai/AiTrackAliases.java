package com.spotifyplusplus.lyrics.ai;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Asks the configured model what else one recording is called, so a title search can use it.
 *
 * <p>This is the second half of the search widening: {@code SearchQueryVariants} can only respell
 * the title Spotify reported, which fixes a Traditional title against a Simplified index and
 * nothing else. A model can do the part no character table can — recognise that the release title
 * is a transliteration, or that the song is catalogued abroad under an English name — and answer
 * with the names worth asking for.
 *
 * <p><b>It runs only after every local spelling has already missed.</b> Nothing here is on the
 * path of a track that resolves: the cache is read for free, and a miss is what schedules the
 * request. A model call is therefore paid for exactly once per recording that Spotify could not
 * find lyrics for, which is also the only case where it can change the outcome.
 *
 * <p>Deliberately not a {@code LayerKind}: the layer contract is a lyric document in and a lyric
 * document out, with a versioned system prompt and a validator that both assume it. Widening a
 * search is a different question with a different answer shape, and bending the lyric contract to
 * carry it would put a schema violation in front of every translation to serve a lookup.
 *
 * <p>Android-free apart from the settings read: safe for unit tests.
 */
public final class AiTrackAliases {
    private static final String TAG = "SpicyAiAliases";
    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";

    /** Flat ceiling: this is a lookup, and a search waiting on it is waiting on nothing else. */
    private static final long TIMEOUT_MS = 15_000L;
    /** Enough to cover the scripts that matter; every extra name is another provider request. */
    private static final int MAX_ALIASES = 4;
    /** A name longer than this is a sentence the model wrote instead of a title. */
    private static final int MAX_ALIAS_LENGTH = 120;
    private static final int MAX_OUTPUT_TOKENS = 400;
    /** Four short names and their JSON punctuation: anything larger is a model that ignored it. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** Alias sets are tiny and read on the search path, so they are never evicted. */
    private static final int CACHE_LIMIT = 256;

    private static final Map<String, List<String>> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            daemon("SpicyAiAliases"));
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(daemon("SpicyAiAliasesTimer"));

    /**
     * Fired on the worker thread when a recording's aliases land.
     *
     * <p>The point of the callback is the whole feature: the visit that scheduled the lookup has
     * already finished and already shown "no lyrics found" with the local spellings only, so
     * without a retry the aliases would not be used until the next time the track is opened.
     */
    public interface Listener {
        void onAliases(String title, String artist);
    }

    private static final List<Listener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private AiTrackAliases() {
    }

    private static ThreadFactory daemon(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Aliases already resolved for this recording, or an empty list. Never blocks, never calls. */
    public static List<String> cached(String title, String artist) {
        List<String> hit = CACHE.get(key(title, artist));
        return hit == null ? Collections.<String>emptyList() : hit;
    }

    /**
     * Resolves aliases in the background when they are not known yet.
     *
     * <p>One request per recording: a second lookup for the same title while the first is still in
     * flight is dropped rather than queued, so a provider walk that asks twice does not pay twice.
     */
    public static void prefetch(final Context context, final String title, final String artist) {
        final String cacheKey = key(title, artist);
        if (cacheKey.isEmpty() || CACHE.containsKey(cacheKey)) return;
        if (!IN_FLIGHT.add(cacheKey)) return;
        WORKER.execute(() -> {
            try {
                List<String> aliases = request(context, title, artist);
                CACHE.put(cacheKey, aliases);
                if (!aliases.isEmpty()) {
                    for (Listener listener : LISTENERS) {
                        try {
                            listener.onAliases(title, artist);
                        } catch (Throwable ignored) {
                            // A surface that cannot handle the news must not break the cache.
                        }
                    }
                }
            } catch (Throwable t) {
                // A lookup that fails is a lookup that did not happen: caching the empty answer
                // would make a transient 429 permanent for the rest of the session.
                com.spotifyplusplus.Diagnostics.event(TAG,
                        "prefetch:" + t.getClass().getSimpleName());
            } finally {
                IN_FLIGHT.remove(cacheKey);
            }
        });
    }

    /** Drops every resolved alias set. Used by the tests and by a credential or model change. */
    public static void clear() {
        CACHE.clear();
        IN_FLIGHT.clear();
    }

    /** True when at least one recording has a resolved alias set. */
    public static boolean hasAny() {
        return !CACHE.isEmpty();
    }

    private static List<String> request(Context context, String title, String artist) {
        // Credential handling, protocol choice and the deadline all live in AiTextCall now, shared
        // with the candidate arbiter; this method only owns the question and the answer shape.
        return parseAliases(AiTextCall.ask(context, SYSTEM_PROMPT,
                userPrompt(title, artist), MAX_OUTPUT_TOKENS));
    }

    private static String userPrompt(String title, String artist) {
        return "Title: " + nz(title) + "\nArtist: " + nz(artist);
    }

    private static final String SYSTEM_PROMPT =
            "You are a music cataloguing assistant. The user gives one recording's title and "
                    + "artist. List the other names the SAME recording is catalogued under: its "
                    + "Simplified Chinese title, its Traditional Chinese title, its English title, "
                    + "and the title with version markers (remaster, live, remix, feat.) removed.\n"
                    + "Rules:\n"
                    + "- Only names genuinely used for this recording. Never invent a translation.\n"
                    + "- Never include the artist name in an entry.\n"
                    + "- If the title is already Latin script, do not translate it into Chinese.\n"
                    + "- Omit any name identical to the title you were given.\n"
                    + "- At most 4 entries, ordered most likely to be indexed first.\n"
                    + "Reply with ONLY a JSON array of strings, e.g. [\"name one\",\"name two\"]. "
                    + "No prose, no code fences.";

    static List<String> parseAliases(String text) {
        List<String> out = new ArrayList<>(MAX_ALIASES);
        String trimmed = stripFence(nz(text));
        if (trimmed.isEmpty()) return out;
        // A reply that opens a JSON array but does not close one is a broken JSON reply, not a
        // bulleted list: falling through to the line reader would turn "[" into a search query.
        boolean jsonIntent = trimmed.startsWith("[") || trimmed.startsWith("{");
        int open = trimmed.indexOf('[');
        int close = trimmed.lastIndexOf(']');
        if (open >= 0 && close > open) {
            try {
                JsonElement parsed = JsonParser.parseString(trimmed.substring(open, close + 1));
                if (parsed.isJsonArray()) {
                    for (JsonElement item : parsed.getAsJsonArray()) {
                        if (item.isJsonPrimitive()) {
                            offer(out, item.getAsString());
                        } else if (item.isJsonObject() && item.getAsJsonObject().has("name")) {
                            offer(out, item.getAsJsonObject().get("name").getAsString());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty() && !jsonIntent) {
            for (String line : trimmed.split("\\r?\\n")) {
                offer(out, line.replaceFirst("^[\\s\\-*•\\d.,)、)]+", "").trim());
            }
        }
        return out.size() <= MAX_ALIASES
                ? Collections.unmodifiableList(out)
                : Collections.unmodifiableList(new ArrayList<>(out.subList(0, MAX_ALIASES)));
    }

    private static String stripFence(String text) {
        String value = text.trim();
        if (!value.startsWith("```")) return value;
        int firstBreak = value.indexOf('\n');
        if (firstBreak < 0) return value;
        value = value.substring(firstBreak + 1);
        int fence = value.lastIndexOf("```");
        if (fence >= 0) value = value.substring(0, fence);
        return value.trim();
    }

    private static void offer(List<String> out, String value) {
        String name = nz(value).replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || name.length() > MAX_ALIAS_LENGTH) return;
        for (String existing : out) {
            if (existing.equalsIgnoreCase(name)) return;
        }
        out.add(name);
    }

    private static String key(String title, String artist) {
        String t = nz(title).toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        return t + "\u0000" + nz(artist).toLowerCase(Locale.ROOT);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
