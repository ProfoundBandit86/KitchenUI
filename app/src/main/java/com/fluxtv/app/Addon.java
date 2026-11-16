package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One installed Stremio-protocol add-on (manifest + URL helpers). */
public class Addon {

    public final String url;
    public final String base;
    public final JSONObject manifest;

    Addon(String url, JSONObject manifest) {
        this.url = url;
        this.manifest = manifest;
        String b = url.endsWith("/manifest.json") ? url.substring(0, url.length() - "/manifest.json".length()) : url;
        this.base = b.replaceAll("/+$", "");
    }

    public String name() {
        return manifest.optString("name", manifest.optString("id", "Add-on"));
    }

    public String description() {
        return manifest.optString("description", "");
    }

    /** Accepts https://…/manifest.json, stremio://…, or a bare base URL. */
    public static String normalize(String raw) {
        String u = raw.trim();
        if (u.startsWith("stremio://")) u = "https://" + u.substring("stremio://".length());
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        if (!u.endsWith("manifest.json")) u = u.replaceAll("/+$", "") + "/manifest.json";
        return u;
    }

    public List<JSONObject> catalogs() {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = manifest.optJSONArray("catalogs");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** Whether this add-on serves `resource` (catalog/meta/stream/subtitles) for the type and id. */
    public boolean has(String resource, String type, String id) {
        JSONArray res = manifest.optJSONArray("resources");
        if (res == null) return false;
        for (int i = 0; i < res.length(); i++) {
            Object r = res.opt(i);
            if (r instanceof String && r.equals(resource)) {
                return contains(manifest.optJSONArray("types"), type)
                        && prefixOk(manifest.optJSONArray("idPrefixes"), id);
            }
            if (r instanceof JSONObject && resource.equals(((JSONObject) r).optString("name"))) {
                JSONObject o = (JSONObject) r;
                JSONArray types = o.has("types") ? o.optJSONArray("types") : manifest.optJSONArray("types");
                JSONArray prefixes = o.has("idPrefixes") ? o.optJSONArray("idPrefixes") : manifest.optJSONArray("idPrefixes");
                return contains(types, type) && prefixOk(prefixes, id);
            }
        }
        return false;
    }

    public String resourceUrl(String resource, String type, String id, String extra) {
        return base + "/" + resource + "/" + Http.enc(type) + "/" + Http.enc(id)
                + (extra == null ? "" : "/" + extra) + ".json";
    }

    /** Catalogs that can be shown on the home screen without extra input. */
    public static boolean isHomeCatalog(JSONObject c) {
        JSONArray req = c.optJSONArray("extraRequired");
        if (req != null && req.length() > 0) return false;
        JSONArray extra = c.optJSONArray("extra");
        if (extra != null) for (int i = 0; i < extra.length(); i++) {
            JSONObject e = extra.optJSONObject(i);
            if (e != null && e.optBoolean("isRequired")) return false;
        }
        return true;
    }

    /** Genre names a catalog can filter by (empty if none). */
    public static List<String> genres(JSONObject c) {
        List<String> out = new ArrayList<>();
        JSONArray extra = c.optJSONArray("extra");
        if (extra != null) for (int i = 0; i < extra.length(); i++) {
            JSONObject e = extra.optJSONObject(i);
            if (e == null || !"genre".equals(e.optString("name"))) continue;
            JSONArray opts = e.optJSONArray("options");
            if (opts != null) for (int j = 0; j < opts.length(); j++) out.add(opts.optString(j));
        }
        if (out.isEmpty() && contains(c.optJSONArray("extraSupported"), "genre") && c.optJSONArray("genres") != null) {
            JSONArray g = c.optJSONArray("genres");
            for (int j = 0; j < g.length(); j++) out.add(g.optString(j));
        }
        return out;
    }

    public static boolean isSearchCatalog(JSONObject c) {
        JSONArray extra = c.optJSONArray("extra");
        if (extra != null) for (int i = 0; i < extra.length(); i++) {
            JSONObject e = extra.optJSONObject(i);
            if (e != null && "search".equals(e.optString("name"))) return true;
        }
        return contains(c.optJSONArray("extraSupported"), "search") && c.optJSONArray("extraSupported") != null;
    }

    private static boolean contains(JSONArray arr, String v) {
        if (arr == null) return true;
        for (int i = 0; i < arr.length(); i++) if (v.equals(arr.optString(i))) return true;
        return false;
    }

    private static boolean prefixOk(JSONArray prefixes, String id) {
        if (prefixes == null || id == null) return true;
        for (int i = 0; i < prefixes.length(); i++) if (id.startsWith(prefixes.optString(i))) return true;
        return false;
    }
}
