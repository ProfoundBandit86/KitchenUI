package com.fluxtv.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Public entry points into Flux TV's package-private server code, for KitchenUI's Kotlin screens. */
public final class Bridge {
    private Bridge() { }

    /** All home catalogs of all add-ons, fetched in parallel. Blocks; call off the main thread. */
    public static List<Addons.Row> catalogRows() {
        return collect(Addons.catalogJobs(), 25);
    }

    /** Search results from every add-on that supports search. Blocks. */
    public static List<Addons.Row> searchRows(String query) {
        return collect(Addons.searchJobs(query), 25);
    }

    private static List<Addons.Row> collect(List<Future<Addons.Row>> jobs, int seconds) {
        List<Addons.Row> out = new ArrayList<>();
        for (Future<Addons.Row> f : jobs) {
            try {
                Addons.Row r = f.get(seconds, TimeUnit.SECONDS);
                if (r != null && !r.items.isEmpty()) out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    // ---- Plex sign-in with a code at plex.tv/link
    public static final class PlexCode {
        public final String code;
        final Plex.Pin pin;
        PlexCode(Plex.Pin pin) { this.pin = pin; this.code = pin.code; }
    }

    public static PlexCode plexStart() throws Exception {
        return new PlexCode(Plex.startPin());
    }

    /** Null until the code has been entered; then the servers found on the account (already saved). */
    public static List<MediaServer> plexPoll(PlexCode c) throws Exception {
        String token = Plex.pollPin(c.pin);
        if (token == null) return null;
        List<MediaServer> found = Plex.discover(token);
        for (MediaServer s : found) Store.saveServer(s);
        return found;
    }

    public static MediaServer plexManual(String url, String token) throws Exception {
        MediaServer s = Plex.manual(url, token);
        Store.saveServer(s);
        return s;
    }

    /** kind = MediaServer.JELLYFIN or MediaServer.EMBY */
    public static MediaServer jellyfinLogin(String kind, String url, String user, String pass) throws Exception {
        MediaServer s = Jellyfin.login(kind, url, user, pass);
        Store.saveServer(s);
        return s;
    }

    public static void removeServer(MediaServer s) {
        Store.removeServer(s.kind, s.id);
        Sources.forget(s);
    }
}
