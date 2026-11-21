package cc.codroid;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructTimespec;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {BundledEnginesTest.HostStat.class, EngineUpdateKeyShadow.class})
public class BundledEnginesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final List<String> installs = new ArrayList<>();
    private static final byte[] FIXTURE = "fixture agy executable\n".getBytes(StandardCharsets.UTF_8);
    // A gzip-compressed ustar containing only "antigravity", mode 0755, with FIXTURE as its payload.
    private static final String ARCHIVE = "H4sIAAAAAAAC/+3NwQrCMBCE4T37FHmENaH0eVaJJVB6SDelfXujF8G7Ivh/lxnmMrZ4maptxQ/5FO3GYXhm956qcXz1x37WmJIElS9oq1vt9/KfbmX3VnOw6Qh5z9fmdpnzSQAAAAAAAAAAAAAAAAAAv+0O30ejxgAoAAA=";

    // Robolectric's lstat follows symlinks and drops real permissions, inode and ctime.
    // Supply the host's actual no-follow stat for these filesystem assertions.
    @Implements(Os.class)
    public static class HostStat {
        private static final Map<FileDescriptor,FileInputStream> streams = new IdentityHashMap<>();
        private static final Map<FileDescriptor,String> names = new IdentityHashMap<>();
        static String settlingFile;
        static long editSecond;
        static int stats;

        @Implementation public static FileDescriptor open(String name, int flags, int mode) throws ErrnoException {
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(new File(name).toPath())) {
                throw new ErrnoException("open", OsConstants.ELOOP);
            }
            try {
                FileInputStream in = new FileInputStream(name);
                FileDescriptor fd = in.getFD();
                names.put(fd, new File(name).getCanonicalPath());
                streams.put(fd, in);
                return fd;
            } catch (IOException e) { throw new ErrnoException("open", OsConstants.ENOENT, e); }
        }

        @Implementation public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
            return lstat(names.get(fd));
        }

        @Implementation public static void fchmod(FileDescriptor fd, int mode) throws ErrnoException {
            try { Files.setAttribute(new File(names.get(fd)).toPath(), "unix:mode", mode); }
            catch (IOException e) { throw new ErrnoException("fchmod", OsConstants.EIO, e); }
        }

        @Implementation public static void close(FileDescriptor fd) throws ErrnoException {
            names.remove(fd);
            FileInputStream in = streams.remove(fd);
            if (in == null) return;
            try { in.close(); }
            catch (IOException e) { throw new ErrnoException("close", OsConstants.EIO, e); }
        }

        @Implementation public static StructStat lstat(String name) throws ErrnoException {
            try {
                Map<String,Object> s = Files.readAttributes(new File(name).toPath(),
                        "unix:dev,ino,mode,nlink,size,lastModifiedTime,ctime", LinkOption.NOFOLLOW_LINKS);
                if (Build.VERSION.SDK_INT >= 27) return NanosecondStat.from(s);
                long modified = ((FileTime) s.get("lastModifiedTime")).toInstant().getEpochSecond();
                long changed = ((FileTime) s.get("ctime")).toInstant().getEpochSecond();
                if (name.equals(settlingFile)) {
                    stats++;
                    if (stats == 1) editSecond = System.currentTimeMillis() / 1000 - 2;
                    if (stats == 2) {
                        // Cross the whole-second cache threshold while hashing, with identical inode and ctime.
                        long wait = (editSecond + 3) * 1000 - System.currentTimeMillis();
                        if (wait > 0) try { Thread.sleep(wait + 1); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4));
                    }
                    changed = editSecond;
                }
                return new StructStat((long) s.get("dev"), (long) s.get("ino"), (int) s.get("mode"),
                        ((Number) s.get("nlink")).longValue(), 0, 0, 0L, (long) s.get("size"), modified, modified, changed, 0L, 0L);
            } catch (IOException e) {
                throw new ErrnoException("lstat", OsConstants.ENOENT, e);
            }
        }

    }

    // API 26 cannot load StructTimespec. Keep the API 27+ signature in its own class.
    private static class NanosecondStat {
        static StructStat from(Map<String,Object> s) {
            StructTimespec mtime = time((FileTime) s.get("lastModifiedTime"));
            return new StructStat((long) s.get("dev"), (long) s.get("ino"), (int) s.get("mode"),
                    ((Number) s.get("nlink")).longValue(), 0, 0, 0L, (long) s.get("size"),
                    mtime, mtime, time((FileTime) s.get("ctime")), 0L, 0L);
        }

        private static StructTimespec time(FileTime value) {
            return new StructTimespec(value.toInstant().getEpochSecond(), value.toInstant().getNano());
        }
    }

    static AssetManager assets(Path apk) {
        AssetManager manager = ReflectionHelpers.callConstructor(AssetManager.class);
        int cookie = ReflectionHelpers.callInstanceMethod(manager, "addAssetPath",
                ReflectionHelpers.ClassParameter.from(String.class, apk.toString()));
        assertNotEquals("add APK to AssetManager", 0, cookie);
        return manager;
    }

    private AssetManager fixture(String version) throws Exception {
        byte[] archive = Base64.getDecoder().decode(ARCHIVE);
        JSONObject pin = new JSONObject().put("bytes", FIXTURE.length)
                .put("sha256", "d1b02d840effd7aa9ff1768c3638a2e720e77715092ee94b691c15d691a85798");
        JSONObject pins = new JSONObject().put("claude", new JSONObject().put("version", "unused"))
                .put("codex", new JSONObject(pin.toString()).put("version", "1"))
                .put("agy", pin.put("version", version));
        Path apk = temp.newFile("bundle-" + version + ".apk").toPath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(apk))) {
            entry(out, "codex.tar.gz", archive);
            entry(out, "agy.tar.gz", archive);
            entry(out, "engines.json", pins.toString().getBytes(StandardCharsets.UTF_8));
        }
        return assets(apk);
    }

    private static void entry(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry("assets/engines/" + name));
        out.write(data);
        out.closeEntry();
    }

    static Env env(AssetManager assets, File dir) throws Exception {
        Context storage = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public AssetManager getAssets() { return assets; }
            @Override public File getFilesDir() { return dir; }
        };
        Env env = new Env(storage);
        if (!env.claudeOff()) Files.createFile(env.claudeOffFlag().toPath());
        return env;
    }

    void launch(Env env) throws Exception {
        installs.clear();
        BundledEngines.prepare(env, new Installer(env, new Installer.Progress() {
            public void step(String msg) { installs.add(msg); }
            public void bytes(long done, long total) {}
        }));
    }

    private static Object inode(Path file) throws IOException {
        return Files.getAttribute(file, "unix:ino", LinkOption.NOFOLLOW_LINKS);
    }

    @Test public void repeatedStartsAndPermissionRepairReuseTheInstalledExecutable() throws Exception {
        try (AssetManager assets = fixture("1")) {
            Env env = env(assets, temp.newFolder("data"));
            Path file = env.home.toPath().resolve(".local/share/multi-agent/agy-1");
            launch(env);
            assertEquals(2, installs.size());
            assertArrayEquals(FIXTURE, Files.readAllBytes(file));
            Object original = inode(file);
            FileTime modified = Files.getLastModifiedTime(file);
            launch(env);
            assertTrue(installs.toString(), installs.isEmpty());
            env.context.getSharedPreferences("bundled-engine-hashes", 0).edit().clear().commit();
            assertTrue(file.toFile().setExecutable(false, false));
            launch(env);
            assertTrue(installs.toString(), installs.isEmpty());
            assertTrue(Files.isExecutable(file));
            assertEquals(original, inode(file));
            assertEquals(modified, Files.getLastModifiedTime(file));
            launch(env);
            assertTrue(installs.toString(), installs.isEmpty());
        }
    }

    @Test @Config(sdk = 26)
    public void verifiedExecutableStaysInPlaceWhenCoarseTimestampSettlesDuringHash() throws Exception {
        try (AssetManager assets = fixture("1")) {
            Env env = env(assets, temp.newFolder("data"));
            Path target = env.home.toPath().resolve(".local/share/multi-agent/agy-1");
            launch(env);
            Object original = inode(target);
            env.context.getSharedPreferences("bundled-engine-hashes", 0).edit().clear().commit();
            HostStat.settlingFile = target.toString();
            HostStat.stats = 0;
            try {
                launch(env);
                assertTrue(HostStat.stats >= 2);
                assertTrue(installs.toString(), installs.isEmpty());
                assertEquals(original, inode(target));
            } finally { HostStat.settlingFile = null; }
        }
    }

    @Test public void coldStartUsesApprovedUpdatesWithoutRecreatingMissingBundledVersions() throws Exception {
        File data = temp.newFolder("data");
        try (AssetManager v1 = fixture("1.5.13"); AssetManager v2 = fixture("1.6.0")) {
            Env first = env(v1, data);
            Path dir = first.home.toPath().resolve(".local/share/multi-agent");
            byte[] upgraded = new byte[256];
            Arrays.fill(upgraded, (byte) '2');
            launch(first);
            Path updates = Files.createDirectory(dir.resolve("updates"));
            Path agy = null;
            for (String id : new String[]{"codex", "agy"}) {
                String version = id.equals("agy") ? "1.5.14" : "1.0.1";
                String digest = Installer.hex(java.security.MessageDigest.getInstance("SHA-256").digest(upgraded));
                JSONObject req = new JSONObject().put("agent", id).put("version", version)
                        .put("ticket", EngineUpdatePins.begin(id, version)).put("sha256", digest).put("bytes", upgraded.length);
                Path executable = Files.write(updates.resolve(id + "-" + version + "-" + digest), upgraded);
                assertTrue(executable.toFile().setExecutable(true, true));
                assertTrue(EngineUpdatePins.approve(first.context, req).getBoolean("ok"));
                Files.delete(dir.resolve(id.equals("agy") ? "agy-1.5.13" : "codex-1"));
                if (id.equals("agy")) agy = executable;
            }
            Env cold = env(v1, data);
            cold.context.getSharedPreferences("bundled-engine-hashes", 0).edit().clear().commit();
            launch(cold);
            assertTrue(installs.toString(), installs.isEmpty());
            assertFalse(Files.exists(dir.resolve("codex-1")));
            assertFalse(Files.exists(dir.resolve("agy-1.5.13")));
            launch(env(v2, data));
            assertEquals(1, installs.size());
            assertArrayEquals(FIXTURE, Files.readAllBytes(dir.resolve("agy-1.6.0")));
            Files.write(agy, new byte[upgraded.length]);
            launch(cold);
            assertEquals(1, installs.size());
            assertArrayEquals(FIXTURE, Files.readAllBytes(dir.resolve("agy-1.5.13")));
            assertFalse(Files.exists(dir.resolve("codex-1")));
            launch(cold);
            assertTrue(installs.toString(), installs.isEmpty());
        }
    }

    @Test public void corruptFilesAndSymlinksAreReplacedAndNewVersionsInstallOnce() throws Exception {
        File data = temp.newFolder("data");
        try (AssetManager v1 = fixture("1"); AssetManager v2 = fixture("2")) {
            Env first = env(v1, data);
            Path dir = first.home.toPath().resolve(".local/share/multi-agent");
            Path target = dir.resolve("agy-1");
            launch(first);
            byte[] wrong = FIXTURE.clone();
            Arrays.fill(wrong, (byte)'?');
            Files.write(target, wrong);
            target.toFile().setExecutable(false, false);
            launch(first);
            assertEquals(1, installs.size());
            assertArrayEquals(FIXTURE, Files.readAllBytes(target));
            Path elsewhere = temp.newFile("outside").toPath();
            Files.write(elsewhere, FIXTURE);
            elsewhere.toFile().setExecutable(false, false);
            Files.delete(target);
            Files.createSymbolicLink(target, elsewhere);
            launch(first);
            assertEquals(1, installs.size());
            assertFalse(Files.isSymbolicLink(target));
            assertArrayEquals(FIXTURE, Files.readAllBytes(elsewhere));
            assertFalse(Files.isExecutable(elsewhere));
            assertArrayEquals(FIXTURE, Files.readAllBytes(target));
            Env upgraded = env(v2, data);
            launch(upgraded);
            assertEquals(1, installs.size());
            assertArrayEquals(FIXTURE, Files.readAllBytes(dir.resolve("agy-2")));
            launch(upgraded);
            assertTrue(installs.toString(), installs.isEmpty());
        }
    }
}
