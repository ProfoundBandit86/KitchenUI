package com.fluxtv.app;

import org.json.JSONObject;

/** A signed-in Plex, Jellyfin or Emby server. */
public class MediaServer {

    public static final String PLEX = "plex", JELLYFIN = "jellyfin", EMBY = "emby";

    public String kind = "", id = "", name = "", url = "", token = "", userId = "";

    public static MediaServer of(String kind, String id, String name, String url, String token, String userId) {
        MediaServer s = new MediaServer();
        s.kind = kind;
        s.id = id;
        s.name = name;
        s.url = url;
        s.token = token;
        s.userId = userId == null ? "" : userId;
        return s;
    }

    /** Prefix used in every title/episode id that comes from this server. */
    public String prefix() {
        return kind + ":" + id + ":";
    }

    public String kindLabel() {
        return PLEX.equals(kind) ? "Plex" : JELLYFIN.equals(kind) ? "Jellyfin" : "Emby";
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("kind", kind);
            j.put("id", id);
            j.put("name", name);
            j.put("url", url);
            j.put("token", token);
            j.put("userId", userId);
        } catch (Exception ignored) { }
        return j;
    }

    public static MediaServer fromJson(JSONObject j) {
        return of(j.optString("kind"), j.optString("id"), j.optString("name"), j.optString("url"),
                j.optString("token"), j.optString("userId"));
    }

    /** Adds http:// if missing and strips trailing slashes. */
    public static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        return u.replaceAll("/+$", "");
    }
}
