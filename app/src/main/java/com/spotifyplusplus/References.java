package com.spotifyplusplus;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import com.spotifyplusplus.xposed.XpLog;
import com.spotifyplusplus.xposed.SpotifySymbolResolver;
import com.spotifyplusplus.xposed.XpReflect;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.matchers.FieldMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class References {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    public static WeakReference<Object> playerState = new WeakReference<>(null);
    public static WeakReference<Object> playerStateWrapper = new WeakReference<>(null);
    /** Strong playback snapshots keep background track detection alive while Spotify UI is idle. */
    public static volatile Object playerStateStrong;
    public static volatile Object playerStateWrapperStrong;
    /**
     * Legacy compatibility mirror of the currently captured Spotify access token. Never
     * authoritative: the process-wide token lifecycle state (token text, captured timestamp,
     * observed expiry, generation, tombstoning) is owned by the package-private
     * SpotifyTokenStore/SpotifyTokenState seam in com.spotifyplusplus.hooks, and this mirror is
     * refreshed from it after every capture, restore, and invalidation. It is blank whenever no
     * usable (present, non-tombstoned, fresh) token exists. Read-only consumers must keep deciding
     * via their own settings (SEND_TOKEN) whether this value may be sent; capture itself never
     * depends on that choice. Do not assign this field outside the token store.
     */
    public static String accessToken = "";
    public static WeakReference<Typeface> beautifulFont = new WeakReference<>(null);
    public static Resources modResources = null;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static volatile Method hasTrackMethod;
    private static volatile Method getContextTrack;

    /**
     * Wrapper classes already known to have no "is there a track" boolean accessor.
     *
     * <p>The absence is discovered by throwing, and the symbol cache deliberately does not store
     * failures, so without this the scan and its log line repeated on every single lyric capture -
     * dozens of times per track. Concurrent because capture hooks fire on several threads.
     */
    private static final java.util.Set<Class<?>> TRACKLESS_WRAPPERS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static Activity currentActivity() {
        return currentActivity.get();
    }

    public static void setCurrentActivity(Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static void clearCurrentActivity(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity.clear();
    }

    public static SpotifyTrack getTrackTitle(ClassLoader classLoader, SpotifySymbolResolver symbols) {
        Object strongState = playerStateStrong;
        Object weakState = playerState == null ? null : playerState.get();
        if(strongState == null && weakState == null) {
            XpLog.log("[SpotifyPlus] playerState is null");
            return null;
        }

        Object state = strongState != null ? strongState : weakState;

        try {
            Object wrapper = XpReflect.callMethod(state, "track");
            if (wrapper == null) return null;
            Class<?> contextClass = XpReflect.findClass(
                    "com.spotify.player.model.ContextTrack", classLoader);

            Object ct = null;
            if (contextClass != null && contextClass.isInstance(wrapper)) {
                // Current Spotify returns the track itself from state.track(). Treating it as a
                // container and asking it for a track accessor found nothing on any build, which
                // is what the 553 "has no track accessor" failures were.
                ct = wrapper;
            } else {
                // Older builds wrap it: the container's "is there a track" boolean is only an
                // early-out, and Spotify 9.1.88 no longer declares it, so its absence falls back
                // to the null-ness of the track the accessor returns.
                Boolean hasTrack = null;
                if (!TRACKLESS_WRAPPERS.contains(wrapper.getClass())) {
                    try {
                        Method hasTrackAccessor = hasTrackMethod;
                        if (hasTrackAccessor == null
                                || hasTrackAccessor.getDeclaringClass() != wrapper.getClass()) {
                            hasTrackAccessor = symbols.trackMethod(wrapper.getClass(), boolean.class);
                            hasTrackMethod = hasTrackAccessor;
                        }
                        hasTrack = (Boolean) XpReflect.callMethod(wrapper, hasTrackAccessor.getName());
                    } catch (NoSuchMethodException absent) {
                        // Remember it: without this the scan repeats and re-logs on every lyric
                        // capture, which is dozens of times per track.
                        TRACKLESS_WRAPPERS.add(wrapper.getClass());
                        XpLog.log("[SpotifyPlus] " + wrapper.getClass().getName()
                                + " has no track-accessor boolean, reading the track directly");
                    }
                }

                if (hasTrack == null || hasTrack) {
                    try {
                        Method contextTrackAccessor = getContextTrack;
                        if (contextTrackAccessor == null
                                || contextTrackAccessor.getDeclaringClass() != wrapper.getClass()) {
                            // Discovered by shape and validated by ContextTrack.isInstance below,
                            // because the declared type is the concrete track in current builds.
                            contextTrackAccessor = symbols.trackAccessor(wrapper.getClass());
                            getContextTrack = contextTrackAccessor;
                        }
                        ct = XpReflect.callMethod(wrapper, contextTrackAccessor.getName());
                    } catch (NoSuchMethodException noAccessor) {
                        // One diagnostic dump per wrapper class: the obfuscated shape is the only
                        // way to learn what this build actually exposes, and guessing at it has
                        // already cost several rounds. Printed once so it cannot flood the log.
                        if (TRACKLESS_WRAPPERS.add(wrapper.getClass())) {
                            XpLog.log("[SpotifyPlus] track accessor missing on "
                                    + wrapper.getClass().getName()
                                    + " state=" + state.getClass().getName()
                                    + " trackType=" + XpReflect.callMethod(state, "track").getClass().getName()
                                    + " methods=" + describeMethods(wrapper.getClass()));
                        }
                    }
                }
            }

            // Without the boolean, "no track" is exactly "the accessor returned nothing".
            if (ct == null) return null;
            if (contextClass != null && contextClass.isInstance(ct)) {
                Object track = contextClass.cast(ct);

                String uri = (String) XpReflect.callMethod(track, "uri");

                @SuppressWarnings("unchecked")
                Map<String, String> md = (Map<String, String>) XpReflect.callMethod(track, "metadata");

                String title = md.get("title");
                String artist = joinArtistNames(md.get("artist_name"), md);
                String album = md.get("album_title");
                String color = md.get("extracted_color");
                String imageId = md.get("image_large_url");
                long duration = 0;
                try {
                    String durationValue = md.get("duration_ms");
                    if (durationValue == null) durationValue = md.get("duration");
                    if (durationValue != null && !durationValue.isEmpty()) {
                        duration = Long.parseLong(durationValue.replaceAll("[^0-9]", ""));
                        if (duration > 0 && duration < 10000) duration *= 1000;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (duration <= 0) duration = (Long) XpReflect.callMethod(state, "duration");
                } catch (Throwable ignored) {
                }
                long position = 0;
                long timestamp = 0;

                Object posOpt = XpReflect.callMethod(state, "positionAsOfTimestamp");
                Matcher m = DIGITS.matcher(posOpt.toString());
                if(m.find()) {
                    long basePos = Long.parseLong(m.group());
                    timestamp = (Long) XpReflect.callMethod(state, "timestamp");
                    position = basePos + (System.currentTimeMillis() - timestamp);
                }

                Map<?, ?> metadata = (Map<?, ?>) XpReflect.getObjectField(track, "metadata");
                boolean saved = false;

                if(metadata.containsKey("collection.in_collection")) {
                    String savedValue = (String) metadata.get("collection.in_collection");
                    saved = Boolean.parseBoolean(savedValue);
                }

                return new SpotifyTrack(title, artist, album, uri, position, color, timestamp, imageId, duration, saved);
            } else {
                // Reached only when state.track() handed back something that is not a ContextTrack.
                XpLog.log("[SpotifyPlus] ContextTrack not found!");
                return null;
            }
        } catch(Exception e) {
            Log.e("SpotifyPlus", "Error getting track information", e);
            return null;
        }
    }

    private static long previousMs;

    /**
     * Zero-argument methods of an obfuscated class, as "name:ReturnType:modifiers".
     *
     * <p>Diagnostic only. The track resolver is version-fragile by nature, and when it fails the
     * only way to correct it is to see the shape the current build actually exposes rather than
     * guessing at it. Bounded so a pathological class cannot flood the log.
     */
    private static String describeMethods(Class<?> type) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        int total = 0;
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                total++;
                if (out.size() >= 25) continue;
                out.add(method.getName() + ":" + method.getReturnType().getSimpleName()
                        + ":" + java.lang.reflect.Modifier.toString(method.getModifiers()));
            }
        } catch (Throwable t) {
            return "unreadable(" + t.getClass().getSimpleName() + ")";
        }
        return total + " no-arg [" + android.text.TextUtils.join(", ", out) + "]";
    }
    /**
     * Full artist credit line. Spotify's player metadata carries the main artist in
     * "artist_name" and any further credited artists in 1-based indexed keys
     * ("artist_name:1", "artist_name:2", ...); Spotify's own client iterates the same
     * keys when it builds the artist list. Joined with ", " to match the Now Playing
     * credit line; downstream matching already splits on commas.
     */
    static String joinArtistNames(String primary, Map<String, String> md) {
        List<String> names = new ArrayList<>();
        if (primary != null && !primary.trim().isEmpty()) names.add(primary.trim());
        if (md != null) {
            for (int index = 1; index <= MAX_INDEXED_ARTISTS; index++) {
                String extra = md.get("artist_name:" + index);
                if (extra == null || extra.trim().isEmpty()) break;
                String name = extra.trim();
                boolean duplicate = false;
                for (String known : names) {
                    if (known.equalsIgnoreCase(name)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) names.add(name);
            }
        }
        if (names.isEmpty()) return primary;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(names.get(i));
        }
        return joined.toString();
    }

    private static final int MAX_INDEXED_ARTISTS = 32;
    public static long getCurrentPlaybackPosition(DexKitBridge bridge, ClassLoader classLoader) {
        Object wrapper = playerStateWrapperStrong != null
                ? playerStateWrapperStrong
                : (References.playerStateWrapper == null ? null : References.playerStateWrapper.get());
        if (wrapper == null) return -1;

        Object state;
        try {
            state = XpReflect.callMethod(wrapper, "getState");

            if (state == null) return -1;
        } catch (Throwable t) {
            return -1;
        }

        try {
            var progressList = bridge.findField(FindField.create().searchInClass(Arrays.asList(bridge.getClassData(state.getClass()))).matcher(FieldMatcher.create().type(long.class)));
            if(progressList.isEmpty()) {
                XpLog.log("[SpotifyPlus] Failed to get progress: " + state.getClass().getName());
                return -1;
            }

            return progressList.get(0).getFieldInstance(classLoader).getLong(state);
        } catch(Exception e) {
            XpLog.log(e);
        }

        return -1;
    }

    public static SharedPreferences getPreferences() {
        Activity activity = currentActivity();
        if(activity == null) return null;

        return activity.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static SharedPreferences getScriptPreferences(String name, Context activity) {
        if(activity == null) {
            XpLog.log("[SpotifyPlus] No activity found");
            return null;
        }

        return activity.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

}
