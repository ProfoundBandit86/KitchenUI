package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Real-Debrid client: device-code sign-in (or a pasted API token), token refresh,
 * and turning a torrent info-hash from a stream add-on into a direct HTTPS link.
 */
public final class RealDebrid {

    private static final String OAUTH = "https://api.real-debrid.com/oauth/v2/";
    private static final String API = "https://api.real-debrid.com/rest/1.0/";
    /** Real-Debrid's public client ID for open-source apps (device-code flow). */
    private static final String OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM";
    private static final String DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0";

    private static final List<String> VIDEO_EXT = Arrays.asList(
            ".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".m2ts", ".webm", ".wmv", ".mpg", ".mpeg");
    private static final List<String> FAILED = Arrays.asList("magnet_error", "error", "virus", "dead");

    /** A Real-Debrid problem explained in plain words. unavailable = this stream can't work; try another. */
    public static class RdException extends Exception {
        public final boolean unavailable;

        RdException(String m, boolean unavailable) {
            super(m);
            this.unavailable = unavailable;
        }
    }

    public static class NotCachedException extends RdException {
        NotCachedException(String m) { super(m, true); }
    }

    /** Turns Real-Debrid's JSON errors (e.g. HTTP 451 infringing_file) into readable messages. */
    static Exception friendly(Exception e) {
        if (e instanceof RdException || !(e instanceof Http.HttpException)) return e;
        Http.HttpException h = (Http.HttpException) e;
        String err = "";
        int code = -1;
        try {
            JSONObject j = new JSONObject(h.body);
            err = j.optString("error");
            code = j.optInt("error_code", -1);
        } catch (Exception ignored) { }
        if (err.equals("infringing_file") || code == 35 || h.code == 451) {
            return new RdException("Real-Debrid has blocked this file. Pick another stream.", true);
        }
        if (err.equals("bad_token") || code == 8 || h.code == 401) {
            return new RdException("Your Real-Debrid sign-in has expired. Sign in again in Settings → Real-Debrid.", false);
        }
        if (err.equals("permission_denied") || code == 9 || code == 20 || h.code == 403) {
            return new RdException("Real-Debrid refused this request. Check that your account has Premium.", false);
        }
        if (code == 21 || err.equals("too_many_active_downloads")) {
            return new RdException("Too many active downloads on your Real-Debrid account. Remove some in your Real-Debrid torrents list.", false);
        }
        if (code == 34 || h.code == 429) return new RdException("Real-Debrid is busy. Try again in a moment.", false);
        if (code == 24 || code == 19 || err.contains("unavailable") || err.contains("dead")) {
            return new RdException("This file isn't available on Real-Debrid. Pick another stream.", true);
        }
        if (h.code >= 500) return new RdException("Real-Debrid is having problems right now. Try again later.", false);
        return new RdException("Real-Debrid: " + (err.isEmpty() ? "error " + h.code : err.replace('_', ' ')), false);
    }

    public static class DeviceCode {
        public String deviceCode, userCode, verificationUrl;
        public int interval, expiresIn;
    }

    public interface Status { void update(String message); }

    private RealDebrid() { }

    // ------------------------------------------------------------ account

    public static boolean isSignedIn() {
        return !Store.get("rd_api_key").isEmpty() || !Store.get("rd_refresh").isEmpty();
    }

    public static void signOut() {
        for (String k : new String[]{"rd_api_key", "rd_access", "rd_refresh", "rd_expiry", "rd_client_id", "rd_client_secret"}) {
            Store.put(k, "");
        }
    }

    public static void setApiKey(String key) {
        signOut();
        Store.put("rd_api_key", key == null ? "" : key.trim());
    }

    public static JSONObject user() throws Exception {
        return new JSONObject(Http.get(API + "user", token()));
    }

    public static DeviceCode startDeviceLogin() throws Exception {
        JSONObject j = new JSONObject(Http.get(OAUTH + "device/code?client_id=" + OPEN_SOURCE_CLIENT_ID
                + "&new_credentials=yes", null));
        DeviceCode d = new DeviceCode();
        d.deviceCode = j.getString("device_code");
        d.userCode = j.getString("user_code");
        d.verificationUrl = j.optString("verification_url", "https://real-debrid.com/device");
        d.interval = Math.max(2, j.optInt("interval", 5));
        d.expiresIn = j.optInt("expires_in", 600);
        return d;
    }

    /** Returns true once the user has approved the code on real-debrid.com. */
    public static boolean pollDeviceLogin(DeviceCode d) throws Exception {
        JSONObject cred;
        try {
            cred = new JSONObject(Http.get(OAUTH + "device/credentials?client_id=" + OPEN_SOURCE_CLIENT_ID
                    + "&code=" + Http.enc(d.deviceCode), null));
        } catch (Http.HttpException notYet) {
            return false;
        }
        if (!cred.has("client_secret")) return false;
        String cid = cred.getString("client_id");
        String secret = cred.getString("client_secret");
        JSONObject tok = new JSONObject(Http.post(OAUTH + "token", Http.map(
                "client_id", cid, "client_secret", secret, "code", d.deviceCode, "grant_type", DEVICE_GRANT), null));
        signOut();
        Store.put("rd_client_id", cid);
        Store.put("rd_client_secret", secret);
        saveToken(tok);
        return true;
    }

    private static void saveToken(JSONObject t) {
        Store.put("rd_access", t.optString("access_token"));
        Store.put("rd_refresh", t.optString("refresh_token"));
        Store.put("rd_expiry", String.valueOf(System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L));
    }

    private static synchronized String token() throws Exception {
        String key = Store.get("rd_api_key");
        if (!key.isEmpty()) return key;
        String refresh = Store.get("rd_refresh");
        if (refresh.isEmpty()) throw new Exception("Sign in to Real-Debrid first (Real-Debrid in the menu).");
        long expiry = 0;
        try { expiry = Long.parseLong(Store.get("rd_expiry")); } catch (NumberFormatException ignored) { }
        if (System.currentTimeMillis() > expiry - 60_000) {
            saveToken(new JSONObject(Http.post(OAUTH + "token", Http.map(
                    "client_id", Store.get("rd_client_id"),
                    "client_secret", Store.get("rd_client_secret"),
                    "code", refresh,
                    "grant_type", DEVICE_GRANT), null)));
        }
        return Store.get("rd_access");
    }

    // ------------------------------------------------------------ resolving

    /**
     * Adds the torrent to Real-Debrid, selects the right file and returns a direct
     * streaming URL. Throws NotCachedException when Real-Debrid doesn't already have it.
     */
    public static String resolve(String infoHash, int fileIdx, String filename, Status status) throws Exception {
        try {
            return resolveRaw(infoHash, fileIdx, filename, status);
        } catch (Exception e) {
            throw friendly(e);
        }
    }

    private static String resolveRaw(String infoHash, int fileIdx, String filename, Status status) throws Exception {
        String tok = token();
        status.update("Adding to Real-Debrid…");
        String id = new JSONObject(Http.post(API + "torrents/addMagnet",
                Http.map("magnet", "magnet:?xt=urn:btih:" + infoHash), tok)).getString("id");
        boolean keep = false;
        try {
            JSONObject info = waitFor(id, tok, 20, "waiting_files_selection", "downloaded");
            if ("waiting_files_selection".equals(info.optString("status"))) {
                int fileId = pickFile(info.optJSONArray("files"), fileIdx, filename);
                Http.post(API + "torrents/selectFiles/" + id,
                        Http.map("files", fileId > 0 ? String.valueOf(fileId) : "all"), tok);
                status.update("Checking Real-Debrid cache…");
                info = waitFor(id, tok, 8, "downloaded");
            }
            if (!"downloaded".equals(info.optString("status"))) {
                throw new NotCachedException("This one isn't ready on Real-Debrid yet. Pick another stream.");
            }
            JSONArray links = info.optJSONArray("links");
            if (links == null || links.length() == 0) throw new Exception("Real-Debrid returned no links");
            status.update("Getting stream link…");
            JSONObject un = new JSONObject(Http.post(API + "unrestrict/link",
                    Http.map("link", links.getString(0)), tok));
            keep = true;
            return un.getString("download");
        } finally {
            if (!keep) {
                try { Http.delete(API + "torrents/delete/" + id, tok); } catch (Exception ignored) { }
            }
        }
    }

    private static JSONObject waitFor(String id, String tok, int seconds, String... targets) throws Exception {
        JSONObject info = new JSONObject();
        for (int i = 0; i < seconds; i++) {
            info = new JSONObject(Http.get(API + "torrents/info/" + id, tok));
            String st = info.optString("status");
            for (String t : targets) if (t.equals(st)) return info;
            if (FAILED.contains(st)) throw new RdException("Real-Debrid couldn't use this file (" + st.replace('_', ' ') + "). Pick another stream.", true);
            Thread.sleep(1000);
        }
        return info;
    }

    /** Real-Debrid file ids are 1-based in torrent order; add-ons give a 0-based fileIdx. */
    private static int pickFile(JSONArray files, int fileIdx, String filename) {
        if (files == null || files.length() == 0) return -1;
        if (fileIdx >= 0) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null && f.optInt("id") == fileIdx + 1 && isVideo(f.optString("path"))) return f.optInt("id");
            }
        }
        if (filename != null && !filename.isEmpty()) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String path = f.optString("path");
                if (path.equals(filename) || path.endsWith("/" + filename)) return f.optInt("id");
            }
        }
        int best = -1, any = -1;
        long bestSize = -1, anySize = -1;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            long size = f.optLong("bytes");
            if (size > anySize) { anySize = size; any = f.optInt("id"); }
            if (isVideo(f.optString("path")) && size > bestSize) { bestSize = size; best = f.optInt("id"); }
        }
        return best > 0 ? best : any;
    }

    private static boolean isVideo(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXT) if (p.endsWith(ext)) return true;
        return false;
    }
}
