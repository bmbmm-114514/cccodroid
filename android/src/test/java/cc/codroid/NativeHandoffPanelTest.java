package cc.codroid;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.time.Duration;
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
@Config(sdk = 28, shadows = NativeHandoffPanelTest.Gateway.class)
public class NativeHandoffPanelTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static String source;
        static JSONArray providers;
        static JSONObject sent;
        static int modelReads;
        static String modelPath;
        static Consumer<JSONObject> models;

        @Implementation protected static void call(Activity a, String method, String route, JSONObject body,
                Consumer<JSONObject> success, Consumer<String> error) {
            if (route.startsWith("/api/multi/state")) {
                JSONObject turn = NativeHome.json("id", "turn-1", "user", "写一个组件", "agent", source);
                JSONObject conversation = NativeHome.json("id", "conversation", "activeAgent", source,
                        "turns", new JSONArray().put(turn));
                success.accept(NativeHome.json("conversation", conversation, "providers", providers));
            } else if (route.startsWith("/api/multi/models?")) {
                modelReads++;
                modelPath = route;
                models = success;
            } else if (route.equals("/api/multi/preferences")) {
                success.accept(NativeHome.json("agent", "codex", "provider", "chatgpt", "model", "codex-test"));
            } else {
                if (route.equals("/api/multi/send")) sent = body;
                success.accept(new JSONObject());
            }
        }
    }

    private NativeHome home;

    @Before public void openConversation() {
        Gateway.source = "claude";
        Gateway.providers = new JSONArray().put(provider("a")).put(provider("b"));
        Gateway.sent = null;
        Gateway.modelReads = 0;
        Gateway.models = null;
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        host.setTheme(NativeAppearance.theme(host));
        home = new NativeHome(host, () -> {});
        home.showChat("conversation");
    }

    @After public void dispose() { home.dispose(); }

    private static JSONObject provider(String id) {
        return NativeHome.json("id", id, "label", "接口 " + id, "model", "default-" + id);
    }

    @Test public void thirdPartyDraftSurvivesProviderReorderingAndAnUnavailableInterfaceCannotSend() {
        choose("自动交接", "第三方接口");
        tap(home.root, "接口 a");
        BottomSheetDialog open = sheet();
        Gateway.providers = new JSONArray().put(provider("b")).put(provider("a"));
        home.chat.refreshSelection();
        tap(open.getWindow().getDecorView(), "接口 b");
        settle();
        EditText model = (EditText) find(home.root, "default-b");
        assertNotNull(model);
        model.setText("custom-b");
        Gateway.providers = new JSONArray().put(provider("a")).put(provider("b"));
        home.chat.refreshSelection();
        assertSame(model, find(home.root, "custom-b"));
        home.share("继续开发");

        Gateway.providers = new JSONArray().put(provider("a"));
        home.chat.refreshSelection();
        assertNotNull(find(home.root, "选择交接接口"));
        tap(home.root, "发送");
        assertNull(Gateway.sent);
        assertNotNull(find(home.root, "继续开发"));

        Gateway.source = "agy";
        Gateway.providers.put(provider("b"));
        home.chat.refreshSelection();
        assertNotNull(find(home.root, "所选接口将接收本对话的可见记录。"));
        tap(home.root, "发送");
        assertNotNull(Gateway.sent);
        assertEquals("third-party", Gateway.sent.optString("mode"));
        assertEquals("b", Gateway.sent.optString("provider"));
        assertEquals("custom-b", Gateway.sent.optString("model"));
        assertEquals("chatgpt", Gateway.sent.optString("destinationProvider"));
        assertEquals("继续开发", Gateway.sent.optString("text"));
    }

    @Test public void handoffDiscoversModelsOnDemandAndKeepsNewerTypedInput() {
        choose("自动交接", "第三方接口");
        EditText model = (EditText) find(home.root, "default-a");
        assertNotNull(model);
        home.chat.refreshSelection();
        assertEquals(0, Gateway.modelReads);
        JSONObject catalog = NativeHome.json("models", new JSONArray().put(NativeHome.json("id", "remote-a", "label", "Remote A")));
        tap(home.root, "选择交接模型");
        assertEquals("/api/multi/models?agent=claude&provider=a&refresh=1", Gateway.modelPath);
        model.setText("typed-after-request");
        Gateway.models.accept(catalog);
        assertEquals("typed-after-request", model.getText().toString());
        assertFalse(sheet().isShowing());
        home.chat.refreshSelection();
        assertEquals(1, Gateway.modelReads);
        tap(home.root, "选择交接模型");
        Gateway.models.accept(catalog);
        tap(sheet().getWindow().getDecorView(), "Remote A");
        settle();
        home.chat.refreshSelection();
        assertEquals("remote-a", model.getText().toString());
        home.share("交接");
        tap(home.root, "发送");
        assertEquals("a", Gateway.sent.optString("provider"));
        assertEquals("remote-a", Gateway.sent.optString("model"));
        assertEquals(2, Gateway.modelReads);
    }

    @Test public void manualAgyHandoffCanBeCancelledAndSendsOnlyConfirmedTurns() {
        Gateway.source = "agy";
        home.chat.refreshSelection();
        assertNull(findClass(home.root, Spinner.class));
        choose("自动交接", "手动选择");
        tap(home.root, "选择历史回合");
        BottomSheetDialog history = sheet();
        MaterialCheckBox row = findClass(history.getWindow().getDecorView(), MaterialCheckBox.class);
        assertNotNull(row);
        row.performClick();
        history.cancel();
        settle();
        assertNotNull(find(home.root, "选择历史回合"));
        tap(home.root, "选择历史回合");
        history = sheet();
        row = findClass(history.getWindow().getDecorView(), MaterialCheckBox.class);
        assertFalse(row.isChecked());
        row.performClick();
        tap(history.getWindow().getDecorView(), "确定");
        settle();
        home.chat.refreshSelection();
        assertTrue(find(home.root, "手动选择").isEnabled());
        assertNotNull(find(home.root, "已选 1 个历史回合"));
        home.share("交接");
        tap(home.root, "发送");
        assertNotNull(Gateway.sent);
        assertEquals("manual", Gateway.sent.optString("mode"));
        assertEquals("turn-1", Gateway.sent.optJSONArray("selected").optString(0));
    }

    @Test public void shortAgyChatDefaultsToOutgoingWithoutSelectingHistory() {
        Gateway.source = "agy";
        home.chat.refreshSelection();
        assertTrue(find(home.root, "自动交接").isEnabled());
        assertNull(find(home.root, "选择历史回合"));
        home.share("继续这个组件");
        tap(home.root, "发送");
        assertNotNull(Gateway.sent);
        assertEquals("outgoing", Gateway.sent.optString("mode"));
        assertEquals("codex", Gateway.sent.optString("agent"));
        assertEquals(0, Gateway.sent.optJSONArray("selected").length());
        assertFalse(Gateway.sent.has("provider"));
    }

    @Test public void outgoingUsesTheExistingProtocolAndEmptyProvidersHaveNoPicker() {
        Gateway.providers = new JSONArray();
        home.chat.refreshSelection();
        choose("自动交接", "第三方接口");
        assertFalse(find(home.root, "暂无可用交接接口").isEnabled());
        choose("第三方接口", "自动交接");
        home.share("转给 Codex");
        tap(home.root, "发送");
        assertEquals("outgoing", Gateway.sent.optString("mode"));
        assertFalse(Gateway.sent.has("provider"));
    }

    private void choose(String current, String choice) {
        tap(home.root, current);
        BottomSheetDialog sheet = sheet();
        tap(sheet.getWindow().getDecorView(), choice);
        settle();
    }

    private static BottomSheetDialog sheet() {
        shadowOf(Looper.getMainLooper()).idle();
        BottomSheetDialog result = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        result.setDismissWithAnimation(false);
        return result;
    }

    private static void settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350)); }

    private static void tap(View root, String label) {
        View target = find(root, label);
        assertNotNull(label, target);
        while (!target.isClickable()) target = (View) target.getParent();
        assertTrue(label, target.performClick());
    }

    private static View find(View root, String label) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
        if (label.equals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T findClass(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findClass(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
