package cc.codroid;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** Offline installation from the signed APK. Activate a version only after all its files are complete. */
final class BundledEngines {
    private static final String ROOT = "engines/";

    static synchronized void prepare(Env env, Installer ins) throws Exception {
        AssetManager assets = env.context.getAssets();
        JSONObject pins = new JSONObject(AppFiles.assetText(env.context, ROOT + "engines.json"));
        SharedPreferences checked = env.context.getSharedPreferences("bundled-engine-hashes", 0);
        JSONObject claude = pins.getJSONObject("claude");
        Path root = new File(env.home, ".local/share/cccodroid/claude").toPath();
        Path current = root.resolve("current"), version = root.resolve(claude.getString("version"));
        boolean activate = !Files.exists(current, LinkOption.NOFOLLOW_LINKS);
        if (!env.claudeOff() && (activate || current.toFile().getCanonicalFile().equals(version.toFile().getCanonicalFile()))) {
            Files.createDirectories(version);
            installFile(assets, "Claude Code", "claude.gz", claude, version.resolve("claude").toFile(), false, ins, checked);
            for (String name : new String[]{"manifest.json", "manifest.json.sig"}) {
                String text = AppFiles.assetText(env.context, ROOT + name);
                if (text == null) throw new IOException("Missing bundled " + name);
                byte[] bundled = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Path file = version.resolve(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == bundled.length &&
                        java.util.Arrays.equals(Files.readAllBytes(file), bundled)) continue;
                Path stage = Files.createTempFile(version, name, ".part");
                try {
                    Files.write(stage, bundled);
                    Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(stage); }
            }
            Files.deleteIfExists(version.resolve(".mismatch"));
            if (activate) {
                Path link = root.resolve("current.bundle");
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, version);
                Files.move(link, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        File dir = new File(env.home, ".local/share/multi-agent");
        Files.createDirectories(dir.toPath());
        JSONObject codex = pins.getJSONObject("codex"), agy = pins.getJSONObject("agy");
        installFile(assets, "Codex", "codex.tar.gz", codex, new File(dir, "codex-app-server-" + codex.getString("version")), true, ins, checked);
        installFile(assets, "Antigravity", "agy.tar.gz", agy, new File(dir, "agy-" + agy.getString("version")), true, ins, checked);
    }

    // Cache a successful content check across app launches. The gateway separately authenticates the executable before use.
    private static String fingerprint(File file, long size, String hash) {
        try {
            StructStat st = Os.lstat(file.getPath());
            if (!OsConstants.S_ISREG(st.st_mode) || (st.st_mode & 0500) != 0500 || st.st_size != size) return null;
            String fp = hash + ":" + st.st_dev + ":" + st.st_ino + ":" + st.st_size + ":" + st.st_mtime + ":" + st.st_ctime;
            if (Build.VERSION.SDK_INT >= 27) return fp + ":" + st.st_mtim.tv_nsec + ":" + st.st_ctim.tv_nsec;
            // API 26 only exposes whole-second ctime. Cache after that timestamp has settled.
            return System.currentTimeMillis() / 1000 - st.st_ctime > 2 ? fp : "";
        } catch (android.system.ErrnoException e) { return null; }
    }

    private static void installFile(AssetManager assets, String label, String asset, JSONObject pin, File target,
                                    boolean tar, Installer ins, SharedPreferences checked) throws Exception {
        long size = pin.getLong("bytes");
        String hash = pin.getString("sha256");
        Path partial = new File(target.getParentFile(), target.getName() + ".bundle-part").toPath();
        Files.deleteIfExists(partial);
        String fp = fingerprint(target, size, hash);
        if (fp != null) {
            if (!fp.isEmpty() && fp.equals(checked.getString(label, null))) return;
            if (hash.equals(Installer.sha256(target)) && fp.equals(fingerprint(target, size, hash))) {
                if (!fp.isEmpty()) checked.edit().putString(label, fp).apply();
                return;
            }
        }
        ins.step("正在从安装包准备 " + label + "…");
        Files.createFile(partial);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = assets.open(ROOT + asset, AssetManager.ACCESS_STREAMING);
                 InputStream in = new GZIPInputStream(raw, 65536); OutputStream out = Files.newOutputStream(partial)) {
                if (tar) {
                    byte[] header = new byte[512];
                    new DataInputStream(in).readFully(header);
                    String field = new String(header, 124, 12, java.nio.charset.StandardCharsets.US_ASCII).trim();
                    if (header[156] != '0' || Long.parseLong(field, 8) != size) throw new IOException("Invalid bundled tar for " + label);
                }
                byte[] buf = new byte[262144];
                long copied = 0, last = 0;
                while (copied < size) {
                    int n = in.read(buf, 0, (int)Math.min(buf.length, size - copied));
                    if (n < 0) throw new EOFException(label);
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    copied += n;
                    if (copied - last >= (8 << 20)) { ins.bundleProgress(copied, size); last = copied; }
                }
                if (!tar && in.read() != -1) throw new IOException("Invalid bundled size for " + label);
            }
            if (!Installer.hex(digest.digest()).equals(hash)) throw new IOException(label + " 校验失败");
            if (!partial.toFile().setExecutable(true, true)) throw new IOException(label + " 设置执行权限失败");
            Files.move(partial, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fp = fingerprint(target, size, hash);
            if (fp != null && !fp.isEmpty()) checked.edit().putString(label, fp).apply();
            ins.bundleProgress(size, size);
        } finally { Files.deleteIfExists(partial); }
    }
}
