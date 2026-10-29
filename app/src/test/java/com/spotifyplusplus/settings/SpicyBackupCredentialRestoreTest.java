package com.spotifyplusplus.settings;

import com.google.gson.JsonObject;
import com.spotifyplusplus.SpotifyPlusConfig;
import com.spotifyplusplus.lyrics.ai.AiCredentialStore;
import com.spotifyplusplus.lyrics.ai.AiSettings;
import com.spotifyplusplus.lyrics.processing.BaiduTranslateCredentials;
import com.spotifyplusplus.lyrics.providers.SpicyOrgKeyStore;
import com.spotifyplusplus.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The credential half of a restore.
 *
 * <p>Two rules are pinned here: a restore never replaces a stored credential that this device can
 * still read with one it cannot, and the owner is told what happened - which keys were kept, and
 * which have to be entered again. Decryption is injected, because a JVM test has no Keystore; the
 * merge rule itself is the code the device runs.
 */
public class SpicyBackupCredentialRestoreTest {

    private static final String GEMINI_KEY = AiCredentialStore.key(AiSettings.PROVIDER_GEMINI);

    /** A device whose readable values are marked: everything else fails to decrypt here. */
    private static final AiCredentialStore.Decryptability DEVICE =
            stored -> stored != null && stored.startsWith("readable:");

    private final FakeAndroidContext context = new FakeAndroidContext();

    // --- the rule ---------------------------------------------------------------------------

    @Test
    public void aWorkingLocalKeyIsNotReplacedByOneThatCannotBeRead() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "readable:local");

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                local, credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext")), DEVICE);

        assertFalse("the unreadable value must not be written", plan.write.has(GEMINI_KEY));
        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), plan.keptScopes);
        assertTrue("a kept key is not one that needs re-entry", plan.reentryScopes.isEmpty());
    }

    @Test
    public void aSameDeviceRestoreStillAppliesAChangedKey() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "readable:older");

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                local, credentialStore(GEMINI_KEY, cell("s", "readable:newer")), DEVICE);

        assertEquals("readable:newer", value(plan.write, GEMINI_KEY));
        assertTrue(plan.keptScopes.isEmpty());
        assertTrue(plan.reentryScopes.isEmpty());
    }

    @Test
    public void anIdenticalValueIsWrittenThroughWithoutBeingReported() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "readable:same");

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                local, credentialStore(GEMINI_KEY, cell("s", "readable:same")), DEVICE);

        assertTrue("identical is not a keep decision", plan.keptScopes.isEmpty());
        assertTrue(plan.reentryScopes.isEmpty());
    }

    @Test
    public void anUnreadableValueWithNothingLocalIsReportedForReentry() {
        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                Collections.emptyMap(),
                credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext")), DEVICE);

        assertEquals("foreign-ciphertext", value(plan.write, GEMINI_KEY));
        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), plan.reentryScopes);
        assertTrue(plan.keptScopes.isEmpty());
    }

    @Test
    public void aReadableIncomingValueReplacesAnUnreadableLocalOne() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "foreign-ciphertext");

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                local, credentialStore(GEMINI_KEY, cell("s", "readable:new")), DEVICE);

        assertEquals("readable:new", value(plan.write, GEMINI_KEY));
        assertTrue(plan.keptScopes.isEmpty());
        assertTrue(plan.reentryScopes.isEmpty());
    }

    @Test
    public void aNonStringValueNeverReplacesAWorkingKey() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "readable:local");
        JsonObject notAString = new JsonObject();
        notAString.addProperty("t", "b");
        notAString.addProperty("v", true);

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                local, credentialStore(GEMINI_KEY, notAString), DEVICE);

        assertFalse(plan.write.has(GEMINI_KEY));
        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), plan.keptScopes);
        assertTrue(plan.reentryScopes.isEmpty());
    }

    @Test
    public void aKeptKeyAndAReentryKeyAreReportedSeparately() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "readable:local");

        JsonObject store = credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext"));
        store.add(AiCredentialStore.key(SpicyOrgKeyStore.SCOPE), cell("s", "foreign-org-key"));

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(local, store, DEVICE);

        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), plan.keptScopes);
        assertEquals(Collections.singletonList(SpicyOrgKeyStore.SCOPE), plan.reentryScopes);
    }

    @Test
    public void nothingIsReportedWhenThereIsNothingToReport() {
        SpicyBackup.CredentialPlan empty = SpicyBackup.planCredentialRestore(
                Collections.emptyMap(), new JsonObject(), DEVICE);
        assertTrue(empty.write.isEmpty());
        assertTrue(empty.keptScopes.isEmpty());
        assertTrue(empty.reentryScopes.isEmpty());

        // A version-1 document carries no credential store at all.
        SpicyBackup.CredentialPlan absent =
                SpicyBackup.planCredentialRestore(Collections.emptyMap(), null, DEVICE);
        assertTrue(absent.write.isEmpty());
        assertTrue(absent.keptScopes.isEmpty());
        assertTrue(absent.reentryScopes.isEmpty());
    }

    @Test
    public void withoutADecryptabilityTheMergeWritesThrough() {
        Map<String, Object> local = new HashMap<>();
        local.put(GEMINI_KEY, "readable:local");

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                local, credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext")), null);

        assertEquals("foreign-ciphertext", value(plan.write, GEMINI_KEY));
        assertTrue(plan.keptScopes.isEmpty());
        assertTrue(plan.reentryScopes.isEmpty());
    }

    @Test
    public void aNonCredentialEntryIsWrittenWithoutCredentialBookkeeping() {
        JsonObject store = credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext"));
        store.add("some_other_key", cell("s", "anything"));

        SpicyBackup.CredentialPlan plan = SpicyBackup.planCredentialRestore(
                Collections.emptyMap(), store, DEVICE);

        assertTrue(plan.write.has("some_other_key"));
        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), plan.reentryScopes);
    }

    // --- the rule as the restore actually applies it -----------------------------------------

    @Test
    public void restoreKeepsTheLocalKeyAndReportsIt() {
        putCredential(GEMINI_KEY, "readable:local");

        SpicyBackup.RestoreOutcome outcome = SpicyBackup.applyRestore(context,
                document(2, twoStores(new JsonObject(),
                        credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext")))),
                null, DEVICE);

        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), outcome.keptScopes);
        assertTrue(outcome.reentryScopes.isEmpty());
        assertEquals("readable:local", storedCredential(GEMINI_KEY));
    }

    @Test
    public void restoreOverwritesWhenTheIncomingValueIsReadableHere() {
        putCredential(GEMINI_KEY, "readable:older");

        SpicyBackup.RestoreOutcome outcome = SpicyBackup.applyRestore(context,
                document(2, twoStores(new JsonObject(),
                        credentialStore(GEMINI_KEY, cell("s", "readable:newer")))),
                null, DEVICE);

        assertTrue(outcome.keptScopes.isEmpty());
        assertTrue(outcome.reentryScopes.isEmpty());
        assertEquals("readable:newer", storedCredential(GEMINI_KEY));
    }

    @Test
    public void restoreReportsAKeyTheOwnerHasToEnterAgain() {
        SpicyBackup.RestoreOutcome outcome = SpicyBackup.applyRestore(context,
                document(2, twoStores(new JsonObject(),
                        credentialStore(GEMINI_KEY, cell("s", "foreign-ciphertext")))),
                null, DEVICE);

        assertEquals(Collections.singletonList(AiSettings.PROVIDER_GEMINI), outcome.reentryScopes);
        assertTrue(outcome.keptScopes.isEmpty());
        assertEquals("foreign-ciphertext", storedCredential(GEMINI_KEY));
    }

    @Test
    public void versionOneDocumentStillRestoresItsStores() {
        JsonObject settings = new JsonObject();
        settings.add("lyrics_source_order", cell("s", "Spotify,LRCLIB"));

        SpicyBackup.RestoreOutcome outcome = SpicyBackup.applyRestore(context,
                document(1, onlySettings(settings)), null, DEVICE);

        assertEquals("a version-1 file is still a usable backup", 1, outcome.written);
        assertEquals("Spotify,LRCLIB",
                context.file(SpotifyPlusConfig.PREFS_NAME).getString("lyrics_source_order", ""));
        assertTrue(outcome.keptScopes.isEmpty());
        assertTrue(outcome.reentryScopes.isEmpty());
    }

    @Test
    public void aDocumentFromANewerFormatIsRefused() {
        SpicyBackup.RestoreOutcome outcome = SpicyBackup.applyRestore(context,
                document(SpicyBackup.FORMAT_VERSION + 1, new JsonObject()), null, DEVICE);

        assertEquals(-1, outcome.written);
    }

    // --- the scope names, pinned to their owners ---------------------------------------------

    @Test
    public void everyOwnedCredentialScopeIsOneTheStoreRecognises() {
        // The provider -> scope mapping itself is owned and covered by AiProviderCredentialScopeTest
        // in the lyrics.ai package, where AiSettings' package-private credentialScopeFor lives. This
        // test's job is the backup question: that every secret a restore may have to report is one
        // the credential store recognises, and that no two of them collide.
        List<String> scopes = new ArrayList<>(Arrays.asList(
                "gemini",
                "openai_official",
                "openrouter",
                "deepseek",
                "custom",
                BaiduTranslateCredentials.APP_ID_SCOPE,
                BaiduTranslateCredentials.SECRET_SCOPE,
                SpicyOrgKeyStore.SCOPE));

        Set<String> keys = new HashSet<>();
        for (String scope : scopes) {
            String key = AiCredentialStore.key(scope);
            assertTrue(key, AiCredentialStore.isCredentialKey(key));
            assertEquals(scope, AiCredentialStore.scopeOf(key));
            assertTrue("duplicate scope " + scope, keys.add(key));
        }
        // Five providers, the Baidu pair and the SpicyLyrics.org key. A new secret added to any
        // store must be named here, or a restore cannot report it.
        assertEquals(8, scopes.size());
    }

    @Test
    public void theOutcomeKnowsWhenThereIsSomethingToReport() {
        assertNotNull(new SpicyBackup.RestoreOutcome(3, null, null));
        assertFalse(new SpicyBackup.RestoreOutcome(3, null, null).hasCredentialNotice());
        assertTrue(new SpicyBackup.RestoreOutcome(3, Collections.singletonList("gemini"), null)
                .hasCredentialNotice());
        assertTrue(new SpicyBackup.RestoreOutcome(3, null, Collections.singletonList("gemini"))
                .hasCredentialNotice());
    }

    // --- helpers -----------------------------------------------------------------------------

    private FakeAndroidContext.MapPrefs credentialStoreFile() {
        return context.file(AiCredentialStore.PREFS);
    }

    private void putCredential(String key, String value) {
        credentialStoreFile().edit().putString(key, value).apply();
    }

    private String storedCredential(String key) {
        return credentialStoreFile().getString(key, "");
    }

    private static JsonObject credentialStore(String key, JsonObject cell) {
        JsonObject store = new JsonObject();
        store.add(key, cell);
        return store;
    }

    private static JsonObject onlySettings(JsonObject settings) {
        JsonObject stores = new JsonObject();
        stores.add(SpotifyPlusConfig.PREFS_NAME, settings);
        return stores;
    }

    private static JsonObject twoStores(JsonObject settings, JsonObject credentials) {
        JsonObject stores = onlySettings(settings);
        stores.add(AiCredentialStore.PREFS, credentials);
        return stores;
    }

    private static JsonObject cell(String type, String value) {
        JsonObject cell = new JsonObject();
        cell.addProperty("t", type);
        cell.addProperty("v", value);
        return cell;
    }

    private static String value(JsonObject store, String key) {
        if (!store.has(key)) return null;
        return store.get(key).getAsJsonObject().get("v").getAsString();
    }

    private static String document(int version, JsonObject stores) {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("exportedAt", 1L);
        root.add("stores", stores);
        return root.toString();
    }
}
