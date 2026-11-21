package cc.codroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/** Native approval for one specific upgrade. Only the verified executable's digest is persisted. */
final class EngineUpdatePins {
    private static final String KEY_UNAVAILABLE = "Android Keystore 暂时无法保存 Agent 版本，请解锁设备后重试";

    static final class DeviceKey {
        private static final String ALIAS = "cc-agent-release-hmac-v2";

        static SecretKey load(boolean create) throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (create && !store.containsAlias(ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build());
                generator.generateKey();
            }
            // Reopen the persisted key even on first use. Only Android Keystore holds its material.
            return (SecretKey) store.getKey(ALIAS, null);
        }
    }

    private static String mac(boolean create, String message) {
        try {
            SecretKey key = DeviceKey.load(create);
            if (key != null) {
                Mac signer = Mac.getInstance("HmacSHA256");
                signer.init(key);
                return Installer.hex(signer.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception e) { throw new IllegalStateException(KEY_UNAVAILABLE, e); }
        throw new IllegalStateException(KEY_UNAVAILABLE);
    }

    private static final class Pending {
        final String version;
        final String ticket = UUID.randomUUID().toString();
        final long expires = SystemClock.elapsedRealtime() + 30 * 60_000L;
        Pending(String version) { this.version = version; }
    }

    private static final Map<String, Pending> pending = new HashMap<>();

    static boolean supported(String agent, String version) {
        return ("codex".equals(agent) || "agy".equals(agent)) && version != null
                && version.matches("(?:0|[1-9][0-9]{0,8})(?:\\.(?:0|[1-9][0-9]{0,8})){2}");
    }

    static synchronized String begin(String agent, String version) {
        if (!supported(agent, version)) throw new IllegalArgumentException("无效的 Agent 版本");
        mac(true, "agent-release-key-check");
        Pending previous = pending.get(agent);
        if (previous != null && previous.version.equals(version) && previous.expires >= SystemClock.elapsedRealtime()) return previous.ticket;
        Pending choice = new Pending(version);
        pending.put(agent, choice);
        return choice.ticket;
    }

    static synchronized boolean authorized(JSONObject req) {
        Pending choice = pending.get(req.optString("agent"));
        return choice != null && choice.expires >= SystemClock.elapsedRealtime()
                && choice.version.equals(req.optString("version")) && choice.ticket.equals(req.optString("ticket"));
    }

    static synchronized JSONObject preflight(JSONObject req) throws Exception {
        if (!authorized(req)) return new JSONObject().put("ok", false).put("message", "请在更新页重新选择要安装的版本");
        try { mac(false, "agent-release-key-check"); }
        catch (IllegalStateException e) { return new JSONObject().put("ok", false).put("message", KEY_UNAVAILABLE); }
        return new JSONObject().put("ok", true);
    }

    private static String signRecord(String agent, String version, long bytes, String sha) {
        return mac(false, "agent-release-v2\n" + agent + "\n" + version + "\n" + bytes + "\n" + sha);
    }

    static synchronized JSONObject approve(Context ctx, JSONObject req) throws Exception {
        String agent = req.optString("agent"), version = req.optString("version"), digest = req.optString("sha256");
        long bytes = req.optLong("bytes");
        if (!authorized(req) || !digest.matches("[a-f0-9]{64}")
                || bytes < 64 || bytes > (1L << 30)) {
            return new JSONObject().put("ok", false).put("message", "请在更新页重新选择要安装的版本");
        }
        String tag;
        try { tag = signRecord(agent, version, bytes, digest); }
        catch (IllegalStateException e) { return new JSONObject().put("ok", false).put("message", KEY_UNAVAILABLE); }
        JSONObject record = new JSONObject().put("version", version).put("bytes", bytes).put("sha256", digest).put("mac", tag);
        if (!ctx.getSharedPreferences("agent-releases", 0).edit().putString(agent, record.toString()).commit()) {
            return new JSONObject().put("ok", false).put("message", "保存 Agent 版本失败");
        }
        pending.remove(agent);
        return new JSONObject().put("ok", true);
    }

    static synchronized JSONObject list(Context ctx) throws Exception {
        JSONObject releases = new JSONObject();
        SharedPreferences prefs = ctx.getSharedPreferences("agent-releases", 0);
        for (String agent : new String[]{"codex", "agy"}) {
            try {
                String raw = prefs.getString(agent, null);
                if (raw == null) continue;
                JSONObject record = new JSONObject(raw);
                String version = record.getString("version"), digest = record.getString("sha256");
                long bytes = record.getLong("bytes");
                String expected = signRecord(agent, version, bytes, digest);
                if (supported(agent, version) && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        record.getString("mac").getBytes(StandardCharsets.US_ASCII))) {
                    record.remove("mac");
                    releases.put(agent, record);
                }
            } catch (org.json.JSONException | ClassCastException ignored) { /* Use the bundled pin for an incomplete record. */ }
            catch (IllegalStateException e) { return new JSONObject().put("ok", false).put("releases", releases).put("message", KEY_UNAVAILABLE); }
        }
        return new JSONObject().put("ok", true).put("releases", releases);
    }

    private EngineUpdatePins() {}
}
