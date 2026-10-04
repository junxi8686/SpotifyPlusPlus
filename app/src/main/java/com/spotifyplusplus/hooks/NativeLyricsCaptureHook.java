package com.spotifyplusplus.hooks;

import static com.spotifyplusplus.hooks.NativeLyricsUtils.safe;

import com.spotifyplusplus.SpotifyTrack;
import com.spotifyplusplus.lyrics.providers.NativeLyricsSource;
import com.spotifyplusplus.lyrics.providers.LyricsRepository;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashSet;
import java.util.Locale;

import com.spotifyplusplus.xposed.XpHooks;
import com.spotifyplusplus.xposed.XpLog;
import com.spotifyplusplus.xposed.XpReflect;
import com.spotifyplusplus.xposed.SpotifySymbolResolver;
import java.util.ArrayList;
import java.util.List;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

/** Installs Spotify native lyrics model capture hooks and forwards candidates to NativeLyricsSource. */
final class NativeLyricsCaptureHook {
    interface TrackProvider {
        SpotifyTrack getCurrentTrack();
    }

    private static final String[] NATIVE_CLASS_NAMES = {
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity",
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity$Line",
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity$Syllable",
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity$Provider",
            "com.spotify.lyrics.data.model.Lyrics",      // <= ~9.1.28
            "com.spotify.lyrics.data.model.ColorLyrics", // renamed in newer Spotify (>= 9.1.56)
            // The parsed service response. The endpoint takes `Accept: application/protobuf`, so
            // on current builds the lines only exist in this protobuf message: the offline table
            // stays empty and the model no longer carries them. These names survive obfuscation
            // because the generated protobuf code and the Retrofit interface refer to them by
            // name, which makes them the one durable handle on Spotify's own lyrics.
            "com.spotify.lyrics.serviceretrofit.proto.v3.LyricsWrapperResponse",
            "com.spotify.lyrics.serviceretrofit.proto.v3.LyricsV3Response",
            "com.spotify.lyrics.serviceretrofit.proto.ColorLyricsResponse",
            "com.spotify.lyrics.serviceretrofit.proto.LyricsResponse"
    };
    /**
     * The color-lyrics endpoint is only an annotation value on the Retrofit interface, so a
     * string probe cannot reach it and those probes resolved nothing on every build so far.
     * The parsed protobuf response is hooked by name instead, which is where the lines are.
     */

    private static final String[][] DEXKIT_PROBES = {
            {"lyrics_entities("},
            {"SELECT * FROM lyrics_entities WHERE track_id = ?"},
            {"INSERT OR REPLACE INTO `lyrics_entities`"},
            {"syncStatus", "vocalRemovalStatus"},
            {"GeneratedJsonAdapter(LyricsDatabaseEntity.Line)"},
            {"GeneratedJsonAdapter(LyricsDatabaseEntity.Syllable)"},
            {"lyricsLines_"},
            {"LyricsLineTag"}
    };
    /**
     * Method-level trace probes for the native lyrics load path: the loader that fetches
     * color-lyrics for a track and the DAO that reads lyrics_entities. Each probe runs its
     * DexKit trace once and the resolved method is cached by symbol record, mirroring the
     * playback wrapper getState discovery. Hooking the load call itself (rather than only
     * model constructors) is what keeps the Spotify row fed on Spotify builds where the
     * model class names moved.
     */
    private static final String[][] NATIVE_LOAD_TRACES = {
            {"SELECT * FROM lyrics_entities WHERE track_id = ?"},
            {"syncStatus", "vocalRemovalStatus"},
    };

    private final LinkedHashSet<String> hookedClassNames = new LinkedHashSet<>();
    private final java.util.Map<String, Integer> seenCounts = new java.util.HashMap<>();
    private final ClassLoader classLoader;
    private final SpotifySymbolResolver symbols;
    private final NativeLyricsSource nativeLyricsSource;
    private final TrackProvider trackProvider;
    private volatile Object spotifyLyricsService;
    private volatile Object spotifyComponent;

    NativeLyricsCaptureHook(
            ClassLoader classLoader,
            SpotifySymbolResolver symbols,
            NativeLyricsSource nativeLyricsSource,
            TrackProvider trackProvider
    ) {
        this.classLoader = classLoader;
        this.symbols = symbols;
        this.nativeLyricsSource = nativeLyricsSource;
        this.trackProvider = trackProvider;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookNativeLyricsCapture");
        for (String name : NATIVE_CLASS_NAMES) {
            try {
                Class<?> cls = XpReflect.findClass(name, classLoader);
                hookResolvedNativeLyricsClass(cls, name);
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics capture missing " + name + ": " + t.getClass().getSimpleName());
            }
        }
        hookDeferredNativeLyricsClassLoading();
        discoverNativeLyricsClasses();
        traceNativeLyricsLoad();
        // Spotify's own lyrics arrive as a protobuf message on current builds: the offline
        // table stays empty, the response body never reaches a named HTTP client, and the
        // endpoint string is annotation-only. The response classes are hooked by name above.
        NativeLyricsNetworkHook.install(classLoader, nativeLyricsSource, trackProvider);
        installExplicitSpotifyRequest();
    }

    /** Uses Spotify's own authenticated Retrofit client when its verified service is present. */
    private void installExplicitSpotifyRequest() {
        try {
            Class<?> service = XpReflect.findClass("p.kqb0", classLoader);
            Class<?> retrofit = XpReflect.findClass("p.hqb0", classLoader);
            Class<?> single = XpReflect.findClass("io.reactivex.rxjava3.core.Single", classLoader);
            Method endpoint = retrofit.getMethod("b", String.class, boolean.class,
                    String.class, boolean.class);
            if (!single.isAssignableFrom(endpoint.getReturnType())) return;
            Field client = service.getDeclaredField("a");
            client.setAccessible(true);
            Field language = service.getDeclaredField("c");
            language.setAccessible(true);
            XpHooks.hookAllConstructors(service, "lyrics:explicitSpotifyClient",
                    (XpHooks.After) param -> {
                        spotifyLyricsService = param.thisObject;
                        XpLog.log(NativeSpicyLyricsHook.TAG
                                + " explicit Spotify client captured");
                    });
            Class<?> provider = XpReflect.findClass("p.oon", classLoader);
            Class<?> component = XpReflect.findClass("p.pon", classLoader);
            XpHooks.hookAllConstructors(provider, "lyrics:spotifyComponentProvider",
                    (XpHooks.After) param -> {
                        if (spotifyComponent == null && param.args != null
                                && param.args.length > 0
                                && component.isInstance(param.args[0])) {
                            spotifyComponent = param.args[0];
                        }
                    });
            nativeLyricsSource.setRequester((track, callback) ->
                    requestSpotifyTrack(track, callback, service, client, language, endpoint));
            XpLog.log(NativeSpicyLyricsHook.TAG + " explicit Spotify request installed");
        } catch (Throwable error) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " explicit Spotify request unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    private void requestSpotifyTrack(SpotifyTrack track,
            LyricsRepository.NativeLyricsProvider.RequestCallback callback,
            Class<?> service, Field clientField, Field languageField, Method endpoint) {
        Object owner = spotifyLyricsService;
        if (owner == null) owner = resolveSpotifyLyricsService(service);
        if (owner == null || track == null || track.uri == null) {
            if (owner == null) XpLog.log(NativeSpicyLyricsHook.TAG
                    + " explicit Spotify client not created");
            callback.onResult(null, "Spotify lyrics request unavailable");
            return;
        }
        String id = com.spotifyplusplus.lyrics.LyricUtils.trackIdFromUri(track.uri);
        if (id.isEmpty()) {
            callback.onResult(null, "Unsupported Spotify track");
            return;
        }
        AtomicBoolean done = new AtomicBoolean();
        Handler main = new Handler(Looper.getMainLooper());
        final Object[] disposable = {null};
        Runnable timeout = () -> {
            if (!done.compareAndSet(false, true)) return;
            dispose(disposable[0]);
            callback.onResult(null, "Spotify lyrics request timed out");
        };
        try {
            Object client = clientField.get(owner);
            Object languageOwner = languageField.get(owner);
            Method languageMethod = languageOwner.getClass().getMethod("j");
            String language = (String) languageMethod.invoke(languageOwner);
            Object request = endpoint.invoke(client, id, false,
                    language == null ? "" : language, false);
            Class<?> consumer = XpReflect.findClass(
                    "io.reactivex.rxjava3.functions.Consumer", classLoader);
            Object success = Proxy.newProxyInstance(classLoader, new Class<?>[]{consumer},
                    (proxy, method, args) -> {
                        if ("accept".equals(method.getName())
                                && done.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout);
                            Object response = args == null || args.length == 0 ? null : args[0];
                            nativeLyricsSource.captureCandidate(track, response,
                                    new Object[]{track.uri}, "explicit:spotify-retrofit");
                            com.spotifyplusplus.lyrics.LyricsDocument doc =
                                    nativeLyricsSource.getNativeLyricsDocument(track);
                            callback.onResult(doc, doc == null
                                    ? "Spotify lyrics response unavailable" : "");
                        }
                        return null;
                    });
            Object failure = Proxy.newProxyInstance(classLoader, new Class<?>[]{consumer},
                    (proxy, method, args) -> {
                        if ("accept".equals(method.getName())
                                && done.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout);
                            Object error = args == null || args.length == 0 ? null : args[0];
                            callback.onResult(null, spotifyRequestError(error));
                        }
                        return null;
                    });
            main.postDelayed(timeout, 10000L);
            disposable[0] = request.getClass().getMethod("subscribe", consumer, consumer)
                    .invoke(request, success, failure);
            if (done.get()) dispose(disposable[0]);
        } catch (Throwable error) {
            main.removeCallbacks(timeout);
            if (done.compareAndSet(false, true)) {
                callback.onResult(null, "Spotify lyrics request failed");
            }
            XpLog.log(NativeSpicyLyricsHook.TAG + " explicit Spotify request failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private Object resolveSpotifyLyricsService(Class<?> service) {
        Object component = spotifyComponent;
        if (component == null) return null;
        try {
            // Spotify 9.1.84's provider for this exact service is the component's `on`
            // binding. Verify the returned class before retaining or invoking it.
            Field binding = component.getClass().getDeclaredField("on");
            binding.setAccessible(true);
            Object provider = binding.get(component);
            if (provider == null) return null;
            Object created = provider.getClass().getMethod("get").invoke(provider);
            if (!service.isInstance(created)) return null;
            spotifyLyricsService = created;
            return created;
        } catch (Throwable error) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " Spotify client resolve failed: "
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    private static String spotifyRequestError(Object error) {
        try {
            int code = (Integer) error.getClass().getMethod("code").invoke(error);
            if (code == 404) return "Spotify has no lyrics for this track";
        } catch (Throwable ignored) {
        }
        return "Spotify lyrics request failed";
    }

    private static void dispose(Object disposable) {
        if (disposable == null) return;
        try {
            disposable.getClass().getMethod("dispose").invoke(disposable);
        } catch (Throwable ignored) {
        }
    }

    private void hookDeferredNativeLyricsClassLoading() {
        try {
            XpHooks.findAfter(ClassLoader.class, "loadClass",
                    "lyrics:ClassLoader#loadClass", param -> {
                        if (!(param.args != null && param.args.length > 0
                                && param.args[0] instanceof String)) return;
                        String name = (String) param.args[0];
                        if (!isNativeLyricsClassName(name)) return;
                        Object result = param.getResult();
                        if (!(result instanceof Class)) return;
                        hookResolvedNativeLyricsClass((Class<?>) result, "deferred:" + name);
                    }, String.class, boolean.class);
            XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred ClassLoader hook installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred hook failed: " + t);
        }
    }

    private void discoverNativeLyricsClasses() {
        for (String[] probe : DEXKIT_PROBES) {
            try {
                List<Class<?>> found = symbols.cache.classes("lyrics." + String.join("|", probe), () -> {
                    var matches = symbols.dexKit().findClass(
                            FindClass.create().matcher(ClassMatcher.create().usingStrings(probe)));
                    List<String> classes = new ArrayList<>();
                    for (var data : matches) {
                        if (classes.size() >= 8) break;
                        classes.add(data.getName());
                    }
                    return classes;
                });
                for (Class<?> cls : found) {
                    hookResolvedNativeLyricsClass(cls, "resolved:" + String.join(",", probe));
                }
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics DexKit probe failed strings=" + String.join(",", probe) + ": " + t);
            }
        }
    }

    /**
     * One-time DexKit trace of the native lyrics load path. Each probe resolves its loader
     * method once and persists it as a symbol record; warm startups reuse the record without
     * loading DexKit. The traced method's return value is captured as a native candidate, so
     * the Spotify row reflects whatever Spotify itself loaded for the track.
     */
    private void traceNativeLyricsLoad() {
        for (String[] probe : NATIVE_LOAD_TRACES) {
            try {
                java.lang.reflect.Method traced = symbols.cache.method(
                        "lyrics.nativeLoad." + String.join("|", probe), () -> {
                            var matches = symbols.dexKit().findMethod(
                                    FindMethod.create().matcher(
                                            MethodMatcher.create().usingStrings(probe)));
                            for (var data : matches) {
                                try {
                                    java.lang.reflect.Method candidate =
                                            data.getMethodInstance(classLoader);
                                    if (candidate == null) continue;
                                    int modifiers = candidate.getModifiers();
                                    if (Modifier.isAbstract(modifiers)
                                            || Modifier.isNative(modifiers)) continue;
                                    return candidate;
                                } catch (Throwable ignored) {
                                }
                            }
                            throw new NoSuchMethodException(
                                    "lyrics native load " + String.join(",", probe));
                        });
                final String tag = "traced:" + traced.getDeclaringClass().getName()
                        + "#" + traced.getName();
                XpHooks.hookAfter(traced, "lyrics:" + tag, param -> {
                    Object result = param.getResult();
                    if (result != null) {
                        captureNativeLyricsCandidate(result, param.args, tag);
                    }
                });
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics load trace installed "
                        + traced.getDeclaringClass().getName() + "#" + traced.getName());
                // The response parser lives next to the request builder, so hook the whole
                // declaring class as well rather than only the one traced method.
                hookResolvedNativeLyricsClass(traced.getDeclaringClass(),
                        "colorEndpointNeighbour:" + String.join(",", probe));
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics load trace failed strings=" + String.join(",", probe)
                        + ": " + t);
            }
        }
    }

    private void hookResolvedNativeLyricsClass(Class<?> cls, String sourceTag) {
        if (cls == null) return;
        String className = cls.getName();
        synchronized (hookedClassNames) {
            if (hookedClassNames.contains(className)) return;
            if (hookedClassNames.size() > 40) return;
            hookedClassNames.add(className);
        }
        try {
            XpHooks.hookAllConstructors(cls, "lyrics:" + className + "#ctor", (XpHooks.After) param -> {
                captureNativeLyricsCandidate(param.thisObject, param.args, sourceTag + ":ctor:" + className);
                // Read first, empty second. The owner asked for Spotify's own lyrics card to go
                // away while keeping the module able to use those lyrics as a source - and both
                // read from this one object, so the order is what makes both true. Blanking it
                // after the capture above leaves the module's copy intact and gives Spotify a
                // object with nothing in it, which is how its Compose card decides to hide itself.
                //
                // Spotify's Now Playing surface is Compose, so there is no View to hide: no
                // resource id, no child to collapse, and hiding the container would take the rest
                // of the screen with it. This is the only place the card's content can be reached.
                hideSpotifyLyrics(param.thisObject, className);
            });
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " native lyrics constructor hook failed " + className + ": " + t.getClass().getSimpleName());
        }
        int methodHooks = 0;
        for (Method method : cls.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) continue;
            if (method.getReturnType() == Void.TYPE) continue;
            if (methodHooks >= 18) break;
            try {
                method.setAccessible(true);
                XpHooks.hookAfter(method, "lyrics:" + className + "#" + method.getName(), param -> {
                    Object result = param.getResult();
                    // Re-read the list's owner, including metadata, on later sheet visits.
                    if (result instanceof java.util.Collection) result = param.thisObject;
                    if (result != null) {
                        captureNativeLyricsCandidate(
                                result,
                                param.args,
                                sourceTag + ":method:" + className + "#" + method.getName()
                        );
                    }
                    // Also on the method path, not only the constructor.
                    //
                    // The constructors of these protos are not what runs: the capture log shows
                    // every sighting arriving through a method, so a blank installed only on the
                    // constructor never executed at all and the card kept its lyrics. Blanking the
                    // receiver here reaches the same model by the route it is actually built on.
                    hideSpotifyLyrics(param.thisObject, className);
                });
                methodHooks++;
            } catch (Throwable ignored) {
            }
        }
        XpLog.log(NativeSpicyLyricsHook.TAG
                + " native lyrics capture hook installed " + className
                + " methods=" + methodHooks
                + " source=" + sourceTag);
    }

    private static boolean isNativeLyricsClassName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("spotify.lyrics")
                || lower.contains("lyricsdatabaseentity")
                || lower.contains("lyricsresponse")
                || lower.contains("lyricsv3response")
                || lower.contains("colorlyricsresponse");
    }

    /**
     * Empties the lyric-bearing fields of a Spotify lyrics model so its own card hides itself.
     *
     * <p>Runs strictly after the module has read the same object, so the module's copy of these
     * lyrics is unaffected. Every step is guarded: an unrecognised field, an unexpected type or a
     * refused reflective call leaves the object exactly as it was. Getting this wrong must cost a
     * card that stays visible, never a crash in Spotify's playback path.
     *
     * <p>Field names on these protos are obfuscated on some builds, so the count of fields actually
     * blanked is logged: a zero there means the name match missed and the approach needs a
     * different handle, and it is the only way to tell without a debugger attached.
     */
    private void hideSpotifyLyrics(Object model, String className) {
        if (model == null) return;
        try {
            if (!spotifyLyricsShouldBeHidden()) return;
        } catch (Throwable ignored) {
            return;
        }
        // The model is only half of it: emptying the lyrics left the section's own bar behind, and
        // that bar is a row in the page's RecyclerView. This runs on the same trigger that already
        // knows lyrics are being served, so the view-side hider is installed from here rather than
        // waiting for the owner to open the settings panel.
        SpotifyLyricsCardHider.ensureInstalled(currentApplication(), model.getClass().getClassLoader());
        int blanked = 0;
        int scanned = 0;
        try {
            int[] counts = blankTextFields(model, 0, new java.util.HashSet<Integer>());
            blanked = counts[0];
            scanned = counts[1];
        } catch (Throwable ignored) {
            return;
        }
        if (blanked == 0) return;
        try {
            synchronized (seenCounts) {
                if (blinkLogged.add(className)) {
                    XpLog.log(NativeSpicyLyricsHook.TAG + " hid Spotify lyrics class="
                            + safe(className) + " fields=" + blanked + "/" + scanned);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Empties every text field reachable from {@code model}, one level of nesting deep.
     *
     * <p>Depth matters: on this build the lines live in a nested object ({@code LyricsResponse#v}
     * returns {@code p.ssc0}), so blanking only the receiver's own fields would leave the lyrics
     * exactly where they are. One level reaches that object; deeper is not attempted because a
     * proto graph can be arbitrarily large and this runs inside a hot method hook.
     *
     * @return {blanked, scanned}
     */
    private static int[] blankTextFields(Object model, int depth, java.util.Set<Integer> seen) {
        int blanked = 0;
        int scanned = 0;
        if (model == null || depth > 1) return new int[]{blanked, scanned};
        if (!seen.add(System.identityHashCode(model))) return new int[]{blanked, scanned};
        java.lang.reflect.Field[] fields;
        try {
            fields = model.getClass().getDeclaredFields();
        } catch (Throwable t) {
            return new int[]{blanked, scanned};
        }
        for (java.lang.reflect.Field field : fields) {
            try {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                scanned++;
                field.setAccessible(true);
                Object value = field.get(model);
                if (value instanceof String) {
                    if (((String) value).isEmpty()) continue;
                    field.set(model, "");
                    blanked++;
                } else if (value instanceof java.util.Collection) {
                    // Clears the host's own list. The switch that guards this whole routine is off
                    // by default and its label carries the warning, because this is not free: a
                    // list Spotify is iterating throws ConcurrentModificationException, and an
                    // immutable one throws UnsupportedOperationException. Both are caught per
                    // field, but the first one occurs on Spotify's thread, not ours.
                    if (((java.util.Collection<?>) value).isEmpty()) continue;
                    ((java.util.Collection<?>) value).clear();
                    blanked++;
                } else if (value instanceof Boolean) {
                    // The flags travel with the text. Emptying every string still left a placeholder
                    // strip where the card had been: the card's own existence is a separate answer
                    // from "are there lines in it", and these are the only non-text fields on the
                    // model that can carry it (LyricsResponse#q and ColorLyricsResponse#o both
                    // return Boolean). Cleared to false, which is the "nothing here" side.
                    if (!((Boolean) value)) continue;
                    field.set(model, Boolean.FALSE);
                    blanked++;
                } else if (value != null && depth < 1 && !value.getClass().getName().startsWith("java.")
                        && !value.getClass().isEnum() && !value.getClass().isArray()) {
                    int[] inner = blankTextFields(value, depth + 1, seen);
                    blanked += inner[0];
                    scanned += inner[1];
                    if (inner[0] > 0) {
                        // Drops the container that held the lyrics, which is what actually takes the
                        // card away rather than leaving a blank one. This is the write the switch's
                        // warning is about: if Spotify serialises this model (a protobuf
                        // getSerializedSize) or stores it (a Room @NonNull bind for the offline
                        // lyrics table this hook also sees), it throws and the process dies.
                        try {
                            field.set(model, null);
                            blanked++;
                        } catch (Throwable ignored) {
                            // A final target refuses this; the emptied text still stands.
                        }
                    }
                }
                // Left alone: nulls, numbers, arrays and enums. Emptying a String to "" and
                // clearing a list keeps the model structurally valid, where writing null could
                // hand Spotify something it dereferences without checking.
            } catch (Throwable ignored) {
                // One uncooperative field must not abandon the rest.
            }
        }
        return new int[]{blanked, scanned};
    }

    /** Field names that carry lyric text on the builds seen so far. */
    @SuppressWarnings("unused")
    private static boolean looksLikeLyricText(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("lyric") || lower.contains("line") || lower.contains("text")
                || lower.contains("content") || lower.contains("syllable") || lower.contains("word")
                || lower.contains("timing") || lower.contains("sing") || lower.contains("color");
    }

    /** The owner's switch, read from the host process's stored settings. */
    private static boolean spotifyLyricsShouldBeHidden() {
        try {
            android.content.Context context = currentApplication();
            if (context == null) return true;
            return com.spotifyplusplus.lyrics.session.LyricsSourcePreferences
                    .ignoresSpotifyLyrics(context);
        } catch (Throwable t) {
            // The setting defaults to on, and a failed read must not leave the card visible while
            // the switch shows as on.
            return true;
        }
    }

    /**
     * The host application, without depending on a helper class this Xposed API may not ship.
     *
     * <p>{@code AndroidAppHelper} is the documented way and is absent here, so the reflective call
     * it wraps is made directly. Failure is reported as null and treated as "setting is on".
     */
    private static android.content.Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method method = activityThread.getMethod("currentApplication");
            Object application = method.invoke(null);
            return application instanceof android.content.Context
                    ? (android.content.Context) application : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private final java.util.Set<String> blinkLogged = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());

    private void captureNativeLyricsCandidate(Object candidate, Object[] ctorArgs, String sourceTag) {        if (candidate instanceof java.util.Collection) return;
        // Throttled invocation trace: proves whether the hooked Spotify lyrics path fires at
        // all on the installed Spotify build, independent of whether parsing succeeds.
        // First five sightings per hook source; steady state stays quiet.
        try {
            synchronized (seenCounts) {
                int seen = seenCounts.containsKey(sourceTag) ? seenCounts.get(sourceTag) : 0;
                if (seen < 5) {
                    seenCounts.put(sourceTag, seen + 1);
                    XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics hook fired source="
                            + safe(sourceTag) + " class="
                            + (candidate == null ? "null" : candidate.getClass().getName())
                            + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
                }
            }
        } catch (Throwable ignored) {
        }
        nativeLyricsSource.captureCandidate(trackProvider.getCurrentTrack(), candidate, ctorArgs, sourceTag);
    }
}
