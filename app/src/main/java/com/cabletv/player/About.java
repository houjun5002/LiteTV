package com.cabletv.player;

import com.cabletv.player.config.BuiltInSources;

import java.util.ArrayList;
import java.util.List;

/**
 * What the app says about itself: where its source is, what it is licensed under, and where the
 * channel data comes from.
 *
 * <p>Kept in one place because these are statements about the project, not about any one screen,
 * and because the same details are shown in the settings screen and over the web configuration
 * page - two copies of a licence name is how they end up disagreeing.
 *
 * <p>The provenance list is not decoration. The app aggregates public playlists it does not own,
 * and saying so plainly is what separates it from the apps that ship the same data while implying
 * they provide it.
 */
public final class About {

    /** The project's public repository. */
    public static final String SOURCE_URL = "https://github.com/vibe4free/LiteTV";

    /** The licence the project is released under. */
    public static final String LICENSE_NAME = "Apache License 2.0";

    private About() {
    }

    /**
     * The upstream projects the built-in playlists come from.
     *
     * <p>Built from {@link BuiltInSources} rather than written out, so a source that is added or
     * removed cannot leave this list claiming something the app no longer uses.
     */
    public static List<String> dataSources() {
        List<String> sources = new ArrayList<>();
        for (BuiltInSources.Source source : BuiltInSources.all()) {
            sources.add(source.label + "\n" + source.url);
        }
        return sources;
    }
}
