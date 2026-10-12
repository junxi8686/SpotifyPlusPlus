# Spotify++ Data Policy

Spotify++ has no analytics, no background uploads, no remote configuration and no advertising
identifiers.

## Reporting a problem

The upstream project's in-app **Report a problem** entry is not part of this fork: its drafts were
addressed to the upstream intake, which this repository cannot read, so the entry was removed.
Nothing in this build starts a diagnostic capture or sends a report, and the compiled intake
endpoint is empty unless a build sets one.

Report a problem through [Issues](https://github.com/junxi8686/SpotifyPlusPlus/issues) instead, with
the details you choose to include. Screenshots can be attached there.

## What the panel shows locally

**Settings → Spotify++ → About & Diagnostics** renders read-only lines about the current lyric
fetch: the source chosen, the candidates seen, the provider, the type chosen, and whether a cache
write happened. They are drawn in the panel and are never uploaded.

The operation capture the upstream code can record (bounded events with an expiry) is inactive
here: recording returns immediately unless a capture is running, and nothing in this build starts
one.

## Never collected or sent

- Your credentials — no client key, no AI service key, no translation AppID or secret, in any form.
- Spotify tokens, cookies or account details.
- Android ID, serial, IMEI or Wi-Fi SSID.
- Artwork identifiers or arbitrary URLs.
- Throwable messages, full logcat, LSPosed logs, screenshots or files from your device.

## Network traffic

What leaves the device is what the features need: lyric lookups to the sources you enabled,
translation to the backend you selected, AI requests to the provider you configured, and the
optional language-model pack download. Each request is one a feature asked for; none of them is
telemetry.
