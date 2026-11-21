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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
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
        static final AtomicInteger writes = new AtomicInteger();
        static CountDownLatch allowSave = new CountDownLatch(1);

        @Implementation
        protected static HttpURLConnection gateway(String method, String path, byte[] body) throws Exception {
            JSONObject result = new JSONObject();
            if (path.startsWith("/api/multi/preferences")) {
                if ("POST".equals(method)) {
                    writes.incrementAndGet();
                    if (!allowSave.await(8, TimeUnit.SECONDS)) throw new AssertionError("save not released");
                    active = new JSONObject(new String(body, StandardCharsets.UTF_8)).getString("provider");
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
        tap(quick, "ChatGPT");
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
