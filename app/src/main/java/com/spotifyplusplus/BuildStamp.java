package com.spotifyplusplus;

/**
 * What this build calls itself, and where its own project lives.
 *
 * <p>The clue used to carry the upstream build counter, which is meaningless now that this is its
 * own release with its own version: the About row and every diagnostic report read {@link #FULL},
 * and the owner asked for both to say 1.0 rather than a fork of someone else's numbering.
 *
 * <p>{@link #PROJECT_URL} is the single place the project's address is written down. It replaces
 * the upstream repository in the About row, in the issue link and in the diagnostic policy link,
 * so there is one string to change rather than three that can drift apart.
 */
public final class BuildStamp {
    public static final String VERSION = "1.0";
    /** Empty on a plain release; a build with a clue reads {@code 1.0 [clue]}. */
    public static final String CLUE = "";
    public static final String FULL = CLUE.isEmpty() ? VERSION : VERSION + " [" + CLUE + "]";

    /** Where this project lives. Set to the owner's repository once it is published. */
    public static final String PROJECT_URL = "https://github.com/junxi8686/SpotifyPlusPlus";
    public static final String ISSUES_URL = PROJECT_URL + "/issues/new";
    public static final String POLICY_URL = PROJECT_URL + "/blob/main/DIAGNOSTIC_DATA_POLICY.md";

    private BuildStamp() {
    }
}
