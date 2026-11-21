package cc.codroid;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {NativeAgentUpdatesTest.Gateway.class, EngineUpdateKeyShadow.class})
public class NativeAgentUpdatesTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static int engineReads;
        static boolean installing;
        static final List<JSONObject> posts = new ArrayList<>();
        static Consumer<JSONObject> reply;
        @Implementation public static void call(Activity owner, String verb, String path, JSONObject body,
                Consumer<JSONObject> ok, Consumer<String> fail) {
            if ("POST".equals(verb)) {
                posts.add(NativeHome.json("path", path, "body", body));
                reply = ok;
            } else if ("/api/multi/engine-updates".equals(path)) {
                engineReads++;
                ok.accept(NativeHome.json("items", new JSONArray()
                    .put(NativeHome.json("agent", "codex", "current", "0.166.0", "state", "current"))
                    .put(NativeHome.json("agent", "agy", "current", "1.5.13", "latest", "1.5.14", "state", installing ? "installing" : "available"))));
            } else if ("/api/app/upd_status".equals(path)) {
                ok.accept(NativeHome.json("items", new JSONArray().put(NativeHome.json("id", "claude", "name", "Claude Code", "current", "2.1.100", "latest", "2.1.101", "state", "available"))));
            } else ok.accept(new JSONObject());
        }
    }

    private NativeHome home;
    private Dialog window;
    @Before public void setup() {
        Gateway.engineReads = 0; Gateway.installing = false; Gateway.posts.clear();
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(NativeAppearance.theme(activity));
        home = new NativeHome(activity, () -> {});
        window = new Dialog(activity, NativeAppearance.theme(activity));
        ((ViewGroup) home.root.getParent()).removeView(home.root);
        window.setContentView(home.root);
        window.show();
    }
    @After public void close() { home.dispose(); window.dismiss(); }

    @Test public void settingsHasIndependentAgentsAndChecksWithoutAnInstallTicket() {
        home.nav("set-update");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertNotNull(text(home.root, "Claude Code"));
        assertNotNull(text(home.root, "Antigravity"));
        assertNotNull(text(home.root, "校验工具包完整性"));
        View card = (View) text(home.root, "Codex").getParent();
        text(card, "检查更新").performClick();
        assertEquals(1, Gateway.posts.size());
        JSONObject body = Gateway.posts.get(0).optJSONObject("body");
        assertEquals("check", body.optString("action"));
        assertEquals("codex", body.optString("agent"));
        assertFalse(body.has("ticket"));
        Gateway.reply.accept(NativeHome.json("ok", true));
        text(home.root, "更新到 2.1.101").performClick();
        JSONObject claude = Gateway.posts.get(1);
        assertEquals("/api/app/upd_apply", claude.optString("path"));
        assertEquals("claude", claude.optJSONObject("body").optString("item"));
        assertEquals("2.1.101", claude.optJSONObject("body").optString("version"));
    }

    @Test public void unavailableKeystoreLeavesManualUpdateUnsubmittedAndShowsRecovery() {
        home.nav("set-update");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        EngineUpdateKeyShadow.unavailable = true;
        try {
            text(home.root, "更新到 1.5.14").performClick();
            assertTrue(Gateway.posts.isEmpty());
            assertNotNull(text(home.root, "Android Keystore 暂时无法保存 Agent 版本，请解锁设备后重试"));
        } finally { EngineUpdateKeyShadow.unavailable = false; }
    }

    @Test public void classicSheetPinsOnlyTheChosenVersionOnceAndStopsPollingOnDismiss() {
        NativeAgentUpdates.show(home.a);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        Dialog sheet = ShadowDialog.getLatestDialog();
        View root = sheet.getWindow().getDecorView();
        View update = text(root, "更新到 1.5.14");
        assertNotNull(update);
        assertTrue(Gateway.posts.isEmpty());
        update.performClick(); update.performClick();
        assertEquals(1, Gateway.posts.size());
        JSONObject body = Gateway.posts.get(0).optJSONObject("body");
        assertEquals("agy", body.optString("agent"));
        assertEquals("1.5.14", body.optString("version"));
        assertFalse(body.optString("ticket").isEmpty());
        Gateway.installing = true;
        Gateway.reply.accept(NativeHome.json("ok", true));
        int reads = Gateway.engineReads;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1250));
        assertTrue(Gateway.engineReads > reads);
        sheet.dismiss();
        reads = Gateway.engineReads;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertEquals(reads, Gateway.engineReads);
    }

    private static TextView text(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
