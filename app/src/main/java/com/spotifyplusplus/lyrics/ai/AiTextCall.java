package com.spotifyplusplus.lyrics.ai;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One synchronous text call against the model the owner configured, and nothing else.
 *
 * <p>Shared by the two places that ask a model a question which is not a lyric document: widening
 * a search ({@link AiTrackAliases}) and choosing between search hits
 * ({@link AiLyricCandidateArbiter}). Both need the same credential handling, the same deadline and
 * the same reply unwrapping.
 *
 * <p>Deliberately not a {@code LayerKind}: the layer contract is a lyric document in and a lyric
 * document out, with a versioned system prompt and a validator that both assume it. These are
 * different questions with different answer shapes, and bending the lyric contract to carry them
 * would put a schema violation in front of every translation to serve a lookup.
 *
 * <p>Every failure returns an empty string. A question that cannot be asked is a question that was
 * not asked; no caller may turn that into a user-visible error, because none of this is on the
 * path of a track that already resolved.
 */
public final class AiTextCall {
    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";
    /** Small answers only: a variant list or a single index. Anything larger is a runaway. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** Flat ceiling: this is a lookup, and the search waiting on it is waiting on nothing else. */
    private static final long TIMEOUT_MS = 15_000L;

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SpicyAiTextTimer");
                thread.setDaemon(true);
                return thread;
            });

    private AiTextCall() {
    }

    /** True when a model call could be made right now. Never throws, never blocks. */
    public static boolean available(Context context) {
        if (context == null) return false;
        try {
            return new AiSettings(context).canRequest();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One clause naming whether a model was in a position to be asked.
     *
     * <p>Appended to a provider's "nothing here" message, because that message is otherwise
     * identical whether a model was asked and answered "none of these" or was never asked at all -
     * and those two need opposite responses from the reader. One means the track really is not in
     * this catalogue under any spelling; the other means a setting is switched off.
     */
    public static String note(Context context) {
        return available(context) ? " (AI checked, none matched)" : " (AI not enabled)";
    }

    /** The model's reply text, or empty when it cannot be asked or does not answer. */
    public static String ask(Context context, String systemPrompt, String userPrompt,
                             int maxOutputTokens) {
        if (context == null) return "";
        AiSettings settings;
        try {
            settings = new AiSettings(context);
        } catch (Throwable t) {
            return "";
        }
        if (!settings.canRequest()) return "";
        String secret = settings.credentials() == null ? ""
                : nz(settings.credentials().load(settings.credentialScope()));
        if (secret.isEmpty()) return "";
        String model = nz(settings.modelName());
        if (model.isEmpty()) return "";

        boolean openAiWire = settings.usesOpenAiWire();
        String url;
        Map<String, String> headers = new LinkedHashMap<>();
        String body;
        if (openAiWire) {
            String base = nz(settings.endpoint());
            if (base.isEmpty()) return "";
            url = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
            headers.put("Authorization", "Bearer " + secret);
            headers.put("Content-Type", "application/json");
            body = openAiBody(model, systemPrompt, userPrompt, maxOutputTokens);
        } else {
            url = GEMINI_BASE + "/models/" + model + ":generateContent";
            headers.put("x-goog-api-key", secret);
            headers.put("Content-Type", "application/json");
            body = geminiBody(systemPrompt, userPrompt, maxOutputTokens);
        }

        AiSignal signal = new AiSignal();
        ScheduledFuture<?> deadline = TIMER.schedule(
                () -> signal.abort("text-timeout"), TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            AiHttp.Result result = AiHttp.postJson(url, headers, body, signal, MAX_RESPONSE_BYTES);
            if (result == null || result.status < 200 || result.status >= 300) return "";
            return openAiWire ? openAiText(result.body) : geminiText(result.body);
        } catch (Throwable t) {
            return "";
        } finally {
            deadline.cancel(false);
        }
    }

    static int clampTokens(int requested) {
        return requested <= 0 ? 256 : Math.min(requested, 4096);
    }

    private static String openAiBody(String model, String systemPrompt, String userPrompt,
                                     int maxOutputTokens) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", nz(systemPrompt));
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", nz(userPrompt));
        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", 0);
        body.addProperty("max_tokens", clampTokens(maxOutputTokens));
        return body.toString();
    }

    private static String geminiBody(String systemPrompt, String userPrompt, int maxOutputTokens) {
        JsonObject system = new JsonObject();
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", nz(systemPrompt));
        systemParts.add(systemPart);
        system.add("parts", systemParts);

        JsonObject part = new JsonObject();
        part.addProperty("text", nz(userPrompt));
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", 0);
        generation.addProperty("maxOutputTokens", clampTokens(maxOutputTokens));

        JsonObject body = new JsonObject();
        body.add("systemInstruction", system);
        body.add("contents", contents);
        body.add("generationConfig", generation);
        return body.toString();
    }

    /** The assistant message out of an OpenAI-compatible reply, or empty. */
    public static String openAiText(String body) {
        try {
            JsonObject root = JsonParser.parseString(nz(body)).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return "";
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            return message == null || !message.has("content") ? "" : message.get("content").getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The concatenated parts out of a Gemini reply, or empty. */
    public static String geminiText(String body) {
        try {
            JsonObject root = JsonParser.parseString(nz(body)).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) return "";
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            JsonArray parts = content == null ? null : content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) return "";
            StringBuilder out = new StringBuilder();
            for (JsonElement part : parts) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    out.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String nz(String value) {
        return value == null ? "" : value;
    }
}
