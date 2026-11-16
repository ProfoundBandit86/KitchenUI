package com.fluxtv.app;

/** The two text helpers the ported server code uses. */
public final class Ui {
    private Ui() { }

    public static String left(long posMs, long durMs) {
        long min = Math.max(1, (durMs - posMs) / 60000);
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min left" : min + " min left";
    }

    public static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
