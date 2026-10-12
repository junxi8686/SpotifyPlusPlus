package com.spotifyplusplus.settings;

/**
 * Whether a stored custom-font value names a file that is not on this device.
 *
 * <p>Both surfaces that render the font-coverage line had the same defect: they built a Typeface
 * from the path and asked that Typeface about coverage, and {@code Typeface.create} answers for
 * any string at all - an unknown path quietly becomes the platform default. The row then reported
 * a healthy coverage verdict for a font that is not installed, which is exactly the state a
 * restore produces when the setting carries an absolute path from another user id (see
 * {@link SpicyBackup}, which repairs the path when it can, and this class, which stops the row
 * from claiming the file is fine when it cannot).
 *
 * <p>Pure but for {@link java.io.File#isFile()}, so it is testable off-device.
 */
public final class FontPathState {
    private FontPathState() {
    }

    /** True when the value names a font file this device cannot open. */
    public static boolean isMissingFile(String path) {
        if (path == null) return false;
        String value = path.trim();
        if (value.isEmpty() || !namesAFile(value)) return false;
        return !new java.io.File(value).isFile();
    }

    /**
     * True when the value is a file path rather than an installed family name.
     *
     * <p>A family name ({@code sans-serif-medium}, {@code serif}) is a legitimate value this
     * setting accepts, and it can never be missing, so it must never be reported as one.
     */
    static boolean namesAFile(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || lower.endsWith(".ttf") || lower.endsWith(".otf");
    }
}
