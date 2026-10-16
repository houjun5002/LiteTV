package com.cabletv.player.util;

import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uParser {
    private static final String TAG = "M3uParser";
    private static final String DEFAULT_GROUP_NAME = "Live";
    private static final Pattern NAME_PATTERN = Pattern.compile(".*,(.+?)$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("group-title=\"(.*?)\"");
    private static final Pattern TVG_LOGO_PATTERN = Pattern.compile("tvg-logo=\"(.*?)\"");
    private static final Pattern TVG_ID_PATTERN = Pattern.compile("tvg-id=\"(.*?)\"");
    private static final Pattern HTTP_USER_AGENT_PATTERN = Pattern.compile("http-user-agent=\"(.*?)\"");
    private static final Pattern HTTP_REFERRER_PATTERN = Pattern.compile("http-referr?er=\"(.*?)\"");
    /**
     * Catchup (回看/时移) attributes. Not an official M3U spec, but the de-facto convention
     * shared by TiviMate / IPTV Smarters / FongMi / rtp2httpd. Values are quoted strings, so
     * matching them is safe; the URL template is only ever fed to the hardened HTTP player.
     */
    private static final Pattern CATCHUP_PATTERN = Pattern.compile("catchup=\"(.*?)\"");
    private static final Pattern CATCHUP_SOURCE_PATTERN = Pattern.compile("catchup-source=\"(.*?)\"");
    private static final Pattern CATCHUP_REPLACE_PATTERN = Pattern.compile("catchup-replace=\"(.*?)\"");
    /**
     * EPG URL carried on the #EXTM3U header line. Not an official M3U attribute, but the de-facto
     * convention shared by Kodi ({@code url-tvg}), TiviMate / IPTVnator / iPlayTV / GSE
     * ({@code x-tvg-url}) and others ({@code tvg-url}). The value is a quoted string, so matching
     * it here is safe: no code ever runs on it, and only http(s) URLs pass the filter below.
     */
    private static final Pattern EPG_URL_ATTR_PATTERN = Pattern.compile(
            "(?i)(?:x-tvg-url|tvg-url|url-tvg)\\s*=\\s*[\"']([^\"']+)[\"']");
    /** Cap on how many embedded EPG fallbacks a playlist may ask us to try. */
    private static final int MAX_EPG_URLS = 5;

    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_REFERER = "Referer";

    public static List<ChannelGroup> parse(String content) {
        return parse(content, null);
    }

    /**
     * @param keepGroups groups to narrow the playlist to, or null/empty to keep every group.
     *                   A playlist that contains none of them is kept whole: narrowing is a
     *                   favour to the remote control, never a way to end up with nothing.
     */
    public static List<ChannelGroup> parse(String content, Collection<String> keepGroups) {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();

        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        if (content.startsWith("#EXTM3U")) {
            parseM3u(content, groupMap);
        } else {
            parseTxt(content, groupMap);
        }
        dropNonChannels(groupMap);
        mergeDuplicates(groupMap);
        narrowToGroups(groupMap, keepGroups);

        return new ArrayList<>(groupMap.values());
    }

    /**
     * Removes entries that are not channels.
     *
     * <p>Public playlists carry bookkeeping lines alongside the stations. CCSH's lists a progress
     * timestamp as an entry - {@code tvg-name="20261016 06:45"} pointing at a test clip - because
     * the generator writes its state into the same file. It parses like any other entry, so it
     * arrives in the channel list as a channel whose name is a date, and because the generator
     * writes it first, it is the one the app opens on a fresh start.
     *
     * <p>A name that is a timestamp, or nothing but punctuation, is not something a viewer could
     * be looking for. The test clip behind it is not the only stream a playlist carries that is
     * not television, so this rejects by shape rather than by the one name seen in the wild.
     */
    private static void dropNonChannels(Map<String, ChannelGroup> groupMap) {
        int dropped = 0;
        List<String> emptied = new ArrayList<>();
        for (Map.Entry<String, ChannelGroup> entry : groupMap.entrySet()) {
            ChannelGroup group = entry.getValue();
            if (group.channels == null || group.channels.isEmpty()) {
                continue;
            }
            List<Channel> kept = new ArrayList<>(group.channels.size());
            for (Channel channel : group.channels) {
                if (looksLikeChannel(channel)) {
                    kept.add(channel);
                } else {
                    dropped++;
                }
            }
            group.channels = kept;
            if (kept.isEmpty()) {
                // A group that held nothing but bookkeeping has no reason to appear in the menu.
                emptied.add(entry.getKey());
            }
        }
        for (String name : emptied) {
            groupMap.remove(name);
        }
        if (dropped > 0) {
            Log.i(TAG, "Dropped " + dropped + " non-channel entries");
        }
    }

    private static boolean looksLikeChannel(Channel channel) {
        if (channel == null || isEmpty(channel.name)) {
            return false;
        }
        String name = channel.name.trim();
        if (TIMESTAMP_PATTERN.matcher(name).matches()) {
            return false;
        }
        // A name with no letter and no ideograph is punctuation or digits: not a station name.
        boolean hasLetter = false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetter(name.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    /** A date, optionally with a time: the shape playlist generators use for their own state. */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\\d{4}[-/]?\\d{2}[-/]?\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2})?)?");

    /**
     * Collapses entries that name the same channel within a group into one entry carrying every
     * source. Public playlists list each channel once per source, so without this a 38-channel
     * CCTV lineup arrives as 455 separate channels of one source each: unreadable on a remote,
     * and - because each one has only a single URL - with nothing to fail over to. Every source
     * is kept, in playlist order, so playback can walk them until one plays.
     *
     * Groups are never merged: two channels of the same name in two groups are two channels.
     */
    private static void mergeDuplicates(Map<String, ChannelGroup> groupMap) {
        for (ChannelGroup group : groupMap.values()) {
            if (group.channels == null || group.channels.size() < 2) {
                continue;
            }
            Map<String, Channel> byName = new LinkedHashMap<>();
            List<Channel> merged = new ArrayList<>();
            for (Channel candidate : group.channels) {
                String key = candidate.name == null ? "" : candidate.name.trim();
                Channel target = byName.get(key);
                if (target == null) {
                    byName.put(key, candidate);
                    merged.add(candidate);
                } else {
                    mergeInto(target, candidate);
                }
            }
            if (merged.size() != group.channels.size()) {
                Log.d(TAG, "Merged " + group.channels.size() + " entries into " + merged.size()
                        + " channels in " + group.name);
            }
            group.channels = merged;
        }
    }

    /**
     * Folds {@code other} into {@code target}. The first entry stays the one on show, so its
     * name, logo and EPG id win; the later copies only contribute what the first one lacks, so
     * a duplicate can fill in a missing logo or catchup template without overwriting a good one.
     */
    private static void mergeInto(Channel target, Channel other) {
        for (String url : other.urls) {
            target.addUrl(url);
        }
        if (isEmpty(target.logo)) {
            target.logo = other.logo;
        }
        if (isEmpty(target.tvgId)) {
            target.tvgId = other.tvgId;
        }
        if (isEmpty(target.catchupType)) {
            target.catchupType = other.catchupType;
        }
        if (isEmpty(target.catchupSource)) {
            target.catchupSource = other.catchupSource;
        }
        if (isEmpty(target.catchupReplace)) {
            target.catchupReplace = other.catchupReplace;
        }
        if (other.headers != null) {
            if (target.headers == null) {
                target.headers = other.headers;
            } else {
                for (Map.Entry<String, String> entry : other.headers.entrySet()) {
                    if (!target.headers.containsKey(entry.getKey())) {
                        target.headers.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    /** Drops every group outside {@code keepGroups}, unless the playlist has none of them. */
    private static void narrowToGroups(Map<String, ChannelGroup> groupMap, Collection<String> keepGroups) {
        if (keepGroups == null || keepGroups.isEmpty()) {
            return;
        }
        List<String> present = new ArrayList<>();
        for (String name : keepGroups) {
            ChannelGroup group = groupMap.get(name);
            if (group != null && group.channels != null && !group.channels.isEmpty()) {
                present.add(name);
            }
        }
        if (present.isEmpty()) {
            Log.w(TAG, "None of the requested groups are present; keeping every group");
            return;
        }
        groupMap.keySet().retainAll(present);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Extracts the EPG feed URL(s) a playlist embeds on its {@code #EXTM3U} header line, e.g.
     * {@code #EXTM3U x-tvg-url="http://host/epg.xml.gz"}. Multiple values are tried in order, so
     * space- or pipe-separated lists keep their fallback meaning. Only http(s) URLs are accepted;
     * a value without a scheme is resolved against the playlist URL when that one is http(s), so
     * a relative path cannot point anywhere outside the playlist's own origin.
     */
    public static List<String> parseEpgUrls(String content, String playlistUrl) {
        if (content == null) {
            return Collections.emptyList();
        }
        String header = normalizeNewlines(content).split("\n", 2)[0];
        if (!header.startsWith("#EXTM3U")) {
            return Collections.emptyList();
        }

        List<String> urls = new ArrayList<>();
        Matcher matcher = EPG_URL_ATTR_PATTERN.matcher(header);
        while (matcher.find() && urls.size() < MAX_EPG_URLS) {
            for (String raw : matcher.group(1).split("[\\s|]+")) {
                String url = resolveEpgUrl(raw.trim(), playlistUrl);
                if (url != null && !urls.contains(url)) {
                    urls.add(url);
                    if (urls.size() >= MAX_EPG_URLS) {
                        break;
                    }
                }
            }
        }
        return urls;
    }

    /** @return an http(s) EPG URL, or null when the value is unusable. */
    private static String resolveEpgUrl(String value, String playlistUrl) {
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        // A relative path is only meaningful against an http(s) playlist; against a local file it
        // could name anything on the device, so it is dropped there.
        if (playlistUrl == null || playlistUrl.isEmpty()
                || !(playlistUrl.startsWith("http://") || playlistUrl.startsWith("https://"))) {
            return null;
        }
        try {
            URL resolved = new URL(new URL(playlistUrl), value);
            String scheme = resolved.getProtocol() == null ? "" : resolved.getProtocol().toLowerCase(Locale.US);
            return ("http".equals(scheme) || "https".equals(scheme)) ? resolved.toString() : null;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static void parseM3u(String content, Map<String, ChannelGroup> groupMap) {
        BufferedReader reader = new BufferedReader(new StringReader(normalizeNewlines(content)));
        String line;
        String currentGroupName = DEFAULT_GROUP_NAME;
        Channel pendingChannel = null;
        // The #EXTM3U header may carry catchup defaults that per-channel attributes override.
        String defaultCatchupType = "";
        String defaultCatchupSource = "";
        String defaultCatchupReplace = "";

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTM3U")) {
                    defaultCatchupType = extractPattern(line, CATCHUP_PATTERN);
                    defaultCatchupSource = extractPattern(line, CATCHUP_SOURCE_PATTERN);
                    defaultCatchupReplace = extractPattern(line, CATCHUP_REPLACE_PATTERN);
                    continue;
                }

                if (line.startsWith("#EXTINF")) {
                    pendingChannel = new Channel();
                    pendingChannel.name = extractPattern(line, NAME_PATTERN);
                    pendingChannel.group = extractPattern(line, GROUP_PATTERN);
                    if (pendingChannel.group.isEmpty()) {
                        pendingChannel.group = currentGroupName;
                    } else {
                        currentGroupName = pendingChannel.group;
                    }
                    pendingChannel.logo = extractPattern(line, TVG_LOGO_PATTERN);
                    pendingChannel.tvgId = extractPattern(line, TVG_ID_PATTERN);
                    putHeader(pendingChannel, HEADER_USER_AGENT,
                            extractPattern(line, HTTP_USER_AGENT_PATTERN));
                    putHeader(pendingChannel, HEADER_REFERER,
                            extractPattern(line, HTTP_REFERRER_PATTERN));
                    // A channel's own catchup wins; otherwise inherit the playlist default.
                    pendingChannel.catchupType = firstNonEmpty(
                            extractPattern(line, CATCHUP_PATTERN), defaultCatchupType);
                    pendingChannel.catchupSource = firstNonEmpty(
                            extractPattern(line, CATCHUP_SOURCE_PATTERN), defaultCatchupSource);
                    pendingChannel.catchupReplace = firstNonEmpty(
                            extractPattern(line, CATCHUP_REPLACE_PATTERN), defaultCatchupReplace);
                    continue;
                }

                // VLC carries the same information on its own option lines, which some
                // generators emit instead of the #EXTINF attributes.
                if (line.startsWith("#EXTVLCOPT:") && pendingChannel != null) {
                    applyVlcOption(pendingChannel, line.substring("#EXTVLCOPT:".length()));
                    continue;
                }

                if (line.startsWith("#")) continue;

                if (pendingChannel != null) {
                    String url = takeInlineHeaders(pendingChannel, line);
                    if (isValidUrl(url)) {
                        pendingChannel.addUrl(url);
                        groupFor(groupMap, pendingChannel.group).addChannel(pendingChannel);
                        pendingChannel = null;
                    }
                }
            }
        } catch (Exception e) {
            // A truncated or malformed playlist still yields every channel read so far.
            Log.w(TAG, "Stopped parsing M3U early: " + e);
        }
    }

    private static void parseTxt(String content, Map<String, ChannelGroup> groupMap) {
        BufferedReader reader = new BufferedReader(new StringReader(normalizeNewlines(content)));
        String line;
        String currentGroupName = DEFAULT_GROUP_NAME;

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.endsWith("#genre#")) {
                    // Accept both the traditional "央视频道,#genre#" and the comma-less
                    // "央视频道#genre#" a local file may well carry; neither may leak the
                    // marker into the group name.
                    String name = line.substring(0, line.length() - "#genre#".length()).trim();
                    if (name.endsWith(",")) {
                        name = name.substring(0, name.length() - 1).trim();
                    }
                    currentGroupName = name;
                    continue;
                }

                if (line.startsWith("#")) continue;

                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;

                Channel channel = new Channel();
                channel.name = parts[0].trim();
                channel.group = currentGroupName;
                // "name,url1#url2#url3": every URL is a source for the same channel, so keep
                // them all as fallbacks instead of dropping everything after the first.
                for (String urlPart : parts[1].trim().split("#")) {
                    String url = takeInlineHeaders(channel, urlPart.trim());
                    if (isValidUrl(url)) {
                        channel.addUrl(url);
                    }
                }
                if (channel.urlCount() > 0) {
                    groupFor(groupMap, currentGroupName).addChannel(channel);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Stopped parsing playlist early: " + e);
        }
    }

    private static ChannelGroup groupFor(Map<String, ChannelGroup> groupMap, String name) {
        ChannelGroup group = groupMap.get(name);
        if (group == null) {
            group = new ChannelGroup(name);
            groupMap.put(name, group);
        }
        return group;
    }

    private static void applyVlcOption(Channel channel, String option) {
        int eq = option.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = option.substring(0, eq).trim();
        String value = option.substring(eq + 1).trim();
        if ("http-user-agent".equalsIgnoreCase(key)) {
            putHeader(channel, HEADER_USER_AGENT, value);
        } else if ("http-referrer".equalsIgnoreCase(key) || "http-referer".equalsIgnoreCase(key)) {
            putHeader(channel, HEADER_REFERER, value);
        }
    }

    /**
     * Splits the "url|User-Agent=x&Referer=y" form used by many playlists, moving the suffix
     * into the channel's headers. Returns the bare URL.
     */
    private static String takeInlineHeaders(Channel channel, String url) {
        int bar = url.indexOf('|');
        if (bar < 0) {
            return url;
        }
        for (String pair : url.substring(bar + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if ("user-agent".equalsIgnoreCase(key)) {
                putHeader(channel, HEADER_USER_AGENT, value);
            } else if ("referer".equalsIgnoreCase(key) || "referrer".equalsIgnoreCase(key)) {
                putHeader(channel, HEADER_REFERER, value);
            } else if (!key.isEmpty()) {
                putHeader(channel, key, value);
            }
        }
        return url.substring(0, bar);
    }

    private static void putHeader(Channel channel, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (channel.headers == null) {
            channel.headers = new LinkedHashMap<>();
        }
        channel.headers.put(name, value);
    }

    private static String normalizeNewlines(String content) {
        return content.replace("\r\n", "\n").replace("\r", "");
    }

    private static String extractPattern(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /** @return the first non-empty value, falling back to the default. */
    private static String firstNonEmpty(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
}
