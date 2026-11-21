package cc.codroid;

import android.app.Activity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Calls only the app's authenticated local gateway. UI callbacks run on the main thread. */
final class NativeRequests {
    private static final ExecutorService pool = Executors.newFixedThreadPool(6);
    static void call(Activity activity, String verb, String path, JSONObject body,
                     Consumer<JSONObject> result, Consumer<String> error) {
        pool.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (!path.startsWith("/api/") || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0)
                    throw new IOException("无效的网关路径");
                byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
                connection = Env.gateway(verb, path, bytes);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(90000);
                if (bytes != null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                int status = connection.getResponseCode();
                boolean success = status >= 200 && status < 300;
                String response = read(success ? connection.getInputStream() : connection.getErrorStream());
                if (!success) {
                    String message = "网关请求失败 (HTTP " + status + ")";
                    try { JSONObject o = new JSONObject(response); message = o.optString("message", o.optString("error", message)); }
                    catch (org.json.JSONException ignored) {}
                    throw new IOException(message);
                }
                JSONObject value = response.isEmpty() ? new JSONObject() : new JSONObject(response);
                activity.runOnUiThread(() -> { if (!activity.isDestroyed() && !activity.isFinishing()) result.accept(value); });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "网关请求失败" : e.getMessage();
                activity.runOnUiThread(() -> { if (!activity.isDestroyed() && !activity.isFinishing()) error.accept(message); });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }
    static void importHistory(MainActivity a, android.net.Uri uri, Consumer<String> finished) {
        pool.execute(() -> {
            java.io.File staged = null;
            HttpURLConnection connection = null;
            String message;
            try {
                staged = java.io.File.createTempFile("import-", ".zip", a.getCacheDir());
                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                try (InputStream raw = a.getContentResolver().openInputStream(uri);
                     java.security.DigestInputStream in = new java.security.DigestInputStream(raw, sha);
                     OutputStream out = new java.io.FileOutputStream(staged)) {
                    MainActivity.copyIn(in, out, a.getCacheDir());
                }
                connection = Env.gatewayWithDigest("POST", "/api/sessions/import", sha.digest());
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Content-Type", "application/zip");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(staged.length());
                try (OutputStream out = connection.getOutputStream()) { java.nio.file.Files.copy(staged.toPath(), out); }
                boolean ok = connection.getResponseCode() == 200;
                JSONObject res = new JSONObject(read(ok ? connection.getInputStream() : connection.getErrorStream()));
                org.json.JSONArray imported = res.optJSONArray("imported"), skipped = res.optJSONArray("skipped");
                message = ok ? "已导入 " + (imported == null ? 0 : imported.length()) + " 个会话，跳过 " + (skipped == null ? 0 : skipped.length()) + " 个" : res.optString("message","导入失败");
            } catch (Exception e) { message = "导入失败：" + e.getMessage(); }
            finally { if (connection != null) connection.disconnect(); if (staged != null) staged.delete(); }
            final String result = message;
            a.runOnUiThread(() -> { if (!a.isDestroyed() && !a.isFinishing()) finished.accept(result); });
        });
    }
    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) != -1;) {
                if (buf.size() + n > (24 << 20)) throw new IOException("返回内容过大");
                buf.write(chunk, 0, n);
            }
            return buf.toString("UTF-8");
        }
    }
    private NativeRequests() {}
}
