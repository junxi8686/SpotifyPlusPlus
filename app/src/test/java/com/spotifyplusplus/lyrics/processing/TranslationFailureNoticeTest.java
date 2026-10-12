package com.spotifyplusplus.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spotifyplusplus.lyrics.session.LayerFailure;

import org.junit.Test;

/**
 * What the reader is told when a translation run ends without a translation.
 *
 * <p>The defect this covers was silence: a Baidu run refused with {@code errno 1022} settled as an
 * empty artifact and nothing reached the screen, so a refusal and a run still in flight looked
 * identical. These assert the mapping only - the lane's own behaviour is covered next to it - which
 * is why every case runs without a device.
 */
public final class TranslationFailureNoticeTest {

    @Test public void baiduRefusalIsNamedAsARefusal() {
        TranslationFailureNotice notice = TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "baidu_errno_1022", 200), "baidu");

        assertNotNull("a refused run must produce a notice", notice);
        assertEquals(TranslationFailureNotice.Kind.REFUSED, notice.kind);
        assertEquals("lyrics_translation_failed_refused", notice.resourceKey());
        assertEquals("Baidu translation failed: the service refused the request.",
                notice.describe());
    }

    /** The official API's own error codes carry the same verdict as the retired endpoint's errno. */
    @Test public void baiduErrorCodesAreRefusals() {
        for (String reason : new String[]{"baidu_error_52003", "baidu_error_54001",
                "baidu_error_58000", "baidu_error_54004"}) {
            TranslationFailureNotice notice = TranslationFailureNotice.of(
                    new LayerFailure(LayerFailure.Reason.UNAVAILABLE, reason, 0), "baidu");

            assertNotNull(reason, notice);
            assertEquals(reason, TranslationFailureNotice.Kind.REFUSED, notice.kind);
            assertEquals(reason, "lyrics_translation_failed_refused", notice.resourceKey());
        }
    }

    /**
     * Baidu's two transient codes are the request not completing, not the service saying no.
     *
     * <p>A reader told "the service refused the request" for a timeout goes looking for a problem
     * with their key; the network wording is the one that matches what happened.
     */
    @Test public void baiduTimeoutAndSystemErrorReadAsNetworkFailures() {
        for (String reason : new String[]{"baidu_error_52001", "baidu_error_52002"}) {
            TranslationFailureNotice notice = TranslationFailureNotice.of(
                    new LayerFailure(LayerFailure.Reason.UNAVAILABLE, reason, 0), "baidu");

            assertNotNull(reason, notice);
            assertEquals(reason, TranslationFailureNotice.Kind.NETWORK, notice.kind);
            assertEquals(reason, "lyrics_translation_failed_network", notice.resourceKey());
        }
    }

    /**
     * Missing credentials are their own outcome.
     *
     * <p>Nothing was asked, so nothing was refused, and the remedy is in the settings panel rather
     * than in a retry: the notice has to say which two values are missing.
     */
    @Test public void missingCredentialsNameTheSetupRatherThanARefusal() {
        TranslationFailureNotice notice = TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "baidu_unconfigured", 0), "baidu");

        assertNotNull(notice);
        assertEquals(TranslationFailureNotice.Kind.NOT_CONFIGURED, notice.kind);
        assertEquals("lyrics_translation_failed_not_configured", notice.resourceKey());
        assertEquals("Baidu translation needs an app ID and secret in Settings.",
                notice.describe());
        // The same reason with no backend named still has to read as a sentence.
        assertEquals("Translation needs an app ID and secret in Settings.",
                TranslationFailureNotice.of(
                        new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "baidu_unconfigured", 0),
                        "").describe());
    }

    /**
     * The endpoint reports its frequency limit inside a 200, which the backend reports as 429. That
     * one keeps the wording it already shipped with, so a reader who saw it before sees it again.
     */
    @Test public void rateLimitKeepsItsExistingWording() {
        // Baidu: errno 54003 inside a 200, reported up as a limit.
        TranslationFailureNotice baidu = TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.RATE_LIMITED, "baidu_errno_54003", 429),
                "baidu");
        // Google: its own cooldown answers 429 with no rate-limit reason attached, which is how the
        // lane's own settlement builds it. Reading only the reason turned that into "failed".
        LayerFailure google = new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "cooldown", 429);
        TranslationFailureNotice googleNotice =
                TranslationFailureNotice.of(google, "google");

        assertNotNull(baidu);
        assertNotNull(googleNotice);
        assertEquals(TranslationFailureNotice.Kind.RATE_LIMITED, baidu.kind);
        assertEquals(TranslationFailureNotice.Kind.RATE_LIMITED, googleNotice.kind);
        assertEquals("lyrics_translation_failed_rate_limited", baidu.resourceKey());
        assertEquals("lyrics_translation_failed_rate_limited", googleNotice.resourceKey());
        assertEquals("Baidu translation rate limited; retry later", baidu.describe());
        assertEquals("Google translation rate limited; retry later", googleNotice.describe());
    }

    /**
     * The same cooldown, as the now-playing shell rebuilds it: from the document's stored reason
     * string alone, with no status and no rate-limit classification. That is the shape the reader
     * actually got, and it used to fall through to "the service refused the request" - a failure
     * with no hint that waiting is the remedy.
     */
    @Test public void aCooldownWithoutAStatusStillReadsAsARateLimit() {
        TranslationFailureNotice notice = TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "cooldown", 0), "baidu");

        assertNotNull(notice);
        assertEquals(TranslationFailureNotice.Kind.RATE_LIMITED, notice.kind);
        assertEquals("lyrics_translation_failed_rate_limited", notice.resourceKey());
        assertEquals("Baidu translation rate limited; retry later", notice.describe());
        // Still told apart from the one state whose remedy is in the settings panel.
        assertEquals(TranslationFailureNotice.Kind.NOT_CONFIGURED,
                TranslationFailureNotice.of(
                        new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "baidu_unconfigured", 0),
                        "baidu").kind);
    }

    @Test public void unreadableAnswerSaysTheAnswerCouldNotBeRead() {
        for (String reason : new String[]{"parse_empty", "partial_parse"}) {
            TranslationFailureNotice notice = TranslationFailureNotice.of(
                    new LayerFailure(LayerFailure.Reason.MALFORMED, reason, 0), "google");

            assertNotNull(reason, notice);
            assertEquals(reason, TranslationFailureNotice.Kind.MALFORMED, notice.kind);
            assertEquals(reason, "lyrics_translation_failed_unreadable", notice.resourceKey());
            assertEquals(reason, "Google translation failed: the service's answer could not be read.",
                    notice.describe());
        }
    }

    /** A call that never landed is a network failure even when no status came back with it. */
    @Test public void transportFailuresReadAsNetworkFailures() {
        LayerFailure timeout = new LayerFailure(LayerFailure.Reason.TIMEOUT,
                "SocketTimeoutException", 0);
        LayerFailure io = new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "IOException", 0);
        LayerFailure refused = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                "ConnectException", 0);
        LayerFailure clientGone = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                "client_unavailable", 0);

        assertEquals(TranslationFailureNotice.Kind.NETWORK,
                TranslationFailureNotice.of(timeout, "baidu").kind);
        assertEquals(TranslationFailureNotice.Kind.NETWORK,
                TranslationFailureNotice.of(io, "baidu").kind);
        assertEquals(TranslationFailureNotice.Kind.NETWORK,
                TranslationFailureNotice.of(refused, "baidu").kind);
        assertEquals(TranslationFailureNotice.Kind.NETWORK,
                TranslationFailureNotice.of(clientGone, "baidu").kind);
        assertEquals("lyrics_translation_failed_network",
                TranslationFailureNotice.of(io, "baidu").resourceKey());
        assertEquals("Baidu translation failed: the network request did not complete.",
                TranslationFailureNotice.of(io, "baidu").describe());
    }

    /** A wire status is the backend answering, so a 4xx or 5xx is a refusal whatever it carried. */
    @Test public void httpRefusalsAreRefusalsEvenWithNoSpecificReason() {
        LayerFailure server = LayerFailure.http(503);
        LayerFailure client = LayerFailure.http(403);

        assertEquals(TranslationFailureNotice.Kind.REFUSED,
                TranslationFailureNotice.of(server, "google").kind);
        assertEquals(TranslationFailureNotice.Kind.REFUSED,
                TranslationFailureNotice.of(client, "google").kind);
        assertEquals("Google translation failed: the service refused the request.",
                TranslationFailureNotice.of(server, "google").describe());
    }

    /** No reason at all still has to say something: an unknown failure is not silence. */
    @Test public void unclassifiedFailureStillProducesANotice() {
        TranslationFailureNotice notice = TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "null_result", 0), "baidu");

        assertNotNull(notice);
        assertEquals(TranslationFailureNotice.Kind.UNAVAILABLE, notice.kind);
        assertEquals("lyrics_translation_failed_generic", notice.resourceKey());
        assertTrue(notice.describe().startsWith("Baidu translation failed"));
    }

    /** Nothing to report: not a failure, and not a cancellation the reader caused. */
    @Test public void cleanSettlementAndCancellationStaySilent() {
        assertNull(TranslationFailureNotice.of(LayerFailure.NONE, "baidu"));
        assertNull(TranslationFailureNotice.of(null, "baidu"));
        assertNull(TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.CANCELLED, "cancelled", 0), "baidu"));
        assertNull(TranslationFailureNotice.of(
                new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "cancelled", 0), "baidu"));
    }

    /**
     * The resource name has to exist in the default strings, or a translated build falls back to
     * the English sentence. The contract test owns the file, so this only guards the four names.
     */
    @Test public void everyKindNamesADistinctResource() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (TranslationFailureNotice.Kind kind : TranslationFailureNotice.Kind.values()) {
            TranslationFailureNotice notice = TranslationFailureNotice.of(
                    failureFor(kind), "baidu");
            assertNotNull(kind.name(), notice);
            assertEquals(kind.name(), kind, notice.kind);
            assertTrue(kind.name(), notice.resourceKey().startsWith("lyrics_translation_failed_"));
            assertTrue(kind.name() + " must not share a message with another kind",
                    keys.add(notice.resourceKey()));
        }
        assertFalse(keys.isEmpty());
    }

    private static LayerFailure failureFor(TranslationFailureNotice.Kind kind) {
        switch (kind) {
            case RATE_LIMITED:
                return new LayerFailure(LayerFailure.Reason.RATE_LIMITED, "baidu_errno_54003", 429);
            case REFUSED:
                return new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "baidu_errno_1022", 200);
            case MALFORMED:
                return new LayerFailure(LayerFailure.Reason.MALFORMED, "parse_empty", 0);
            case NETWORK:
                return new LayerFailure(LayerFailure.Reason.TIMEOUT, "SocketTimeoutException", 0);
            case NOT_CONFIGURED:
                return new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "baidu_unconfigured", 0);
            default:
                return new LayerFailure(LayerFailure.Reason.UNAVAILABLE, "null_result", 0);
        }
    }
}
