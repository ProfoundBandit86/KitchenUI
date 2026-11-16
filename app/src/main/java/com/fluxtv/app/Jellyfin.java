package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Jellyfin and Emby client (Jellyfin is a fork of Emby, so the API is mostly shared).
 * Jellyfin uses the "Authorization: MediaBrowser …" header and the newer /Items?userId= routes,
 * which Jellyfin 10.9 through 12 accept. Emby uses X-Emby-Token and /Users/{id}/… routes.
 */
final class Jellyfin {

    private Jellyfin() { }

    private static boolean emby(MediaServer s) {
        return MediaServer.EMBY.equals(s.kind);
    }

    private static Map<String, String> headers(MediaServer s) {
        String auth = "MediaBrowser Client=\"Flux TV\", Device=\"Android TV\", DeviceId=\"" + Store.deviceId()
                + "\", Version=\"1.0\"" + (s.token.isEmpty() ? "" : ", Token=\"" + s.token + "\"");
        Map<String, String> h = Http.map("Accept", "application/json");
        if (emby(s)) {
            h.put("X-Emby-Authorization", auth);
            if (!s.token.isEmpty()) h.put("X-Emby-Token", s.token);
        } else {
            h.put("Authorization", auth);
        }
        return h;
    }

    /** Query-string token for stream URLs handed to the player. */
    private static String tokenParam(MediaServer s) {
        return (emby(s) ? "api_key=" : "ApiKey=") + Http.enc(s.token);
    }

    private static String get(MediaServer s, String path) throws Exception {
        return Http.call("GET", s.url + path, headers(s), null, null);
    }

    private static void post(MediaServer s, String path, String json) throws Exception {
        Http.call("POST", s.url + path, headers(s), json == null ? "" : json, "application/json");
    }

    // ------------------------------------------------------------ sign in

    static MediaServer login(String kind, String rawUrl, String user, String pass) throws Exception {
        String url = MediaServer.normalizeUrl(rawUrl);
        MediaServer s = MediaServer.of(kind, "", "", url, "", "");
        JSONObject body = new JSONObject();
        body.put("Username", user == null ? "" : user.trim());
        body.put("Pw", pass == null ? "" : pass);
        JSONObject r = new JSONObject(Http.call("POST", url + "/Users/AuthenticateByName", headers(s),
                body.toString(), "application/json"));
        s.token = r.getString("AccessToken");
        s.userId = r.getJSONObject("User").getString("Id");
        s.id = r.optString("ServerId", url);
        s.name = kind.equals(MediaServer.EMBY) ? "Emby" : "Jellyfin";
        try {
            JSONObject info = new JSONObject(get(s, "/System/Info/Public"));
            if (!info.optString("ServerName").isEmpty()) s.name = info.optString("ServerName");
            if (!info.optString("Id").isEmpty()) s.id = info.optString("Id");
        } catch (Exception ignored) { }
        return s;
    }

    // ------------------------------------------------------------ routes

    private static String itemsPath(MediaServer s, String query) {
        return emby(s) ? "/Users/" + s.userId + "/Items?" + query : "/Items?userId=" + s.userId + "&" + query;
    }

    private static String itemPath(MediaServer s, String itemId) {
        return emby(s) ? "/Users/" + s.userId + "/Items/" + itemId : "/Items/" + itemId + "?userId=" + s.userId;
    }

    private static String viewsPath(MediaServer s) {
        return emby(s) ? "/Users/" + s.userId + "/Views" : "/UserViews?userId=" + s.userId;
    }

    private static String latestPath(MediaServer s, String parentId) {
        String q = "ParentId=" + parentId + "&Limit=30&Fields=ProductionYear";
        return emby(s) ? "/Users/" + s.userId + "/Items/Latest?" + q : "/Items/Latest?userId=" + s.userId + "&" + q;
    }

    private static String resumePath(MediaServer s) {
        String q = "Limit=20&MediaTypes=Video&Fields=ProductionYear";
        return emby(s) ? "/Users/" + s.userId + "/Items/Resume?" + q : "/UserItems/Resume?userId=" + s.userId + "&" + q;
    }

    private static String playedPath(MediaServer s, String itemId) {
        return emby(s) ? "/Users/" + s.userId + "/PlayedItems/" + itemId : "/UserPlayedItems/" + itemId + "?userId=" + s.userId;
    }

    private static String image(MediaServer s, String itemId, String type, int width) {
        return s.url + "/Items/" + itemId + "/Images/" + type + "?maxWidth=" + width + "&quality=90";
    }

    // ------------------------------------------------------------ browsing

    private static final String FIELDS = "Fields=ProductionYear,Overview,DateCreated,UserData";

    /** Items in progress ("Continue watching" on the server). */
    static List<JSONObject> deck(MediaServer s) throws Exception {
        return previews(s, new JSONObject(get(s, resumePath(s) + "&" + FIELDS)).optJSONArray("Items"));
    }

    /** Newest movies and shows in every library. */
    static List<JSONObject> recent(MediaServer s) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONArray views = new JSONObject(get(s, viewsPath(s))).optJSONArray("Items");
        if (views != null) for (int i = 0; i < views.length(); i++) {
            JSONObject v = views.optJSONObject(i);
            if (v == null) continue;
            String ct = v.optString("CollectionType");
            if (!(ct.equals("movies") || ct.equals("tvshows") || ct.isEmpty())) continue;
            try {
                out.addAll(previews(s, new JSONArray(get(s, latestPath(s, v.optString("Id")) + "&" + FIELDS))));
            } catch (Exception ignored) { }
        }
        return out;
    }

    static List<JSONObject> search(MediaServer s, String q) throws Exception {
        String path = itemsPath(s, "SearchTerm=" + Http.enc(q) + "&Recursive=true&IncludeItemTypes=Movie,Series&Limit=40&" + FIELDS);
        return previews(s, new JSONObject(get(s, path)).optJSONArray("Items"));
    }

    /** Same card shape as Plex.previews: add-on fields plus badge, added, progress, sub, t. */
    private static List<JSONObject> previews(MediaServer s, JSONArray items) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String type = it.optString("Type");
            JSONObject p = new JSONObject();
            boolean episode = type.equals("Episode") && !it.optString("SeriesId").isEmpty();
            if (type.equals("Movie") || type.equals("Series") || type.equals("Video")) {
                String id = it.optString("Id");
                p.put("id", s.prefix() + id);
                p.put("type", type.equals("Series") ? "series" : "movie");
                p.put("name", it.optString("Name"));
                p.put("poster", image(s, id, "Primary", 300));
                p.put("background", image(s, id, "Backdrop", 1280));
            } else if (episode) {
                String sid = it.optString("SeriesId");
                p.put("id", s.prefix() + sid);
                p.put("type", "series");
                p.put("name", it.optString("SeriesName"));
                p.put("poster", image(s, sid, "Primary", 300));
                p.put("background", image(s, sid, "Backdrop", 1280));
                p.put("still", image(s, it.optString("Id"), "Primary", 640));
                p.put("label", "S" + it.optInt("ParentIndexNumber") + " E" + it.optInt("IndexNumber"));
            } else {
                continue;
            }
            if (it.has("ProductionYear")) p.put("releaseInfo", String.valueOf(it.optInt("ProductionYear")));
            p.put("description", it.optString("Overview"));
            p.put("badge", s.kindLabel());
            p.put("added", parseDate(it.optString("DateCreated")));
            JSONObject ud = it.optJSONObject("UserData");
            long dur = it.optLong("RunTimeTicks") / 10_000;
            long pos = ud == null ? 0 : ud.optLong("PlaybackPositionTicks") / 10_000;
            p.put("t", ud == null ? 0 : parseDate(ud.optString("LastPlayedDate")));
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

    static JSONObject meta(MediaServer s, String itemId) throws Exception {
        JSONObject it = new JSONObject(get(s, itemPath(s, itemId)));
        boolean series = "Series".equals(it.optString("Type"));
        JSONObject m = new JSONObject();
        String id = s.prefix() + itemId;
        m.put("id", id);
        m.put("type", series ? "series" : "movie");
        m.put("name", it.optString("Name"));
        m.put("poster", image(s, itemId, "Primary", 400));
        if (it.optJSONArray("BackdropImageTags") != null && it.optJSONArray("BackdropImageTags").length() > 0) {
            m.put("background", image(s, itemId, "Backdrop", 1280));
        }
        m.put("description", it.optString("Overview"));
        if (it.has("ProductionYear")) m.put("releaseInfo", String.valueOf(it.optInt("ProductionYear")));
        if (it.optLong("RunTimeTicks") > 0) m.put("runtime", (it.optLong("RunTimeTicks") / 600_000_000L) + " min");
        if (it.has("CommunityRating")) m.put("imdbRating", String.format(Locale.ROOT, "%.1f", it.optDouble("CommunityRating")));
        m.put("genres", it.optJSONArray("Genres") != null ? it.optJSONArray("Genres") : new JSONArray());

        JSONArray cast = new JSONArray(), directors = new JSONArray();
        JSONArray people = it.optJSONArray("People");
        if (people != null) for (int i = 0; i < people.length(); i++) {
            JSONObject p = people.optJSONObject(i);
            if (p == null) continue;
            if ("Actor".equals(p.optString("Type"))) cast.put(p.optString("Name"));
            if ("Director".equals(p.optString("Type"))) directors.put(p.optString("Name"));
        }
        m.put("cast", cast);
        m.put("director", directors);

        if (series) {
            JSONArray eps = new JSONObject(get(s, "/Shows/" + itemId + "/Episodes?UserId=" + s.userId
                    + "&Fields=Overview")).optJSONArray("Items");
            JSONArray videos = new JSONArray();
            if (eps != null) for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                JSONObject v = new JSONObject();
                String vid = s.prefix() + e.optString("Id");
                v.put("id", vid);
                v.put("name", e.optString("Name"));
                v.put("season", e.optInt("ParentIndexNumber", 1));
                v.put("episode", e.optInt("IndexNumber"));
                v.put("released", e.optString("PremiereDate"));
                v.put("thumbnail", image(s, e.optString("Id"), "Primary", 640));
                v.put("overview", e.optString("Overview"));
                videos.put(v);
                importProgress(e, PlayInfo.of("series", id, vid, it.optString("Name"), m.optString("poster"),
                        "S" + v.optInt("season") + " E" + v.optInt("episode")));
            }
            m.put("videos", videos);
        } else {
            importProgress(it, PlayInfo.of("movie", id, id, it.optString("Name"), m.optString("poster"), ""));
        }
        return m;
    }

    private static void importProgress(JSONObject item, PlayInfo info) {
        JSONObject ud = item.optJSONObject("UserData");
        if (ud == null) return;
        long dur = item.optLong("RunTimeTicks") / 10_000;
        long pos = ud.optLong("PlaybackPositionTicks") / 10_000;
        long when = parseDate(ud.optString("LastPlayedDate"));
        if (ud.optBoolean("Played")) Store.importProgress(info, 1, 1, when);
        else if (pos > 0 && dur > 0) Store.importProgress(info, pos, dur, when);
    }

    private static long parseDate(String iso) {
        if (iso == null || iso.length() < 19) return 1;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 1;
        }
    }

    // ------------------------------------------------------------ streams

    static List<Addons.Stream> streams(MediaServer s, String itemId) throws Exception {
        JSONObject it = new JSONObject(get(s, itemPath(s, itemId)));
        List<Addons.Stream> out = new ArrayList<>();
        JSONArray sources = it.optJSONArray("MediaSources");
        int count = sources == null || sources.length() == 0 ? 1 : sources.length();
        for (int i = 0; i < count; i++) {
            JSONObject src = sources == null ? null : sources.optJSONObject(i);
            String msid = src != null ? src.optString("Id", itemId) : itemId;
            String detail = src != null ? describe(src) : "";
            String label = s.name + " (" + s.kindLabel() + ")";

            JSONObject direct = new JSONObject();
            direct.put("name", label);
            direct.put("title", "Direct play · original file" + detail);
            direct.put("url", s.url + "/Videos/" + itemId + "/stream?static=true&MediaSourceId=" + Http.enc(msid)
                    + "&DeviceId=" + Http.enc(Store.deviceId()) + "&" + tokenParam(s));
            out.add(new Addons.Stream(label, direct));

            JSONObject hls = new JSONObject();
            hls.put("name", label);
            hls.put("title", "Transcoded by server (H.264/AAC) · use if direct play fails" + detail);
            hls.put("url", s.url + "/Videos/" + itemId + "/master.m3u8?MediaSourceId=" + Http.enc(msid)
                    + "&VideoCodec=h264&AudioCodec=aac&TranscodingMaxAudioChannels=2&MaxStreamingBitrate=20000000"
                    + "&SegmentContainer=ts&DeviceId=" + Http.enc(Store.deviceId())
                    + "&PlaySessionId=" + UUID.randomUUID().toString().replace("-", "") + "&" + tokenParam(s));
            out.add(new Addons.Stream(label, hls));
        }
        return out;
    }

    private static String describe(JSONObject src) {
        StringBuilder sb = new StringBuilder();
        JSONArray streams = src.optJSONArray("MediaStreams");
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            JSONObject st = streams.optJSONObject(i);
            if (st != null && "Video".equals(st.optString("Type")) && st.optInt("Height") > 0) {
                sb.append(" · ").append(st.optInt("Height")).append('p');
                break;
            }
        }
        if (!src.optString("Container").isEmpty()) sb.append(" · ").append(src.optString("Container"));
        if (src.optLong("Size") > 0) sb.append(String.format(Locale.ROOT, " · %.1f GB", src.optLong("Size") / 1e9));
        return sb.toString();
    }

    // ------------------------------------------------------------ IMDb matching

    /** imdb id → Jellyfin item id for every movie and series in the library. */
    static Map<String, String> imdbIndex(MediaServer s) throws Exception {
        Map<String, String> map = new java.util.HashMap<>();
        String path = itemsPath(s, "Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds"
                + "&EnableImages=false&EnableUserData=false");
        JSONArray items = new JSONObject(get(s, path)).optJSONArray("Items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject ids = it.optJSONObject("ProviderIds");
            if (ids == null) continue;
            String imdb = ids.optString("Imdb", ids.optString("IMDB"));
            if (!imdb.isEmpty()) map.put(imdb, it.optString("Id"));
        }
        return map;
    }

    static String episodeId(MediaServer s, String seriesId, int season, int episode) throws Exception {
        JSONArray eps = new JSONObject(get(s, "/Shows/" + seriesId + "/Episodes?UserId=" + s.userId
                + "&Season=" + season)).optJSONArray("Items");
        if (eps != null) for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && e.optInt("ParentIndexNumber") == season && e.optInt("IndexNumber") == episode) {
                return e.optString("Id");
            }
        }
        return null;
    }

    // ------------------------------------------------------------ progress sync

    static void report(MediaServer s, String itemId, long posMs, long durMs) throws Exception {
        JSONObject body = new JSONObject();
        body.put("ItemId", itemId);
        body.put("MediaSourceId", itemId);
        body.put("PositionTicks", posMs * 10_000);
        post(s, "/Sessions/Playing/Stopped", body.toString());
        if (durMs > 0 && (double) posMs / durMs >= 0.92) post(s, playedPath(s, itemId), null);
    }
}
