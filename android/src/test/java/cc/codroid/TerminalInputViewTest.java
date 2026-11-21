package cc.codroid;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h640dp-mdpi")
public class TerminalInputViewTest {
    private final Activity a = Robolectric.buildActivity(Activity.class).setup().get();
    private final LinearLayout root = new LinearLayout(a);
    private final ScrollView viewport = new ScrollView(a);
    private final List<String> writes = new ArrayList<>();
    private final List<Consumer<Boolean>> replies = new ArrayList<>();
    private final TerminalInputView output = TerminalInputView.attach(viewport, writes::add);
    private final EditText draft;
    private final View send;

    public TerminalInputViewTest() {
        a.setTheme(NativeAppearance.theme(a));
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        TerminalControls.add(root, (data, reply) -> { writes.add(data); replies.add(reply); });
        a.setContentView(root);
        LinearLayout controls = (LinearLayout) root.getChildAt(1);
        LinearLayout composer = (LinearLayout) controls.getChildAt(1);
        draft = (EditText) composer.getChildAt(0);
        send = composer.getChildAt(2);
        layout();
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 640);
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test public void firstTapInBlankViewportShowsKeyboardAndDoesNotEchoLocally() {
        draft.requestFocus();
        output.display("$ ");
        layout();
        assertEquals(viewport.getHeight(), output.getHeight());
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] { MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP }) {
            MotionEvent tap = MotionEvent.obtain(now, now + action, action, 180, viewport.getHeight() - 8, 0);
            viewport.dispatchTouchEvent(tap);
            tap.recycle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180));
        assertTrue(output.hasFocus());
        assertTrue(Shadows.shadowOf(a.getSystemService(InputMethodManager.class)).isSoftInputVisible());
        InputConnection ime = output.onCreateInputConnection(new EditorInfo());
        assertTrue(ime.commitText("pwd", 1));
        assertEquals(Arrays.asList("pwd"), writes);
        assertEquals("$ ", output.getText().toString());
    }

    @Test public void compositionBackspaceAndEnterUseOrderedPtyInput() {
        InputConnection ime = output.onCreateInputConnection(new EditorInfo());
        ime.setComposingText("ni", 1);
        ime.setComposingText("nih", 1);
        ime.deleteSurroundingText(1, 0);
        assertEquals("ni", ime.getTextBeforeCursor(6, 0).toString());
        assertTrue(writes.isEmpty());
        ime.commitText("你", 1);
        ime.setComposingText("a😀", 1);
        ime.deleteSurroundingTextInCodePoints(1, 0);
        ime.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ime.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        ime.deleteSurroundingText(1, 0);
        ime.performEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals(Arrays.asList("你", "a", "\r", "\u007f", "\r"), writes);
        assertEquals("", output.getText().toString());
    }

    @Test public void asynchronousOutputAndInputRestartPreserveOnlyTheLiveCandidate() {
        assertTrue(output.requestFocus());
        InputConnection original = output.onCreateInputConnection(new EditorInfo());
        original.setComposingText("zhong", 1);
        output.display("unrelated shell output");
        layout();
        assertEquals("zhong", original.getTextBeforeCursor(100, 0).toString());
        InputConnection replacement = output.onCreateInputConnection(new EditorInfo());
        original.closeConnection();
        assertFalse(original.performEditorAction(EditorInfo.IME_ACTION_DONE));
        assertEquals("zhong", replacement.getTextBeforeCursor(100, 0).toString());
        replacement.commitText("中", 1);
        replacement.setComposingText("pending", 1);
        assertTrue(draft.requestFocus());
        assertFalse(replacement.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
        assertFalse(replacement.finishComposingText());
        assertEquals(Arrays.asList("中"), writes);
    }

    @Test public void bottomEditorRetainsFocusAndNewerCommandAcrossEchoAndLateReplies() {
        assertTrue(draft.requestFocus());
        draft.setText("one");
        send.performClick();
        draft.setText("two");
        output.display("one\r\n");
        layout();
        assertTrue(draft.hasFocus());
        draft.onEditorAction(EditorInfo.IME_ACTION_SEND);
        assertEquals(Arrays.asList("one\r", "two\r"), writes);
        replies.get(0).accept(true);
        assertEquals("two", draft.getText().toString());
        replies.get(1).accept(false);
        assertEquals("two", draft.getText().toString());
        send.performClick();
        replies.get(2).accept(true);
        assertEquals("", draft.getText().toString());
        assertTrue(draft.hasFocus());
    }
}
