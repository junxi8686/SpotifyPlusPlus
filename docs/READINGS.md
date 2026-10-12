# Readings and transliteration

This is the reading (transliteration) support this build actually ships. It replaces upstream's
`TRANSLITERATION.md`, which documents its author's personal romanization system as well; that system
is not part of this fork and nothing here produces it (see the last section).

## Offline, deterministic

These readings are computed on the device from packaged tables and a local engine. No network call
is made for them.

| Language or script | What is produced |
|---|---|
| Japanese | Furigana (kana over the kanji), romaji, or both. Long vowels are spelled out (`えい`, `おう`) rather than marked with macrons |
| Mandarin | Pinyin, with tone marks on or off |
| Cantonese | Jyutping, with tone numbers on or off |
| Korean | A Revised-Romanization-style reading, and a pronunciation pass that applies the standard sound changes |
| Cyrillic | A Latin-letter reading (`Привет` → `Privet`) |
| Greek | A Latin-letter reading (`Αγάπη` → `Agapi`) |

The Japanese path resolves readings per word and per syllable, so a reading can be attached to the
word it belongs to rather than to the whole line. Chinese and Cantonese readings come from a
character map plus a phrase table, so common multi-character words read correctly rather than
character by character.

## What is configurable

- **Chinese reading mode** — pinyin or jyutping, and it can be chosen per song. The two are
  different answers, not one answer in two spellings, so the choice is part of what a cached
  reading was produced for.
- **Tones** — on or off, for pinyin tone marks and jyutping tone numbers alike.
- **Japanese** — furigana, romaji, or both.
- **Where readings appear** — the fullscreen lyrics screen, the now-playing card, and the share
  card all render the same reading projection.

## AI readings for everything else

Scripts with no offline table (Thai, Arabic, Devanagari, Tamil, and others) can be read by the AI
Sound layer, which asks the model the owner configured for a reading of each row. That path is
opt-in like the rest of the AI layer, is billed per request, and stores its result under the same
per-row identity as a local reading — so switching between a local and an AI reading of the same row
is a change of configuration, not a silent replacement.

## What this build does not do

Upstream's document describes **eza式**, a personal custom romanization of Mandarin, Cantonese and
Korean based on Vietnamese orthography, with its own consonant, vowel and tone tables. It is not in
this tree, no setting selects it, and no reading produced here claims to be it. Porting it would
mean shipping one contributor's private orthography as a product feature, so it was left out
deliberately rather than dropped by accident.

## Where the code lives

- `app/src/main/java/com/spotifyplusplus/lyrics/language/SpicyRomanizer.java` — Cyrillic, Greek and
  Korean readings, and the shared entry points.
- `app/src/main/java/com/spotifyplusplus/lyrics/language/SpicyJapaneseChineseProcessor.java` —
  Japanese and Chinese readings, including the romaji long-vowel spelling.
- `app/src/main/java/com/spotifyplusplus/lyrics/language/JyutpingRomanizer.java` and the packaged
  character and phrase data beside it — Cantonese.
- `app/src/main/java/com/spotifyplusplus/lyrics/language/FuriganaTable.java` — the furigana table.
- `app/src/main/java/com/spotifyplusplus/lyrics/reading/ReadingPlanFactory.java` — how a reading
  becomes a renderable plan, including per-word and per-syllable attachment.
- `app/src/main/java/com/spotifyplusplus/lyrics/ai/AiLayerRunner.java` — the AI Sound path for
  scripts without an offline table.
