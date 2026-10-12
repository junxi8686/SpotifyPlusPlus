package com.spotifyplusplus.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.Test;

/**
 * What the settings panel will and will not accept as a Baidu credential.
 *
 * <p>The shape checks are the whole point of this class: a value that is stored but cannot sign a
 * request turns one paste error into an authentication failure on a song much later, where its cause
 * is no longer visible. The encryption itself is {@code AiCredentialStore}'s and is covered next to
 * it; these cases only assert that nothing reaches it that should not.
 */
public class BaiduTranslateCredentialsTest {

    @Test public void acceptsWhatBaiduIssues() {
        assertTrue(BaiduTranslateCredentials.isAppIdShape("2015063000000001"));
        assertTrue(BaiduTranslateCredentials.isAppIdShape(" 2015063000000001 "));
        assertTrue(BaiduTranslateCredentials.isSecretShape("12345678"));
        assertTrue(BaiduTranslateCredentials.isSecretShape("kZ8xQ2-w_9Lm4Rt7Vb1Nc6Pd3Sf5Gh0J"));
    }

    @Test public void rejectsWhatIsObviouslyNotAnAppId() {
        assertFalse(BaiduTranslateCredentials.isAppIdShape(""));
        assertFalse(BaiduTranslateCredentials.isAppIdShape(null));
        assertFalse(BaiduTranslateCredentials.isAppIdShape("2015 0630"));
        // A pasted line from the console rather than the value itself.
        assertFalse(BaiduTranslateCredentials.isAppIdShape("APP ID: 2015063000000001"));
        assertFalse(BaiduTranslateCredentials.isAppIdShape("https://fanyi-api.baidu.com/"));
    }

    @Test public void rejectsWhatIsObviouslyNotASecret() {
        assertFalse(BaiduTranslateCredentials.isSecretShape(""));
        assertFalse(BaiduTranslateCredentials.isSecretShape(null));
        // Truncated paste.
        assertFalse(BaiduTranslateCredentials.isSecretShape("12ab"));
        // A newline in the middle is not whitespace to trim: it would be signed as part of the key.
        assertFalse(BaiduTranslateCredentials.isSecretShape("1234\n5678"));
    }

    /**
     * The console's copy button appends a newline, so the check trims before it validates and both
     * storage and the request builder only ever see the trimmed form - which is why one stray
     * character cannot silently change every signature.
     */
    @Test public void trailingWhitespaceFromACopyButtonIsTrimmedNotSigned() {
        assertTrue(BaiduTranslateCredentials.isSecretShape("12345678\n"));
        assertTrue(BaiduTranslateCredentials.isAppIdShape("2015063000000001\n"));
        assertFalse(BaiduTranslateCredentials.isSecretShape("12345678 " + "x"));
    }

    @Test public void masksAllButTheLastFourCharacters() {
        assertEquals("", BaiduTranslateCredentials.masked(""));
        assertEquals("", BaiduTranslateCredentials.masked(null));
        // Short values reveal nothing at all.
        assertEquals("••••", BaiduTranslateCredentials.masked("12345678"));
        assertEquals("••••0001", BaiduTranslateCredentials.masked("2015063000000001"));
    }

    /** Nothing is read or written without a context, which is the JVM test's own situation. */
    @Test public void withoutAContextNothingIsStoredOrLoaded() {
        assertFalse(BaiduTranslateCredentials.has(null));
        assertEquals("", BaiduTranslateCredentials.appId(null));
        assertEquals("", BaiduTranslateCredentials.secret(null));
        assertFalse(BaiduTranslateCredentials.saveAppId(null, "2015063000000001"));
        assertFalse(BaiduTranslateCredentials.saveSecret(null, "12345678"));
        BaiduTranslateCredentials.deleteAppId(null);
        BaiduTranslateCredentials.deleteSecret(null);
    }

    @Test public void aFreshContextHasNoCredentials() {
        FakeAndroidContext context = new FakeAndroidContext();

        assertFalse(BaiduTranslateCredentials.has(context));
        assertTrue(BaiduTranslateCredentials.appId(context).isEmpty());
        assertTrue(BaiduTranslateCredentials.secret(context).isEmpty());
    }

    /** Validation happens before storage, so a rejected value never reaches the cipher. */
    @Test public void aRejectedValueIsNotStored() {
        FakeAndroidContext context = new FakeAndroidContext();

        assertFalse(BaiduTranslateCredentials.saveAppId(context, "APP ID: 1234"));
        assertFalse(BaiduTranslateCredentials.saveSecret(context, "no"));

        assertFalse(BaiduTranslateCredentials.has(context));
    }
}
