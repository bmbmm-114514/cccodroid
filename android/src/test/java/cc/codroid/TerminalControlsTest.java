package cc.codroid;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h640dp-mdpi")
public class TerminalControlsTest {
    private Activity a;
    private LinearLayout root;
    private HorizontalScrollView scroller;
    private TableLayout table;
    private TerminalKeyInput input;
    private final List<String> writes = new ArrayList<>();

    @Before public void open() {
        a = Robolectric.buildActivity(Activity.class).setup().get();
        a.setTheme(NativeAppearance.theme(a));
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        input = new TerminalKeyInput(writes::add);
        TerminalControls.add(root, input);
        a.setContentView(root);
        scroller = (HorizontalScrollView) root.getChildAt(0);
        table = (TableLayout) scroller.getChildAt(0);
        layout(360);
    }

    private void layout(int width) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, width, root.getMeasuredHeight());
    }

    private MaterialButton button(int row, int col) {
        return (MaterialButton) ((TableRow) table.getChildAt(row)).getChildAt(col);
    }

    @Test public void bothRowsFitARegularPhoneAndWriteTheExactLabelsAndControlSequences() {
        assertEquals(1, root.getChildCount());
        assertEquals(2, table.getChildCount());
        assertFalse(scroller.canScrollHorizontally(1));
        String[][] labels = {{"Esc", "/", "–", "Home", "↑", "End", "PgUp"},
                             {"Tab", "Ctrl", "Alt", "←", "↓", "→", "PgDn"}};
        for (int row = 0; row < labels.length; row++) {
            assertEquals(7, ((TableRow)table.getChildAt(row)).getChildCount());
            for (int col = 0; col < 7; col++) {
                MaterialButton b = button(row, col);
                assertEquals(labels[row][col], b.getText().toString());
                assertTrue(b.getWidth() >= 48 && b.getHeight() >= 48);
                assertEquals(0, b.getLayout().getEllipsisCount(0));
                assertEquals(b.length(), b.getLayout().getLineEnd(0));
                if (row == 1) assertEquals(button(0, col).getLeft(), b.getLeft());
                if (row == 0 || col < 1 || col > 2) assertTrue(b.performClick());
            }
        }
        assertEquals(Arrays.asList("\u001b", "/", "-", "\u001b[H", "\u001b[A", "\u001b[F", "\u001b[5~",
                "\t", "\u001b[D", "\u001b[B", "\u001b[C", "\u001b[6~"), writes);
    }

    @Test public void compactWidthAndLargeLabelsScrollBothRowsTogether() {
        for (int row = 0; row < 2; row++) for (int col = 0; col < 7; col++) button(row, col).setTextSize(26);
        layout(320);
        assertTrue(scroller.canScrollHorizontally(1));
        scroller.scrollTo(table.getWidth(), 0);
        assertFalse(scroller.canScrollHorizontally(1));
        for (int row = 0; row < 2; row++) {
            MaterialButton last = button(row, 6);
            assertTrue(last.getRight() - scroller.getScrollX() <= scroller.getWidth());
            assertEquals(0, last.getLayout().getEllipsisCount(0));
            assertEquals(last.length(), last.getLayout().getLineEnd(0));
        }
    }

    @Test public void oneShotModifiersChangeAppearanceAndAccessibilityWithoutTakingInputFocus() {
        ScrollView viewport = new ScrollView(a);
        TerminalInputView terminal = TerminalInputView.attach(viewport, input);
        root.addView(viewport, 0, new LinearLayout.LayoutParams(-1, 96));
        layout(360);
        assertTrue(terminal.requestFocus());
        InputConnection ime = terminal.onCreateInputConnection(new EditorInfo());
        MaterialButton ctrl = button(1, 1), alt = button(1, 2);
        int inactiveInk = ctrl.getCurrentTextColor();
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent touch = MotionEvent.obtain(time, time + action, action, ctrl.getWidth() / 2f, 18f, 0);
            ctrl.dispatchTouchEvent(touch);
            touch.recycle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertTrue(ctrl.isChecked());
        assertNotEquals(inactiveInk, ctrl.getCurrentTextColor());
        assertEquals("已启用一次，再点取消", ViewCompat.getStateDescription(ctrl));
        alt.performClick();
        assertTrue(alt.isChecked());
        alt.performClick();
        assertFalse(alt.isChecked());
        assertTrue(ctrl.isChecked());
        assertTrue(writes.isEmpty());
        assertTrue(ime.commitText("c", 1));
        assertEquals(Arrays.asList("\u0003"), writes);
        assertFalse(ctrl.isChecked());
        assertEquals("未启用", ViewCompat.getStateDescription(ctrl));
        assertTrue(terminal.hasFocus());
        assertFalse(ctrl.isFocusableInTouchMode());
    }
}
