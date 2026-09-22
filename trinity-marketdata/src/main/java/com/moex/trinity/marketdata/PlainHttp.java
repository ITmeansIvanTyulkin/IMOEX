package com.moex.trinity.marketdata;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Blocking GET/POST without {@code java.net.http.HttpClient}.
 * JDK 17 HttpClient SSLFlowDelegate busy-spins (HTTP/1.1 and HTTP/2) and
 * does not honor request timeouts once unwrap loops. Same bytes, no strategy.
 */
public final class PlainHttp {

    private PlainHttp() {
    }

    public static String get(String url, int timeoutMs, String userAgent) throws Exception {
        Reply r = exchange("GET", url, timeoutMs, userAgent, null, null, null);
        if (r.status < 200 || r.status >= 300) {
            throw new IllegalStateException("HTTP " + r.status);
        }
        return r.body;
    }

    public static Reply exchange(
            String method,
            String url,
            int timeoutMs,
            String userAgent,
            String contentType,
            byte[] body
    ) throws Exception {
        return exchange(method, url, timeoutMs, userAgent, contentType, body, null);
    }

    public static Reply exchange(
            String method,
            String url,
            int timeoutMs,
            String userAgent,
            String contentType,
            byte[] body,
            Map<String, String> extraHeaders
    ) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        int ms = Math.max(250, timeoutMs);
        c.setConnectTimeout(ms);
        c.setReadTimeout(ms);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", userAgent == null || userAgent.isBlank()
                ? "TRINITY/1.0" : userAgent);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Connection", "close");
        if (extraHeaders != null) {
            for (var e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
        }
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null && !contentType.isBlank()) {
                c.setRequestProperty("Content-Type", contentType);
            }
            c.setFixedLengthStreamingMode(body.length);
            try (var out = c.getOutputStream()) {
                out.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            try (InputStream stream = in) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        c.disconnect();
        return new Reply(code, text);
    }

    public record Reply(int status, String body) {
    }
}
