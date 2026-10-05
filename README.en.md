<div align="center">

# Spotify++

**Animated, synchronized lyrics inside Spotify — an Xposed / LSPosed module (Simplified-Chinese fork)**

Replaces Spotify's own lyrics surface with one that actually works: concurrent multi-source search, word-by-word timing, translations, romanization, full-screen rendering, desktop lyrics and Android Auto lyrics.

Unofficial project, not affiliated with Spotify or Spicy Lyrics.

[简体中文](readme.md) · **English** · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md)

</div>

---

## Contents

- [What this is](#what-this-is)
- [Features](#features)
- [Lyrics sources](#lyrics-sources)
- [Installation](#installation)
- [First run](#first-run)
- [Backup and migration](#backup-and-migration)
- [Building from source](#building-from-source)
- [Troubleshooting](#troubleshooting)
- [Known issues](#known-issues)
- [What this fork changes](#what-this-fork-changes)
- [Credits and licence](#credits-and-licence)

---

## What this is

A lyrics module that runs inside the Spotify process, **based on [Spicy EX](https://github.com/amarinne/spicy-ex)** by [amarinne](https://github.com/amarinne), aimed primarily at Chinese-language listeners.

**This repository is a modified fork, not the original project.** Upstream copyright belongs to its author; this fork's changes are released under the same **AGPL-3.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

It does one simple thing: **it makes sure the lyrics you see are the right ones.**

Spotify's own lyrics come only from its own provider — often Traditional Chinese, often line-timed — while the best lyrics for a Chinese song usually sit on QQ Music or NetEase: Simplified, word-timed, with a translation. This module queries several lyric sources at once, ranks every candidate by **quality** — timing precision first, then completeness, then match identity, and Simplified script ahead of Traditional at equal rank — and renders the winner into Spotify's lyrics screen.

---

## Features

### Getting the lyrics

- **Six sources searched concurrently** — every enabled source is asked at the same time, the fastest usable answer appears first, and it is replaced only by something the ranker scores strictly better
- **Word-by-word lyrics** (karaoke effect), with four timing tiers: syllable, word, line, unsynced
- **Smart matching** — exact Spotify ID, title search, Simplified↔Traditional cross-search, aliases and spelling variants (e.g. `反乌托邦 - 拼接版` versus `拼接乌托邦`)
- **Search by name** — when automatic matching fails, search the title yourself, pick from the results, and fetch by ID
- **Source list** — check each source on its own, see its match status, timing tier, translation and capabilities, and pin one manually
- **Local cache** — anything fetched is kept, so replaying a track needs no new requests

### Showing them

- **Translations** and **romanization / readings**
- **Full-screen lyrics surface** (takes over Spotify's own lyrics page)
- **Desktop lyrics** and **picture-in-picture** lyrics
- **Android Auto lyrics** — full player and cards
- **HyperGlow** lyric publishing
- Duet view, background-vocal markers, share cards

### Match quality

- **Simplified preferred** — at equal rank, Simplified text wins, so you stop seeing Traditional when a Simplified version exists
- Timing-health checks — documents with broken, out-of-range or length-mismatched timelines are rejected
- Poisoned-document filtering — suspicious responses are refused

### Other

- **Complete Simplified-Chinese interface**, plus English / Japanese / Korean / Russian
- **Backup and restore** — settings, keys, source order, per-track choices and the whole catalogue in one file

---

## Lyrics sources

| Source | Notes | Key required |
|---|---|---|
| **SpicyLyrics.org** | Community library, high quality, often word-timed | ✅ `sl_pk_` client key |
| **Spotify** | Spotify's own (usually line-timed; Chinese may be Traditional) | ❌ |
| **AMLL** | Apple-Music-style word-timed library | ❌ |
| **LRCLIB** | Open lyrics database | ❌ |
| **QQ Music** | Best word-timed coverage for Chinese songs, often translated | ❌ |
| **NetEase Cloud Music** | Broad Chinese coverage, often translated | ❌ |

> **Apple Music has been removed** as a lyrics source.

### Default priority

```
Syllable  >  Word  >  Line  >  Static (unsynced)
```

**Timing outranks source identity** — a word-timed delivery from QQ Music beats a line-timed one from Spotify, even though Spotify is the "native" source. Source capability and script are only consulted at equal timing.

### The SpicyLyrics.org key

This source needs your own client key:

1. Request one at **https://developers.spicylyrics.org/catalog/spicy-ex**
2. It must begin with **`sl_pk_`**
3. Enter it under **Settings → SpicyLyrics.org client key**

A key that does not match is refused with a message. With no key stored the source is skipped automatically and the other five keep working.

---

## Installation

### Requirements

- **Root + LSPosed** (recommended), or
- **No root + LSPatch** (you patch Spotify yourself)

> The module cannot work on an unpatched Spotify: it relies on Xposed framework injection.

### Steps

1. Download the latest APK from [Releases](https://github.com/junxi8686/SpotifyPlusPlus/releases) and install it
2. Open **LSPosed** → **Modules** → enable **Spotify++**
3. Under **Scope**, tick `Spotify`
   - also tick `Android Auto` if you want lyrics there
4. **Force-stop Spotify** (Settings → Apps → Spotify → Force stop)
5. Reopen Spotify

> ⚠️ **Step 4 is not optional.** Installing an APK does **not** restart the Spotify process, and LSPosed only loads modules when a process starts. Skip it and you are testing the old code — by far the most common source of confusion when troubleshooting.

---

## First run

Just play a track; the lyrics appear on their own.

To adjust things:

- **Source list** — tap the source button in the lyrics screen to see how each source matched, re-check one on its own, or pin one manually
- **Settings** — find the **Spotify++** section inside Spotify's settings

### Tuning

| What you want | How |
|---|---|
| Word-by-word lyrics first | On by default, nothing to set |
| A specific source first | Settings → Source order, move it to the top |
| Simplified Chinese for Chinese songs | On by default (Simplified wins at equal rank) |
| Stop auto-following, scroll manually | Turn off "follow playback" |
| Lyrics drift out of sync | Settings → Reset lyrics sync |

---

## Backup and migration

**Settings → Export backup** writes a JSON file to `Downloads/Spicy EX/SpicyEX-backup.json`.

It carries:

| Contents | Notes |
|---|---|
| All settings | Interface, rendering and behaviour options |
| **AI API keys** | Stored encrypted |
| **SpicyLyrics.org client key** | Stored encrypted |
| **Source order and per-track choices** | The priority you tuned |
| **The whole catalogue** | Which sources each track has, how they matched, what was checked |
| SpicyLyrics.org key revision and access state | So restored data cannot be confused with newer results |

Restore with **Settings → Import backup** and pick that JSON.

> **Import overwrites.** Export once as a safety net first.
> Diagnostic drafts and capture buffers are **not** included — that is transient state about a report in progress.
> **The format is backward compatible**: older backups still import, and a store the file omits is left untouched rather than cleared.

---

## Building from source

### Toolchain

| Component | Version |
|---|---|
| JDK | 17 |
| Android SDK | Platform 35 (compileSdk 35 / minSdk 27) |
| Gradle | 8.14.2 (wrapper included) |
| Android Gradle Plugin | 8.8.2 |

### Commands

```bash
git clone https://github.com/junxi8686/SpotifyPlusPlus.git
cd SpotifyPlusPlus
./gradlew :app:assembleDebug          # output in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # unit tests
```

On Windows use `gradlew.bat`.

---

## Troubleshooting

<details>
<summary><b>Installed but nothing happens</b></summary>

1. Confirm the module is enabled in LSPosed and **Spotify is ticked in its scope**
2. **Force-stop Spotify and reopen it** — required
3. Confirm your Spotify version is supported (this build targets 9.1.88)

</details>

<details>
<summary><b>Lyrics are Traditional but I want Simplified</b></summary>

At equal rank Simplified is preferred automatically. If you still see Traditional:

1. Open the **source list** and see whether any source offers Simplified word-timed lyrics
2. If one does, pin it manually
3. If none does, no Simplified lyrics exist for that track yet

</details>

<details>
<summary><b>A source never finds anything</b></summary>

Tap that row in the **source list** to check it on its own:

- **Tick** → that source has lyrics for this track
- **Cross** → it genuinely has none, or the network failed

Remember that catalogue availability **changes over time** — no lyrics today does not mean none tomorrow.

</details>

<details>
<summary><b>SpicyLyrics.org produces nothing</b></summary>

1. Confirm the key begins with `sl_pk_` and saved successfully (a message appears either way)
2. **Tap it once manually** in the source list — a manual check takes the explicit-recovery path and clears a previous access block
3. A key the server has rejected pauses that source

</details>

<details>
<summary><b>Restoring onto a new phone</b></summary>

Use **export/import**. Note that:

- Keys are **encrypted** in the file, but the encryption key is device-bound — **re-enter your keys** after moving devices
- Everything else — settings, source order, catalogue — restores normally

</details>

<details>
<summary><b>Lyrics drift out of sync</b></summary>

Settings → **Reset lyrics sync**. It clears the stored offset and re-anchors the module to the playback clock.

</details>

---

## Known issues

- Some tracks match slowly on obscure sources (network latency, not the module)
- Android Auto lyrics are not heavily tested across car head units
- A few tracks carry lyrics whose length does not match the recording (the overrun check filters most)

Report problems in [Issues](https://github.com/junxi8686/SpotifyPlusPlus/issues) with:

- Spotify version
- Module version
- Steps to reproduce
- Relevant screenshots

---

## What this fork changes

This repository merges [Spicy EX](https://github.com/amarinne/spicy-ex) **v2.0.0** onto this project's **v1.1**, with these adjustments:

### Simplified Chinese

- Added `values-zh-rCN` covering every string resource
- Fixed upstream strings that Java referenced but the resources did not define, which made the Chinese interface fall back to English
- Localised dynamic copy such as the lyric credit line and error messages
- Fixed devices where the language list showed only the current language and could not switch back

### Lyric matching

- **Quality-first ranking** — upstream v2.0.0 switched to "source first" (pick the primary source, then compare timing only within it), so a better fallback could never replace the primary text. Restored to "timing > completeness > match identity > provider", keeping Simplified preferred
- **Concurrent multi-source fetching** — upstream asked sources one at a time, so the wait was the sum of all of them; now they are asked together, first usable answer shown, upgrades only for a strictly better score
- **Search by name** — when automatic matching fails, search and choose yourself
- Simplified↔Traditional cross-search and alias variants (fixing "one character apart treated as two songs")
- **The source list checks each track once**, and no longer announces every success

### Other

- **Apple Music removed** as a source
- **Backup widened from 2 stores to 6** — upstream backed up settings and keys only, so a restore left an empty catalogue and no source selection
- Fixed three GitHub links that pointed at addresses naming no repository
- Removed the "Report a problem" entry, whose drafts were addressed to the upstream tracker rather than this fork
- Adapted track lookup for Spotify 9.1.88 (upstream's approach throws `NoSuchMethodException` there, which disabled automatic search entirely)

See [MODIFICATIONS.md](MODIFICATIONS.md) and [FEATURES_USER.md](FEATURES_USER.md).

---

## Credits and licence

- Upstream: [amarinne/spicy-ex](https://github.com/amarinne/spicy-ex)
- Rendering approach informed by [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) (a Spicetify extension)
- Lyric sources: SpicyLyrics.org, AMLL, LRCLIB, QQ Music, NetEase Cloud Music
- Icons: [Lucide](https://github.com/lucide-icons/lucide) (ISC / MIT)

Released under **AGPL-3.0** — see [LICENSE](LICENSE).

> Not affiliated with Spotify, Spicy Lyrics or any lyrics provider. All lyrics remain the property of their respective rights holders.
