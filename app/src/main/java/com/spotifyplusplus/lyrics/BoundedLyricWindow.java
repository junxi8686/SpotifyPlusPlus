package com.spotifyplusplus.lyrics;

/**
 * Computes the attached row range for the native Spicy lyrics ScrollView.
 *
 * This is an incremental virtualization step: short songs still render fully,
 * while long songs keep a bounded active window attached to reduce view count
 * and per-frame animation work without replacing the renderer with RecyclerView.
 */
public final class BoundedLyricWindow {
    private final int fullRenderThreshold;
    private final int beforeActive;
    private final int afterActive;

    public BoundedLyricWindow(int fullRenderThreshold, int beforeActive, int afterActive) {
        this.fullRenderThreshold = Math.max(1, fullRenderThreshold);
        this.beforeActive = Math.max(0, beforeActive);
        this.afterActive = Math.max(0, afterActive);
    }

    public Range rangeFor(int totalLines, int activeIndex) {
        if (totalLines <= 0) return new Range(0, -1, true);
        if (totalLines <= fullRenderThreshold) return new Range(0, totalLines - 1, true);

        int anchor = activeIndex >= 0 ? activeIndex : 0;
        anchor = Math.max(0, Math.min(totalLines - 1, anchor));

        int start = Math.max(0, anchor - beforeActive);
        int end = Math.min(totalLines - 1, anchor + afterActive);

        int desired = Math.min(totalLines, beforeActive + afterActive + 1);
        int current = end - start + 1;
        if (current < desired) {
            int growBefore = Math.min(start, desired - current);
            start -= growBefore;
            current = end - start + 1;
            int growAfter = Math.min(totalLines - 1 - end, desired - current);
            end += growAfter;
        }

        return new Range(start, end, false);
    }

    public static final class Range {
        public final int start;
        public final int end;
        public final boolean fullRender;

        private Range(int start, int end, boolean fullRender) {
            this.start = start;
            this.end = end;
            this.fullRender = fullRender;
        }

        public boolean contains(int index) {
            return index >= start && index <= end;
        }
    }
}
