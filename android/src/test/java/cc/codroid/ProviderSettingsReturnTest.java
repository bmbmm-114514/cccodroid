package cc.codroid;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = ProviderSettingsReturnTest.Gateway.class)
public class ProviderSettingsReturnTest {
    @Implements(value = Env.class, isInAndroidSdk = false)
    public static class Gateway {
        static volatile String active = "anthropic";
        static volatile JSONObject lastWrite;
        static final AtomicInteger writes = new AtomicInteger();
        static CountDownLatch allowSave = new CountDownLatch(1);

        @Implementation
        protected static HttpURLConnection gateway(String method, String path, byte[] body) throws Exception {
            JSONObject result = new JSONObject();
            if (path.startsWith("/api/multi/preferences")) {
                if ("POST".equals(method)) {
                    writes.incrementAndGet();
                    if (!allowSave.await(8, TimeUnit.SECONDS)) throw new AssertionError("save not released");
                    lastWrite = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    active = lastWrite.getString("provider");
                }
                result.put("provider", active);
                result.put("agent", "chatgpt".equals(active) ? "codex" : "claude");
            } else if (path.equals("/api/login/start")) {
                result.put("url", "https://claude.ai/oauth/authorize");
            } else if (path.startsWith("/api/multi/models")) {
                JSONArray models = new JSONArray();
                String prefix = path.contains("agent=codex") ? "Codex" : "Claude";
                for (int i = 0; i < 25; i++) {
                    models.put(new JSONObject().put("id", "m" + i).put("label", prefix + " " + i));
                }
                result.put("models", models);
            }
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            return new HttpURLConnection(new URL("http://localhost/")) {
                @Override public int getResponseCode() { return HTTP_OK; }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
                @Override public void disconnect() { }
            };
        }
    }

    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class CatalogRequests {
        static final Map<String, Consumer<JSONObject>> success = new HashMap<>();
        static final Map<String, Consumer<String>> failure = new HashMap<>();
        static int count;

        @Implementation
        protected static void call(Activity activity, String verb, String path, JSONObject body,
                                   Consumer<JSONObject> result, Consumer<String> error) {
            assertEquals("GET", verb);
            assertNull("Duplicate in-flight read " + path, success.put(path, result));
            failure.put(path, error);
            count++;
        }

        static void complete(String path, JSONObject result) {
            failure.remove(path);
            assertNotNull("Expected read " + path, success.get(path));
            success.remove(path).accept(result);
        }

        static void failRead(String path, String message) {
            success.remove(path);
            assertNotNull("Expected read " + path, failure.get(path));
            failure.remove(path).accept(message);
        }
    }

    private static final String CLAUDE = "/api/multi/models?agent=claude&model=&provider=anthropic";
    private static final String GPT_PREFS = "/api/multi/preferences?provider=chatgpt";
    private static final String GOOGLE_PREFS = "/api/multi/preferences?provider=google";
    private static final String GPT = "/api/multi/models?agent=codex&model=gpt-5&provider=chatgpt";
    private static final String GEMINI = "/api/multi/models?agent=agy&model=&provider=google";

    @Before public void resetGateway() {
        Gateway.active = "anthropic";
        Gateway.writes.set(0);
        Gateway.allowSave = new CountDownLatch(1);
        Gateway.lastWrite = null;
        CatalogRequests.success.clear();
        CatalogRequests.failure.clear();
        CatalogRequests.count = 0;
    }

    private static JSONObject catalog(String id, String label) throws Exception {
        return new JSONObject().put("models", new JSONArray().put(new JSONObject().put("id", id).put("label", label)));
    }

    @Test @Config(shadows = {Gateway.class, CatalogRequests.class})
    public void switchingAccountsKeepsRowsAndCoalescesCatalogsUntilAUserPicksAModel() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AtomicInteger changes = new AtomicInteger();
        ProviderSettingsDialog.quick(activity, changes::incrementAndGet);
        BottomSheetDialog quick = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        await(() -> CatalogRequests.success.containsKey(CLAUDE));
        CatalogRequests.complete(CLAUDE, catalog("sonnet", "Sonnet"));
        View previous = find(quick, "Sonnet");
        assertNotNull(previous);
        tap(quick, "ChatGPT");
        assertSame(previous, find(quick, "Sonnet"));
        assertNull(find(quick, "正在读取模型…"));
        previous.performClick();
        tap(quick, "Google");
        tap(quick, "ChatGPT");
        CatalogRequests.complete(GOOGLE_PREFS, new JSONObject());
        CatalogRequests.complete(GEMINI, catalog("gemini-pro", "Gemini Pro"));
        assertSame(previous, find(quick, "Sonnet"));
        assertNull(find(quick, "Gemini Pro"));
        CatalogRequests.complete(GPT_PREFS, new JSONObject().put("model", "gpt-5"));
        CatalogRequests.complete(GPT, catalog("gpt-5", "GPT 5"));
        assertNotNull(find(quick, "GPT 5"));
        tap(quick, "Google");
        assertNotNull(find(quick, "Gemini Pro"));
        assertEquals(5, CatalogRequests.count);
        previous.performClick();
        assertEquals(0, Gateway.writes.get());
        Gateway.allowSave.countDown();
        tap(quick, "Gemini Pro");
        await(() -> changes.get() == 1);
        assertEquals(1, Gateway.writes.get());
        assertEquals("agy", Gateway.lastWrite.getString("agent"));
        assertEquals("google", Gateway.lastWrite.getString("provider"));
        assertEquals("gemini-pro", Gateway.lastWrite.getString("model"));
    }

    @Test @Config(shadows = {Gateway.class, CatalogRequests.class})
    public void failedPreferenceAndCatalogReadsCanRetryWithoutCommittingAFallback() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProviderSettingsDialog.quick(activity, null);
        BottomSheetDialog quick = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        await(() -> CatalogRequests.success.containsKey(CLAUDE));
        CatalogRequests.complete(CLAUDE, catalog("sonnet", "Sonnet"));
        tap(quick, "ChatGPT");
        CatalogRequests.failRead(GPT_PREFS, "接口暂不可用");
        assertNull(find(quick, "Sonnet"));
        tap(quick, "重试");
        JSONObject saved = new JSONObject().put("model", "gpt-5");
        CatalogRequests.complete(GPT_PREFS, saved);
        CatalogRequests.failRead(GPT, "目录暂不可用");
        assertNotNull(find(quick, "gpt-5（自定义）"));
        tap(quick, "重试");
        CatalogRequests.complete(GPT, catalog("gpt-5", "GPT 5"));
        assertNotNull(find(quick, "GPT 5"));
        assertNull(find(quick, "重试"));
        close(quick);
        assertEquals(0, Gateway.writes.get());
    }

    @Test public void cancellingSettingsOverPreviewRetainsSheetScrollAndDraft() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        EditText draft = new EditText(activity);
        draft.setText("继续这个项目");
        draft.setSelection(2);
        activity.setContentView(draft);
        AtomicInteger changes = new AtomicInteger();
        ProviderSettingsDialog.quick(activity, changes::incrementAndGet);
        BottomSheetDialog quick = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        await(() -> find(quick, "Claude 0") != null);
        tap(quick, "ChatGPT");
        assertNotNull(find(quick, "Claude 0"));
        await(() -> find(quick, "Codex 15") != null);
        NestedScrollView list = scroll(find(quick, "Codex 15"));
        View root = quick.getWindow().getDecorView();
        root.measure(exact(360), exact(700));
        root.layout(0, 0, 360, 700);
        list.scrollTo(0, 155);
        int y = list.getScrollY();
        assertTrue(y > 0);

        tap(quick, "接口与模型");
        PixelProviderPage details = (PixelProviderPage) ShadowDialog.getLatestDialog();
        await(() -> find(details, "登录") != null && find(details, "登录").isEnabled());
        tap(details, "登录");
        await(() -> Shadows.shadowOf(activity).peekNextStartedActivity() != null);
        assertEquals("https://claude.ai/oauth/authorize", Shadows.shadowOf(activity).getNextStartedActivity().getDataString());
        controller.pause().stop().restart().start().resume();
        assertTrue(details.isShowing());
        tap(details, "返回");
        await(() -> !details.isShowing());
        assertTrue(quick.isShowing());
        assertSame(list, scroll(find(quick, "Codex 15")));
        assertEquals(y, list.getScrollY());
        close(quick);
        assertEquals(0, Gateway.writes.get());
        assertEquals(0, changes.get());
        assertSame(draft, ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0));
        assertEquals(2, draft.getSelectionStart());
        assertEquals("继续这个项目", draft.getText().toString());
    }

    @Test public void saveCompletingAfterBackRefreshesPreviewThenNotifiesChatOnce() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AtomicInteger changes = new AtomicInteger();
        ProviderSettingsDialog.quick(activity, changes::incrementAndGet);
        BottomSheetDialog quick = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        tap(quick, "ChatGPT");
        await(() -> find(quick, "Codex 0") != null);
        tap(quick, "接口与模型");
        PixelProviderPage details = (PixelProviderPage) ShadowDialog.getLatestDialog();
        tap(details, "ChatGPT 登录");
        await(() -> Gateway.writes.get() == 1);
        tap(details, "返回");
        await(() -> !details.isShowing());
        try {
            assertTrue(quick.isShowing());
            assertEquals(0, changes.get());
        } finally {
            Gateway.allowSave.countDown();
        }
        await(() -> {
            View selected = find(quick, "引擎默认");
            while (selected != null && !selected.isSelected()) {
                selected = selected.getParent() instanceof View ? (View) selected.getParent() : null;
            }
            return selected != null && find(quick, "Codex 0") != null;
        });
        close(quick);
        await(() -> changes.get() == 1);
        assertEquals(1, Gateway.writes.get());
    }

    @Test @Config(shadows = {Gateway.class, CatalogRequests.class})
    public void fullSettingsRetainsRowsDuringReadsAndRetriesErrorsWithinModelCard() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProviderSettingsDialog.show(activity, null);
        Dialog page = ShadowDialog.getLatestDialog();
        String state = "/api/multi/state";
        String codex = "/api/multi/models?agent=codex&model=&provider=chatgpt";
        JSONObject accounts = new JSONObject().put("claude", new JSONObject())
                .put("codex", new JSONObject()).put("agy", new JSONObject());
        await(() -> CatalogRequests.success.containsKey(CLAUDE));
        CatalogRequests.complete(state, accounts);
        CatalogRequests.complete(CLAUDE, catalog("sonnet", "Sonnet").put("defaultModel", "sonnet"));
        View claudeRow = find(page, "Claude 登录，Claude Code");
        View oldValue = find(page, "默认（sonnet）");
        View custom = find(page, "输入自定义模型");
        assertNotNull(oldValue);
        tap(page, "ChatGPT 登录");
        assertSame(claudeRow, find(page, "Claude 登录，Claude Code"));
        assertSame(oldValue, find(page, "默认（sonnet）"));
        assertFalse(custom.isEnabled());
        CatalogRequests.complete(state, accounts);
        Gateway.allowSave.countDown();
        await(() -> CatalogRequests.success.containsKey(codex));

        tap(page, "Google 登录");
        CatalogRequests.complete(state, accounts);
        await(() -> CatalogRequests.success.containsKey(GEMINI));
        CatalogRequests.complete(codex, catalog("gpt", "GPT").put("defaultModel", "gpt"));
        assertSame(oldValue, find(page, "默认（sonnet）"));
        assertFalse(custom.isEnabled());
        CatalogRequests.failRead(GEMINI, "offline");
        View retry = find(page, "模型列表未能更新 · 重试");
        assertNotNull(retry);
        assertSame(custom.getParent(), retry.getParent());
        assertTrue(custom.isEnabled());
        tap(page, "模型列表未能更新 · 重试");
        CatalogRequests.complete(GEMINI, catalog("gemini", "Gemini").put("defaultModel", "gemini"));
        assertNotNull(find(page, "默认（gemini）"));
        assertNull(find(page, "模型列表未能更新 · 重试"));

        tap(page, "Claude 登录");
        CatalogRequests.complete(state, accounts);
        await(() -> CatalogRequests.success.containsKey(CLAUDE));
        assertSame(oldValue, find(page, "默认（sonnet）"));
        assertTrue(custom.isEnabled());
        CatalogRequests.failRead(CLAUDE, "offline");
        assertSame(oldValue, find(page, "默认（sonnet）"));
        page.dismiss();
    }

    @Test public void mainSettingsEntryClosesDirectly() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProviderSettingsDialog.show(activity, null);
        Dialog details = ShadowDialog.getLatestDialog();
        tap(details, "返回");
        await(() -> !details.isShowing());
        assertSame(details, ShadowDialog.getLatestDialog());
    }

    private static int exact(int size) {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
    }

    private static NestedScrollView scroll(View child) {
        while (!(child instanceof NestedScrollView)) child = (View) child.getParent();
        return (NestedScrollView) child;
    }

    private static void close(BottomSheetDialog quick) throws Exception {
        quick.setDismissWithAnimation(false);
        quick.cancel();
        await(() -> !quick.isShowing());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 320; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for UI or gateway");
    }

    private static void tap(Dialog dialog, String label) throws Exception {
        await(() -> find(dialog, label) != null);
        View view = find(dialog, label);
        while (!view.hasOnClickListeners()) view = (View) view.getParent();
        assertTrue(view.performClick());
    }

    private static View find(Dialog dialog, String label) {
        return find(dialog.getWindow().getDecorView(), label);
    }

    private static View find(View root, String label) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if ((root instanceof TextView && label.contentEquals(((TextView) root).getText()))
                || label.equals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }
}
