package com.spotifyplusplus.lyrics.processing;

import android.content.Context;

import com.spotifyplusplus.lyrics.ai.AiCredentialStore;
import com.spotifyplusplus.lyrics.ai.AiText;

/**
 * The Baidu Translate app id and secret at rest.
 *
 * <p>Baidu's official translate API is credentialled, which is the whole difference from the
 * keyless endpoint this backend used before: {@code appid} identifies the application and the
 * secret signs every request, so both are the owner's credentials and both are stored the way the
 * other secrets in this module are - in {@link AiCredentialStore}, encrypted with a key the app
 * cannot export, never in a plain preferences value and never in a build file. The store is the
 * same one the SpicyLyrics.org client key uses; a third preferences file would be one more place
 * a secret could be written in the clear.
 *
 * <p>The app id is not itself a secret - it appears in every request - but it lives here for the
 * same reason: one store, one delete path, and no half-configured pair left behind by a partial
 * edit. {@link #has} answers "both are present", which is the only state the backend can use.
 *
 * <p>Values are validated on the way in, not on the way out. A rejected value is a paste error -
 * a URL, a whole console page, a stray label - and storing it would turn one bad keystroke into an
 * authentication failure on a song much later.
 */
public final class BaiduTranslateCredentials {
    /**
     * Where the pair comes from. Shown in the settings dialogs, because a value from anywhere else
     * cannot work: the app id identifies an account, and the secret is the account's own key.
     */
    public static final String CONSOLE_URL = "https://fanyi-api.baidu.com/";

    /** Credential scopes inside the shared encrypted store. Public so a backup names them once. */
    public static final String APP_ID_SCOPE = "baidu_translate_appid";
    public static final String SECRET_SCOPE = "baidu_translate_secret";

    /**
     * Length caps. Baidu issues an app id of digits and a secret of letters and digits, so these
     * are generous upper bounds of the same alphabet rather than a guess at the exact format: a
     * value this long is a document, not a credential.
     */
    private static final int MAX_APP_ID_LENGTH = 64;
    private static final int MAX_SECRET_LENGTH = 128;
    /** Below this a value is a truncated paste rather than a key. */
    private static final int MIN_SECRET_LENGTH = 6;

    private BaiduTranslateCredentials() {
    }

    /** @return the stored app id, or empty when there is none or it cannot be decrypted */
    public static String appId(Context context) {
        return load(context, APP_ID_SCOPE);
    }

    /** @return the stored secret, or empty when there is none or it cannot be decrypted */
    public static String secret(Context context) {
        return load(context, SECRET_SCOPE);
    }

    /** True when both halves are stored, which is what a request needs. */
    public static boolean has(Context context) {
        return !appId(context).isEmpty() && !secret(context).isEmpty();
    }

    /** Stores the app id. @return false when the value is empty or not an app id shape */
    public static synchronized boolean saveAppId(Context context, String value) {
        String appId = AiText.nz(value).trim();
        if (!isAppIdShape(appId)) return false;
        return store(context).save(APP_ID_SCOPE, appId);
    }

    /** Stores the secret. @return false when the value is empty or not a secret shape */
    public static synchronized boolean saveSecret(Context context, String value) {
        String secret = AiText.nz(value).trim();
        if (!isSecretShape(secret)) return false;
        return store(context).save(SECRET_SCOPE, secret);
    }

    /** Removes the stored app id; a request then reports itself as unconfigured. */
    public static synchronized void deleteAppId(Context context) {
        if (context != null) store(context).delete(APP_ID_SCOPE);
    }

    /** Removes the stored secret. */
    public static synchronized void deleteSecret(Context context) {
        if (context != null) store(context).delete(SECRET_SCOPE);
    }

    /**
     * Masked form for a settings row: the last four characters, never enough to use.
     *
     * <p>Empty when nothing is stored, so a row can render "Not set" from the same answer it uses
     * for the value.
     */
    public static String masked(String value) {
        return AiCredentialStore.mask(AiText.nz(value).trim());
    }

    /**
     * An app id is what Baidu's console shows: digits or letters, one token, no spaces. The check
     * is about shape only - a wrong-but-well-formed value is answered by the service with
     * {@code 52003}, which is a clearer report than anything invented here.
     */
    static boolean isAppIdShape(String value) {
        String appId = AiText.nz(value).trim();
        if (appId.isEmpty() || appId.length() > MAX_APP_ID_LENGTH) return false;
        for (int i = 0; i < appId.length(); i++) {
            if (!isCredentialChar(appId.charAt(i))) return false;
        }
        return true;
    }

    /** A secret is one whitespace-free ASCII token of the issued length. */
    static boolean isSecretShape(String value) {
        String secret = AiText.nz(value).trim();
        if (secret.length() < MIN_SECRET_LENGTH || secret.length() > MAX_SECRET_LENGTH) return false;
        for (int i = 0; i < secret.length(); i++) {
            if (!isCredentialChar(secret.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isCredentialChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '_' || c == '-';
    }

    /**
     * Reads one credential, re-checked on the way out: a value written by an older build, or by a
     * console's copy button with a newline attached, must not reach a request builder. The signature
     * is computed over whatever is stored, so one stray character refuses every call as a bad sign.
     */
    private static String load(Context context, String scope) {
        if (context == null) return "";
        String value = AiText.nz(store(context).load(scope)).trim();
        boolean shape = APP_ID_SCOPE.equals(scope) ? isAppIdShape(value) : isSecretShape(value);
        return shape ? value : "";
    }

    private static AiCredentialStore store(Context context) {
        return AiCredentialStore.create(context);
    }
}
