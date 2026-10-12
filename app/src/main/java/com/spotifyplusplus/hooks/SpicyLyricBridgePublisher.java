package com.spotifyplusplus.hooks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import com.eza.hyperglow.bridge.ISpicyLyricBridge;
import com.spotifyplusplus.Diagnostics;

import com.spotifyplusplus.xposed.XpLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileOutputStream;
import java.io.IOException;

final class SpicyLyricBridgePublisher {
    static final int PROTOCOL_VERSION = 1;
    private static final String CONSUMER_PACKAGE = "com.eza.hyperglow";
    private static final ComponentName BRIDGE_COMPONENT = new ComponentName(
            CONSUMER_PACKAGE,
            "com.eza.hyperglow.bridge.SpicyLyricBridgeService"
    );
    private static final Uri BRIDGE_URI = Uri.parse(
            "content://com.eza.hyperglow.spicybridge/session"
    );

    private final Context context;
    private final java.util.concurrent.ThreadPoolExecutor providerExecutor =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(2));
    private String latestProviderMethod;
    private Bundle latestProviderPayload;
    private boolean providerDrainQueued;
    private ISpicyLyricBridge bridge;
    private Bundle retainedState;
    private String pendingClearProducerId;
    private long pendingClearGeneration;
    private final SpicyBridgeReplayState stateReplayState = new SpicyBridgeReplayState();
    private final SpicyBridgeReplayState documentReplayState = new SpicyBridgeReplayState();
    private Bundle retainedDocumentMetadata;
    private byte[] retainedDocument;
    private boolean binding;
    private boolean bound;
    private boolean providerFallback;
    private boolean enabled;
    /** Consecutive failed attempts, and the time before which none may be made. */
    private int failureAttempts;
    private long retryAtMs;
    /** The consumer's package as last probed, and when. Zero means it has never been probed. */
    private boolean consumerPresent = true;
    private long presenceCheckedAtMs;

    SpicyLyricBridgePublisher(Context context) {
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
    }

    synchronized void enable() {
        enabled = true;
        // A fresh enable starts a fresh ladder and re-probes the consumer, so toggling the setting
        // is never answered with a backoff earned before it.
        failureAttempts = 0;
        retryAtMs = 0;
        presenceCheckedAtMs = 0;
        Diagnostics.setHyperGlowBridgeStatus("connecting");
        Diagnostics.event("hyperglow_bridge", "connection_state",
                Diagnostics.context("status", "connecting"));
        connect();
    }

    private synchronized void connect() {
        if (!enabled) return;
        if (bridge != null || binding || providerFallback) return;
        long now = SystemClock.elapsedRealtime();
        if (!SpicyBridgeBackoff.attemptDue(now, retryAtMs)) return;
        if (!consumerReady(now)) return;
        binding = true;
        try {
            Intent intent = new Intent().setComponent(BRIDGE_COMPONENT);
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                providerFallback = true;
                Diagnostics.setHyperGlowBridgeStatus("provider_fallback");
                Diagnostics.event("hyperglow_bridge", "connection_state",
                        Diagnostics.context("status", "provider_fallback"));
                noteAttemptFailure(now, "bindService returned false",
                        "component=" + BRIDGE_COMPONENT.flattenToShortString()
                                + " hyperglow=" + (consumerPresent ? "present" : "absent"), null);
                publishPendingLocked();
            } else {
                bound = true;
            }
        } catch (Throwable t) {
            binding = false;
            noteAttemptFailure(now, "bindService threw",
                    "component=" + BRIDGE_COMPONENT.flattenToShortString(), t);
        }
    }

    /**
     * True when the consumer package may be attempted, false while it is known absent.
     *
     * <p>A refused bind and an unknown provider uri are the same fact - the companion app is not
     * installed - so one probe of the package list answers both, instead of two failing IPC calls
     * per publication. The negative answer is cached for a minute, which is also how quickly
     * installing the companion later is noticed.
     */
    private boolean consumerReady(long nowMs) {
        if (!SpicyBridgeBackoff.presenceCheckDue(nowMs, presenceCheckedAtMs)) return consumerPresent;
        presenceCheckedAtMs = nowMs;
        boolean present = isConsumerInstalled();
        if (present != consumerPresent || !present) {
            XpLog.log("[SpotifyPlusBridge] consumer " + (present ? "present" : "absent")
                    + " package=" + CONSUMER_PACKAGE
                    + (present ? "" : " retryInMs=" + SpicyBridgeBackoff.PRESENCE_TTL_MS));
        }
        consumerPresent = present;
        return present;
    }

    /** The package list, not an IPC call: the cheap half of the diagnosis. */
    private boolean isConsumerInstalled() {
        try {
            context.getPackageManager().getPackageInfo(CONSUMER_PACKAGE, 0);
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * Records a failed attempt: it schedules the next one and emits one line that names the call
     * site, the argument that was rejected and the exception's own message.
     *
     * <p>The message used to be discarded, which is why a run of identical lines said only that
     * something was refused. {@code IllegalArgumentException} from a provider call is Android's
     * "Unknown URI" - the authority has no registered provider - and that text is the diagnosis.
     */
    private synchronized void noteAttemptFailure(long nowMs, String what, String detail, Throwable t) {
        failureAttempts++;
        long delay = SpicyBridgeBackoff.delayMs(failureAttempts - 1);
        retryAtMs = nowMs + delay;
        XpLog.log("[SpotifyPlusBridge] " + what + " " + detail
                + " attempt=" + failureAttempts
                + " retryInMs=" + delay
                + " msg=" + describeMessage(t));
        Diagnostics.event("hyperglow_bridge", "publication_result", t,
                Diagnostics.context("result", "error", "status", "attempt_failed"));
    }

    /** A successful exchange clears the ladder and says so, once. */
    private synchronized void noteAttemptSuccess(String what) {
        if (failureAttempts > 0) {
            XpLog.log("[SpotifyPlusBridge] " + what + " recovered afterAttempts=" + failureAttempts);
        }
        failureAttempts = 0;
        retryAtMs = 0;
    }

    synchronized void publish(Bundle state) {
        if (!enabled) return;
        retainedState = new Bundle(state);
        stateReplayState.retainPayload();
        pendingClearProducerId = null;
        if (bridge == null) {
            if (providerFallback) publishPendingLocked(); else connect();
            return;
        }
        publishPendingLocked();
    }

    synchronized void clear(String producerId, long generation) {
        clearRetainedStateLocked();
        pendingClearProducerId = producerId;
        pendingClearGeneration = generation;
        clearRetainedDocumentLocked();
        if (bridge == null) {
            if (providerFallback) publishPendingLocked(); else connect();
            return;
        }
        publishPendingLocked();
    }

    synchronized void publishDocument(Bundle metadata, byte[] compressedDocument) {
        if (!enabled) {
            documentSkipped("publisher_disabled", 0);
            return;
        }
        if (metadata == null || compressedDocument == null ||
                compressedDocument.length > SpicyLyricBridgeDocumentSerializer.MAX_COMPRESSED_BYTES) {
            documentSkipped(metadata == null ? "metadata_missing"
                    : compressedDocument == null ? "payload_missing" : "payload_oversized",
                    compressedDocument == null ? 0 : compressedDocument.length);
            return;
        }
        retainedDocumentMetadata = new Bundle(metadata);
        retainedDocument = compressedDocument.clone();
        documentReplayState.retainPayload();
        if (bridge == null) {
            connect();
            return;
        }
        publishPendingDocumentLocked();
    }

    private static void documentSkipped(String reason, int bytes) {
        Diagnostics.event("hyperglow_bridge", "document_skipped",
                Diagnostics.context("reason", reason, "bytes", String.valueOf(bytes)));
    }

    synchronized void clearAndDisconnect(String producerId, long generation) {
        enabled = false;
        clearRetainedStateLocked();
        pendingClearProducerId = producerId;
        pendingClearGeneration = generation;
        clearRetainedDocumentLocked();
        if (bridge != null) {
            publishPendingLocked();
        } else {
            providerFallback = true;
            publishPendingProviderLocked();
        }
        bridge = null;
        binding = false;
        providerFallback = false;
        Diagnostics.setHyperGlowBridgeStatus("disabled");
        Diagnostics.event("hyperglow_bridge", "connection_state",
                Diagnostics.context("status", "disabled"));
        unbindLocked();
    }

    private void publishPendingDocumentLocked() {
        long revision = documentReplayState.pendingRevision();
        if (bridge == null || revision == 0L
                || retainedDocumentMetadata == null || retainedDocument == null) return;
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readSide = pipe[0];
            ParcelFileDescriptor writeSide = pipe[1];
            bridge.publishDocument(new Bundle(retainedDocumentMetadata), readSide);
            readSide.close();
            byte[] payload = retainedDocument;
            providerExecutor.execute(() -> writeDocument(writeSide, payload));
            documentReplayState.markPublished(revision);
        } catch (Throwable t) {
            if (pipe != null) {
                try { pipe[0].close(); } catch (IOException ignored) {}
                try { pipe[1].close(); } catch (IOException ignored) {}
            }
            log("document publish failed", t);
            if (t instanceof RemoteException) handleDisconnectLocked(t);
        }
    }

    private void clearRetainedDocumentLocked() {
        retainedDocumentMetadata = null;
        retainedDocument = null;
        documentReplayState.clearPayload();
    }

    private void clearRetainedStateLocked() {
        retainedState = null;
        stateReplayState.clearPayload();
    }

    private void publishPendingLocked() {
        if (providerFallback) {
            publishPendingProviderLocked();
            return;
        }
        if (bridge == null) return;
        try {
            long stateRevision = stateReplayState.pendingRevision();
            if (stateRevision != 0L && retainedState != null) {
                bridge.publishState(new Bundle(retainedState));
                stateReplayState.markPublished(stateRevision);
            } else if (pendingClearProducerId != null) {
                bridge.clearState(pendingClearProducerId, pendingClearGeneration);
                pendingClearProducerId = null;
            }
            publishPendingDocumentLocked();
        } catch (RemoteException e) {
            handleDisconnectLocked(e);
        }
    }

    private void publishPendingProviderLocked() {
        long stateRevision = stateReplayState.pendingRevision();
        if (stateRevision != 0L && retainedState != null) {
            Bundle payload = new Bundle();
            payload.putBundle("state", new Bundle(retainedState));
            queueProvider("publish", payload);
            stateReplayState.markPublished(stateRevision);
        } else if (pendingClearProducerId != null) {
            Bundle payload = new Bundle();
            payload.putString("producerId", pendingClearProducerId);
            payload.putLong("generation", pendingClearGeneration);
            queueProvider("clear", payload);
            pendingClearProducerId = null;
        }
    }

    /** Only the newest pending state survives a slow provider call. */
    private synchronized void queueProvider(String method, Bundle payload) {
        latestProviderMethod = method;
        latestProviderPayload = payload;
        if (providerDrainQueued) return;
        providerDrainQueued = true;
        try {
            providerExecutor.execute(() -> {
                while (true) {
                    String nextMethod;
                    Bundle nextPayload;
                    synchronized (SpicyLyricBridgePublisher.this) {
                        if (latestProviderPayload == null) {
                            providerDrainQueued = false;
                            return;
                        }
                        nextMethod = latestProviderMethod;
                        nextPayload = latestProviderPayload;
                        latestProviderPayload = null;
                    }
                    callProvider(nextMethod, nextPayload);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            providerDrainQueued = false;
            // Preserve the latest state for the next publication; never queue an unbounded backlog.
        }
    }

    private void callProvider(String method, Bundle payload) {
        long now = SystemClock.elapsedRealtime();
        if (!SpicyBridgeBackoff.attemptDue(now, retryAtMs)) return;
        // Known absent: no IPC is attempted at all, and the presence probe paces the retries, so a
        // device without the companion app gets one line a minute instead of a failing call each.
        if (!consumerReady(now)) return;
        try {
            context.getContentResolver().call(BRIDGE_URI, method, null, payload);
            noteAttemptSuccess("provider call");
            Diagnostics.setHyperGlowBridgeStatus("provider_connected");
            Diagnostics.event("hyperglow_bridge", "publication_result",
                    Diagnostics.context("result", "success", "status", "provider"));
            synchronized (this) {
                if (enabled && providerFallback) {
                    providerFallback = false;
                    connect();
                }
            }
        } catch (Throwable t) {
            // Deliberately no rebind here. Reconnecting on the spot is what turned two failures
            // into a cycle: the bind refused, the provider call failed, the bind was attempted
            // again from inside the failure handler, and the pair repeated per publication.
            noteAttemptFailure(now, "provider call failed",
                    "site=callProvider method=" + method + " uri=" + BRIDGE_URI
                            + " bytes=" + (payload == null ? 0 : payloadSize(payload)), t);
            Diagnostics.setHyperGlowBridgeStatus("provider_failed");
            Diagnostics.event("hyperglow_bridge", "publication_result", t,
                    Diagnostics.context("result", "error", "status", "provider"));
        }
    }

    /** Rough payload size for the failure line; the parcel is not rewritten to measure it. */
    private static int payloadSize(Bundle payload) {
        try {
            android.os.Parcel parcel = android.os.Parcel.obtain();
            try {
                parcel.writeBundle(payload);
                return parcel.dataSize();
            } finally {
                parcel.recycle();
            }
        } catch (Throwable unmeasurable) {
            return -1;
        }
    }

    private void writeDocument(ParcelFileDescriptor destination, byte[] payload) {
        try (ParcelFileDescriptor fd = destination;
             FileOutputStream output = new FileOutputStream(fd.getFileDescriptor())) {
            output.write(payload);
        } catch (Throwable t) {
            log("document pipe failed", t);
        }
    }

    private void handleDisconnectLocked(Throwable cause) {
        bridge = null;
        binding = false;
        unbindLocked();
        log("bridge disconnected", cause);
        Diagnostics.setHyperGlowBridgeStatus("disconnected");
        Diagnostics.event("hyperglow_bridge", "connection_state", cause,
                Diagnostics.context("status", "disconnected"));
        if (enabled) connect();
    }

    private void unbindLocked() {
        if (!bound) return;
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
        }
        bound = false;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (SpicyLyricBridgePublisher.this) {
                binding = false;
                if (!enabled) {
                    unbindLocked();
                    return;
                }
                stateReplayState.onConnectionOpened();
                documentReplayState.onConnectionOpened();
                bridge = ISpicyLyricBridge.Stub.asInterface(service);
                Diagnostics.setHyperGlowBridgeStatus("connected");
                Diagnostics.event("hyperglow_bridge", "connection_state",
                        Diagnostics.context("status", "connected"));
                XpLog.log("[SpotifyPlusBridge] connected");
                publishPendingLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (SpicyLyricBridgePublisher.this) {
                bridge = null;
                Diagnostics.setHyperGlowBridgeStatus("disconnected");
                XpLog.log("[SpotifyPlusBridge] service disconnected, rebinding");
                binding = false;
                unbindLocked();
                if (enabled) connect();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (SpicyLyricBridgePublisher.this) {
                bridge = null;
                XpLog.log("[SpotifyPlusBridge] binding died, rebinding");
                binding = false;
                unbindLocked();
                if (enabled) connect();
            }
        }
    };

    private static void log(String message, Throwable t) {
        XpLog.log("[SpotifyPlusBridge] " + message + ": " + t.getClass().getSimpleName()
                + " msg=" + describeMessage(t));
    }

    /**
     * The exception's own message, flattened and bounded.
     *
     * <p>It is the half of a failure that names what was rejected - Android answers a provider call
     * with no registered authority with {@code IllegalArgumentException: Unknown URI ...} - and it
     * used to be dropped, leaving a run of identical lines that said only "IllegalArgumentException".
     */
    private static String describeMessage(Throwable t) {
        if (t == null) return "";
        String message = t.getMessage();
        if (message == null) return "";
        String flat = message.replace('\n', ' ').trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160);
    }
}
