package com.spotifyplusplus.lyrics.processing;

import com.spotifyplusplus.lyrics.language.LatinLanguageGate;
import com.spotifyplusplus.lyrics.language.ScriptClassifier;
import com.spotifyplusplus.lyrics.language.TextDetectionLookup;

import com.spotifyplusplus.lyrics.session.DetectionResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.spotifyplusplus.lyrics.BackgroundLine;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

public class ProviderTranslationResolverTest {
    @Test
    public void declaredProviderLanguageMustMatchTarget() {
        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "en", "en"));
        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "en-US", "en"));
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "en", "vi"));
        assertEquals("Xin chào", ProviderTranslationResolver.resolve("你好", "Xin chào", "vi", "vi"));
    }

    @Test
    public void unlabelledLatinProviderTextRequiresItsOwnDetection() {
        TextDetectionLookup english = text -> DetectionResult.detected("", text,
                ScriptClassifier.ScriptClass.LATIN, "en", 0.9);

        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "", "en", english));
        // No evidence, no acceptance — not even for an English target.
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "", "en",
                TextDetectionLookup.NONE));
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "", "fr", english));
        // Accented text does not establish a language either.
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "es"));
    }

    @Test
    public void foreignLatinTextIsNotAcceptedAsEnglishWithoutEnglishDetection() {
        TextDetectionLookup spanish = spanishLookup();

        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "en", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "en", TextDetectionLookup.NONE));
        assertEquals("Esta canción ya está en español", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "en",
                text -> DetectionResult.detected("", text, ScriptClassifier.ScriptClass.LATIN, "en", 0.9)));
    }

    @Test
    public void accentedProviderTextIsNotAcceptedForAnUnrelatedTarget() {
        TextDetectionLookup spanish = spanishLookup();

        // Regression probes: Spanish text with Vietnamese selected, and English text supplied
        // for a Spanish target, must not be accepted on accents or on the source lyric's language.
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "vi", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "esta canción", "Hello my friend", "", "es", spanish));
    }

    @Test
    public void providerDetectionOfTheTranslationTextDecides() {
        TextDetectionLookup spanish = spanishLookup();
        TextDetectionLookup englishForHola = text -> "Hola amigo".equals(text)
                ? DetectionResult.detected("", text, ScriptClassifier.ScriptClass.LATIN, "en", 0.9)
                : null;

        assertEquals("Esta canción ya está en español", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "es", spanish));
        assertEquals("Hola amigo", ProviderTranslationResolver.resolve(
                "hello friend", "Hola amigo", "", "es", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "hello friend", "Hola amigo", "", "es", englishForHola));
        assertEquals("", ProviderTranslationResolver.resolve(
                "hello friend", "Hola amigo", "", "es", TextDetectionLookup.NONE));
    }

    @Test
    public void providerResolutionNeverInvokesTheDetector() {
        long before = LatinLanguageGate.detectionCountForTest();
        TextDetectionLookup spanish = spanishLookup();

        assertEquals("Esta canción ya está en español", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "es", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "vi", spanish));

        assertEquals(before, LatinLanguageGate.detectionCountForTest());
    }

    /** Detection that only vouches for the two Spanish strings used by these tests. */
    private static TextDetectionLookup spanishLookup() {
        return text -> text != null && (text.startsWith("Esta") || text.startsWith("Hola"))
                ? DetectionResult.detected("", text, ScriptClassifier.ScriptClass.LATIN, "es", 0.9)
                : null;
    }

    @Test
    public void chineseVariantsDoNotCrossTargets() {
        assertEquals("这首歌", ProviderTranslationResolver.resolve("this song", "这首歌", "zh", "zh"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "這首歌", "zh", "zh"));
        assertEquals("這首歌", ProviderTranslationResolver.resolve("this song", "這首歌", "zh", "zh-TW"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "这首歌", "zh-Hans", "zh-TW"));
    }

    @Test
    public void unlabelledHanProviderTextIsAcceptedForTheMatchingChineseTarget() {
        // Script inference is one of the evidence kinds this resolver documents, and Han was the
        // one script it never inferred. A Chinese provider translation with no declared language
        // therefore inferred as nothing, and the only way left to accept it was a session
        // detection of that exact string - so with none stored, every Chinese translation was
        // withheld and the lines went off to be machine-translated instead.
        assertEquals("这首歌", ProviderTranslationResolver.resolve("this song", "这首歌", "", "zh"));
        assertEquals("這首歌", ProviderTranslationResolver.resolve("this song", "這首歌", "", "zh-TW"));
        // The variants still must not cross: a Traditional translation is not a zh-Hans answer.
        assertEquals("", ProviderTranslationResolver.resolve("this song", "這首歌", "", "zh"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "这首歌", "", "zh-TW"));
        // Kana and Hangul are tested before Han, so Japanese and Korean are not read as Chinese.
        assertEquals("", ProviderTranslationResolver.resolve("this song", "この歌です", "", "zh"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "이 노래", "", "zh"));
        // Latin is still not assumed to be English without its own detection.
        assertEquals("", ProviderTranslationResolver.resolve("这首歌", "this song", "", "en"));
    }

    @Test
    public void unlabelledScriptsTheProjectCanDetectAreInferredToo() {
        // Cyrillic, Devanagari and Gurmukhi had predicates in SpicyTextDetection but were never
        // consulted here, so a Russian, Hindi or Punjabi provider translation was withheld exactly
        // as the Chinese one was. Every line of such a song fell through to machine translation.
        assertEquals("Эта песня", ProviderTranslationResolver.resolve("this song", "Эта песня", "", "ru"));
        assertEquals("यह गाना", ProviderTranslationResolver.resolve("this song", "यह गाना", "", "hi"));
        assertEquals("ਇਹ ਗੀਤ", ProviderTranslationResolver.resolve("this song", "ਇਹ ਗੀਤ", "", "pa"));
        // And each still must not satisfy an unrelated target.
        assertEquals("", ProviderTranslationResolver.resolve("this song", "Эта песня", "", "zh"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "यह गाना", "", "ru"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "ਇਹ ਗੀਤ", "", "hi"));
    }

    @Test
    public void providerTranslationFieldsSurviveCopies() {
        LyricsLine source = new LyricsLine();
        source.providerTranslatedText = "Hello";
        source.providerTranslationLanguage = "en";
        BackgroundLine background = new BackgroundLine();
        background.providerTranslatedText = "Background";
        background.providerTranslationLanguage = "en";
        source.backgroundLines.add(background);

        LyricsLine copy = LyricsLine.copyOf(source);
        assertEquals("Hello", copy.providerTranslatedText);
        assertEquals("en", copy.providerTranslationLanguage);
        assertEquals("Background", copy.backgroundLines.get(0).providerTranslatedText);
        assertEquals("en", copy.backgroundLines.get(0).providerTranslationLanguage);
    }

    @Test
    public void matchingProviderTranslationSatisfiesGeneratedWorkForThatLine() {
        LyricsDocument document = new LyricsDocument();
        LyricsLine line = new LyricsLine();
        line.text = "你好";
        line.providerTranslatedText = "Hello";
        line.providerTranslationLanguage = "en";
        document.lines.add(line);

        assertEquals(1, ProviderTranslationResolver.applyTranslations(document, "en"));
        assertTrue(LyricsDocumentProcessor.hasDisplayedTranslation(document));
        assertFalse(LyricsDocumentProcessor.hasGeneratedTranslationWork(document, "zh", "en"));
    }

    @Test
    public void failedOrPartialGeneratedPassIsNotComplete() {
        assertFalse(LyricsMeaningLane.translationPassComplete(true,
                Arrays.asList(1, 2), new HashSet<>(Collections.singletonList(1))));
        assertTrue(LyricsMeaningLane.translationPassComplete(true,
                Arrays.asList(1, 2), new HashSet<>(Arrays.asList(1, 2))));
        assertTrue(LyricsMeaningLane.translationPassComplete(false,
                Arrays.asList(1, 2), Collections.emptySet()));
    }
}
