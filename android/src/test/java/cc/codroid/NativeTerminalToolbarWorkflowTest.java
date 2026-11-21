package cc.codroid;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w412dp-h860dp-mdpi", shadows = {
        NativeTerminalToolbarWorkflowTest.ListGateway.class, NativeTerminalToolbarWorkflowTest.Pty.class})
public class NativeTerminalToolbarWorkflowTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class ListGateway {
        @Implementation protected static void call(Activity owner, String method, String route, JSONObject body,
                                                  Consumer<JSONObject> success, Consumer<String> error) throws Exception {
            success.accept(route.equals("/api/term/list") ? new JSONObject("{\"available\":true,\"terms\":["
                    + "{\"id\":\"two\",\"title\":\"bash 2\"},{\"id\":\"one\",\"title\":\"bash 1\"}]}")
                    : new JSONObject());
        }
    }

    @Implements(value = Env.class, isInAndroidSdk = false)
    public static class Pty {
        static final BlockingQueue<String> writes = new LinkedBlockingQueue<>();

        @Implementation protected static HttpURLConnection gateway(String path) throws Exception {
            return gateway("GET", path, null);
        }

        @Implementation protected static HttpURLConnection gateway(String method, String path, byte[] signed) throws Exception {
            return new HttpURLConnection(new URL("http://localhost/")) {
                final ByteArrayOutputStream input = new ByteArrayOutputStream();
                @Override public int getResponseCode() {
                    if (path.endsWith("/in")) writes.add(path + "=" + new String(input.toByteArray(), StandardCharsets.UTF_8));
                    return 200;
                }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
                @Override public OutputStream getOutputStream() { return input; }
                @Override public void connect() {}
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
            };
        }
    }

    static NativeHome openTerminal() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).get();
        a.setTheme(NativeAppearance.theme(a));
        NativeHome home = new NativeHome(a, () -> {});
        home.nav("terminal");
        return home;
    }

    static void layout(NativeHome home, int width, int height, int keyboard) {
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsets.Type.ime(), keyboard > 0).build();
        home.root.dispatchApplyWindowInsets(insets);
        home.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        home.root.layout(0, 0, width, height);
    }

    static <T extends View> T find(View root, Class<T> type, String text) {
        if (type.isInstance(root) && (text == null || (root instanceof TextView
                && text.equalsIgnoreCase(((TextView) root).getText().toString())))) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T child = find(group.getChildAt(i), type, text);
                if (child != null) return child;
            }
        }
        return null;
    }

    private Rect bounds(NativeHome home, View child) {
        Rect box = new Rect(0, 0, child.getWidth(), child.getHeight());
        home.root.offsetDescendantRectToMyCoords(child, box);
        return box;
    }

    private boolean visibleInside(View container, View child) {
        for (View node = child;; node = (View) node.getParent()) {
            if (node.getVisibility() != View.VISIBLE) return false;
            if (node == container) return true;
        }
    }

    private void assertKeyboardLayout(NativeHome home, int keyboardTop) {
        TerminalInputView output = find(home.body, TerminalInputView.class, null);
        View viewport = (View) output.getParent();
        Rect screen = bounds(home, viewport);
        Rect escape = bounds(home, find(home.body, TextView.class, "Esc"));
        Rect lastKey = bounds(home, find(home.body, TextView.class, "PgDn"));
        assertTrue("the prompt must fit above the shortcut keys",
                screen.height() >= output.getLineHeight() + output.getPaddingTop() + output.getPaddingBottom());
        assertTrue(screen.bottom <= escape.top);
        assertTrue(escape.height() > 0 && escape.bottom <= lastKey.top);
        assertTrue(lastKey.height() > 0 && lastKey.bottom <= keyboardTop);
    }

    private InputConnection tapTerminal(NativeHome home) {
        TerminalInputView output = find(home.body, TerminalInputView.class, null);
        assertNotNull(output);
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            long t = SystemClock.uptimeMillis();
            MotionEvent tap = MotionEvent.obtain(t, t, action, 20, output.getHeight() - 8, 0);
            output.dispatchTouchEvent(tap);
            tap.recycle();
        }
        assertTrue(output.hasFocus());
        return output.onCreateInputConnection(new EditorInfo());
    }

    @Test public void softKeyboardAndToolbarWriteToTheSelectedPtyWithItsOwnModifiers() throws Exception {
        Pty.writes.clear();
        NativeHome home = openTerminal();
        try {
            layout(home, 412, 860, 300);
            assertNull(find(home.body, EditText.class, null));
            ArrayList<View> sendButtons = new ArrayList<>();
            home.body.findViewsWithText(sendButtons, "发送", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            assertTrue(sendButtons.isEmpty());
            assertKeyboardLayout(home, 860 - 300);
            InputConnection first = tapTerminal(home);
            find(home.body, TextView.class, "Ctrl").performClick();
            assertTrue(first.commitText("c", 1));
            assertEquals("/api/term/one/in=\u0003", Pty.writes.poll(8, TimeUnit.SECONDS));

            find(home.body, TextView.class, "Alt").performClick();
            find(home.body, TextView.class, "bash 2").performClick();
            layout(home, 412, 860, 300);
            InputConnection second = tapTerminal(home);
            assertTrue(second.commitText("c", 1));
            assertEquals("/api/term/two/in=c", Pty.writes.poll(8, TimeUnit.SECONDS));
        } finally {
            home.dispose();
        }
    }

    @Test @Config(qualifiers = "w760dp-h412dp-land-mdpi")
    public void landscapeKeepsPromptAndTwoKeyRowsAboveTheImeWithoutReplacingTheSession() throws Exception {
        Pty.writes.clear();
        NativeHome home = openTerminal();
        try {
            layout(home, 760, 412, 0);
            TextView git = find(home.body, TextView.class, "Git");
            TextView session = find(home.body, TextView.class, "bash 1");
            TerminalInputView output = find(home.body, TerminalInputView.class, null);
            assertTrue(visibleInside(home.body, git));
            assertTrue(visibleInside(home.body, session));
            InputConnection ime = tapTerminal(home);
            ime.setComposingText("zhong", 1);
            layout(home, 760, 412, 205);
            assertKeyboardLayout(home, 412 - 205);
            assertSame(output, find(home.body, TerminalInputView.class, null));
            assertTrue(output.hasFocus());
            layout(home, 760, 412, 0);
            assertTrue(visibleInside(home.body, git));
            assertTrue(visibleInside(home.body, session));
            assertTrue(output.hasFocus());
            assertEquals("zhong", ime.getTextBeforeCursor(8, 0).toString());
            assertTrue(ime.commitText("中", 1));
            assertEquals("/api/term/one/in=中", Pty.writes.poll(8, TimeUnit.SECONDS));
        } finally {
            home.dispose();
        }
    }

}
