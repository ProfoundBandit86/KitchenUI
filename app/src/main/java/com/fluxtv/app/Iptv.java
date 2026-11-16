package com.fluxtv.app;

import android.util.Base64;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * IPTV: Xtream Codes logins (server + username + password) and M3U playlists with an
 * optional XMLTV guide. Works with providers and with Dispatcharr, which can share its
 * channels as an Xtream Codes login (port 9191, a user's "XC password") or as M3U/EPG links.
 *
 * Provider movies and series use ids like "iptv:&lt;src&gt;:m:&lt;id.ext&gt;",
 * "iptv:&lt;src&gt;:s:&lt;seriesId&gt;" and episodes "iptv:&lt;src&gt;:e:&lt;id.ext&gt;".
 */
public final class Iptv {

    public static final String XTREAM = "xtream", M3U = "m3u";

    public static class Source {
        public String kind = "", id = "", name = "", url = "", user = "", pass = "", m3u = "", epg = "";

        JSONObject toJson() {
            JSONObject j = new JSONObject();
            try {
                j.put("kind", kind); j.put("id", id); j.put("name", name); j.put("url", url);
                j.put("user", user); j.put("pass", pass); j.put("m3u", m3u); j.put("epg", epg);
            } catch (Exception ignored) { }
            return j;
        }

        static Source fromJson(JSONObject j) {
            Source s = new Source();
            s.kind = j.optString("kind"); s.id = j.optString("id"); s.name = j.optString("name");
            s.url = j.optString("url"); s.user = j.optString("user"); s.pass = j.optString("pass");
            s.m3u = j.optString("m3u"); s.epg = j.optString("epg");
            return s;
        }

        public boolean xtream() {
            return XTREAM.equals(kind);
        }
    }

    public static class Category {
        public final String id, name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static class Channel {
        public String id = "", name = "", logo = "", group = "", epgId = "", url = "", num = "";
    }

    public static class Program {
        public String title = "", desc = "";
        public long start, end;
    }

    private static final long CHANNEL_TTL = 30 * 60 * 1000L, EPG_TTL = 10 * 60 * 1000L, GUIDE_TTL = 3 * 3600 * 1000L;
    private static final Map<String, List<Channel>> channelCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> channelTime = new ConcurrentHashMap<>();
    private static final Map<String, List<Program>> epgCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> epgTime = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, List<Program>>> guides = new ConcurrentHashMap<>();
    private static final Map<String, Long> guideTime = new ConcurrentHashMap<>();
    private static final Map<String, JSONArray> vodCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> vodTime = new ConcurrentHashMap<>();

    private Iptv() { }

    // ------------------------------------------------------------ saved sources

    public static synchronized List<Source> sources() {
        List<Source> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get("iptv").isEmpty() ? "[]" : Store.get("iptv"));
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(Source.fromJson(a.optJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    public static Source source(String id) {
        for (Source s : sources()) if (s.id.equals(id)) return s;
        return null;
    }

    public static synchronized void save(Source src) {
        JSONArray out = new JSONArray();
        for (Source s : sources()) if (!s.id.equals(src.id)) out.put(s.toJson());
        out.put(src.toJson());
        Store.put("iptv", out.toString());
        Store.bumpSources();
    }

    public static synchronized void remove(String id) {
        JSONArray out = new JSONArray();
        for (Source s : sources()) if (!s.id.equals(id)) out.put(s.toJson());
        Store.put("iptv", out.toString());
        channelCache.remove(id);
        vodCache.clear();
        Store.bumpSources();
    }

    /** Settings switch: show provider movies & series in Movies / TV Shows / search. */
    public static boolean vodEnabled() {
        return !"off".equals(Store.get("iptv_vod"));
    }

    public static void setVodEnabled(boolean on) {
        Store.put("iptv_vod", on ? "on" : "off");
        Store.bumpSources();
    }

    // ------------------------------------------------------------ sign in

    /** Accepts "host:port", "http://host:port", or a pasted …/player_api.php?… address. */
    static String normalizeServer(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        for (String tail : new String[]{"/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php"}) {
            if (u.endsWith(tail)) u = u.substring(0, u.length() - tail.length());
        }
        return u.replaceAll("/+$", "");
    }

    public static Source loginXtream(String server, String user, String pass) throws Exception {
        Source s = new Source();
        s.kind = XTREAM;
        s.url = normalizeServer(server);
        s.user = user == null ? "" : user.trim();
        s.pass = pass == null ? "" : pass.trim();
        if (s.user.isEmpty() || s.pass.isEmpty()) throw new Exception("Enter the username and password");
        JSONObject j;
        try {
            j = new JSONObject(Http.get(base(s), null));
        } catch (Http.HttpException e) {
            if (e.code == 401 || e.code == 403) throw new Exception("Wrong username or password (for Dispatcharr, use the user's XC password)");
            throw new Exception("The server answered with an error (" + e.code + "). Check the address.");
        } catch (org.json.JSONException e) {
            throw new Exception("That address doesn't look like an Xtream Codes server");
        }
        JSONObject ui = j.optJSONObject("user_info");
        if (ui == null || ui.optInt("auth", 0) != 1) {
            throw new Exception("Wrong username or password (for Dispatcharr, use the user's XC password)");
        }
        if (!ui.optString("status").isEmpty() && !ui.optString("status").equalsIgnoreCase("Active")) {
            throw new Exception("This account is " + ui.optString("status").toLowerCase(Locale.ROOT));
        }
        String host;
        try {
            host = new URI(s.url).getHost();
        } catch (Exception e) {
            host = s.url;
        }
        s.name = host + " · " + s.user;
        s.id = Integer.toHexString((s.url + "|" + s.user).hashCode());
        return s;
    }

    private static final Pattern GET_PHP = Pattern.compile("^(https?://[^?]+?)/get\\.php\\?(.*)$");

    /** Adds an M3U playlist. An Xtream-style get.php link is turned into a full Xtream login. */
    public static Source addM3u(String m3u, String epg) throws Exception {
        String url = m3u == null ? "" : m3u.trim();
        if (url.isEmpty()) throw new Exception("Enter the playlist (M3U) address");
        if (!url.startsWith("http")) url = "http://" + url;
        Matcher m = GET_PHP.matcher(url);
        if (m.find()) {
            Map<String, String> q = query(m.group(2));
            if (q.containsKey("username") && q.containsKey("password")) {
                try {
                    return loginXtream(m.group(1), q.get("username"), q.get("password"));
                } catch (Exception ignored) { }   // not a full Xtream server: keep it as a playlist
            }
        }
        Source s = new Source();
        s.kind = M3U;
        s.m3u = url;
        s.epg = epg == null ? "" : epg.trim();
        List<Channel> ch = parseM3u(url);
        if (ch.isEmpty()) throw new Exception("That playlist has no channels");
        try {
            s.name = new URI(url).getHost() + " · playlist";
        } catch (Exception e) {
            s.name = "Playlist";
        }
        s.id = Integer.toHexString(url.hashCode());
        channelCache.put(s.id, ch);
        channelTime.put(s.id, System.currentTimeMillis());
        return s;
    }

    private static Map<String, String> query(String q) {
        Map<String, String> out = new HashMap<>();
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq), java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------ Xtream API

    private static String base(Source s) {
        return s.url + "/player_api.php?username=" + Http.enc(s.user) + "&password=" + Http.enc(s.pass);
    }

    private static String api(Source s, String action, String extra) throws Exception {
        return Http.get(base(s) + "&action=" + action + (extra == null ? "" : "&" + extra), null);
    }

    private static JSONArray apiArray(Source s, String action, String extra) throws Exception {
        String body = api(s, action, extra).trim();
        if (body.startsWith("[")) return new JSONArray(body);
        return new JSONArray();   // some servers answer {} or "" when empty
    }

    // ------------------------------------------------------------ live TV

    public static List<Category> liveCategories(Source s) throws Exception {
        List<Category> out = new ArrayList<>();
        if (s.xtream()) {
            JSONArray a;
            java.io.File cf = file(s, "cats");
            if (cf.exists() && System.currentTimeMillis() - cf.lastModified() < 24L * 3600 * 1000) {
                try {
                    byte[] d = new byte[(int) cf.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(cf)) { int off = 0, n; while (off < d.length && (n = in.read(d, off, d.length - off)) > 0) off += n; }
                    a = new JSONArray(new String(d, StandardCharsets.UTF_8));
                } catch (Exception e) { a = apiArray(s, "get_live_categories", null); }
            } else {
                a = apiArray(s, "get_live_categories", null);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(cf)) { fo.write(a.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c != null) out.add(new Category(c.optString("category_id"), c.optString("category_name")));
            }
        } else {
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (Channel c : channels(s)) seen.put(c.group.isEmpty() ? "Channels" : c.group, true);
            for (String g : seen.keySet()) out.add(new Category(g, g));
        }
        return out;
    }

    /** Every live channel for the source (cached for 30 minutes). */
    public static List<Channel> channels(Source s) throws Exception {
        Long t = channelTime.get(s.id);
        List<Channel> cached = channelCache.get(s.id);
        if (cached != null && t != null && System.currentTimeMillis() - t < CHANNEL_TTL) return cached;
        // Saved copy from last time: show it straight away, refresh in the background when it's old.
        if (cached == null) {
            List<Channel> disk = loadChannels(s);
            long age = System.currentTimeMillis() - diskTime(s, "channels");
            if (disk != null && !disk.isEmpty() && age < 3L * 24 * 3600 * 1000) {
                channelCache.put(s.id, disk);
                channelTime.put(s.id, age > 3600 * 1000 ? 0L : System.currentTimeMillis() - age);
                if (age > 3600 * 1000) App.IO.execute(() -> { try { fetchChannels(s); } catch (Exception ignored) { } });
                return disk;
            }
        }
        return fetchChannels(s);
    }

    private static synchronized List<Channel> fetchChannels(Source s) throws Exception {
        List<Channel> out = new ArrayList<>();
        if (s.xtream()) {
            JSONArray a = apiArray(s, "get_live_streams", null);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Channel c = new Channel();
                c.id = o.optString("stream_id");
                c.name = o.optString("name");
                c.logo = o.optString("stream_icon");
                c.group = o.optString("category_id");
                c.epgId = o.optString("epg_channel_id");
                c.num = o.optString("num");
                out.add(c);
            }
        } else {
            out = parseM3u(s.m3u);
        }
        channelCache.put(s.id, out);
        channelTime.put(s.id, System.currentTimeMillis());
        saveChannels(s, out);
        return out;
    }

    // ------------------------------------------------------------ saved copies on disk (fast start)

    private static java.io.File file(Source s, String what) {
        java.io.File d = new java.io.File(App.ctx.getFilesDir(), "iptv");
        d.mkdirs();
        return new java.io.File(d, s.id.replaceAll("[^A-Za-z0-9_-]", "_") + "." + what);
    }

    private static long diskTime(Source s, String what) {
        java.io.File f = file(s, what);
        return f.exists() ? f.lastModified() : 0;
    }

    private static void saveChannels(Source s, List<Channel> list) {
        try {
            JSONArray a = new JSONArray();
            for (Channel c : list) {
                a.put(new JSONObject().put("i", c.id).put("n", c.name).put("l", c.logo).put("g", c.group)
                        .put("e", c.epgId).put("u", c.url).put("c", c.num));
            }
            java.io.File f = file(s, "channels"), tmp = new java.io.File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(f);
        } catch (Exception ignored) { }
    }

    private static List<Channel> loadChannels(Source s) {
        java.io.File f = file(s, "channels");
        if (!f.exists()) return null;
        try {
            byte[] d = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < d.length && (n = in.read(d, off, d.length - off)) > 0) off += n;
            }
            JSONArray a = new JSONArray(new String(d, StandardCharsets.UTF_8));
            List<Channel> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Channel c = new Channel();
                c.id = o.optString("i"); c.name = o.optString("n"); c.logo = o.optString("l"); c.group = o.optString("g");
                c.epgId = o.optString("e"); c.url = o.optString("u"); c.num = o.optString("c");
                out.add(c);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<Channel> channelsIn(Source s, String categoryId) throws Exception {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels(s)) {
            String g = s.xtream() ? c.group : (c.group.isEmpty() ? "Channels" : c.group);
            if (categoryId == null || categoryId.equals(g)) out.add(c);
        }
        return out;
    }

    /** Stream address. Xtream: MPEG-TS by default, HLS (.m3u8) as the fallback. */
    public static String liveUrl(Source s, Channel c, boolean hls) {
        if (!s.xtream()) return c.url;
        return s.url + "/live/" + Http.enc(s.user) + "/" + Http.enc(s.pass) + "/" + c.id + (hls ? ".m3u8" : ".ts");
    }

    private static List<Channel> parseM3u(String url) throws Exception {
        List<Channel> out = new ArrayList<>();
        try (InputStream in = Http.open(url);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            Channel pending = null;
            String group = "";
            int n = 0;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    pending = new Channel();
                    pending.epgId = attr(line, "tvg-id");
                    pending.logo = attr(line, "tvg-logo");
                    pending.group = attr(line, "group-title");
                    pending.num = attr(line, "tvg-chno");
                    int comma = line.lastIndexOf(',');
                    pending.name = comma >= 0 ? line.substring(comma + 1).trim() : attr(line, "tvg-name");
                    if (pending.name.isEmpty()) pending.name = attr(line, "tvg-name");
                } else if (line.startsWith("#EXTGRP:")) {
                    group = line.substring(8).trim();
                } else if (!line.isEmpty() && !line.startsWith("#") && pending != null) {
                    pending.url = line;
                    if (pending.group.isEmpty()) pending.group = group;
                    pending.id = String.valueOf(n++);
                    if (pending.num.isEmpty()) pending.num = String.valueOf(n);
                    out.add(pending);
                    pending = null;
                }
            }
        }
        return out;
    }

    private static String attr(String line, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(line);
        return m.find() ? m.group(1).trim() : "";
    }

    // ------------------------------------------------------------ guide (EPG)

    /** What's on now and next for a channel (may be empty). */
    public static List<Program> nowNext(Source s, Channel c) {
        String key = s.id + "|" + c.id;
        Long t = epgTime.get(key);
        List<Program> cached = epgCache.get(key);
        if (cached != null && t != null && System.currentTimeMillis() - t < EPG_TTL) return current(cached);
        List<Program> list = new ArrayList<>();
        List<Program> full = programs(s, c);
        if (!full.isEmpty()) return current(full);
        try {
            if (s.xtream()) {
                String body = api(s, "get_short_epg", "stream_id=" + c.id + "&limit=4").trim();
                JSONArray a = body.startsWith("{") ? new JSONObject(body).optJSONArray("epg_listings") : null;
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    Program p = new Program();
                    p.title = b64(o.optString("title"));
                    p.desc = b64(o.optString("description"));
                    p.start = o.optLong("start_timestamp") * 1000L;
                    p.end = o.optLong("stop_timestamp") * 1000L;
                    if (p.start == 0) p.start = xmlDate(o.optString("start").replaceAll("[-: ]", "") + " +0000");
                    if (p.end == 0) p.end = xmlDate(o.optString("end").replaceAll("[-: ]", "") + " +0000");
                    list.add(p);
                }
            }
        } catch (Exception ignored) { }
        epgCache.put(key, list);
        epgTime.put(key, System.currentTimeMillis());
        return current(list);
    }

    private static List<Program> current(List<Program> all) {
        long now = System.currentTimeMillis();
        List<Program> out = new ArrayList<>();
        for (Program p : all) if (p.end > now && out.size() < 2) out.add(p);
        return out;
    }

    private static String b64(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return s;   // some servers send plain text
        }
    }

    // ------------------------------------------------------------ the full TV guide (XMLTV), kept on disk

    /** Programmes for a channel from the saved guide, oldest first (empty until the guide has loaded). */
    public static List<Program> programs(Source s, Channel c) {
        Map<String, List<Program>> g = guides.get(s.id);
        if (g == null || c.epgId.isEmpty()) return new ArrayList<>();
        List<Program> list = g.get(c.epgId);
        if (list == null) list = g.get(c.epgId.toLowerCase(Locale.ROOT));
        return list == null ? new ArrayList<>() : list;
    }

    /** True once a guide is in memory for the source. */
    public static boolean guideReady(Source s) {
        return guides.containsKey(s.id);
    }

    /** XMLTV address: your own EPG link if set, else the Xtream provider's xmltv.php. */
    public static String epgUrl(Source s) {
        if (!s.epg.isEmpty()) return s.epg;
        if (s.xtream()) return s.url + "/xmltv.php?username=" + Http.enc(s.user) + "&password=" + Http.enc(s.pass);
        return "";
    }

    public static long guideAge(Source s) {
        Long t = guideTime.get(s.id);
        long disk = diskTime(s, "epg");
        long when = t != null ? t : disk;
        return when == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - when;
    }

    /**
     * Loads the guide: memory, then the saved copy, then the provider. Blocking (worker threads).
     * force = download even if the saved copy is recent.
     */
    public static Map<String, List<Program>> loadGuide(Source s, boolean force) throws Exception {
        Map<String, List<Program>> g = guides.get(s.id);
        if (g != null && !force && guideAge(s) < GUIDE_TTL) return g;
        if (g == null && !force) {
            Map<String, List<Program>> disk = readGuide(s);
            if (disk != null) {
                guides.put(s.id, disk);
                guideTime.put(s.id, diskTime(s, "epg"));
                if (guideAge(s) < 12L * 3600 * 1000) {
                    if (guideAge(s) > GUIDE_TTL) App.IO.execute(() -> { try { downloadGuide(s); } catch (Exception ignored) { } });
                    return disk;
                }
            }
        }
        return downloadGuide(s);
    }

    private static final Object GUIDE_LOCK = new Object();

    private static Map<String, List<Program>> downloadGuide(Source s) throws Exception {
        synchronized (GUIDE_LOCK) {
            String url = epgUrl(s);
            if (url.isEmpty()) return new HashMap<>();
            // Only keep channels we actually have (providers' guides can be huge).
            java.util.Set<String> wanted = new java.util.HashSet<>();
            try { for (Channel c : channels(s)) if (!c.epgId.isEmpty()) { wanted.add(c.epgId); wanted.add(c.epgId.toLowerCase(Locale.ROOT)); } } catch (Exception ignored) { }
            Map<String, List<Program>> g = new HashMap<>();
            long now = System.currentTimeMillis(), from = now - 3 * 3600 * 1000L, until = now + 48 * 3600 * 1000L;
            try (InputStream raw0 = Http.open(url)) {
                java.io.BufferedInputStream raw = new java.io.BufferedInputStream(raw0, 65536);
                raw.mark(4);
                int b1 = raw.read(), b2 = raw.read();
                raw.reset();
                InputStream in = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(raw, 65536) : raw;
                XmlPullParser x = Xml.newPullParser();
                x.setInput(in, null);
                Program cur = null;
                String channel = null, field = null;
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String tag = x.getName();
                        if ("programme".equals(tag)) {
                            channel = x.getAttributeValue(null, "channel");
                            long st = xmlDate(x.getAttributeValue(null, "start"));
                            long en = xmlDate(x.getAttributeValue(null, "stop"));
                            if (channel != null && en > from && st < until && (wanted.isEmpty() || wanted.contains(channel))) {
                                cur = new Program();
                                cur.start = st;
                                cur.end = en;
                            } else {
                                cur = null;
                            }
                        } else if (cur != null && ("title".equals(tag) || "desc".equals(tag))) {
                            field = tag;
                        }
                    } else if (ev == XmlPullParser.TEXT && cur != null && field != null) {
                        if (field.equals("title") && cur.title.isEmpty()) cur.title = x.getText().trim();
                        if (field.equals("desc") && cur.desc.isEmpty()) {
                            String d = x.getText().trim();
                            cur.desc = d.length() > 500 ? d.substring(0, 500) + "…" : d;
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        if ("programme".equals(x.getName()) && cur != null && channel != null) {
                            List<Program> list = g.get(channel);
                            if (list == null) g.put(channel, list = new ArrayList<>());
                            list.add(cur);
                            cur = null;
                        }
                        field = null;
                    }
                }
            }
            for (List<Program> list : g.values()) Collections.sort(list, (a, b) -> Long.compare(a.start, b.start));
            guides.put(s.id, g);
            guideTime.put(s.id, System.currentTimeMillis());
            epgCache.clear();
            writeGuide(s, g);
            return g;
        }
    }

    private static void writeGuide(Source s, Map<String, List<Program>> g) {
        java.io.File f = file(s, "epg"), tmp = new java.io.File(f.getPath() + ".tmp");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                new GZIPOutputStream(new java.io.FileOutputStream(tmp)), 65536))) {
            out.writeInt(1);
            out.writeInt(g.size());
            for (Map.Entry<String, List<Program>> e : g.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().size());
                for (Program p : e.getValue()) {
                    out.writeLong(p.start);
                    out.writeLong(p.end);
                    out.writeUTF(p.title.length() > 300 ? p.title.substring(0, 300) : p.title);
                    out.writeUTF(p.desc);
                }
            }
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        tmp.renameTo(f);
    }

    private static Map<String, List<Program>> readGuide(Source s) {
        java.io.File f = file(s, "epg");
        if (!f.exists()) return null;
        long cutoff = System.currentTimeMillis() - 3 * 3600 * 1000L;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                new GZIPInputStream(new java.io.FileInputStream(f)), 65536))) {
            if (in.readInt() != 1) return null;
            int n = in.readInt();
            Map<String, List<Program>> g = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                String id = in.readUTF();
                int m = in.readInt();
                List<Program> list = new ArrayList<>(m);
                for (int k = 0; k < m; k++) {
                    Program p = new Program();
                    p.start = in.readLong();
                    p.end = in.readLong();
                    p.title = in.readUTF();
                    p.desc = in.readUTF();
                    if (p.end > cutoff) list.add(p);
                }
                g.put(id, list);
            }
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Background pre-loading: channel lists, the full guide and channel logos for every provider,
     * so Live TV opens instantly. Runs at start and every few hours.
     */
    public static void prefetchAll() {
        for (Source s : sources()) {
            try {
                List<Channel> list = channels(s);
                if (s.xtream()) liveCategories(s);
                loadGuide(s, false);
                int n = 0;
                for (Channel c : list) {
                    if (n++ > 3000) break;
                    ImageLoader.prefetch(c.logo);
                }
            } catch (Exception ignored) { }
        }
    }

    private static long xmlDate(String v) {
        if (v == null || v.length() < 14) return 0;
        try {
            String d = v.trim();
            SimpleDateFormat f = new SimpleDateFormat(d.length() > 14 ? "yyyyMMddHHmmss Z" : "yyyyMMddHHmmss", Locale.ROOT);
            if (d.length() <= 14) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(d).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ movies & series (Xtream only)

    private static JSONArray cachedList(Source s, String action, String extra) throws Exception {
        String key = s.id + "|" + action + "|" + extra;
        Long t = vodTime.get(key);
        JSONArray a = vodCache.get(key);
        if (a != null && t != null && System.currentTimeMillis() - t < CHANNEL_TTL) return a;
        a = apiArray(s, action, extra);
        vodCache.put(key, a);
        vodTime.put(key, System.currentTimeMillis());
        return a;
    }

    private static JSONObject preview(Source s, JSONObject o, boolean series) throws Exception {
        JSONObject p = new JSONObject();
        if (series) {
            p.put("id", "iptv:" + s.id + ":s:" + o.optString("series_id"));
            p.put("type", "series");
            p.put("poster", o.optString("cover"));
            p.put("description", o.optString("plot"));
            JSONArray bd = o.optJSONArray("backdrop_path");
            if (bd != null && bd.length() > 0) p.put("background", bd.optString(0));
            String rd = o.optString("releaseDate", o.optString("release_date"));
            if (rd.length() >= 4) p.put("releaseInfo", rd.substring(0, 4));
        } else {
            String ext = o.optString("container_extension", "mp4");
            p.put("id", "iptv:" + s.id + ":m:" + o.optString("stream_id") + "." + ext);
            p.put("type", "movie");
            p.put("poster", o.optString("stream_icon"));
        }
        p.put("name", o.optString("name"));
        String rating = o.optString("rating");
        if (!rating.isEmpty() && !rating.equals("0")) p.put("imdbRating", rating);
        p.put("badge", "IPTV");
        return p;
    }

    /** Rows of provider movies or series by the provider's own categories. */
    public static List<Addons.Row> vodRows(String type, int maxRowsPerSource) {
        List<Addons.Row> out = new ArrayList<>();
        if (!vodEnabled()) return out;
        boolean series = "series".equals(type);
        for (Source s : sources()) {
            if (!s.xtream()) continue;
            try {
                JSONArray cats = cachedList(s, series ? "get_series_categories" : "get_vod_categories", null);
                for (int i = 0; i < cats.length() && out.size() < maxRowsPerSource; i++) {
                    JSONObject c = cats.optJSONObject(i);
                    if (c == null) continue;
                    JSONArray items = cachedList(s, series ? "get_series" : "get_vod_streams", "category_id=" + c.optString("category_id"));
                    List<JSONObject> list = new ArrayList<>();
                    for (int k = 0; k < items.length() && list.size() < 30; k++) {
                        JSONObject o = items.optJSONObject(k);
                        if (o != null) list.add(preview(s, o, series));
                    }
                    if (list.isEmpty()) continue;
                    Addons.Row r = new Addons.Row(c.optString("category_name"), list);
                    r.type = type;
                    r.note = "IPTV";
                    out.add(r);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Provider movies and series whose names contain the query. */
    public static List<JSONObject> search(String q) {
        List<JSONObject> out = new ArrayList<>();
        if (!vodEnabled() || q == null || q.trim().isEmpty()) return out;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        for (Source s : sources()) {
            if (!s.xtream()) continue;
            for (boolean series : new boolean[]{false, true}) {
                try {
                    JSONArray all = cachedList(s, series ? "get_series" : "get_vod_streams", null);
                    for (int i = 0; i < all.length() && out.size() < 60; i++) {
                        JSONObject o = all.optJSONObject(i);
                        if (o != null && o.optString("name").toLowerCase(Locale.ROOT).contains(needle)) out.add(preview(s, o, series));
                    }
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ details & playback

    private static String[] parts(String id) {
        return id.split(":", 4);   // iptv, src, kind, rest
    }

    private static Source sourceFor(String id) throws Exception {
        String[] p = parts(id);
        Source s = p.length == 4 ? source(p[1]) : null;
        if (s == null) throw new Exception("That IPTV provider has been removed");
        return s;
    }

    private static JSONArray list(String csv) {
        JSONArray a = new JSONArray();
        if (csv != null) for (String x : csv.split("\\s*[,/]\\s*")) if (!x.trim().isEmpty()) a.put(x.trim());
        return a;
    }

    public static JSONObject meta(String id) throws Exception {
        Source s = sourceFor(id);
        String[] p = parts(id);
        JSONObject m = new JSONObject();
        m.put("id", id);
        if (p[2].equals("m")) {
            String vodId = p[3].contains(".") ? p[3].substring(0, p[3].lastIndexOf('.')) : p[3];
            JSONObject j = new JSONObject(api(s, "get_vod_info", "vod_id=" + vodId));
            JSONObject info = j.optJSONObject("info") != null ? j.optJSONObject("info") : new JSONObject();
            JSONObject md = j.optJSONObject("movie_data") != null ? j.optJSONObject("movie_data") : new JSONObject();
            m.put("type", "movie");
            m.put("name", info.optString("name", md.optString("name")));
            fill(m, info);
        } else if (p[2].equals("s")) {
            JSONObject j = new JSONObject(api(s, "get_series_info", "series_id=" + p[3]));
            JSONObject info = j.optJSONObject("info") != null ? j.optJSONObject("info") : new JSONObject();
            m.put("type", "series");
            m.put("name", info.optString("name"));
            fill(m, info);
            JSONArray videos = new JSONArray();
            Object eps = j.opt("episodes");
            List<JSONObject> all = new ArrayList<>();
            if (eps instanceof JSONObject) {
                java.util.Iterator<String> it = ((JSONObject) eps).keys();
                while (it.hasNext()) {
                    JSONArray season = ((JSONObject) eps).optJSONArray(it.next());
                    if (season != null) for (int i = 0; i < season.length(); i++) if (season.optJSONObject(i) != null) all.add(season.optJSONObject(i));
                }
            } else if (eps instanceof JSONArray) {
                JSONArray seasons = (JSONArray) eps;
                for (int i = 0; i < seasons.length(); i++) {
                    JSONArray season = seasons.optJSONArray(i);
                    if (season != null) for (int k = 0; k < season.length(); k++) if (season.optJSONObject(k) != null) all.add(season.optJSONObject(k));
                }
            }
            for (JSONObject e : all) {
                JSONObject v = new JSONObject();
                v.put("id", "iptv:" + s.id + ":e:" + e.optString("id") + "." + e.optString("container_extension", "mp4"));
                v.put("name", e.optString("title"));
                v.put("season", e.optInt("season", 1));
                v.put("episode", e.optInt("episode_num"));
                JSONObject ei = e.optJSONObject("info");
                if (ei != null) {
                    v.put("thumbnail", ei.optString("movie_image"));
                    v.put("overview", ei.optString("plot"));
                    v.put("released", ei.optString("releasedate", ei.optString("air_date")));
                }
                videos.put(v);
            }
            m.put("videos", videos);
        } else {
            throw new Exception("Unknown IPTV title");
        }
        return m;
    }

    private static void fill(JSONObject m, JSONObject info) throws Exception {
        m.put("poster", info.optString("movie_image", info.optString("cover")));
        JSONArray bd = info.optJSONArray("backdrop_path");
        if (bd != null && bd.length() > 0) m.put("background", bd.optString(0));
        m.put("description", info.optString("plot", info.optString("description")));
        String rd = info.optString("releasedate", info.optString("releaseDate"));
        if (rd.length() >= 4) {
            m.put("releaseInfo", rd.substring(0, 4));
            m.put("released", rd);
        }
        String dur = info.optString("duration");
        if (dur.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
            String[] hms = dur.split(":");
            m.put("runtime", (Integer.parseInt(hms[0]) * 60 + Integer.parseInt(hms[1])) + " min");
        } else if (info.optInt("episode_run_time") > 0) {
            m.put("runtime", info.optInt("episode_run_time") + " min");
        }
        m.put("genres", list(info.optString("genre")));
        m.put("cast", list(info.optString("cast", info.optString("actors"))));
        m.put("director", list(info.optString("director")));
        String rating = info.optString("rating");
        if (!rating.isEmpty() && !rating.equals("0")) m.put("imdbRating", rating);
        String yt = info.optString("youtube_trailer");
        if (!yt.isEmpty()) {
            JSONArray t = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("ytId", yt);
            t.put(o);
            m.put("trailerStreams", t);
        }
    }

    public static List<Addons.Stream> streams(String id) throws Exception {
        Source s = sourceFor(id);
        String[] p = parts(id);
        String path = p[2].equals("m") ? "/movie/" : p[2].equals("e") ? "/series/" : null;
        if (path == null) throw new Exception("Pick an episode to play");
        JSONObject j = new JSONObject();
        String ext = p[3].contains(".") ? p[3].substring(p[3].lastIndexOf('.') + 1) : "";
        j.put("name", s.name + " (IPTV)");
        j.put("title", "IPTV · " + ext.toUpperCase(Locale.ROOT));
        j.put("url", s.url + path + Http.enc(s.user) + "/" + Http.enc(s.pass) + "/" + p[3]);
        Addons.Stream st = new Addons.Stream(s.name + " (IPTV)", j);
        st.library = true;
        List<Addons.Stream> out = new ArrayList<>();
        out.add(st);
        return out;
    }
}
