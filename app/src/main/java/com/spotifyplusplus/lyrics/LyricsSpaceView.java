package com.spotifyplusplus.lyrics;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Height-only spacer used by the lyric row virtualizer. */
public final class LyricsSpaceView extends View {
    public LyricsSpaceView(Context context, int height) {
        super(context);
        setHeightPx(height);
    }

    public void setHeightPx(int height) {
        int target = Math.max(0, height);
        ViewGroup.LayoutParams existing = getLayoutParams();
        if (existing instanceof LinearLayout.LayoutParams
                && existing.height == target
                && existing.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            return;
        }
        setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, target));
    }
}
