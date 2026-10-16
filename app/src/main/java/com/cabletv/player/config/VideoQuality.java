package com.cabletv.player.config;

/**
 * The picture-quality tiers the remote cycles through.
 *
 * <p>These are a ceiling, not a request for a specific rendition: a station sending one quality
 * ignores them, and a station sending five gets the best one under the ceiling. They are tiers
 * rather than raw resolutions because that is how a viewer thinks about them, and because a
 * height a playlist happens to list is not one a viewer wants to be choosing between.
 *
 * <p>Capping costs nothing and gains nothing unless the source offers choices, which public
 * playlists mostly do not - so this is kept to a single keystroke rather than a settings screen
 * it would be a nuisance to reach.
 */
public final class VideoQuality {

    /** Let the player adapt: the best rendition that fits the connection. */
    public static final int TIER_AUTO = 0;
    /** Up to 1080p. */
    public static final int TIER_1080P = 1;
    /** Up to 720p. The usual choice for a box on a slow line. */
    public static final int TIER_720P = 2;
    /** Up to 480p. For a metered or congested connection. */
    public static final int TIER_480P = 3;

    /** The tiers, in the order the remote cycles them. */
    private static final int[] TIERS = {TIER_AUTO, TIER_1080P, TIER_720P, TIER_480P};

    private VideoQuality() {
    }

    /**
     * The next tier after this one, wrapping around.
     *
     * <p>Unknown values fall through to auto: a stored value from a future version that removed a
     * tier should not leave the viewer stuck on a setting that no longer exists.
     */
    public static int next(int current) {
        int index = -1;
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i] == current) {
                index = i;
                break;
            }
        }
        return TIERS[(index + 1) % TIERS.length];
    }

    /**
     * The tallest picture height this tier allows, in pixels.
     *
     * @return the cap, or 0 for no cap
     */
    public static int maxHeight(int tier) {
        switch (tier) {
            case TIER_1080P:
                return 1080;
            case TIER_720P:
                return 720;
            case TIER_480P:
                return 480;
            default:
                return 0;
        }
    }
}
