package com.fluxtv.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes requests to Plex / Jellyfin / Emby servers. Server titles use ids like
 * "plex:&lt;serverId&gt;:&lt;ratingKey&gt;" or "jellyfin:&lt;serverId&gt;:&lt;itemId&gt;".
 */
public final class Sources {

    private static final long INDEX_TTL = 15 * 60 * 1000L;
    private static final Map<String, Map<String, String>> imdbIndex = new ConcurrentHashMap<>();
    private static final Map<String, Long> imdbIndexTime = new ConcurrentHashMap<>();

    private Sources() { }

    public static boolean isServerId(String id) {
        return id != null && (id.startsWith("iptv:") || id.startsWith(MediaServer.PLEX + ":") || id.startsWith(MediaServer.JELLYFIN + ":")
                || id.startsWith(MediaServer.EMBY + ":"));
    }

    private static MediaServer serverFor(String id) throws Exception {
        String[] p = id.split(":", 3);
        if (p.length < 3) throw new Exception("Bad id " + id);
        MediaServer s = Store.server(p[0], p[1]);
        if (s == null) throw new Exception("That server has been removed");
        return s;
    }

    private static String itemOf(String id) {
        return id.split(":", 3)[2];
    }

    private static boolean plex(MediaServer s) {
        return MediaServer.PLEX.equals(s.kind);
    }

    // ------------------------------------------------------------ browsing

    public static List<JSONObject> deck(MediaServer s) {
        try {
            return plex(s) ? Plex.deck(s) : Jellyfin.deck(s);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<JSONObject> recent(MediaServer s) {
        try {
            return plex(s) ? Plex.recent(s) : Jellyfin.recent(s);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<JSONObject> search(MediaServer s, String q) {
        try {
            return plex(s) ? Plex.search(s, q) : Jellyfin.search(s, q);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static JSONObject meta(String id) throws Exception {
        if (id.startsWith("iptv:")) return Iptv.meta(id);
        MediaServer s = serverFor(id);
        return plex(s) ? Plex.meta(s, itemOf(id)) : Jellyfin.meta(s, itemOf(id));
    }

    public static List<Addons.Stream> streams(String videoId) throws Exception {
        if (videoId.startsWith("iptv:")) return Iptv.streams(videoId);
        MediaServer s = serverFor(videoId);
        return mark(plex(s) ? Plex.streams(s, itemOf(videoId)) : Jellyfin.streams(s, itemOf(videoId)));
    }

    private static List<Addons.Stream> mark(List<Addons.Stream> list) {
        for (Addons.Stream st : list) st.library = true;
        return list;
    }

    // ------------------------------------------------------------ "also on your server"

    /**
     * For a title opened from an add-on (IMDb id like "tt0111161" or "tt0903747:1:2"),
     * finds the same movie/episode on every server and returns its streams.
     */
    public static List<Addons.Stream> matchStreams(MediaServer s, String videoId) {
        List<Addons.Stream> out = new ArrayList<>();
        if (videoId == null || !videoId.startsWith("tt")) return out;
        try {
            String[] p = videoId.split(":");
            String key = index(s).get(p[0]);
            if (key == null) return out;
            if (p.length >= 3) {
                int season = Integer.parseInt(p[1]), episode = Integer.parseInt(p[2]);
                key = plex(s) ? Plex.episodeKey(s, key, season, episode) : Jellyfin.episodeId(s, key, season, episode);
                if (key == null) return out;
            }
            out.addAll(mark(plex(s) ? Plex.streams(s, key) : Jellyfin.streams(s, key)));
        } catch (Exception ignored) { }
        return out;
    }

    /** imdb id → item id on this server (cached for 15 minutes). */
    public static Map<String, String> index(MediaServer s) throws Exception {
        String k = s.kind + ":" + s.id;
        Long t = imdbIndexTime.get(k);
        Map<String, String> m = imdbIndex.get(k);
        if (m == null || t == null || System.currentTimeMillis() - t > INDEX_TTL) {
            m = plex(s) ? Plex.imdbIndex(s) : Jellyfin.imdbIndex(s);
            imdbIndex.put(k, m);
            imdbIndexTime.put(k, System.currentTimeMillis());
        }
        return m;
    }

    // ------------------------------------------------------------ progress sync

    /** Tells the server how far you got, so other Plex/Jellyfin/Emby apps stay in sync. */
    public static void report(String videoId, long posMs, long durMs) {
        if (!isServerId(videoId) || videoId.startsWith("iptv:")) return;
        App.IO.execute(() -> {
            try {
                MediaServer s = serverFor(videoId);
                if (plex(s)) Plex.report(s, itemOf(videoId), posMs, durMs);
                else Jellyfin.report(s, itemOf(videoId), posMs, durMs);
            } catch (Exception ignored) { }
        });
    }

    public static void forget(MediaServer s) {
        imdbIndex.remove(s.kind + ":" + s.id);
        imdbIndexTime.remove(s.kind + ":" + s.id);
    }
}
