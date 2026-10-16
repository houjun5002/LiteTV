package com.cabletv.player.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The playlists the app falls back to when the user has not configured one, so a fresh install
 * shows live TV instead of an empty screen.
 *
 * <p>The order is the fallback order: the first one that loads wins. Every entry is a mirror
 * rather than a canonical GitHub URL, because {@code raw.githubusercontent.com} is unreachable
 * from many home networks in mainland China - which is where these boxes live. A mirror of the
 * same playlist is worth more than the original file nobody can download.
 *
 * <p>Two properties decide which playlists are worth carrying, and both were measured rather than
 * assumed. The first is <em>how many sources each channel has</em>: a playlist that gives CCTV2 a
 * single URL makes failover impossible, and the channel dies with that one host. The second is
 * whether the hosts answer from a home connection at all. A playlist is only as good as the
 * second-best source behind each channel.
 */
public final class BuiltInSources {

    /**
     * Groups a built-in playlist is narrowed to. A public aggregate lists hundreds of channels
     * across dozens of groups; on a television remote, CCTV and the satellite channels are what
     * the EPG-free "just turn it on" case needs, and the rest is one long scroll away.
     */
    public static final String GROUP_CCTV = "央视频道";
    public static final String GROUP_SATELLITE = "卫视频道";

    /**
     * The endpoints, in the order they are tried, each through a different route.
     *
     * <p>best-fan comes first because it is the only one of the two candidates that carries
     * several sources per channel - six for CCTV1, seven for CCTV2 - with logos and EPG ids.
     * zbds carries one source for each, and the measurement that mattered was that several of
     * those single sources do not answer from a home connection at all: CCTV2's only entry timed
     * out at eight seconds, so the channel was simply unwatchable through that playlist while a
     * dozen working alternatives existed elsewhere. A playlist where every channel has a fallback
     * is worth more than a larger one where none does.
     */
    private static final List<Source> SOURCES = Collections.unmodifiableList(Arrays.asList(
            new Source("best-fan 央视专版（推荐）",
                    "https://gh-proxy.com/raw.githubusercontent.com/best-fan/iptv-sources/master/cn_cctv.m3u8"),
            new Source("CCSH 精简版（央视 + 卫视）",
                    "https://cdn.jsdelivr.net/gh/CCSH/IPTV@main/live_lite.m3u"),
            new Source("best-fan 卫视专版",
                    "https://gh-proxy.com/raw.githubusercontent.com/best-fan/iptv-sources/master/cn_province.m3u8"),
            new Source("CCSH 精简版 · 备用线路",
                    "https://gh-proxy.com/raw.githubusercontent.com/CCSH/IPTV/refs/heads/main/live_lite.m3u")
    ));

    private BuiltInSources() {
    }

    /** A playlist endpoint and the name to show the viewer for it. */
    public static final class Source {
        public final String label;
        public final String url;

        Source(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    /** The playlist a fresh install starts on. */
    public static String primary() {
        return SOURCES.get(0).url;
    }

    /**
     * The playlists worth trying after {@code currentUrl} failed, in order.
     *
     * A URL that is not one of ours (the user's own, or none at all) is followed by the whole
     * list: the user's source is preferred but not trusted to be the only one that works.
     */
    public static List<String> fallbacksFrom(String currentUrl) {
        int index = indexOf(currentUrl);
        int from = index < 0 ? 0 : index + 1;
        List<String> urls = new ArrayList<>();
        for (int i = from; i < SOURCES.size(); i++) {
            urls.add(SOURCES.get(i).url);
        }
        return urls;
    }

    /**
     * Whether a playlist is one of ours, mirror or not.
     *
     * <p>Matching the exact endpoints is not enough. The same playlist is served through several
     * hosts, and a viewer who pasted the canonical GitHub URL - or who upgraded from a version
     * that shipped it - has a URL that is not on the list while the content is identical. Treating
     * that as a personal playlist meant it was not narrowed, so the viewer's channel list grew to
     * every group the aggregate carries - the movies, the local stations, and the "更新时间"
     * timestamp entry that is not a channel at all.
     *
     * <p>So the identifier is matched on the path rather than the host: any endpoint serving one
     * of these projects' playlists is one of ours.
     */
    public static boolean isBuiltIn(String url) {
        if (url == null) {
            return false;
        }
        if (indexOf(url) >= 0) {
            return true;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String marker : FINGERPRINTS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Substrings identifying the upstream projects whose playlists are narrowed.
     *
     * <p>Deliberately path-shaped rather than host-shaped, so every mirror of the same content is
     * recognised however it is reached.
     */
    private static final List<String> FINGERPRINTS = Collections.unmodifiableList(Arrays.asList(
            "ccsh/iptv",
            "best-fan/iptv-sources"
    ));

    /** Every built-in playlist with a display name, for the settings screen and the web page. */
    public static List<Source> all() {
        return new ArrayList<>(SOURCES);
    }

    /** The groups {@link #GROUP_CCTV} and {@link #GROUP_SATELLITE}, for the parser to narrow to. */
    public static List<String> nationalGroups() {
        return Arrays.asList(GROUP_CCTV, GROUP_SATELLITE);
    }

    private static int indexOf(String url) {
        if (url == null) {
            return -1;
        }
        for (int i = 0; i < SOURCES.size(); i++) {
            if (SOURCES.get(i).url.equals(url)) {
                return i;
            }
        }
        return -1;
    }
}
