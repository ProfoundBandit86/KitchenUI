package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Talks to installed add-ons. All methods block; call from background threads. */
public final class Addons {

    private static final Map<String, Addon> cache = new ConcurrentHashMap<>();
    static final ExecutorService POOL = Executors.newFixedThreadPool(8);
    private static final int ROW_LIMIT = 30;

    public static class Row {
        public final String title;
        public final List<JSONObject> items;
        /** movie / series / other add-on type */
        public String type = "";
        /** small grey text after the title, e.g. the add-on's name */
        public String note = "";
        /** true = wide 16:9 cards (Continue watching) */
        public boolean wide = false;

        public Row(String title, List<JSONObject> items) {
            this.title = title;
            this.items = items;
        }
    }

    public static class Stream {
        /** Add-on or server name shown in the stream list. */
        public final String source;
        public final JSONObject json;
        /** From your Plex / Jellyfin / Emby library. */
        public boolean library;

        public Stream(String source, JSONObject json) {
            this.source = source;
            this.json = json;
        }
    }

    private Addons() { }

    /** The add-on's own settings page (Torrentio: debrid key, quality filters…), or null if it has none. */
    public static String configureUrl(Addon a) {
        org.json.JSONObject hints = a.manifest.optJSONObject("behaviorHints");
        if (hints == null || !hints.optBoolean("configurable")) return null;
        return a.base + "/configure";
    }

    public static List<Addon> all() {
        List<Addon> out = new ArrayList<>();
        for (String url : Store.addonUrls()) {
            Addon a = load(url);
            if (a != null) out.add(a);
        }
        return out;
    }

    public static Addon load(String url) {
        Addon a = cache.get(url);
        if (a != null) return a;
        try {
            String s = Http.get(url, null);
            a = new Addon(url, new JSONObject(s));
            Store.cacheManifest(url, s);
        } catch (Exception e) {
            String cached = Store.cachedManifest(url);
            if (cached == null) return null;
            try {
                a = new Addon(url, new JSONObject(cached));
            } catch (Exception x) {
                return null;
            }
        }
        cache.put(url, a);
        return a;
    }

    public static Addon install(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new Exception("Enter an add-on URL");
        String url = Addon.normalize(raw);
        String s = Http.get(url, null);
        JSONObject m = new JSONObject(s);
        if (!m.has("id") || !m.has("resources")) throw new Exception("That URL isn't an add-on manifest");
        Addon a = new Addon(url, m);
        Store.cacheManifest(url, s);
        cache.put(url, a);
        // Same add-on with new settings (e.g. Torrentio after Configure): replace it in place.
        String replaced = null;
        for (String old : Store.addonUrls()) {
            if (old.equals(url)) continue;
            Addon o = load(old);
            if (o != null && o.manifest.optString("id").equals(m.optString("id"))) { replaced = old; break; }
        }
        if (replaced != null) {
            Store.replaceAddon(replaced, url);
            cache.remove(replaced);
        } else {
            Store.addAddon(url);
        }
        return a;
    }

    public static void uninstall(String url) {
        cache.remove(url);
        Store.removeAddon(url);
    }

    // ------------------------------------------------------------ catalogs

    /** Every home-screen catalog of every add-on, as jobs (blended with servers by Blend). */
    static List<Future<Row>> catalogJobs() {
        List<Future<Row>> jobs = new ArrayList<>();
        for (Addon a : all()) {
            for (JSONObject cat : a.catalogs()) {
                if (!Addon.isHomeCatalog(cat)) continue;
                jobs.add(POOL.submit(() -> {
                    Row r = fetchRow(a, cat, null, cat.optString("name", cat.optString("id")));
                    r.type = cat.optString("type");
                    r.note = a.name();
                    return r;
                }));
            }
        }
        return jobs;
    }

    /** First catalog of this type that can filter by genre. */
    private static Object[] genreCatalog(String type) {
        for (Addon a : all()) {
            for (JSONObject cat : a.catalogs()) {
                if (!type.equals(cat.optString("type")) || !Addon.isHomeCatalog(cat)) continue;
                if (!Addon.genres(cat).isEmpty()) return new Object[]{a, cat};
            }
        }
        return null;
    }

    /** Genre names for movies or series (from the first add-on catalog that has genres). */
    public static List<String> genreNames(String type) {
        Object[] ac = genreCatalog(type);
        return ac == null ? new ArrayList<>() : Addon.genres((JSONObject) ac[1]);
    }

    /** One page (about 100 titles) of a genre; skip = how many were already shown. */
    public static List<JSONObject> genrePage(String type, String genre, int skip) throws Exception {
        Object[] ac = genreCatalog(type);
        if (ac == null) return new ArrayList<>();
        String extra = "genre=" + Http.enc(genre) + (skip > 0 ? "&skip=" + skip : "");
        return fetchRow((Addon) ac[0], (JSONObject) ac[1], extra, genre).items;
    }

    /** Rows like "Action", "Comedy"… for the Movies / TV Shows sections. */
    public static List<Row> genreRows(String type, int max) {
        Object[] ac = genreCatalog(type);
        List<Row> out = new ArrayList<>();
        if (ac == null) return out;
        Addon a = (Addon) ac[0];
        JSONObject cat = (JSONObject) ac[1];
        List<Future<Row>> jobs = new ArrayList<>();
        List<String> genres = Addon.genres(cat);
        for (int i = 0; i < genres.size() && i < max; i++) {
            String g = genres.get(i);
            jobs.add(POOL.submit(() -> {
                Row r = fetchRow(a, cat, "genre=" + Http.enc(g), g);
                r.type = type;
                r.note = a.name();
                return r;
            }));
        }
        for (Future<Row> f : jobs) {
            try {
                Row r = f.get(40, TimeUnit.SECONDS);
                if (!r.items.isEmpty()) out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Titles in the same genre, for "More like this". */
    public static List<JSONObject> similar(String type, String genre, String excludeId) {
        List<JSONObject> out = new ArrayList<>();
        Object[] ac = genreCatalog(type);
        if (ac == null || genre == null || genre.isEmpty()) return out;
        JSONObject cat = (JSONObject) ac[1];
        String match = null;
        for (String g : Addon.genres(cat)) if (g.equalsIgnoreCase(genre)) match = g;
        if (match == null) return out;
        try {
            for (JSONObject m : fetchRow((Addon) ac[0], cat, "genre=" + Http.enc(match), match).items) {
                if (!m.optString("id").equals(excludeId) && out.size() < 20) out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static List<Future<Row>> searchJobs(String query) {
        List<Future<Row>> jobs = new ArrayList<>();
        for (Addon a : all()) {
            for (JSONObject cat : a.catalogs()) {
                if (!Addon.isSearchCatalog(cat)) continue;
                jobs.add(POOL.submit(() -> {
                    Row r = fetchRow(a, cat, "search=" + Http.enc(query), a.name());
                    r.type = cat.optString("type");
                    return r;
                }));
            }
        }
        return jobs;
    }

    private static Row fetchRow(Addon a, JSONObject cat, String extra, String title) throws Exception {
        String url = a.resourceUrl("catalog", cat.optString("type"), cat.optString("id"), extra);
        JSONArray metas = new JSONObject(Http.get(url, null)).optJSONArray("metas");
        List<JSONObject> items = new ArrayList<>();
        if (metas != null) for (int i = 0; i < metas.length() && items.size() < ROW_LIMIT; i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            if (!m.has("type")) m.put("type", cat.optString("type"));
            items.add(m);
        }
        return new Row(title, items);
    }

    static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------ meta + streams

    public static JSONObject meta(String type, String id) throws Exception {
        if (id.startsWith("tmdb:")) throw new Exception("TMDB titles aren't supported here");
        if (Sources.isServerId(id)) return Sources.meta(id);
        Exception last = null;
        for (Addon a : all()) {
            if (!a.has("meta", type, id)) continue;
            try {
                JSONObject m = new JSONObject(Http.get(a.resourceUrl("meta", type, id, null), null)).optJSONObject("meta");
                if (m != null) return m;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new Exception("No installed add-on has details for this title");
    }

    // The title page looks up sources as soon as it opens (for "Best source"). Pressing Play reuses that
    // lookup, even if it's still running, instead of asking every add-on again.
    private static final java.util.Map<String, java.util.concurrent.FutureTask<List<Stream>>> RECENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> RECENT_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RECENT_MS = 10 * 60 * 1000;

    public static List<Stream> streams(String type, String videoId) throws Exception {
        String key = type + "|" + videoId + "|" + Store.profile();
        Long at = RECENT_AT.get(key);
        if (at != null && System.currentTimeMillis() - at > RECENT_MS) { RECENT.remove(key); RECENT_AT.remove(key); }
        java.util.concurrent.FutureTask<List<Stream>> mine = new java.util.concurrent.FutureTask<>(() -> fetchStreams(type, videoId));
        java.util.concurrent.FutureTask<List<Stream>> task = RECENT.putIfAbsent(key, mine);
        if (task == null) {
            task = mine;
            RECENT_AT.put(key, System.currentTimeMillis());
            task.run();
        }
        try {
            List<Stream> got = task.get();
            if (got.isEmpty()) { RECENT.remove(key, task); RECENT_AT.remove(key); }   // nothing found: ask again next time
            return new ArrayList<>(got);
        } catch (java.util.concurrent.ExecutionException e) {
            RECENT.remove(key, task);   // don't keep failures
            RECENT_AT.remove(key);
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : e;
        }
    }

    /** Forget cached sources (e.g. Retry). */
    public static void forgetStreams() {
        RECENT.clear();
        RECENT_AT.clear();
    }

    private static List<Stream> fetchStreams(String type, String videoId) throws Exception {
        if (Sources.isServerId(videoId)) return Sources.streams(videoId);

        // Same title on your Plex/Jellyfin/Emby servers (matched by IMDb id) is listed first.
        List<Future<List<Stream>>> serverJobs = new ArrayList<>();
        for (MediaServer s : Store.servers()) serverJobs.add(POOL.submit(() -> Sources.matchStreams(s, videoId)));

        List<Addon> addons = new ArrayList<>();
        List<Future<JSONArray>> jobs = new ArrayList<>();
        for (Addon a : all()) {
            if (!a.has("stream", type, videoId)) continue;
            addons.add(a);
            jobs.add(POOL.submit(() ->
                    new JSONObject(Http.get(a.resourceUrl("stream", type, videoId, null), null)).optJSONArray("streams")));
        }
        List<Stream> out = new ArrayList<>();
        for (Future<List<Stream>> f : serverJobs) {
            try {
                out.addAll(f.get(60, TimeUnit.SECONDS));
            } catch (Exception ignored) { }
        }
        for (int i = 0; i < jobs.size(); i++) {
            try {
                JSONArray arr = jobs.get(i).get(40, TimeUnit.SECONDS);
                if (arr == null) continue;
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject s = arr.optJSONObject(j);
                    if (s != null) out.add(new Stream(addons.get(i).name(), s));
                }
            } catch (Exception ignored) { }
        }
        return out;
    }
}
