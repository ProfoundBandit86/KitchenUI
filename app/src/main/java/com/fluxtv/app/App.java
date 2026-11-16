package com.fluxtv.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared threads and context for the streaming code ported from Flux TV.
 * (In Flux TV this was the Application class; here KitchenUI calls init() at start-up.)
 */
public final class App {
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final ExecutorService IO = Executors.newFixedThreadPool(4);
    public static Context ctx;

    public interface Ok<T> { void run(T value); }
    public interface Err { void run(Exception e); }

    private App() { }

    public static synchronized void init(Context c) {
        if (ctx != null) return;
        ctx = c.getApplicationContext();
        Store.init(ctx);
    }

    public static <T> void async(Callable<T> work, Ok<T> ok, Err err) {
        IO.execute(() -> {
            try {
                T v = work.call();
                MAIN.post(() -> ok.run(v));
            } catch (Exception e) {
                MAIN.post(() -> { if (err != null) err.run(e); });
            }
        });
    }

    public static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
