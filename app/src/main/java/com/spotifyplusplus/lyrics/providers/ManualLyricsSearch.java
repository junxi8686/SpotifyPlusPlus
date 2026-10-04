package com.spotifyplusplus.lyrics.providers;

import com.spotifyplusplus.lyrics.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The raw result list a provider returns for a phrase the owner typed.
 *
 * <p>Nothing here scores, ranks, filters or asks a model. That is the point: every automatic path
 * in this package answers "is this the same recording?" with a threshold or a judgement, and both
 * can be wrong in the one case that matters - a release catalogued under a different name. This
 * class only reports what the provider said, so the owner can recognise their own song in a list.
 *
 * <p>Parsing is pure and Android-free, which is what makes it testable off-device.
 */
public final class ManualLyricsSearch {
    /** One provider result, as the provider named it. */
    public static final class Hit {
        public final String itemId;
        public final String title;
        public final String artists;
        public final long durationMs;

        public Hit(String itemId, String title, String artists, long durationMs) {
            this.itemId = itemId == null ? "" : itemId;
            this.title = title == null ? "" : title;
            this.artists = artists == null ? "" : artists;
            this.durationMs = Math.max(0L, durationMs);
        }

        /** What the chooser shows: the title, then who performs it. */
        public String label() {
            return artists.isEmpty() ? title : title + " — " + artists;
        }
    }

    /** A source that can be asked for a phrase. Only these appear in the chooser. */
    public enum Searchable {
        NETEASE("netease", "网易云音乐",
                "https://music.163.com/api/search/get?s=%s&type=1&offset=0&limit=30"),
        QQ("qq", "QQ 音乐", "https://u.y.qq.com/cgi-bin/musicu.fcg"),
        LRCLIB("lrclib", "LRCLIB", "https://lrclib.net/api/search?q=%s");

        public final String id;
        public final String zhName;
        public final String urlTemplate;

        Searchable(String id, String zhName, String urlTemplate) {
            this.id = id;
            this.zhName = zhName;
            this.urlTemplate = urlTemplate;
        }
    }

    private ManualLyricsSearch() {
    }

    /** Parses a NetEase {@code result.songs} array. */
    public static List<Hit> parseNetease(String body) {
        List<Hit> hits = new ArrayList<>();
        JsonArray songs = arrayAt(body, "result", "songs");
        if (songs == null) return hits;
        for (JsonElement element : songs) {
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            StringBuilder artists = new StringBuilder();
            JsonArray list = Json.optArray(song, "artists", "ar");
            if (list != null) {
                for (JsonElement artist : list) {
                    if (!artist.isJsonObject()) continue;
                    String name = Json.optString(artist.getAsJsonObject(), "name");
                    if (name.isEmpty()) continue;
                    if (artists.length() > 0) artists.append("/");
                    artists.append(name);
                }
            }
            hits.add(new Hit(String.valueOf((long) Json.optDouble(song, 0d, "id")),
                    Json.optString(song, "name", "title"), artists.toString(),
                    (long) Json.optDouble(song, 0d, "duration")));
        }
        return hits;
    }

    /** Parses a QQ {@code ...service.data.body.song.list} array. */
    public static List<Hit> parseQq(String body) {
        List<Hit> hits = new ArrayList<>();
        JsonArray songs = arrayAt(body, "music.search.SearchCgiService", "data", "body", "song",
                "list");
        if (songs == null) return hits;
        for (JsonElement element : songs) {
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            StringBuilder artists = new StringBuilder();
            JsonArray list = Json.optArray(song, "singer");
            if (list != null) {
                for (JsonElement artist : list) {
                    if (!artist.isJsonObject()) continue;
                    String name = Json.optString(artist.getAsJsonObject(), "name");
                    if (name.isEmpty()) continue;
                    if (artists.length() > 0) artists.append("/");
                    artists.append(name);
                }
            }
            hits.add(new Hit(Json.optString(song, "mid", "songmid"),
                    Json.optString(song, "title", "name"), artists.toString(),
                    (long) (Json.optDouble(song, 0d, "interval", "duration") * 1000d)));
        }
        return hits;
    }

    /** Parses an LRCLIB search array. */
    public static List<Hit> parseLrclib(String body) {
        List<Hit> hits = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) return hits;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                hits.add(new Hit(String.valueOf((long) Json.optDouble(row, 0d, "id")),
                        Json.optString(row, "trackName", "name"),
                        Json.optString(row, "artistName", "artist"),
                        (long) (Json.optDouble(row, 0d, "duration") * 1000d)));
            }
        } catch (Throwable ignored) {
        }
        return hits;
    }

    /** Dispatches to the parser for {@code source}. Unknown sources yield an empty list. */
    public static List<Hit> parse(Searchable source, String body) {
        if (source == null || body == null) return Collections.emptyList();
        switch (source) {
            case NETEASE:
                return parseNetease(body);
            case QQ:
                return parseQq(body);
            case LRCLIB:
                return parseLrclib(body);
            default:
                return Collections.emptyList();
        }
    }

    /** The JSON body of a QQ search POST for {@code query}. */
    public static String qqRequestBody(String query) {
        JsonObject param = new JsonObject();
        param.addProperty("num_per_page", "30");
        param.addProperty("page_num", "1");
        param.addProperty("query", query == null ? "" : query);
        param.addProperty("search_type", "0");
        JsonObject service = new JsonObject();
        service.addProperty("method", "DoSearchForQQMusicDesktop");
        service.addProperty("module", "music.search.SearchCgiService");
        service.add("param", param);
        JsonObject root = new JsonObject();
        root.add("music.search.SearchCgiService", service);
        return root.toString();
    }

    private static JsonArray arrayAt(String body, String... path) {
        try {
            JsonElement node = JsonParser.parseString(body);
            for (String key : path) {
                if (node == null || !node.isJsonObject()) return null;
                node = node.getAsJsonObject().get(key);
            }
            return node != null && node.isJsonArray() ? node.getAsJsonArray() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
