package cc.codroid;

import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (!env.claudeReady() && !env.claudeOff()) {
            JSONObject pin = pins.getJSONObject("claude");
            File root = new File(env.home, ".local/share/cccodroid/claude");
            File version = new File(root, pin.getString("version"));
            Files.createDirectories(version.toPath());
            installFile(assets, "Claude Code", "claude.gz", pin, new File(version, "claude"), false, ins);
            for (String name : new String[]{"manifest.json", "manifest.json.sig"}) {
                try (InputStream in = assets.open(ROOT + name)) {
                    Files.copy(in, new File(version, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path link = new File(root, "current.bundle").toPath();
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, version.toPath());
            Files.move(link, new File(root, "current").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        File dir = new File(env.home, ".local/share/multi-agent");
        Files.createDirectories(dir.toPath());
        JSONObject codex = pins.getJSONObject("codex"), agy = pins.getJSONObject("agy");
        installFile(assets, "Codex", "codex.tar.gz", codex, new File(dir, "codex-app-server-" + codex.getString("version")), true, ins);
        installFile(assets, "Antigravity", "agy.tar.gz", agy, new File(dir, "agy-" + agy.getString("version")), true, ins);
    }

    private static void installFile(AssetManager assets, String label, String asset, JSONObject pin, File target,
                                    boolean tar, Installer ins) throws Exception {
        long size = pin.getLong("bytes");
        if (target.isFile() && target.length() == size && target.canExecute()) return;
        ins.step("正在从安装包准备 " + label + "…");
        Path partial = new File(target.getParentFile(), target.getName() + ".bundle-part").toPath();
        Files.deleteIfExists(partial);
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
            if (!Installer.hex(digest.digest()).equals(pin.getString("sha256"))) throw new IOException(label + " 校验失败");
            if (!partial.toFile().setExecutable(true, true)) throw new IOException(label + " 设置执行权限失败");
            Files.move(partial, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            ins.bundleProgress(size, size);
        } finally { Files.deleteIfExists(partial); }
    }
}
