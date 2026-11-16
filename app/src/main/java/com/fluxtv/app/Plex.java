package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Plex client: plex.tv/link sign-in, server discovery, browsing, playback and progress sync. */
final class Plex {

    private static final String PLEX_TV = "https://plex.tv/api/v2/";

    static class Pin {
        long id;
        String code;
    }

    private Plex() { }

    private static Map<String, String> headers(String token) {
        Map<String, String> h = Http.map(
                "Accept", "application/json",
                "X-Plex-Product", "Flux TV",
                "X-Plex-Version", "1.0",
                "X-Plex-Client-Identifier", Store.deviceId(),
                "X-Plex-Platform", "Android",
                "X-Plex-Device", "Android TV",
                "X-Plex-Device-Name", "Flux TV");
        if (token != null && !token.isEmpty()) h.put("X-Plex-Token", token);
        return h;
    }

    private static JSONObject get(MediaServer s, String path) throws Exception {
        JSONObject j = new JSONObject(Http.call("GET", s.url + path, headers(s.token), null, null));
        JSONObject mc = j.optJSONObject("MediaContainer");
        return mc != null ? mc : new JSONObject();
    }

    private static String sep(String path) {
        return path.contains("?") ? "&" : "?";
    }

    // ------------------------------------------------------------ sign in

    static Pin startPin() throws Exception {
        JSONObject j = new JSONObject(Http.call("POST", PLEX_TV + "pins", headers(null), "strong=false", null));
        Pin p = new Pin();
        p.id = j.getLong("id");
        p.code = j.getString("code");
        return p;
    }

    /** Returns the account token once the code has been entered at plex.tv/link, otherwise null. */
    static String pollPin(Pin p) throws Exception {
        JSONObject j = new JSONObject(Http.call("GET", PLEX_TV + "pins/" + p.id, headers(null), null, null));
        if (j.isNull("authToken")) return null;
        String t = j.optString("authToken");
        return t.isEmpty() ? null : t;
    }

    /** All servers on the account, each with the fastest connection that answers. */
    static List<MediaServer> discover(String accountToken) throws Exception {
        JSONArray res = new JSONArray(Http.call("GET", PLEX_TV + "resources?includeHttps=1&includeRelay=1",
                headers(accountToken), null, null));
        List<MediaServer> out = new ArrayList<>();
        for (int i = 0; i < res.length(); i++) {
            JSONObject r = res.optJSONObject(i);
            if (r == null || !r.optString("provides").contains("server")) continue;
            String token = r.optString("accessToken", accountToken);
            String url = bestConnection(r.optJSONArray("connections"), token);
            if (url == null) continue;
            out.add(MediaServer.of(MediaServer.PLEX, r.optString("clientIdentifier"), r.optString("name"), url, token, ""));
        }
        return out;
    }

    private static String bestConnection(JSONArray conns, String token) {
        if (conns == null) return null;
        // Try local first, then remote, then Plex relay.
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < conns.length(); i++) {
                JSONObject c = conns.optJSONObject(i);
                if (c == null) continue;
                boolean local = c.optBoolean("local"), relay = c.optBoolean("relay");
                boolean match = pass == 0 ? local && !relay : pass == 1 ? !local && !relay : relay;
                if (!match) continue;
                String uri = c.optString("uri").replaceAll("/+$", "");
                if (Http.ping(uri + "/identity", headers(token), 4000)) return uri;
            }
        }
        return null;
    }

    /** Adds a server by address + token (e.g. from the phone setup page). */
    static MediaServer manual(String rawUrl, String token) throws Exception {
        String url = MediaServer.normalizeUrl(rawUrl);
        MediaServer s = MediaServer.of(MediaServer.PLEX, "", "Plex", url, token.trim(), "");
        JSONObject mc = get(s, "/");
        s.id = mc.optString("machineIdentifier", url);
        if (!mc.optString("friendlyName").isEmpty()) s.name = mc.optString("friendlyName");
        return s;
    }

    // ------------------------------------------------------------ images

    private static String image(MediaServer s, String path, int w, int h) {
        if (path == null || path.isEmpty()) return "";
        return s.url + "/photo/:/transcode?width=" + w + "&height=" + h + "&minSize=1&upscale=1&url="
                + Http.enc(path) + "&X-Plex-Token=" + Http.enc(s.token);
    }

    // ------------------------------------------------------------ browsing

    /** On deck: in-progress and next-up items. */
    static List<JSONObject> deck(MediaServer s) throws Exception {
        return previews(s, get(s, "/library/onDeck").optJSONArray("Metadata"));
    }

    /** Newest movies and shows in every library. */
    static List<JSONObject> recent(MediaServer s) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONArray dirs = get(s, "/library/sections").optJSONArray("Directory");
        if (dirs != null) for (int i = 0; i < dirs.length(); i++) {
            JSONObject d = dirs.optJSONObject(i);
            if (d == null || !(d.optString("type").equals("movie") || d.optString("type").equals("show"))) continue;
            try {
                JSONObject mc = get(s, "/library/sections/" + d.optString("key")
                        + "/all?sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=30");
                out.addAll(previews(s, mc.optJSONArray("Metadata")));
            } catch (Exception ignored) { }
        }
        return out;
    }

    static List<JSONObject> search(MediaServer s, String q) throws Exception {
        JSONArray hubs = get(s, "/hubs/search?query=" + Http.enc(q) + "&limit=30").optJSONArray("Hub");
        List<JSONObject> items = new ArrayList<>();
        if (hubs != null) for (int i = 0; i < hubs.length(); i++) {
            JSONObject h = hubs.optJSONObject(i);
            if (h == null || !(h.optString("type").equals("movie") || h.optString("type").equals("show"))) continue;
            items.addAll(previews(s, h.optJSONArray("Metadata")));
        }
        return items;
    }

    /**
     * Card data in the same shape as add-on catalog items, plus: badge, added (ms),
     * progress (0..1), sub (e.g. "S2 E4 · 24 min left"), t (last watched, ms).
     * Episodes are shown as their show.
     */
    private static List<JSONObject> previews(MediaServer s, JSONArray items) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String type = it.optString("type");
            JSONObject p = new JSONObject();
            boolean episode = type.equals("episode") && !it.optString("grandparentRatingKey").isEmpty();
            if (type.equals("movie") || type.equals("show")) {
                p.put("id", s.prefix() + it.optString("ratingKey"));
                p.put("type", type.equals("show") ? "series" : "movie");
                p.put("name", it.optString("title"));
                p.put("poster", image(s, it.optString("thumb"), 300, 450));
                if (!it.optString("art").isEmpty()) p.put("background", image(s, it.optString("art"), 1280, 720));
                if (it.has("year")) p.put("releaseInfo", String.valueOf(it.optInt("year")));
            } else if (episode) {
                p.put("id", s.prefix() + it.optString("grandparentRatingKey"));
                p.put("type", "series");
                p.put("name", it.optString("grandparentTitle"));
                p.put("poster", image(s, it.optString("grandparentThumb"), 300, 450));
                String art = it.optString("grandparentArt", it.optString("art"));
                if (!art.isEmpty()) p.put("background", image(s, art, 1280, 720));
                p.put("still", image(s, it.optString("thumb"), 640, 360));
                p.put("label", "S" + it.optInt("parentIndex") + " E" + it.optInt("index"));
            } else {
                continue;
            }
            p.put("description", it.optString("summary"));
            p.put("badge", "Plex");
            p.put("added", it.optLong("addedAt") * 1000L);
            p.put("t", it.optLong("lastViewedAt") * 1000L);
            long dur = it.optLong("duration"), pos = it.optLong("viewOffset");
            if (pos > 0 && dur > 0) {
                p.put("progress", (double) pos / dur);
                p.put("sub", (episode ? p.optString("label") + " · " : "") + Ui.left(pos, dur));
            } else if (episode) {
                p.put("sub", p.optString("label") + " · Up next");
            }
            out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------ details

    static JSONObject meta(MediaServer s, String ratingKey) throws Exception {
        JSONArray arr = get(s, "/library/metadata/" + ratingKey).optJSONArray("Metadata");
        if (arr == null || arr.length() == 0) throw new Exception("Title not found on " + s.name);
        JSONObject it = arr.getJSONObject(0);
        boolean show = "show".equals(it.optString("type"));
        String id = s.prefix() + ratingKey;

        JSONObject m = new JSONObject();
        m.put("id", id);
        m.put("type", show ? "series" : "movie");
        m.put("name", it.optString("title"));
        m.put("poster", image(s, it.optString("thumb"), 400, 600));
        if (!it.optString("art").isEmpty()) m.put("background", image(s, it.optString("art"), 1280, 720));
        m.put("description", it.optString("summary"));
        if (it.has("year")) m.put("releaseInfo", String.valueOf(it.optInt("year")));
        if (it.optLong("duration") > 0) m.put("runtime", (it.optLong("duration") / 60000) + " min");
        double rating = it.has("audienceRating") ? it.optDouble("audienceRating") : it.optDouble("rating");
        if (!Double.isNaN(rating) && rating > 0) m.put("imdbRating", String.format(Locale.ROOT, "%.1f", rating));
        m.put("genres", tags(it.optJSONArray("Genre"), 5));
        m.put("cast", tags(it.optJSONArray("Role"), 8));
        m.put("director", tags(it.optJSONArray("Director"), 3));

        if (show) {
            JSONArray eps = get(s, "/library/metadata/" + ratingKey + "/allLeaves").optJSONArray("Metadata");
            JSONArray videos = new JSONArray();
            if (eps != null) for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                String vid = s.prefix() + e.optString("ratingKey");
                JSONObject v = new JSONObject();
                v.put("id", vid);
                v.put("name", e.optString("title"));
                v.put("season", e.optInt("parentIndex", 1));
                v.put("episode", e.optInt("index"));
                v.put("released", e.optString("originallyAvailableAt"));
                v.put("thumbnail", image(s, e.optString("thumb"), 640, 360));
                v.put("overview", e.optString("summary"));
                videos.put(v);
                importProgress(e, PlayInfo.of("series", id, vid, it.optString("title"), m.optString("poster"),
                        "S" + v.optInt("season") + " E" + v.optInt("episode")));
            }
            m.put("videos", videos);
        } else {
            importProgress(it, PlayInfo.of("movie", id, id, it.optString("title"), m.optString("poster"), ""));
        }
        return m;
    }

    private static JSONArray tags(JSONArray arr, int max) {
        JSONArray out = new JSONArray();
        if (arr != null) for (int i = 0; i < arr.length() && i < max; i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t != null) out.put(t.optString("tag"));
        }
        return out;
    }

    private static void importProgress(JSONObject item, PlayInfo info) {
        long when = item.optLong("lastViewedAt") * 1000L;
        if (when <= 0) when = 1;
        long dur = item.optLong("duration"), pos = item.optLong("viewOffset");
        if (item.optInt("viewCount") > 0 && pos == 0) Store.importProgress(info, 1, 1, when);
        else if (pos > 0 && dur > 0) Store.importProgress(info, pos, dur, when);
    }

    // ------------------------------------------------------------ streams

    static List<Addons.Stream> streams(MediaServer s, String ratingKey) throws Exception {
        JSONArray arr = get(s, "/library/metadata/" + ratingKey).optJSONArray("Metadata");
        if (arr == null || arr.length() == 0) throw new Exception("Title not found on " + s.name);
        JSONObject it = arr.getJSONObject(0);
        String label = s.name + " (Plex)";
        List<Addons.Stream> out = new ArrayList<>();

        JSONArray media = it.optJSONArray("Media");
        if (media != null) for (int i = 0; i < media.length(); i++) {
            JSONObject md = media.optJSONObject(i);
            JSONArray parts = md == null ? null : md.optJSONArray("Part");
            JSONObject part = parts == null || parts.length() == 0 ? null : parts.optJSONObject(0);
            if (part == null || part.optString("key").isEmpty()) continue;
            StringBuilder detail = new StringBuilder();
            if (!md.optString("videoResolution").isEmpty()) detail.append(" · ").append(md.optString("videoResolution")).append(md.optString("videoResolution").matches("\\d+") ? "p" : "");
            if (!md.optString("container").isEmpty()) detail.append(" · ").append(md.optString("container"));
            if (part.optLong("size") > 0) detail.append(String.format(Locale.ROOT, " · %.1f GB", part.optLong("size") / 1e9));

            JSONObject direct = new JSONObject();
            direct.put("name", label);
            direct.put("title", "Direct play · original file" + detail);
            String key = part.optString("key");
            direct.put("url", s.url + key + sep(key) + "X-Plex-Token=" + Http.enc(s.token));
            out.add(new Addons.Stream(label, direct));

            JSONObject hls = new JSONObject();
            hls.put("name", label);
            hls.put("title", "Transcoded by server · use if direct play fails" + detail);
            hls.put("url", s.url + "/video/:/transcode/universal/start.m3u8?path=" + Http.enc("/library/metadata/" + ratingKey)
                    + "&mediaIndex=" + i + "&partIndex=0&protocol=hls&fastSeek=1&directPlay=0&directStream=1"
                    + "&videoQuality=100&maxVideoBitrate=20000&videoResolution=1920x1080&subtitles=burn"
                    + "&session=" + UUID.randomUUID() + "&X-Plex-Product=Flux%20TV&X-Plex-Platform=Android"
                    + "&X-Plex-Client-Identifier=" + Http.enc(Store.deviceId()) + "&X-Plex-Token=" + Http.enc(s.token));
            out.add(new Addons.Stream(label, hls));
        }
        return out;
    }

    // ------------------------------------------------------------ IMDb matching

    /** imdb id → ratingKey for every movie and show on the server. */
    static Map<String, String> imdbIndex(MediaServer s) throws Exception {
        Map<String, String> map = new HashMap<>();
        JSONArray dirs = get(s, "/library/sections").optJSONArray("Directory");
        if (dirs != null) for (int i = 0; i < dirs.length(); i++) {
            JSONObject d = dirs.optJSONObject(i);
            if (d == null || !(d.optString("type").equals("movie") || d.optString("type").equals("show"))) continue;
            JSONArray items = get(s, "/library/sections/" + d.optString("key") + "/all?includeGuids=1").optJSONArray("Metadata");
            if (items == null) continue;
            for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                JSONArray guids = it == null ? null : it.optJSONArray("Guid");
                if (guids == null) continue;
                for (int k = 0; k < guids.length(); k++) {
                    JSONObject g = guids.optJSONObject(k);
                    String gid = g == null ? "" : g.optString("id");
                    if (gid.startsWith("imdb://")) map.put(gid.substring(7), it.optString("ratingKey"));
                }
            }
        }
        return map;
    }

    static String episodeKey(MediaServer s, String showKey, int season, int episode) throws Exception {
        JSONArray eps = get(s, "/library/metadata/" + showKey + "/allLeaves").optJSONArray("Metadata");
        if (eps != null) for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && e.optInt("parentIndex") == season && e.optInt("index") == episode) return e.optString("ratingKey");
        }
        return null;
    }

    // ------------------------------------------------------------ progress sync

    static void report(MediaServer s, String ratingKey, long posMs, long durMs) throws Exception {
        Http.call("GET", s.url + "/:/timeline?ratingKey=" + ratingKey + "&key=" + Http.enc("/library/metadata/" + ratingKey)
                + "&state=stopped&time=" + posMs + "&duration=" + durMs, headers(s.token), null, null);
        if (durMs > 0 && (double) posMs / durMs >= 0.92) {
            Http.call("GET", s.url + "/:/scrobble?identifier=com.plexapp.plugins.library&key=" + ratingKey,
                    headers(s.token), null, null);
        }
    }
}
