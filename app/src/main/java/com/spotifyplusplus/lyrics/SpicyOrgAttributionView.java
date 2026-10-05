package com.spotifyplusplus.lyrics;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.net.URI;

/** Response credit with the contributor links supplied by the provider. */
public final class SpicyOrgAttributionView extends TextView {
    private LyricsDocument boundDocument;
    private boolean boundIncludeProvider = true;

    public SpicyOrgAttributionView(Context context) {
        super(context);
        setTextSize(12);
        setTextColor(Color.rgb(210, 210, 210));
        setLinkTextColor(Color.WHITE);
        setGravity(Gravity.CENTER);
        int padding = Math.round(6 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        setMovementMethod(LinkMovementMethod.getInstance());
        setVisibility(View.GONE);
    }

    public void bind(LyricsDocument document) {
        bind(document, true);
    }

    public void bind(LyricsDocument document, boolean includeProvider) {
        if (boundDocument == document && boundIncludeProvider == includeProvider) return;
        SpannableStringBuilder text = new SpannableStringBuilder();
        boolean previousContributor = false;
        for (SpicyOrgAttribution.Credit credit : SpicyOrgAttribution.credits(document, includeProvider)) {
            if (text.length() > 0) text.append(previousContributor ? ", " : "\n");
            String label = localizeCredit(credit.label);
            int labelStart = text.length();
            text.append(label);
            previousContributor = credit.nameStart >= 0;
            if (credit.nameStart >= 0 && webLink(credit.url)) {
                // The link must begin at the contributor's name. Translating the prefix moves it,
                // so the offset is measured on the translated prefix instead of reusing the
                // provider's English one.
                int offset = localizedPrefixLength(credit.label, credit.nameStart);
                text.setSpan(new URLSpan(credit.url), labelStart + offset, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        boundDocument = document;
        boundIncludeProvider = includeProvider;
        setText(text);
        setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /**
     * Chinese rendering of the credit line.
     *
     * <p>The attribution is assembled in {@link SpicyOrgAttribution} in English because the
     * contributor names and URLs are provider data, but this is the line the reader sees under the
     * lyrics, so it follows the interface language like the rest of the panel. Only the prefixes
     * are translated - uploader and maker names are people's names and stay as they are.
     */
    private String localizeCredit(String label) {
        if (label == null || !chineseUi()) return label;
        if (label.startsWith("Lyrics from ")) return "歌词来源：" + label.substring(12);
        if (label.startsWith("uploaded by ")) return "上传者：" + label.substring(12);
        if (label.startsWith("made by ")) return "制作：" + label.substring(8);
        return label;
    }

    /** Length of the translated prefix, i.e. where the contributor's name begins. */
    private int localizedPrefixLength(String label, int originalOffset) {
        if (label == null || !chineseUi() || originalOffset <= 0 || originalOffset > label.length()) {
            return Math.max(0, originalOffset);
        }
        return localizeCredit(label.substring(0, originalOffset)).length();
    }

    /** True when the phone is set to Chinese, which is the language this build ships for. */
    private boolean chineseUi() {
        try {
            String language = java.util.Locale.getDefault().getLanguage();
            return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean webLink(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) { return false; }
    }
}
