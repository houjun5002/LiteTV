package com.cabletv.player.config;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups the channel list for navigation and remembers which channels are favourites.
 *
 * The rest of the app identifies a channel by its position in the flat playlist, so every index
 * this class accepts or returns is such a flat one; a group only decides which of those indices the
 * channel keys can reach.
 */
public class ChannelNavigator {

    /** The favourites group is always first, so its position does not move as playlists change. */
    public static final int FAVORITES_GROUP = 0;
    /** Every channel in the playlist, for viewers who would rather not be fenced into a group. */
    public static final int ALL_GROUP = 1;
    /** The playlist's own groups start after the two built-in ones. */
    private static final int FIRST_PLAYLIST_GROUP = 2;

    /** A navigable group: the playlist's own groups, with the built-in ones in front of them. */
    public static class Group {
        /** Playlist group name; empty for the built-in groups, whose labels belong to the UI. */
        public final String name;
        public final boolean favorites;
        public final boolean all;
        /** Positions in the flat channel list, in playlist order. */
        public final List<Integer> channels;

        Group(String name, boolean favorites, boolean all, List<Integer> channels) {
            this.name = name;
            this.favorites = favorites;
            this.all = all;
            this.channels = Collections.unmodifiableList(channels);
        }
    }

    private final Set<String> mFavorites = new LinkedHashSet<>(AppConfig.getFavoriteChannels());
    private List<Channel> mFlatChannels = Collections.emptyList();
    private List<Group> mGroups = new ArrayList<>();
    /** The group the channel keys walk while watching full screen; everything, until told otherwise. */
    private int mCurrentGroup = ALL_GROUP;

    public ChannelNavigator() {
        setChannels(null, null);
    }

    public void setChannels(List<ChannelGroup> groups, List<Channel> flatChannels) {
        mFlatChannels = flatChannels != null ? flatChannels : Collections.<Channel>emptyList();

        List<Group> built = new ArrayList<>();
        built.add(buildFavoritesGroup());
        built.add(buildAllGroup());
        int flatIndex = 0;
        if (groups != null) {
            for (ChannelGroup group : groups) {
                List<Integer> indices = new ArrayList<>();
                if (group.channels != null) {
                    for (int i = 0; i < group.channels.size(); i++) {
                        // The flat list is the groups concatenated in order, so counting along
                        // them yields the same indices the repository handed out.
                        indices.add(flatIndex++);
                    }
                }
                if (!indices.isEmpty()) {
                    built.add(new Group(group.name != null ? group.name : "", false, false, indices));
                }
            }
        }
        mGroups = built;
        if (mCurrentGroup >= mGroups.size()) {
            mCurrentGroup = ALL_GROUP;
        }
    }

    public int groupCount() {
        return mGroups.size();
    }

    public Group group(int groupIndex) {
        return groupIndex >= 0 && groupIndex < mGroups.size() ? mGroups.get(groupIndex) : null;
    }

    public int currentGroupIndex() {
        return mCurrentGroup;
    }

    public void setCurrentGroupIndex(int groupIndex) {
        if (groupIndex >= 0 && groupIndex < mGroups.size()) {
            mCurrentGroup = groupIndex;
        }
    }

    /**
     * @return the playlist group holding this channel, never a built-in group: a channel is always
     *         somewhere in the playlist, but only sometimes a favourite.
     */
    public int playlistGroupOf(int channelIndex) {
        for (int i = FIRST_PLAYLIST_GROUP; i < mGroups.size(); i++) {
            if (mGroups.get(i).channels.contains(channelIndex)) {
                return i;
            }
        }
        return ALL_GROUP;
    }

    /**
     * @return the group the channel keys should walk once this channel is playing: the current one
     *         when it holds the channel — so tuning by number inside "All" stays in "All" — and
     *         otherwise the playlist group the channel came from.
     */
    public int homeGroupFor(int channelIndex) {
        return positionOf(mCurrentGroup, channelIndex) >= 0 ? mCurrentGroup : playlistGroupOf(channelIndex);
    }

    public List<Channel> channelsOf(int groupIndex) {
        List<Channel> channels = new ArrayList<>();
        Group group = group(groupIndex);
        if (group != null) {
            for (int index : group.channels) {
                if (index >= 0 && index < mFlatChannels.size()) {
                    channels.add(mFlatChannels.get(index));
                }
            }
        }
        return channels;
    }

    /** @return the channels at those flat indices, skipping any that are out of range. */
    public List<Channel> channelsOf(List<Integer> indices) {
        List<Channel> channels = new ArrayList<>();
        if (indices == null) {
            return channels;
        }
        for (int index : indices) {
            if (index >= 0 && index < mFlatChannels.size()) {
                channels.add(mFlatChannels.get(index));
            }
        }
        return channels;
    }

    /** @return the flat index of the channel shown at that row of the group, or -1. */
    public int channelIndexAt(int groupIndex, int position) {
        Group group = group(groupIndex);
        if (group == null || position < 0 || position >= group.channels.size()) {
            return -1;
        }
        return group.channels.get(position);
    }

    /** @return which row of the group shows this channel, or -1 when it is not in the group. */
    public int positionOf(int groupIndex, int channelIndex) {
        Group group = group(groupIndex);
        return group != null ? group.channels.indexOf(channelIndex) : -1;
    }

    /**
     * Walks one group, wrapping at its ends, so the channel keys stay inside the group the viewer
     * chose instead of wandering into an unrelated part of the playlist.
     *
     * @return the flat index to tune to, or -1 when the group is empty
     */
    public int step(int groupIndex, int channelIndex, int delta) {
        Group group = group(groupIndex);
        if (group == null || group.channels.isEmpty()) {
            return -1;
        }
        int count = group.channels.size();
        int position = group.channels.indexOf(channelIndex);
        if (position < 0) {
            // Tuned outside the group (a channel number was typed, say): enter at the end the
            // viewer is heading towards.
            return group.channels.get(delta >= 0 ? 0 : count - 1);
        }
        return group.channels.get(((position + delta) % count + count) % count);
    }

    public boolean isFavorite(Channel channel) {
        return channel != null && channel.name != null && mFavorites.contains(channel.name);
    }

    /**
     * Adds or removes a favourite and saves the result immediately, so a favourite survives the app
     * being killed right after it was marked.
     *
     * @return true when the channel is a favourite afterwards
     */
    public boolean toggleFavorite(Channel channel) {
        if (channel == null || channel.name == null || channel.name.isEmpty()) {
            return false;
        }
        boolean nowFavorite = !mFavorites.remove(channel.name);
        if (nowFavorite) {
            mFavorites.add(channel.name);
        }
        AppConfig.setFavoriteChannels(new ArrayList<>(mFavorites));
        if (mGroups.isEmpty()) {
            mGroups.add(buildFavoritesGroup());
        } else {
            mGroups.set(FAVORITES_GROUP, buildFavoritesGroup());
        }
        return nowFavorite;
    }

    private Group buildFavoritesGroup() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < mFlatChannels.size(); i++) {
            if (isFavorite(mFlatChannels.get(i))) {
                indices.add(i);
            }
        }
        return new Group("", true, false, indices);
    }

    private Group buildAllGroup() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < mFlatChannels.size(); i++) {
            indices.add(i);
        }
        return new Group("", false, true, indices);
    }

    // ---- searching -----------------------------------------------------------------------

    /**
     * Finds the channels matching a query.
     *
     * <p>Two ways to match: the channel's name contains the query, or its initials do. The
     * second is what makes search usable on a television remote — typing "CCTV5" is fine, but a
     * Chinese name is not something anyone wants to spell out on a D-pad, and the initials of one
     * are a couple of presses.
     *
     * <p>Matching ignores case, separators and full/half-width differences, so "cctv5", "CCTV-5"
     * and "ＣＣＴＶ５" all find the same channel.
     *
     * @param query what the viewer has typed so far; blank returns nothing
     * @return flat playlist indices, best match first, capped
     */
    public List<Integer> search(String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) {
            return new ArrayList<>();
        }
        ChannelSearch search = new ChannelSearch(mFlatChannels);
        return search.matching(needle);
    }

    /**
     * Whether a channel name matches a query, for a caller that already has the needle normalised.
     * Exposed so the list can highlight and the settings screen can reuse the same rules.
     */
    public static boolean matches(String channelName, String rawQuery) {
        String needle = normalize(rawQuery);
        if (needle.isEmpty()) {
            return false;
        }
        return normalize(channelName).contains(needle)
                || initialsOf(channelName).contains(needle);
    }

    /**
     * The initials of a name: the letters and digits of a Latin one, and the first letter of each
     * Chinese syllable for a Chinese one.
     *
     * <p>Syllable initials come from {@link PinyinInitials}. A character it does not know is
     * skipped rather than made to fail the whole match: the name itself has already been searched
     * by the time this runs, so an incomplete table can only ever miss, never misfire.
     */
    static String initialsOf(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) (c + 32));
            } else {
                String initial = PinyinInitials.of(c);
                if (initial != null) {
                    out.append(initial);
                }
            }
        }
        return out.toString();
    }

    /**
     * Strips what does not distinguish one channel name from another: case, separators, and the
     * full-width forms a playlist may have used for either.
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Full-width ASCII (ＣＣＴＶ５) folds onto its half-width form.
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }
            if (c == '-' || c == '_' || c == ' ' || c == '\u3000' || c == '\u00b7' || c == '+') {
                continue;
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * Ranks channels against a search query.
     *
     * <p>Split out from the navigator so the ranking is a plain function of (channels, query) and
     * can be exercised without a device.
     */
    static final class ChannelSearch {

        /**
         * Upper bound on results. The list shows one column of them, and a query matching half the
         * playlist is not something a viewer can use anyway.
         */
        static final int MAX_RESULTS = 60;

        private final List<Channel> mChannels;

        ChannelSearch(List<Channel> channels) {
            mChannels = channels;
        }

        /** @return flat indices, exact name first, then prefix, then contains, then initials. */
        List<Integer> matching(String needle) {
            List<Integer> exact = new ArrayList<>();
            List<Integer> prefix = new ArrayList<>();
            List<Integer> contains = new ArrayList<>();
            List<Integer> initials = new ArrayList<>();
            for (int i = 0; i < mChannels.size(); i++) {
                Channel channel = mChannels.get(i);
                if (channel == null || channel.name == null) {
                    continue;
                }
                String name = normalize(channel.name);
                if (name.equals(needle)) {
                    exact.add(i);
                } else if (name.startsWith(needle)) {
                    prefix.add(i);
                } else if (name.contains(needle)) {
                    contains.add(i);
                } else if (initialsOf(channel.name).contains(needle)) {
                    initials.add(i);
                }
            }
            // A channel whose name starts with the query is a much better guess than one that
            // merely mentions it, so the tiers stay separate and in that order.
            List<Integer> results = new ArrayList<>();
            results.addAll(exact);
            results.addAll(prefix);
            results.addAll(contains);
            results.addAll(initials);
            return results.size() > MAX_RESULTS
                    ? new ArrayList<>(results.subList(0, MAX_RESULTS))
                    : results;
        }
    }
}
