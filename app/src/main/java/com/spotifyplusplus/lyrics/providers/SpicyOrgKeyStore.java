package com.spotifyplusplus.lyrics.providers;

import android.content.Context;
import com.spotifyplusplus.lyrics.ai.AiCredentialStore;

/** The user's own Spicy Lyrics client key, encrypted in Spotify's private storage. */
public final class SpicyOrgKeyStore {
    public static final String CATALOG_URL = "https://developers.spicylyrics.org/catalog/spicy-ex";
    private static final String SCOPE = "spicy_org_client_key";
    private static final String STATE = "SpotifyPlusSpicyOrgKeyState";

    private SpicyOrgKeyStore() { }

    public static boolean valid(String key) {
        return key != null && key.length() <= 512 && key.matches("sl_pk_[A-Za-z0-9_-]+");
    }

    public static String load(Context context) {
        if (context == null) return "";
        String key = AiCredentialStore.create(context).load(SCOPE);
        return valid(key) ? key : "";
    }

    public static boolean has(Context context) { return !load(context).isEmpty(); }

    /**
     * Masked form for the settings row: enough of the prefix and tail to tell two keys apart,
     * never enough to use. Empty when no key is stored.
     */
    public static String masked(Context context) {
        String key = load(context);
        if (key.isEmpty()) return "";
        if (key.length() <= 12) return "••••";
        return key.substring(0, 9) + "••••" + key.substring(key.length() - 4);
    }

    public static synchronized boolean save(Context context, String key) {
        return save(context, key, AiCredentialStore.create(context));
    }

    static synchronized boolean save(Context context, String key, AiCredentialStore store) {
        if (context == null || !valid(key) || !store.save(SCOPE, key)) return false;
        advanceEpoch(context);
        return true;
    }

    public static synchronized void delete(Context context) {
        if (context == null) return;
        AiCredentialStore.create(context).delete(SCOPE);
        advanceEpoch(context);
    }

    /** Non-secret revision used to reject results from requests made with an older key. */
    public static long epoch(Context context) {
        return context == null ? 0L : context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
                .getLong("epoch", 0L);
    }

    private static void advanceEpoch(Context context) {
        long next = Math.max(System.currentTimeMillis(), epoch(context) + 1L);
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit().putLong("epoch", next).apply();
    }
}
