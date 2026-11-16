package com.fluxtv.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal blocking HTTP helper. Call from background threads only. */
public final class Http {

    public static class HttpException extends IOException {
        public final int code;
        public final String body;

        HttpException(int code, String body) {
            super("HTTP " + code + (body == null || body.isEmpty() ? "" : ": " + shorten(body)));
            this.code = code;
            this.body = body;
        }
    }

    private static final String FORM = "application/x-www-form-urlencoded";

    private Http() { }

    public static String get(String url, String bearer) throws IOException {
        return text(request("GET", url, bearerHeader(bearer), null, null, 30000));
    }

    public static String post(String url, Map<String, String> form, String bearer) throws IOException {
        return text(request("POST", url, bearerHeader(bearer), form(form), FORM, 30000));
    }

    public static String delete(String url, String bearer) throws IOException {
        return text(request("DELETE", url, bearerHeader(bearer), null, null, 30000));
    }

    public static byte[] bytes(String url) throws IOException {
        return request("GET", url, null, null, null, 30000);
    }

    /** Streams a (possibly large) file straight into out, following redirects. */
    public static void download(String url, OutputStream out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "KitchenUI/1.0 (Android)");
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpException(code, "");
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Opens a (possibly huge) response as a stream; closing it releases the connection. */
    public static InputStream open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "KitchenUI/1.0 (Android)");
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new HttpException(code, "");
        }
        InputStream in = c.getInputStream();
        return new java.io.FilterInputStream(in) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { c.disconnect(); }
            }
        };
    }

    /** Any method with custom headers. contentType may be null when there is no body. */
    public static String call(String method, String url, Map<String, String> headers, String body, String contentType)
            throws IOException {
        return text(request(method, url, headers, body, contentType, 30000));
    }

    /** True if the URL answers with a non-error status within the timeout. */
    public static boolean ping(String url, Map<String, String> headers, int timeoutMs) {
        try {
            request("GET", url, headers, null, null, timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static String form(Map<String, String> form) {
        if (form == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    private static Map<String, String> bearerHeader(String bearer) {
        return bearer == null || bearer.isEmpty() ? null : map("Authorization", "Bearer " + bearer);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static byte[] request(String method, String url, Map<String, String> headers, String body,
                                  String contentType, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(Math.min(timeoutMs, 15000));
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "KitchenUI/1.0 (Android)");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType == null ? FORM : contentType);
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] out = read(is);
            if (code >= 400) throw new HttpException(code, text(out));
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
