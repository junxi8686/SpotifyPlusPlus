package com.spotifyplusplus.lyrics;

/** Projects one sentence's progress onto its visual lines in reading order. */
final class SentenceFill {
    private SentenceFill() { }

    static float lineGradient(float gradient, float totalWidth, float precedingWidth, float lineWidth) {
        float progress = (gradient - LyricAnimations.GRADIENT_UNSUNG) / LyricAnimations.GRADIENT_RANGE;
        float local = (Math.max(0f, Math.min(1f, progress)) * totalWidth - precedingWidth)
                / Math.max(1f, lineWidth);
        return LyricAnimations.gradientPosition(Math.max(0f, Math.min(1f, local)));
    }

    /**
     * Maps laid-out word rectangles to visual rows, including rows inside timing groups.
     *
     * <p>A sentence's fill has to know which of its words share a printed row, and the flexbox's own
     * child list cannot answer that: a timing group is one child whose words may wrap across two or
     * more rows. Rows are found from the rectangles themselves, top to bottom, and a word joins the
     * row already open when its top is above that row's vertical middle.
     */
    static int[] visualLineIndices(float[][] bounds) {
        Integer[] order = new Integer[bounds.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(bounds[a][1], bounds[b][1]));
        int[] lines = new int[bounds.length];
        int line = -1;
        float top = 0f, bottom = 0f;
        for (int index : order) {
            float[] box = bounds[index];
            if (line < 0 || box[1] >= (top + bottom) / 2f) {
                line++;
                top = box[1];
                bottom = box[3];
            } else {
                top = Math.min(top, box[1]);
                bottom = Math.max(bottom, box[3]);
            }
            lines[index] = line;
        }
        return lines;
    }
}
