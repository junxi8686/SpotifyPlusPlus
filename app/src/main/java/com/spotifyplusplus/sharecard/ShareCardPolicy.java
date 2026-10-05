package com.spotifyplusplus.sharecard;

import android.content.Context;
import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.SpicyOrgAttribution;
import com.spotifyplusplus.lyrics.providers.SpicyOrgPolicy;
import com.spotifyplusplus.lyrics.providers.SpicyOrgAccessState;

/** Share cards retain response credit without exporting org lyrics as plain text. */
final class ShareCardPolicy {
    private ShareCardPolicy() { }

    static boolean usable(Context context, LyricsDocument document, long nowMs) {
        return !SpicyOrgPolicy.expires(document, nowMs)
                && (!SpicyOrgPolicy.isRestricted(document) || !SpicyOrgAccessState.isTerminated(context));
    }

    static boolean requiresImage(LyricsDocument document) {
        return SpicyOrgPolicy.isRestricted(document);
    }

    static String creditText(LyricsDocument document, boolean includeUrls) {
        StringBuilder text = new StringBuilder();
        boolean previousContributor = false;
        for (SpicyOrgAttribution.Credit credit : SpicyOrgAttribution.credits(document)) {
            if (text.length() > 0) text.append(!includeUrls && previousContributor ? ", " : "\n");
            text.append(credit.label);
            previousContributor = credit.nameStart >= 0;
            if (includeUrls && !credit.url.isEmpty()) text.append("\n").append(credit.url);
        }
        return text.toString();
    }

    static String sharedText(LyricsDocument document, String quote, String title, String artist,
                             String trackLink) {
        String text = title + " - " + artist;
        if (!requiresImage(document) && quote != null && !quote.trim().isEmpty()) {
            text = "\"" + quote + "\"\n" + text;
        }
        String credits = creditText(document, true);
        if (!credits.isEmpty()) text += "\n" + credits;
        return text + "\n" + trackLink;
    }
}