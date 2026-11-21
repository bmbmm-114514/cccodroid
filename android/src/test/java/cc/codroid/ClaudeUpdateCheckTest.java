package cc.codroid;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = ClaudeUpdateCheckTest.Releases.class)
public class ClaudeUpdateCheckTest {
    @Implements(value = Installer.class, isInAndroidSdk = false)
    public static class Releases {
        static final List<String> requested = Collections.synchronizedList(new ArrayList<>());
        static final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);

        @Implementation protected static byte[] httpGetSmall(String url) throws IOException {
            requested.add(url);
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timed out");
            } catch (InterruptedException e) { throw new IOException(e); }
            return "2.1.309".getBytes(StandardCharsets.US_ASCII);
        }
    }

    @Test public void checkingClaudeAloneSkipsOtherUpdatesAndCoalescesRepeatedTaps() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Updater updater = Updater.get(ctx);
        updater.prefs().edit().putLong("lastCheck", 1234L).commit();
        updater.checkClaudeOnlyAsync();
        try {
            assertTrue(Releases.started.await(5, TimeUnit.SECONDS));
            updater.checkClaudeOnlyAsync();
            assertTrue(updater.status().getBoolean("checking"));
        } finally { Releases.release.countDown(); }

        JSONObject status;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            status = updater.status();
            if (!status.getBoolean("checking")) break;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        assertFalse(status.getBoolean("checking"));
        assertEquals(Collections.singletonList("https://downloads.claude.ai/claude-code-releases/stable"), Releases.requested);
        assertEquals(1234L, status.getLong("lastCheck"));
        JSONArray items = status.getJSONArray("items");
        boolean found = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if ("claude".equals(item.getString("id"))) {
                assertEquals("available", item.getString("state"));
                assertEquals("2.1.309", item.getString("latest"));
                found = true;
            } else assertEquals("unknown", item.getString("state"));
        }
        assertTrue(found);
    }
}
