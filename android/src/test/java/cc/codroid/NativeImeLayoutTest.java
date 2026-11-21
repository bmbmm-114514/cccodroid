package cc.codroid;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import java.time.Duration;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w412dp-h860dp-mdpi", shadows = NativeNavigationMotionTest.Gateway.class)
public class NativeImeLayoutTest {
    private NativeHome home() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).get();
        a.setTheme(NativeAppearance.theme(a));
        return new NativeHome(a, () -> {});
    }

    private static void layout(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) root;
            for (int i = 0; i < parent.getChildCount(); i++) {
                T found = find(parent.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static WindowInsets frame(int keyboard) {
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsets.Type.ime(), keyboard > 0).build();
    }

    private static int editorBottom(View editor, View root) {
        int bottom = editor.getHeight();
        for (View v = editor; v != root; v = (View) v.getParent()) bottom += v.getTop();
        return bottom;
    }

    @Test public void composerTracksTheKeyboardWithoutRebuildingThePageOrLosingBackHistory() {
        NativeHome h = home();
        try {
            h.nav("projects");
            h.nav("chat");
            h.root.dispatchApplyWindowInsets(frame(0));
            layout(h.root, 412, 860);
            EditText editor = find(h.body, EditText.class);
            View nav = find(h.root, BottomNavigationView.class);
            editor.setText("保留输入中的草稿");
            editor.setSelection(3);
            editor.requestFocus();
            View page = (View) h.body.getParent();
            int generation = h.generation;
            int closed = editorBottom(editor, h.root);
            int barHeight = nav.getHeight();
            assertTrue(barHeight > 0);

            WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), null, 240);
            h.root.dispatchWindowInsetsAnimationPrepare(animation);
            h.root.dispatchApplyWindowInsets(frame(320));
            assertEquals(24, h.root.getPaddingBottom());
            for (int keyboard : new int[]{40, 100, 200, 320}) {
                h.root.dispatchWindowInsetsAnimationProgress(frame(keyboard), Collections.singletonList(animation));
                layout(h.root, 412, 860);
                assertEquals(closed - Math.max(0, keyboard - 24 - barHeight), editorBottom(editor, h.root));
                assertEquals(24, h.root.getPaddingTop());
                assertEquals(1f, page.getAlpha(), .01f);
            }
            h.root.dispatchWindowInsetsAnimationEnd(animation);
            assertTrue(h.back());
            assertEquals("chat", h.screen);
            assertSame(editor, find(h.body, EditText.class));
            assertTrue(editor.hasFocus());
            assertEquals(3, editor.getSelectionStart());
            assertEquals("保留输入中的草稿", editor.getText().toString());
            assertEquals(generation, h.generation);
            h.root.dispatchApplyWindowInsets(frame(0));
            layout(h.root, 412, 860);
            assertEquals(closed, editorBottom(editor, h.root));
            assertSame(nav, find(h.root, BottomNavigationView.class));
            assertTrue(h.back());
            assertEquals("projects", h.screen);
        } finally { h.dispose(); }
    }

    @Test public void abortedPrepareCannotBlockTheNextInsetsDispatch() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(host);
        host.setContentView(root);
        NativeImeLayout keyboard = new NativeImeLayout(root, () -> {});
        keyboard.attach(host.getWindow());
        try {
            root.dispatchApplyWindowInsets(frame(0));
            WindowInsetsAnimation abandoned = new WindowInsetsAnimation(WindowInsets.Type.ime(), null, 240);
            root.dispatchWindowInsetsAnimationPrepare(abandoned);
            root.dispatchApplyWindowInsets(frame(250));
            root.getViewTreeObserver().dispatchOnPreDraw();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            root.dispatchApplyWindowInsets(frame(310));
            assertEquals(310, root.getPaddingBottom());
        } finally { keyboard.detach(); }
    }

    @Test public void resizingTimelineFollowsOnlyAReaderAlreadyAtTheEnd() {
        NativeHome h = home();
        try {
            ScrollView timeline = find(h.body, ScrollView.class);
            LinearLayout messages = (LinearLayout) timeline.getChildAt(0);
            View content = new View(h.a);
            messages.addView(content, new LinearLayout.LayoutParams(50, 2200));
            layout(h.root, 412, 860);
            timeline.scrollTo(0, 10000);
            h.root.dispatchApplyWindowInsets(frame(310));
            layout(h.root, 412, 860);
            assertFalse(timeline.canScrollVertically(1));
            timeline.scrollTo(0, 230);
            h.root.dispatchApplyWindowInsets(frame(0));
            layout(h.root, 412, 860);
            assertEquals(230, timeline.getScrollY());
        } finally { h.dispose(); }
    }

    @Test @Config(sdk = 28)
    public void legacyResizeInsetsAreAppliedOnlyOnceAboveTheKeyboard() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(host);
        host.setContentView(root);
        NativeImeLayout keyboard = new NativeImeLayout(root, () -> {});
        keyboard.attach(host.getWindow());
        try {
            androidx.core.graphics.Insets safe = androidx.core.graphics.Insets.of(0, 24, 0, 24);
            androidx.core.view.WindowInsetsCompat resized = new androidx.core.view.WindowInsetsCompat.Builder()
                    .setStableInsets(safe)
                    .setSystemWindowInsets(androidx.core.graphics.Insets.of(0, 24, 0, 280)).build();
            root.dispatchApplyWindowInsets(resized.toWindowInsets());
            root.getViewTreeObserver().dispatchOnPreDraw();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(280, root.getPaddingBottom());
            assertTrue(keyboard.isVisible());
        } finally { keyboard.detach(); }
    }

    @Test @Config(sdk = 28, qualifiers = "w740dp-h420dp-land-mdpi")
    public void legacyLandscapeResizeKeepsRailAndFocusedDraft() {
        NativeHome h = home();
        try {
            layout(h.root, 740, 420);
            View rail = find(h.root, NavigationRailView.class);
            assertNotNull(rail);
            EditText editor = find(h.body, EditText.class);
            editor.setText("横屏");
            editor.requestFocus();
            layout(h.root, 740, 230);
            assertSame(rail, find(h.root, NavigationRailView.class));
            assertSame(editor, find(h.body, EditText.class));
            assertTrue(editor.hasFocus());
            assertEquals("横屏", editor.getText().toString());
            assertNotEquals(0, editor.getImeOptions() & android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        } finally { h.dispose(); }
    }
}
