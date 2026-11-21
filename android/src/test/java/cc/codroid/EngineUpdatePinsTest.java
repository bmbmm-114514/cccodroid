package cc.codroid;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Duration;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = EngineUpdateKeyShadow.class)
public class EngineUpdatePinsTest {
    private static final String SHA = "2caf73499287af25bd8731e4f719446240e56a141208ac6107e851af581263ab";
    private final Context ctx = RuntimeEnvironment.getApplication();

    private JSONObject request(String agent, String version, String ticket) throws Exception {
        return new JSONObject().put("agent", agent).put("version", version).put("ticket", ticket)
                .put("bytes", 4096L).put("sha256", SHA);
    }

    @Test public void repeatedSelectionAndIndependentAgentsRetainTheirOwnApprovals() throws Exception {
        String first = EngineUpdatePins.begin("codex", "0.18.0");
        assertEquals(first, EngineUpdatePins.begin("codex", "0.18.0"));
        String agy = EngineUpdatePins.begin("agy", "1.5.14");
        assertFalse(EngineUpdatePins.authorized(request("agy", "1.5.14", first)));
        assertFalse(EngineUpdatePins.authorized(request("codex", "0.19.0", first)));
        String selected = EngineUpdatePins.begin("codex", "0.19.0");
        assertNotEquals(first, selected);
        assertFalse(EngineUpdatePins.approve(ctx, request("codex", "0.18.0", first)).getBoolean("ok"));
        JSONObject valid = request("codex", "0.19.0", selected);
        assertTrue(EngineUpdatePins.authorized(valid));
        assertTrue(EngineUpdatePins.approve(ctx, valid).getBoolean("ok"));
        assertFalse(EngineUpdatePins.authorized(valid));
        assertFalse(EngineUpdatePins.approve(ctx, valid).getBoolean("ok"));
        assertTrue(EngineUpdatePins.approve(ctx, request("agy", "1.5.14", agy)).getBoolean("ok"));
        JSONObject records = EngineUpdatePins.list(ctx).getJSONObject("releases");
        assertEquals("0.19.0", records.getJSONObject("codex").getString("version"));
        assertEquals("1.5.14", records.getJSONObject("agy").getString("version"));
        assertEquals(SHA, records.getJSONObject("agy").getString("sha256"));
        assertEquals(4096L, records.getJSONObject("codex").getLong("bytes"));
        assertFalse(records.getJSONObject("codex").has("ticket"));
        assertFalse(records.getJSONObject("codex").has("mac"));
    }

    @Test public void invalidOrExpiredApprovalLeavesThePreviousPinAvailable() throws Exception {
        String prev = EngineUpdatePins.begin("agy", "1.5.13");
        assertTrue(EngineUpdatePins.approve(ctx, request("agy", "1.5.13", prev)).getBoolean("ok"));
        String ticket = EngineUpdatePins.begin("agy", "1.5.14");
        for (long size : new long[]{0, 63, (1L << 30) + 1}) {
            assertFalse(EngineUpdatePins.approve(ctx, request("agy", "1.5.14", ticket).put("bytes", size)).getBoolean("ok"));
        }
        assertFalse(EngineUpdatePins.approve(ctx, request("agy", "1.5.14", ticket).put("sha256", "invalid")).getBoolean("ok"));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31));
        JSONObject expired = request("agy", "1.5.14", ticket);
        assertFalse(EngineUpdatePins.authorized(expired));
        assertFalse(EngineUpdatePins.approve(ctx, expired).getBoolean("ok"));
        assertNotEquals(ticket, EngineUpdatePins.begin("agy", "1.5.14"));
        assertEquals("1.5.13", EngineUpdatePins.list(ctx).getJSONObject("releases").getJSONObject("agy").getString("version"));
    }

    @Test public void missingOrModifiedRecordsFallBackToTheBundledPin() throws Exception {
        assertFalse(EngineUpdatePins.supported("claude", "2.1.308"));
        assertFalse(EngineUpdatePins.supported("agy", "latest"));
        String ticket = EngineUpdatePins.begin("codex", "0.19.0");
        assertTrue(EngineUpdatePins.approve(ctx, request("codex", "0.19.0", ticket)).getBoolean("ok"));
        SharedPreferences prefs = ctx.getSharedPreferences("agent-releases", 0);
        String original = prefs.getString("codex", "");
        JSONObject edited = new JSONObject(original).put("bytes", 8192L);
        prefs.edit().putString("codex", edited.toString()).putString("agy", original).commit();
        assertEquals(0, EngineUpdatePins.list(ctx).getJSONObject("releases").length());
        prefs.edit().putString("codex", "{").remove("agy").commit();
        assertEquals(0, EngineUpdatePins.list(ctx).getJSONObject("releases").length());
    }

    @Test public void lockedKeystorePreventsDownloadAndPreservesTheSelectedVersion() throws Exception {
        String approved = EngineUpdatePins.begin("codex", "0.19.0");
        assertTrue(EngineUpdatePins.approve(ctx, request("codex", "0.19.0", approved)).getBoolean("ok"));
        String next = EngineUpdatePins.begin("codex", "0.20.0");
        assertTrue(EngineUpdatePins.preflight(request("codex", "0.20.0", next)).getBoolean("ok"));
        SharedPreferences prefs = ctx.getSharedPreferences("agent-releases", 0);
        String original = prefs.getString("codex", "");
        EngineUpdateKeyShadow.unavailable = true;
        try {
            try { EngineUpdatePins.begin("agy", "1.5.14"); fail("Cannot start a download without a persistent key"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Android Keystore")); }
            JSONObject blocked = EngineUpdatePins.preflight(request("codex", "0.20.0", next));
            assertFalse(blocked.getBoolean("ok"));
            assertTrue(blocked.getString("message").contains("Android Keystore"));
            assertFalse(EngineUpdatePins.approve(ctx, request("codex", "0.20.0", next)).getBoolean("ok"));
            assertFalse(EngineUpdatePins.list(ctx).getBoolean("ok"));
            assertEquals(original, prefs.getString("codex", null));
        } finally { EngineUpdateKeyShadow.unavailable = false; }
        assertEquals("0.19.0", EngineUpdatePins.list(ctx).getJSONObject("releases").getJSONObject("codex").getString("version"));
    }
}
