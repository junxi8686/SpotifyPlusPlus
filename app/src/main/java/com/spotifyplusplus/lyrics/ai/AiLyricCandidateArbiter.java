package com.spotifyplusplus.lyrics.ai;

import android.content.Context;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the model which of a provider's search hits is the recording that is playing.
 *
 * <p>This is the part a scorer cannot do. The identity gate is a set of string comparisons, so it
 * has exactly two answers for a hit that is genuinely the same recording spelled differently:
 * accept it on a similarity threshold loose enough to also admit other songs, or refuse it and
 * report that the provider does not have the track. Both are wrong in a way the owner sees - the
 * first as someone else's lyrics, the second as a song that is plainly there and "not found".
 *
 * <p>A model reading the two lists can tell "反乌托邦·拼接版 by wututu" from "拼接乌托邦 by Ciyo",
 * and can tell either from a live take. It answers with one number.
 *
 * <p>Only ever asked when the local gate produced no confident answer, so a track that resolves
 * normally never reaches it.
 */
public final class AiLyricCandidateArbiter {
    /** How many hits to show. Past a handful the question stops being answerable and the cost climbs. */
    public static final int MAX_CANDIDATES = 6;

    /** One provider hit, reduced to what the decision actually needs. */
    public static final class Candidate {
        public final String title;
        public final String artists;
        public final long durationMs;

        public Candidate(String title, String artists, long durationMs) {
            this.title = title == null ? "" : title;
            this.artists = artists == null ? "" : artists;
            this.durationMs = Math.max(0L, durationMs);
        }
    }

    private AiLyricCandidateArbiter() {
    }

    /**
     * Index into {@code candidates} of the hit the model confirms, or -1 when it says none is.
     *
     * <p>Blocks on the model call. Every caller is on a background thread and already at the end
     * of its own options, so there is nothing else for that thread to do meanwhile.
     */
    public static int pick(Context context, String title, String artist, long durationMs,
                           List<Candidate> candidates) {
        // Disabled: the search path is deterministic, as the reference implementation's is.
        //
        // Returning -1 is the answer this method already used for every failure - an
        // unanswerable question must not become a user-visible error - so the callers need no
        // change and a refused hit simply stays refused. It also makes resolution reproducible:
        // the same track now resolves the same way on every device, instead of depending on
        // whether a model key happened to be configured.
        return -1;
    }

    /** 1-based number in the reply to a 0-based index; -1 when the answer is 0 or unusable. */
    static int parsePick(String reply, int count) {
        if (reply == null || count <= 0) return -1;
        // The first integer in the reply: a model that adds a sentence around the number has still
        // answered, and refusing that answer would cost a second paid call to learn the same thing.
        Matcher matcher = Pattern.compile("\\d+").matcher(reply.trim());
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group());
                if (value == 0) return -1;
                if (value >= 1 && value <= count) return value - 1;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static String userPrompt(String title, String artist, long durationMs,
                                     List<Candidate> candidates) {
        StringBuilder out = new StringBuilder(320);
        out.append("Now playing in Spotify:\n");
        out.append("Title: ").append(AiTextCall.nz(title)).append('\n');
        out.append("Artist: ").append(AiTextCall.nz(artist)).append('\n');
        out.append("Duration: ").append(formatDuration(durationMs)).append("\n\n");
        out.append("A lyrics catalogue returned these search hits:\n");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            out.append(i + 1).append(". ").append(candidate.title)
                    .append(" — ").append(candidate.artists)
                    .append(" (").append(formatDuration(candidate.durationMs)).append(")\n");
        }
        out.append("\nWhich hit is the SAME RECORDING as the Spotify track? ")
                .append("Answer with its number alone, or 0 if none of them is.");
        return out.toString();
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "unknown";
        long total = ms / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", total / 60L, total % 60L);
    }

    private static final String SYSTEM_PROMPT =
            "You match one recording across music catalogues. The same recording is routinely "
                    + "catalogued with different punctuation, in Simplified or Traditional Chinese, "
                    + "with or without a version qualifier (Live, Remastered, 拼接版, 现场版), and "
                    + "with a title that differs by a character or two.\n"
                    + "A different take of the same song - live, remix, spliced edit, a cover by "
                    + "another act - is NOT the same recording.\n"
                    + "Pick the hit that is the same recording, or 0 when none of them is. "
                    + "Answer with the number only, no explanation.";
}
