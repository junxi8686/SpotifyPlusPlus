package com.spotifyplusplus.lyrics.ai;

import android.content.Context;
import android.content.SharedPreferences;

import com.spotifyplusplus.Diagnostics;

import java.nio.charset.StandardCharsets;

/**
 * The provider key at rest.
 *
 * <p>Kept in its own preferences file, encrypted with a key the app cannot export, and never
 * written anywhere else. What that does and does not buy is worth stating plainly, because
 * overstating it would be worse than the limit itself:
 *
 * <ul>
 *   <li><b>It resists</b> backup extraction, file-level snooping, and anyone reading the data
 *       directory offline. The ciphertext is useless without a key that never leaves the Keystore.
 *   <li><b>It does not resist</b> Spotify itself, or another module in the same process. We run
 *       inside Spotify, so the Keystore key belongs to Spotify's UID and anything else running
 *       there can ask for the plaintext exactly as we do. The real blast-radius control is a
 *       dedicated provider key with a spend cap set in the provider's own console.
 *   <li><b>Uninstalling Spicy EX does not remove it</b>, because these preferences belong to
 *       Spotify. That is why delete is an explicit action rather than something left to cleanup.
 *   <li><b>Copying the ciphertext does not move the key.</b> A backup carries these values
 *       verbatim, so they keep working on this install for as long as the Spotify-side data and
 *       its Keystore key both survive - a module update, a Spotify update, or a module
 *       uninstall. They cannot be read after an uninstall or clear-data (the platform destroys
 *       the key), on another device, or in another Android user of the same device.
 * </ul>
 *
 * <p>The cipher is injected so the storage logic — the cap, the delete, the empty cases — is
 * testable off-device, where there is no Keystore at all.
 */
public final class AiCredentialStore {

    /** Encrypts and decrypts a secret. The Keystore implementation is the only real one. */
    public interface Cipher {
        /** @return ciphertext safe to persist, or null when it could not be encrypted */
        String encrypt(String plaintext);

        /** @return the secret, or null when it cannot be recovered */
        String decrypt(String ciphertext);

        /** Drops the underlying key material, if any. */
        void clear();
    }

    /**
     * Whether one stored value can still be read on this device.
     *
     * <p>A restore has to answer that before it overwrites anything: ciphertext written on another
     * device is indistinguishable from a usable value until it is tried, and replacing a value
     * that works here with one that cannot be read turns a working install into a broken one.
     */
    public interface Decryptability {
        /** @param stored the raw preference value; never null, may be empty */
        boolean canDecrypt(String stored);
    }

    /** The preference file every secret in this module lives in. */
    public static final String PREFS = "SpotifyPlusAiCredentials";
    private static final String KEY_PREFIX = "secret_";

    /** The preference key one scope is stored under, as a backup sees it. */
    public static String key(String providerId) {
        return KEY_PREFIX + AiText.nz(providerId);
    }

    /** True only for the preference keys this store owns. */
    public static boolean isCredentialKey(String preferenceKey) {
        return preferenceKey != null && preferenceKey.startsWith(KEY_PREFIX)
                && preferenceKey.length() > KEY_PREFIX.length();
    }

    /** The scope inside one of this store's preference keys, or empty for a foreign key. */
    public static String scopeOf(String preferenceKey) {
        return isCredentialKey(preferenceKey) ? preferenceKey.substring(KEY_PREFIX.length()) : "";
    }

    /**
     * This device's ability to read a stored value, backed by the Keystore cipher.
     *
     * <p>Never throws: a missing or unusable Keystore key answers false, and so does a value that
     * is not in this store's format at all.
     */
    public static Decryptability decryptability(Context context) {
        AiCredentialStore store = create(context);
        return stored -> store.cipher != null && store.decryptStored(stored) != null;
    }

    private final SharedPreferences prefs;
    private final Cipher cipher;

    public AiCredentialStore(Context context, Cipher cipher) {
        this.prefs = context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.cipher = cipher;
    }

    /** The Keystore-backed store, as used at runtime. */
    public static AiCredentialStore create(Context context) {
        return new AiCredentialStore(context, new AiKeystoreCipher());
    }

    /**
     * Stores {@code secret} for {@code providerId}.
     *
     * @return false when the secret is empty, too large, or could not be encrypted. A failure
     *         leaves any existing secret untouched rather than half-replacing it.
     */
    public boolean save(String providerId, String secret) {
        if (prefs == null || cipher == null) return false;
        String id = AiText.nz(providerId);
        String value = AiText.nz(secret);
        if (id.isEmpty() || value.isEmpty()) return false;
        if (value.getBytes(StandardCharsets.UTF_8).length > AiContract.MAX_CREDENTIAL_BYTES) {
            // A pasted document rather than a key. Refusing is kinder than storing it and failing
            // every request afterwards with an auth error.
            return false;
        }
        String encrypted = cipher.encrypt(value);
        if (encrypted == null || encrypted.isEmpty()) {
            Diagnostics.event("AiCredentialStore", "encrypt_failed");
            return false;
        }
        prefs.edit().putString(key(id), encrypted).apply();
        return true;
    }

    /** @return the secret, or empty when there is none or it cannot be decrypted */
    public String load(String providerId) {
        if (prefs == null || cipher == null) return "";
        String stored = storedValue(key(AiText.nz(providerId)));
        if (stored.isEmpty()) return "";
        String secret = cipher.decrypt(stored);
        if (secret == null) {
            // The Keystore key that wrote these bytes is gone, or belongs to another device, so
            // they will never decrypt again: treat the credential as absent and let the owner
            // re-enter it. Not a device-credential change - this key is created with
            // setUserAuthenticationRequired(false), so a lock-screen change does not touch it.
            // The real causes are an uninstall or clear-data (the platform destroys the key), a
            // restore made on a different device, or a different Android user on the same device.
            Diagnostics.event("AiCredentialStore", "decrypt_failed");
            return "";
        }
        return secret;
    }

    /**
     * One stored value, or empty when it is absent or is not a string.
     *
     * <p>Guarded the way {@code SettingsStore.get} guards every typed setting: a restored or
     * hand-edited backup can put a boolean, an integer or a string set under a key this store
     * reads as a string, and {@code SharedPreferences.getString} throws a
     * {@link ClassCastException} for that. A credential that cannot be read is absent, never an
     * exception thrown out of a background reader.
     */
    private String storedValue(String preferenceKey) {
        try {
            return AiText.nz(prefs.getString(preferenceKey, ""));
        } catch (ClassCastException invalidStoredValue) {
            Diagnostics.event("AiCredentialStore", "stored_value_type_mismatch");
            return "";
        }
    }

    /** @return the plaintext of one raw stored value, or null when it cannot be read */
    private String decryptStored(String stored) {
        return AiText.nz(stored).isEmpty() ? null : cipher.decrypt(stored);
    }

    public boolean has(String providerId) {
        return !load(providerId).isEmpty();
    }

    /** Removes the stored secret. The only way one leaves the device short of a factory reset. */
    public void delete(String providerId) {
        if (prefs == null) return;
        prefs.edit().remove(key(AiText.nz(providerId))).apply();
    }

    /** Removes every stored secret and the key material behind them. */
    public void deleteAll() {
        if (prefs != null) prefs.edit().clear().apply();
        if (cipher != null) cipher.clear();
    }

    /**
     * A masked form for display: enough to recognise which key is stored, never enough to use it.
     *
     * <p>Only the last four characters, and only for a key long enough that four characters do not
     * meaningfully narrow it down.
     */
    public static String mask(String secret) {
        String value = AiText.nz(secret);
        if (value.isEmpty()) return "";
        if (value.length() <= 8) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }
}
