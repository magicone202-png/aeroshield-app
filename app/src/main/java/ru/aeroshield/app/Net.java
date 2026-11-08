package ru.aeroshield.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Простые HTTP-запросы к прибору 1 (ESP32 в той же сети Wi-Fi). */
public final class Net {
    private Net() {}

    public static final class Resp {
        public final int code;
        public final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
    }

    /** method: GET или POST. body для POST — строка вида a=1&b=2. Код 0 — нет связи. */
    public static Resp request(String method, String url, String body, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            if ("POST".equals(method)) {
                byte[] data = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream os = c.getOutputStream();
                os.write(data);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                text = bo.toString("UTF-8");
            }
            return new Resp(code, text);
        } catch (Exception e) {
            return new Resp(0, "");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
