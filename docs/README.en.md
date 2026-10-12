<div align="center">

<img src="../assets/icon.svg" alt="Spotify++" width="140">

# Spotify++

**Animated, synchronized lyrics inside Spotify — an Xposed / LSPosed module (Simplified-Chinese fork)**

Replaces Spotify's own lyrics surface with a better one: concurrent multi-source search, word-by-word timing, translations, romanization, full-screen rendering, desktop lyrics and Android Auto lyrics.

Unofficial project, not affiliated with Spotify or any lyrics provider.

[简体中文](readme.md) · **English** · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md)

![release 1.5](https://img.shields.io/badge/release-1.5-blue) ![licence AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-green) ![Android 8.1+](https://img.shields.io/badge/platform-Android%208.1%2B-3ddc84) ![LSPosed / Xposed](https://img.shields.io/badge/framework-LSPosed%20%7C%20Xposed-orange)

</div>

---

## Contents

- [What this is](#what-this-is)
- [What is new in 1.5](#what-is-new-in-15)
- [How the lyrics source is chosen](#how-the-lyrics-source-is-chosen)
- [The tiered rule and the weighted score](#the-tiered-rule-and-the-weighted-score)
- [Features](#features)
- [Lyrics sources and credentials](#lyrics-sources-and-credentials)
- [Installation](#installation)
- [First run](#first-run)
- [Backup and migration](#backup-and-migration)
- [Building from source](#building-from-source)
- [Troubleshooting](#troubleshooting)
- [Known issues and limits](#known-issues-and-limits)
- [What this fork changes](#what-this-fork-changes)
- [Credits and licence](#credits-and-licence)

---

## What this is

Spotify++ is a lyrics module that runs inside the Spotify process, **based on Spicy EX** by amarinne, aimed primarily at Chinese-language listeners. It takes over Spotify's lyrics surface: it queries several lyric sources at once, picks the most suitable document with one scoring rule, and renders it — word- or syllable-timed, translated, romanized, on the full-screen page, on the desktop or in Android Auto.

Choosing which lyrics to show is the point of the module. Spotify offers one document, and for a Chinese song it is often Traditional and line-timed; the same song may exist elsewhere as a Simplified, word-timed, translated edition. The module does not decide in advance which source is best — it measures every candidate and compares the totals.

**This repository is a modified fork, not the original project.** Upstream copyright belongs to its author; this fork's changes are released under the same **AGPL-3.0** — see [LICENSE](../LICENSE), [NOTICE](../NOTICE) and [MODIFICATIONS.md](MODIFICATIONS.md).

---

## What is new in 1.5

### New features

- **The layout can be edited live** — a change takes effect in the editor straight away, with no need to close and reopen it, and every element can be hidden or shown on its own
- **Lyric display script** (optional; "no conversion" by default) — Chinese lyrics can be shown in Simplified at display time, converted by phrase, so `頭髮` and `出發` each come out right; **off by default, and it changes nothing for anyone who does not set it**
- **A new ranking algorithm for lyrics** — word timing first, Simplified ahead of Traditional, more translation is better, a closer duration is better, and the document's language should match the song; a weighted score picks the most suitable document. The rule is described in [the next section](#how-the-lyrics-source-is-chosen)
- **Automatic selection asks every source** — it no longer stops at the first answer; when a later source returns, the round is compared again and the higher score wins
- **A new optional machine-translation backend** — enter your own AppID and secret under Settings to use it; the default remains an online translator that needs no account
- **A restore now tells you which credentials have to be entered again** — credentials are encrypted on the device, so they cannot be decrypted after a move or a reinstall, and the restore names them one by one

### Fixed problems

- The floating-window lyrics vanished immediately after Spotify 9.1.90
- When Spotify closed the host window, the floating window was closed with it
- After an interrupted AI translation, a saved result was treated as complete and the lyrics were missing rows
- An AI translation retry threw away a result that had already been paid for, and billed for it again
- Lyric gradients and word spacing were not smooth enough
- The lyric position showed one of Spotify's internal view names instead of lyrics, and such content already in the cache was still read out
- Translations for Chinese lyrics were dropped entirely — not a single line came through
- Lyrics could stick on "translating", with the screen unresponsive
- A failed translation produced no message at all
- Repeated lines (the chorus, sung again and again) did not get translations
- The source that needs a key was never selected and found nothing
- In the picker, a source that could not deliver looked usable but did nothing when tapped
- Restoring a backup overwrote a credential that was still working on the device
- Cross-source lyric blending was removed — it paired one document's timing with another's text, and when the row counts disagreed the result drifted out of alignment while looking fine
- Parts of the Japanese and Korean interface were filled in; those locales previously showed English there
- The source-attribution row is no longer shown under the lyrics

---

## How the lyrics source is chosen

One round starts from every candidate the **enabled sources** returned for this track. Each candidate carries its own facts: timing level, lyric text, translation coverage, document length, how it was matched. The rule has two steps — discard documents that are not this recording, then score the survivors, highest total first.

### Step one: discard a different edit

A document whose lines keep running **ten seconds or more** past the end of the recording is not this edit of the song. It is removed from the round.

The test is one-sided: **stopping early is not a problem.** An outro, a fade, an instrumental close — all make the lyrics shorter than the recording, and that is ordinary. No penalty, no removal.

### Step two: seven components

Twenty points in total, seven components. Every component is read from the candidate itself; only the duration component looks at the whole round, as explained below.

| Component | Max | What it reads | Why it matters |
|---|---|---|---|
| Timing precision | +4 | In-line timing (word or syllable) takes the full four; line timing +2; no timeline 0 | The highlight has to sit on the voice. In-line marks let it advance character by character inside a line; line timing can only jump the whole line. Chinese sources normally time one character per mark |
| Simplified script | +5 | First the document is judged to be Chinese text, then graded on the share of *variant* characters written Simplified: 95% or more takes the full five, then 80%, 60%, 40% and 20% each step down | A Simplified and a Traditional edition are two documents of the same song, and the reader reads characters. A document with no variant character at all (你今天很好, for instance) takes the full score — there is nothing Traditional in it |
| Translation coverage | +2 | Lines carrying a provider translation divided by the lines that can carry one (interludes and empty lines are excluded), scaled to two | Translations are read line by line: the more of the document is translated, the more of it is readable. Paid proportionally, so 26% and 74% do not tie and every extra translated line counts |
| Duration fit | +3 | The timeline that ends closest to the recording's end takes three; the next two, then one, then nothing | A lyric document and a recording are often different edits (live, cut, extended). The drift you hear comes from here, not from timing precision |
| Completeness | +1 | The whole song +1; a fragment 0 | A chorus-only fragment is not a usable sole source, however precisely it is timed |
| Match identity | +2 | An exact Spotify id match +2; a provider's own song-id mapping +1; found by search 0 | An exact id means "this candidate was asked about this track"; a search hit only means the names look alike. Spotify's own source is not paid these two — it is handed that id and looks the track up by it |
| Language match | +3 | The candidate's own language equals the song's +3; a mismatch or an undetermined verdict 0 | Subtitling a Chinese song in Chinese is not the same as subtitling an English song in Chinese. The song's language is read from the Spotify document in the same round, so it costs no request and no wait |

**The Chinese test is a heuristic, and it works on ratios rather than single characters.** Over 10% kana in the sample means Japanese (a Chinese song that quotes a Japanese line is still a Chinese song, hence the ratio). Under ten Han characters is too little to judge. Under a quarter Han by density is not Chinese text (an English song with a few Chinese words is not Chinese lyrics). Failing the test costs this component its points and nothing else — no penalty, no removal — so a wrong verdict can withhold points but cannot push a wrong document to the top.

**Duration is graded by rank within the round, not against fixed seconds.** One measured round came back with every source between 24 and 39 seconds short of the recording's end. A fixed band ("within 2 seconds: three points") would have paid every candidate zero and said nothing about which was closer. Ranked, that round reads 3, 2, 1, 0, 0 — the only evidence the round contains. The cost is that a document's score depends on who else is in the round, so a different set of candidates can change it.

**Translation is paid by coverage, not by presence.** A fully translated document takes the two; a half-translated one about one; none takes nothing.

**Match identity is a payment, never a gate.** An exact id does not win by itself: it says the right track was asked about, not that the document is timed, complete or Simplified — the other components judge that. A document found by search is likewise not rejected for having been found by search.

### Ties, manual pins and stability

- **On equal totals**, the configured source order decides; then a fixed built-in order; and last the candidate id, which keeps the outcome independent of the order in which answers arrive.
- **The seated document keeps its seat** on an equal total or an identical digest, so a restart or a reordered callback cannot flip a persisted winner. A challenger needs a strictly higher total.
- **Manual pins**: a candidate you pinned is used for that track. If the pin cannot be fetched for the moment, the automatic best is rendered as a clearly marked temporary fallback without clearing the pin.
- **Two selection modes**: the default *Auto* compares all sources together; *Source order* takes the first enabled source that has lyrics and ranks only inside it. A per-track pin holds in either mode.

### What this rule deliberately does not read

Match confidence, capability counts such as background vocals, duet marking and credits, timing health, and the identity of the source do not decide a seat. They are still shown in the source list so you can judge for yourself; they do not choose this round's document. A source enters only as the tie-break, after every component has been paid.

---

## The tiered rule and the weighted score

1.4 did not start from nothing: v1.1 compared candidates **tier by tier**, and 1.4 replaced that with a **weighted total**. Both pick a sensible document; the difference is the trade-off.

### v1.1: tiered comparison

A fixed list of tiers, asked in order, **decided at the first tier that differed**:

```
overrun elimination → timing level → completeness → provider translation/transliteration
  → Simplified share → match method → capability count → timing health → match confidence
  → duration distance → fixed source order → candidate id
```

Every tier was a rank, a boolean or a threshold, with no weights. Its strength is that it is **simple and predictable**: there is one decision path, the same two documents compare the same way in any round (independently of the other candidates), the log line reads as a chain, and hard rules such as "word timing must always beat line timing" are stated directly. Its limit is that it can express **priority but not magnitude**: two signals cannot compensate for each other, and a difference in any earlier tier skips every later one — confidence and duration distance sit near the end, so an earlier difference can leave them out of the decision entirely. The retired rule also ranked syllable timing above word timing (in Latin text one mark per syllable really is not one mark per word); 1.4 pays both the same and lets the tie-break decide.

### 1.4: weighted total

```
discard a different edit → sum the seven components → equal totals: source order → candidate id
```

Each component has a value, an advantage in one can cover a shortfall in another, and ranks became bands. A worked example: a **word-timed, Simplified** document against a **line-timed, Traditional** one that was matched by **exact id** — timing pays 4 against 2, the script component can add up to 5 more, and the exact match is worth only 2, so the word-timed Simplified document can lead. Conversely, when the two agree on timing and are close on script, those 2 points for the exact match or the configured source order decide it.

The cost, stated plainly: one component is a rank within the round, so a score is a function of **the candidate set**, and the same document can score differently in a different round. Comparing across rounds means reading the per-component trace, not just the total.

### What each is good at

- **The old rule is good at being predictable.** Few tiers, a clear order, every step you can recite; the relative order of two candidates does not depend on who else is in the round. For a hard preference such as "word timing always over line timing", it says so directly.
- **The new rule is good at weighing several signals at once.** Timing, script, translation, duration, completeness, match identity and language are summed together, degrees are banded, and a weak component can be covered by a strong one. Grading the duration component by rank also lets a round where every source is tens of seconds out still separate them.

The fork used the first rule and then replaced it with the second — they do not coexist. They differ in what they put first: **a deterministic order, or a weighable trade-off.**

---

## Features

### Getting the lyrics

- **Concurrent multi-source search** — every enabled source is asked at the same time; the fastest usable answer appears first and is replaced only by a strictly higher-scoring candidate
- **Word-by-word lyrics** (karaoke effect), in four timing tiers: syllable, word, line, unsynced
- **Smart matching** — exact Spotify ID, title search, Simplified↔Traditional cross-search, aliases and spelling variants (e.g. `反乌托邦 - 拼接版` versus `拼接乌托邦`)
- **Search by name** — when automatic matching fails, search the title yourself, pick from the results, and fetch by ID
- **Source list** — check each source on its own; see match status, timing tier, translation and capabilities; a source that cannot deliver is marked as such, and you can pin one manually
- **Local cache** — anything fetched is kept, so replaying a track needs no new requests

### Showing them

- **Translations** and **romanization / readings**
- **Full-screen lyrics surface** (takes over Spotify's own lyrics page)
- **Desktop lyrics** and **picture-in-picture** lyrics
- **Android Auto lyrics** — full player and cards
- **HyperGlow** lyric publishing
- Duet view, background-vocal markers, share cards

### Match quality

- **Scored selection** — seven weighted components; the script is banded by share and translation is paid by coverage
- **Overrun removal** — a document running ten seconds or more past the recording is read as a different edit and removed
- **Poisoned-document filtering** — suspicious responses are refused
- Timing health, match confidence and similar fields are still shown in the source list for a manual decision

### Other

- **A complete Simplified-Chinese interface**, plus English / Japanese / Korean / Russian
- **Backup and restore** — settings, source order, per-track pins, the whole catalogue, caches and fonts in one file

---

## Lyrics sources and credentials

The module queries several lyric sources at once: Spotify's own lyrics plus a number of third-party libraries and platforms. The same source behaves very differently from track to track — one has finer timing, one carries translations more often, one only has Traditional text — which is exactly what the scoring rule is for. You do not need to know which is which: **in Auto mode every enabled source competes on one total.**

### One source needs a key

One source needs a client key of your own:

1. Request one from that service, following the note in Settings
2. Enter it under **Settings → Spotify++ → client key**
3. A key that does not match the expected format is refused with a message

With no key, or with a key the service rejects, that source is skipped automatically and the others keep working. The key is stored encrypted on this device only and **does not travel to another device with a backup** (see [Backup and migration](#backup-and-migration)).

### What you control

- **Which sources are enabled** — switch one off and it is not asked at all
- **Source order** — both the tie-break in Auto mode and the priority in Source-order mode
- **Selection mode** — Auto (one total across sources) or Source order (the first source with lyrics wins)
- **Per-track pins** — pin one candidate from the source list

---

## Installation

### Requirements

| Item | Requirement |
|---|---|
| System | Android 8.1 or newer (minSdk 27, targetSdk 35) |
| Module framework | **Xposed / LSPosed** (recommended, needs root) or **LSPatch** (no root; you patch Spotify yourself). The module uses Xposed API 102 |
| Spotify | This build is adapted to **9.1.88 – 9.1.90**; older versions, betas and third-party rebuilds may not work |
| Module version | 1.5 (versionCode 8) |

> The module relies on Xposed framework injection and **cannot work on an unpatched Spotify**.

### Steps

1. Download the latest APK from [Releases](https://github.com/junxi8686/SpotifyPlusPlus/releases) and install it
2. Open **LSPosed** → **Modules** → enable **Spotify++**
3. Under **Scope**, tick `Spotify`
   - also tick `Android Auto` if you want lyrics there
4. **Force-stop Spotify** (Settings → Apps → Spotify → Force stop)
5. Reopen Spotify

> ⚠️ **Step 4 is not optional.** Installing an APK does **not** restart the Spotify process, and LSPosed only loads modules when a process starts. Skip it and you are testing the old code — by far the most common source of confusion when troubleshooting.
>
> **Updating works the same way**: install the new APK over the old one, then force-stop and reopen Spotify. With LSPatch, enable *Override version code*.

---

## First run

Just play a track; the lyrics appear on their own. To give the first track a good result, the module asks every enabled source at once and re-compares whenever a later answer arrives.

### Where the settings are

Two entry points:

- **Settings** — the **Spotify++** section inside Spotify's settings. Everything lives here: lyric sources (enabled set, order, selection mode, client key), translation (backend, target language, cache), AI translation and readings, interface and rendering, cache clearing, export and import
- **Source list** — the source button in the lyrics screen. It shows how each source matched this track, lets you re-check one on its own, and lets you pin one manually

### Tuning

| What you want | How |
|---|---|
| Word-by-word lyrics first | On by default, nothing to set |
| A specific source first | Settings → Source order, move it to the top; or pin it for this track from the source list |
| Simplified Chinese for Chinese songs | On by default (the script component is part of the score) |
| Stop auto-following, scroll manually | Turn off "follow playback" |
| Lyrics drift out of sync | Settings → Reset lyrics sync |
| Ask one source less | Settings → switch that source off |

---

## Backup and migration

**Settings → Export settings and data** writes one JSON file (by default `Downloads/Spotify++/SpotifyPlusPlus-backup.json`; you can also pick a location). Restore with **Import settings and data**.

### What the backup carries

| Contents | Notes |
|---|---|
| All settings | Interface, rendering, behaviour, selection mode |
| Source order and per-track pins | The priority and the single-track choices you tuned |
| The whole catalogue | Which sources each track has, how they matched, what was checked |
| Lyric and translation caches | Whole databases, copied byte for byte, so a restore does not re-download lyrics or re-request translations |
| Imported fonts | Custom font files travel and are re-pointed at this install's copy on restore |
| Credential ciphertext | The client key, AI service keys, and the translation backend's AppID and secret. Readable only by the install that wrote the backup, so a new device must enter them again (see below) |
| A little runtime state | Key revision and access state, so restored data cannot be confused with newer results |

### What it deliberately does not carry

- **Diagnostic drafts and capture buffers** — transient state about a report in progress, not something to move between installs
- **The language model pack** — tens of megabytes and re-downloadable; the settings that point at it travel instead
- **Plaintext credentials** — only ciphertext, see below

### Credentials cannot travel

Credentials are encrypted on the device with a key held in the system Keystore, bound to Spotify's identity on this device. That key is **not written into the backup**. So:

- On the same device, a module update, a Spotify update, or an uninstall/reinstall of the module keeps them working
- After moving to a new phone, switching Android user, or clearing Spotify's data, the ciphertext in the backup cannot be decrypted — **enter the credentials again on the new device**
- The module **never imports or migrates a key**, and no message claims it does

If the device already holds a credential it can still read, a restore **never replaces it with a value the file carries that cannot be read here**. When the restore finishes it names, item by item, which credentials have to be entered again in Settings.

### Restore rules

- **Entries the file carries overwrite same-named entries; entries it omits are left untouched** (an older backup still imports, and nothing it does not cover is cleared)
- A backup written by a newer version is refused rather than guessed at
- The catalogue and the translation cache **take effect after Spotify restarts**

Related documents: [MODIFICATIONS.md](MODIFICATIONS.md) (the AGPL change notice), [FEATURES_USER.md](FEATURES_USER.md) (the feature list) and [READINGS.md](READINGS.md) (the readings and transliteration guide).

---

## Building from source

### Toolchain

| Component | Version |
|---|---|
| JDK | 17 |
| Android SDK | Platform 35 (compileSdk 35 / minSdk 27) |
| Gradle | 8.14.2 (wrapper included) |
| Android Gradle Plugin | 8.8.2 |
| Xposed API | 102.0.0 (`io.github.libxposed:api`) |

### Commands

```bash
git clone https://github.com/junxi8686/SpotifyPlusPlus.git
cd SpotifyPlusPlus
./gradlew :app:assembleDebug          # output in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # unit tests
```

On Windows use `gradlew.bat`.

### Test groups

The unit tests can be run by group instead of in full:

```bash
./gradlew :app:testDebugUnitTest -PtestGroup=lyrics        # lyrics
./gradlew :app:testDebugUnitTest -PtestGroup=settings      # settings and strings
./gradlew :app:testDebugUnitTest -PtestGroup=corpus        # language golden samples
```

Available groups: `settings`, `diagnostics`, `hooks`, `lyrics`, `lyrics-ai`, `lyrics-session`, `corpus`. Two evaluation tools are opt-in only (`-PtestTools=jp-export`, `-PtestTools=model-survey`); they write files or call paid services and are not regression tests.

---

## Troubleshooting

<details>
<summary><b>Installed but nothing happens</b></summary>

1. Confirm the module is enabled in LSPosed and **Spotify is ticked in its scope**
2. **Force-stop Spotify and reopen it** — required
3. Confirm your Spotify version is supported (this build is adapted to 9.1.88 – 9.1.90)

</details>

<details>
<summary><b>Lyrics are Traditional but I want Simplified</b></summary>

The script component is part of the score, so a Simplified document normally wins on its own. If you still see Traditional:

1. Open the **source list** and see whether any source offers Simplified lyrics
2. If one does, pin it
3. If none does, no Simplified lyrics exist for that track yet — the rule cannot invent one

</details>

<details>
<summary><b>A source never finds anything</b></summary>

Tap that row in the **source list** to check it on its own:

- **Tick** → that source has lyrics for this track
- **Cross** → it genuinely has none, or the network failed

A source that cannot deliver is shown as unavailable rather than looking usable but doing nothing. Remember that catalogue availability **changes over time** — no lyrics today does not mean none tomorrow.

</details>

<details>
<summary><b>The source that needs a key produces nothing</b></summary>

1. Confirm the key saved successfully in Settings (a message appears either way)
2. **Tap it once manually** in the source list — a manual check takes the explicit-recovery path and clears a previous access block
3. A key the server has rejected pauses that source

</details>

<details>
<summary><b>Restoring onto a new phone</b></summary>

Use **export/import**. Note that:

- Settings, source order, catalogue and caches restore normally
- The ciphertext travels in the file, but the key that unwraps it is device-bound — **enter the credentials again on the new device**
- When the restore finishes, the module names which items have to be re-entered

</details>

<details>
<summary><b>Lyrics drift out of sync</b></summary>

First check the source list: if the timeline ends far from the recording's end, that document is a different edit and you can pin another one.

If the timeline itself is fine:

Settings → **Reset lyrics sync**. It clears the stored offset and re-anchors the module to the playback clock.

</details>

---

## Known issues and limits

- **A score depends on the round it is scored in** — duration fit is graded by rank, so a different set of candidates can change a document's total
- **The language test is a heuristic** — a Japanese song with almost no kana can read as Chinese; a Chinese song sung mostly in English can fall under the Han-density gate; Hanja in Korean is not told apart from Chinese. A wrong verdict withholds points from this component and never adds any
- **Some tracks match slowly on slow sources** — network latency, not the module
- **Android Auto lyrics** are not heavily tested across car head units
- **A few tracks** carry lyrics whose length does not match the recording; an overrun of ten seconds or more is removed, and a smaller one can still show
- **Readings are rule- and dictionary-driven** — Mandarin polyphones, artistic Japanese readings, Cyrillic transliteration and the static Greek table all have known edges
- **The non-root LSPatch flow** depends on the documented downgrade-login-upgrade method; login or integrity checks may fail
- **HyperGlow publishing** needs a device that supports it

Report problems in [Issues](https://github.com/junxi8686/SpotifyPlusPlus/issues) with:

- Spotify version
- Module version
- Steps to reproduce
- Relevant screenshots

---

## What this fork changes

This repository keeps merging upstream Spicy EX onto this project's **v1.1**: 1.4 replaced the ranking rule, and 1.5 ports upstream **v2.0.41**, with these adjustments:

### Simplified Chinese

- Added `values-zh-rCN` covering every string resource
- Fixed upstream strings that Java referenced but the resources did not define, which made the Chinese interface fall back to English
- Localised dynamic copy such as the lyric credit line and error messages
- Fixed devices where the language list showed only the current language and could not switch back

### Lyric matching

- **Scored selection** (1.4) — upstream v2.0.0 switched to "source first" (pick the primary source, then compare timing only within it), so a better fallback could never replace the primary text. This fork's v1.1 went back to quality-first tiered comparison, and 1.4 replaced that with a weighted total over seven components; see [The tiered rule and the weighted score](#the-tiered-rule-and-the-weighted-score)
- **Concurrent multi-source fetching** — upstream asked sources one at a time, so the wait was the sum of all of them; now they are asked together, the fastest usable answer is shown, and only a higher score replaces it
- **Search by name** — when automatic matching fails, search and choose yourself
- Simplified↔Traditional cross-search and alias variants (fixing "one character apart treated as two songs")
- **The source list checks each track once**, and no longer announces every success
- **Documents from a different edit are discarded** — a candidate running ten seconds or more past the recording does not compete

### Backup and everything else

- **The backup widened from two stores to settings, source choices, catalogue, caches, fonts and credential ciphertext** (readable only by the install that wrote it) — upstream backed up settings and keys only, so a restore left an empty catalogue and no source selection
- **Credential rules on restore** — a credential this device can still read is never overwritten, and the restore reports which ones have to be entered again
- One lyric source that is no longer used was removed; a stale entry in an old setting or backup is not queried
- Fixed three GitHub links that pointed at addresses naming no repository
- Removed the "Report a problem" entry, whose drafts were addressed to the upstream tracker rather than this fork
- Adapted track lookup and the floating window for Spotify 9.1.88 – 9.1.90 (upstream's approach throws `NoSuchMethodException` there, which disabled automatic search entirely)
- Fixed the translation chain: Chinese translations dropped, sticking on "translating", silent failures, and repeated lines left untranslated

See [MODIFICATIONS.md](MODIFICATIONS.md) and [FEATURES_USER.md](FEATURES_USER.md).

---

## Credits and licence

- Upstream: Spicy EX by amarinne
- Rendering approach informed by Spicy Lyrics (a Spicetify extension)
- Icons: [Lucide](https://github.com/lucide-icons/lucide) (ISC / MIT)
- Lyrics come from Spotify's own lyrics and several third-party sources; this page does not list them one by one

Released under **AGPL-3.0** — see [LICENSE](../LICENSE).

> Not affiliated with Spotify, Spicy Lyrics or any lyrics provider. All lyrics remain the property of their respective rights holders.
