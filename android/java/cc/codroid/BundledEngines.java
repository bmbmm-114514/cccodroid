package cc.codroid;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.ErrnoException;
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
        JSONObject updates = null;
        try { updates = EngineUpdatePins.list(env.context).getJSONObject("releases"); }
        catch (Exception e) { android.util.Log.w("BundledEngines", "无法读取已确认的 Agent 更新", e); }
        prepareAgent(assets, dir, "codex", "Codex", pins.getJSONObject("codex"), updates, ins, checked);
        // 0.1.x used the standalone server. Reclaim its space only after the full client is ready.
        File oldServer = new File(dir, "codex-app-server-0.166.0");
        try {
            if (Files.isRegularFile(oldServer.toPath(), LinkOption.NOFOLLOW_LINKS) && oldServer.length() == 214949224L
                    && Installer.sha256(oldServer).equals("f21b0b95117d4f32151abe78d8030cc1175e877fe741deaaea46451f2b51ade2")) {
                Files.delete(oldServer.toPath());
            }
        } catch (IOException ignored) { /* The current client is ready even if cleanup must wait. */ }
        prepareAgent(assets, dir, "agy", "Antigravity", pins.getJSONObject("agy"), updates, ins, checked);
    }

    private static void prepareAgent(AssetManager assets, File dir, String agent, String label, JSONObject bundled,
                                     JSONObject updates, Installer ins, SharedPreferences checked) throws Exception {
        String baseVersion = bundled.getString("version");
        JSONObject update = updates == null ? null : updates.optJSONObject(agent);
        if (update != null && Updater.cmpVer(update.getString("version"), baseVersion) > 0) {
            String digest = update.getString("sha256");
            File executable = new File(new File(dir, "updates"), agent + "-" + update.getString("version") + "-" + digest);
            try {
                if (usable(executable, update.getLong("bytes"), digest, "update-" + agent, checked)) return;
            } catch (IOException e) { android.util.Log.w("BundledEngines", "无法校验已更新的 " + label, e); }
        }
        installFile(assets, label, agent + ".tar.gz", bundled, new File(dir, agent + "-" + baseVersion), true, ins, checked);
    }

    private static String fingerprint(StructStat st, String hash) {
        String fp = hash + ":" + st.st_dev + ":" + st.st_ino + ":" + st.st_size + ":" + st.st_mtime + ":" + st.st_ctime;
        if (Build.VERSION.SDK_INT >= 27) fp += ":" + st.st_mtim.tv_nsec + ":" + st.st_ctim.tv_nsec;
        return fp;
    }

    private static boolean cacheable(StructStat st) {
        // API 26 exposes only whole seconds. Never reuse or store a recent coarse timestamp.
        return Build.VERSION.SDK_INT >= 27 || System.currentTimeMillis() / 1000 - st.st_ctime > 2;
    }

    private static boolean usable(File file, long size, String hash, String label, SharedPreferences checked)
            throws IOException {
        // Recheck once if a gateway repair or update replaces the path during verification.
        for (int attempt = 0; attempt < 2; attempt++) {
            FileDescriptor fd = null;
            try {
                fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                StructStat before = Os.fstat(fd);
                if (!OsConstants.S_ISREG(before.st_mode) || before.st_size != size || (before.st_mode & 0400) == 0) return false;
                String fp = fingerprint(before, hash);
                boolean executable = (before.st_mode & 0100) != 0;
                if (executable && cacheable(before) && fp.equals(checked.getString(label, null))) {
                    if (fp.equals(fingerprint(Os.lstat(file.getPath()), hash))) return true;
                    continue;
                }
                try (FileInputStream in = new FileInputStream(fd)) {
                    boolean matches = hash.equals(Installer.sha256(in));
                    if (!fp.equals(fingerprint(Os.fstat(fd), hash))) continue;
                    if (!matches) return false;
                    if (!executable) {
                        // Restore a lost execute bit only on the exact file whose contents we verified.
                        if (before.st_nlink != 1) return false;
                        Os.fchmod(fd, (before.st_mode & 0777) | 0100);
                    }
                    StructStat ready = Os.fstat(fd);
                    String stamp = fingerprint(ready, hash);
                    if ((ready.st_mode & 0500) != 0500 || (executable && !fp.equals(stamp))
                            || !stamp.equals(fingerprint(Os.lstat(file.getPath()), hash))) continue;
                    if (executable && cacheable(before)) checked.edit().putString(label, stamp).apply();
                    return true;
                }
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ELOOP || e.errno == OsConstants.EACCES
                        || e.errno == OsConstants.EPERM || e.errno == OsConstants.ENXIO) return false;
                throw new IOException("无法检查 " + label, e);
            } finally {
                if (fd != null && fd.valid()) try { Os.close(fd); } catch (ErrnoException ignored) { }
            }
        }
        return false;
    }

    private static void installFile(AssetManager assets, String label, String asset, JSONObject pin, File target,
                                    boolean tar, Installer ins, SharedPreferences checked) throws Exception {
        long size = pin.getLong("bytes");
        String hash = pin.getString("sha256");
        Path partial = new File(target.getParentFile(), target.getName() + ".bundle-part").toPath();
        Files.deleteIfExists(partial);
        if (usable(target, size, hash, label, checked)) return;
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
            StructStat staged = Os.lstat(partial.toString());
            Files.move(partial, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            StructStat ready = Os.lstat(target.getPath());
            if (staged.st_dev == ready.st_dev && staged.st_ino == ready.st_ino && ready.st_size == size
                    && OsConstants.S_ISREG(ready.st_mode) && (ready.st_mode & 0500) == 0500 && cacheable(ready)) {
                checked.edit().putString(label, fingerprint(ready, hash)).apply();
            }
            ins.bundleProgress(size, size);
        } finally { Files.deleteIfExists(partial); }
    }
}
