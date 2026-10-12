package com.spotifyplusplus.lyrics.processing;

import com.spotifyplusplus.lyrics.session.LayerFailure;

/**
 * Names why a translation run ended without a translation, in the one form every surface can use.
 *
 * <p>The lane knows the backend's own vocabulary - {@code baidu_errno_1022}, {@code parse_empty},
 * {@code cooldown}, a transport exception name - and the surface owning the screen knows which
 * strings resource is on it. This is the crossing point: one classification plus the raw reason
 * code, so the surface names the failure in the reader's language without the lane having to know
 * a resource id.
 *
 * <p>It exists because the lane used to log the reason and stop there: a refused Baidu run settled
 * with an empty artifact, the session marked the layer FAILED, and the reader saw a chip that
 * looked exactly like one that was still working. Nothing on screen said a request had been made
 * and answered.
 */
public final class TranslationFailureNotice {
    /** What went wrong, at the granularity a reader can act on. */
    public enum Kind {
        /** The backend answered and refused the request. */
        REFUSED,
        /** The endpoint's own frequency limit. Kept apart: waiting is the whole remedy. */
        RATE_LIMITED,
        /** An answer arrived and could not be read as translations. */
        MALFORMED,
        /** The call never completed. */
        NETWORK,
        /**
         * The backend was never asked, because its credentials are not stored.
         *
         * <p>Its own kind rather than a refusal: a refusal is the service answering, and the remedy
         * for this one is in the settings panel, not in a retry.
         */
        NOT_CONFIGURED,
        /** Everything else, including a refusal this build has no specific name for. */
        UNAVAILABLE
    }

    public final Kind kind;
    /** Backend token as the device logs spell it: "google", "baidu". */
    public final String backend;
    /** Raw reason code from the run, carried for logs and diagnostics. Never shown verbatim. */
    public final String reason;

    private TranslationFailureNotice(Kind kind, String backend, String reason) {
        this.kind = kind == null ? Kind.UNAVAILABLE : kind;
        this.backend = backend == null ? "" : backend;
        this.reason = reason == null ? "" : reason;
    }

    /**
     * The notice for a settled run, or null when there is nothing to tell the reader.
     *
     * <p>A run that produced some translations is not a failure here: the rows it did produce are
     * on screen, and a notice about the rows it did not would be describing partial success as
     * breakage. Cancellation is not a failure either - the reader or the track change caused it.
     */
    public static TranslationFailureNotice of(LayerFailure failure, String backend) {
        if (failure == null || !failure.isFailure()) return null;
        if (failure.reason == LayerFailure.Reason.CANCELLED) return null;
        // Retiring a run is also how a track change stops it, so the same silence applies.
        if ("cancelled".equals(failure.detail)) return null;
        return new TranslationFailureNotice(kindOf(failure), backend, failure.detail);
    }

    static Kind kindOf(LayerFailure failure) {
        LayerFailure.Reason reason = failure.reason;
        // The limit is read from the status as well as the classification: the Google path answers
        // its own cooldown as a 429 with no rate-limit reason attached, and that answer has to keep
        // the wording the reader already knows.
        if (reason == LayerFailure.Reason.RATE_LIMITED || failure.httpStatus == 429) {
            return Kind.RATE_LIMITED;
        }
        // The now-playing shell rebuilds its failure from the document's stored reason string and
        // has no status, so the backend's own cooldown token has to classify on its own. Without
        // this a cooldown read as "the service refused the request" - the generic failure the owner
        // saw with no way to tell that waiting was the whole remedy.
        if (isRateLimitReason(failure.detail)) return Kind.RATE_LIMITED;
        if (reason == LayerFailure.Reason.TIMEOUT) return Kind.NETWORK;
        if (reason == LayerFailure.Reason.MALFORMED) return Kind.MALFORMED;
        if (reason == LayerFailure.Reason.CLIENT_ERROR
                || reason == LayerFailure.Reason.SERVER_ERROR) return Kind.REFUSED;
        // Checked before the refusal test: the backend names missing credentials with its own
        // reason, and "the service refused the request" would send the reader to the wrong remedy.
        if (isNotConfigured(failure.detail)) return Kind.NOT_CONFIGURED;
        // Baidu's own timeout and system-error codes are the request not completing, which is what
        // the network wording already says; the remaining codes are the service answering.
        if (isBaiduTransient(failure.detail)) return Kind.NETWORK;
        // Connection failures arrive as UNAVAILABLE because the lane records the exception type
        // there, so the type name is what separates a dead network from an unclassified refusal.
        if (isTransportReason(failure.detail)) return Kind.NETWORK;
        // A non-zero error code is the backend answering and saying no, which is the refusal this
        // build must name even when the number itself means nothing to the reader.
        return hasErrno(failure.detail) ? Kind.REFUSED : Kind.UNAVAILABLE;
    }

    /** The backend was never asked: the owner has to store the credentials first. */
    static boolean isNotConfigured(String reason) {
        return BaiduTranslateBackend.REASON_UNCONFIGURED.equals(reason);
    }

    /**
     * The backend's own "requests are held back" token, which is what a run settles with when it
     * was refused before anything was sent.
     *
     * <p>It is the opposite of {@link #isNotConfigured}: a cooldown can only be reported after the
     * credentials check passed. Keeping them apart is what lets the reader be told to wait instead
     * of being sent to the settings panel for a credential that is already stored.
     */
    static boolean isRateLimitReason(String reason) {
        return "cooldown".equals(reason) || "rate_limited".equals(reason);
    }

    /** The two Baidu codes that mean "no answer", not "an answer that says no". */
    static boolean isBaiduTransient(String reason) {
        return BaiduTranslateBackend.REASON_TIMEOUT.equals(reason)
                || BaiduTranslateBackend.REASON_SYSTEM_ERROR.equals(reason);
    }

    static boolean isTransportReason(String reason) {
        return "SocketTimeoutException".equals(reason)
                || "InterruptedIOException".equals(reason)
                || "ConnectException".equals(reason)
                || "UnknownHostException".equals(reason)
                || "SocketException".equals(reason)
                || "SSLException".equals(reason)
                || "SSLHandshakeException".equals(reason)
                || "IOException".equals(reason)
                || "client_unavailable".equals(reason)
                || "joined_request_failed".equals(reason)
                || "failed".equals(reason);
    }

    /**
     * True for a reason that carries Baidu's own error code.
     *
     * <p>Both spellings are accepted: {@code baidu_errno_} is what the retired page endpoint's field
     * was called and what older logs and diagnostics contain, {@code baidu_error_} is the official
     * API's {@code error_code}. A log reader greps the token, not the field name behind it.
     */
    static boolean hasErrno(String reason) {
        if (reason == null) return false;
        String token = "";
        if (reason.startsWith("baidu_errno_")) {
            token = reason.substring("baidu_errno_".length());
        } else if (reason.startsWith("baidu_error_")) {
            token = reason.substring("baidu_error_".length());
        }
        return !token.isEmpty() && !"0".equals(token) && !"missing".equals(token);
    }

    /**
     * Resource name for this notice. Every kind has one, so a surface shows a localized message
     * rather than falling back to English text on a translated build.
     */
    public String resourceKey() {
        switch (kind) {
            case REFUSED: return "lyrics_translation_failed_refused";
            case RATE_LIMITED: return "lyrics_translation_failed_rate_limited";
            case MALFORMED: return "lyrics_translation_failed_unreadable";
            case NETWORK: return "lyrics_translation_failed_network";
            case NOT_CONFIGURED: return "lyrics_translation_failed_not_configured";
            default: return "lyrics_translation_failed_generic";
        }
    }

    /**
     * English sentence for this notice, and the literal stand-in when the strings resource is
     * missing. One line: the reader has to tell "it failed" from "it is still working" at a glance,
     * not read a report.
     */
    public String describe() {
        String name = capitalize(backend);
        String prefix = name.isEmpty() ? "Translation" : name + " translation";
        switch (kind) {
            case REFUSED:
                return prefix + " failed: the service refused the request.";
            case RATE_LIMITED:
                return name.isEmpty() ? "Translation rate limited; retry later"
                        : name + " translation rate limited; retry later";
            case MALFORMED:
                return prefix + " failed: the service's answer could not be read.";
            case NETWORK:
                return prefix + " failed: the network request did not complete.";
            case NOT_CONFIGURED:
                // Names the two values and where they go: the reader has to leave this screen to fix
                // it, so "not configured" alone would be a dead end.
                return prefix + " needs an app ID and secret in Settings.";
            default:
                return prefix + " failed.";
        }
    }

    static String capitalize(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
