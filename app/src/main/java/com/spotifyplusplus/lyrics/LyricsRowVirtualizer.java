package com.spotifyplusplus.lyrics;

import java.util.List;

/** Pure row-window math for the bounded lyric renderer. */
public final class LyricsRowVirtualizer {
    private LyricsRowVirtualizer() {
    }

    public static int[] buildRowHeightPrefix(List<AppliedLine> lines, RowHeightEstimator estimator) {
        int count = lines == null ? 0 : lines.size();
        int[] prefix = new int[count + 1];
        for (int i = 0; i < count; i++) {
            int height = estimator == null ? 0 : estimator.heightForIndex(i);
            prefix[i + 1] = prefix[i] + Math.max(0, height);
        }
        return prefix;
    }

    public static int findLineIndexForOffset(int[] prefix, int offsetPx, int lineCount) {
        if (prefix == null || prefix.length <= 1 || lineCount <= 0) return 0;
        int target = Math.max(0, offsetPx);
        int low = 1;
        int high = Math.min(prefix.length - 1, lineCount);
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (target < prefix[mid]) high = mid;
            else low = mid + 1;
        }
        return Math.max(0, Math.min(lineCount - 1, low - 1));
    }

    public static SpacerHeights spacerHeights(int[] prefix, int count, int renderedWindowStart, int renderedWindowEnd) {
        if (prefix == null || prefix.length == 0 || count <= 0) return new SpacerHeights(0, 0);
        int safeCount = Math.min(count, prefix.length - 1);
        int topIndex = Math.max(0, Math.min(renderedWindowStart, safeCount));
        int bottomStart = Math.max(0, Math.min(renderedWindowEnd + 1, safeCount));
        int topHeight = prefix[topIndex];
        int bottomHeight = prefix[safeCount] - prefix[bottomStart];
        return new SpacerHeights(topHeight, bottomHeight);
    }

    public static boolean shouldRemountWindowForViewport(
            int count,
            boolean dirty,
            int renderedWindowStart,
            int renderedWindowEnd,
            int fullRenderThreshold,
            int edgeBuffer,
            int anchor
    ) {
        if (count <= 0) return false;
        if (dirty || renderedWindowEnd < renderedWindowStart) return true;
        if (count <= fullRenderThreshold) return false;
        return anchor <= renderedWindowStart + edgeBuffer
                || anchor >= renderedWindowEnd - edgeBuffer;
    }

    public interface RowHeightEstimator {
        int heightForIndex(int index);
    }

    public static final class SpacerHeights {
        public final int top;
        public final int bottom;

        public SpacerHeights(int top, int bottom) {
            this.top = Math.max(0, top);
            this.bottom = Math.max(0, bottom);
        }
    }
}
