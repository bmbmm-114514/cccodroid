package cc.codroid;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w320dp-h640dp-mdpi")
public class TerminalControlsTest {
    private static View find(View view, Class<?> type, String label) {
        if (type.isInstance(view) && (label == null || label.contentEquals(
                view instanceof TextView ? ((TextView) view).getText() : view.getContentDescription()))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = find(group.getChildAt(i), type, label);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    @Test public void separatedScrollableKeysSendExactPtyBytesAndRetainRejectedInput() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(NativeAppearance.theme(activity));
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        List<String> writes = new ArrayList<>();
        TerminalControls.add(root, data -> !data.startsWith("offline") && writes.add(data));
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, 320, root.getMeasuredHeight());

        HorizontalScrollView scroll = (HorizontalScrollView) find(root, HorizontalScrollView.class, null);
        assertTrue(scroll.getChildAt(0).getWidth() > scroll.getWidth());
        for (String label : Arrays.asList("Ctrl+C", "Ctrl+D", "Ctrl+Z", "Tab", "Esc", "↑", "↓", "←", "→")) {
            View key = find(root, TextView.class, label);
            assertTrue(key.getWidth() >= 48 && key.getHeight() >= 48);
            assertTrue(key.performClick());
        }
        assertEquals(Arrays.asList("\u0003", "\u0004", "\u001a", "\t", "\u001b",
                "\u001b[A", "\u001b[B", "\u001b[D", "\u001b[C"), writes);
        EditText editor = (EditText) find(root, EditText.class, null);
        editor.setText("pwd");
        View send = find(root, android.widget.ImageButton.class, "发送");
        View insert = find(root, android.widget.ImageButton.class, "仅输入");
        assertTrue(send.getWidth() >= 48 && insert.getWidth() >= 48);
        insert.performClick();
        assertEquals("pwd", writes.get(9));
        assertEquals("", editor.getText().toString());
        send.performClick();
        assertEquals("\n", writes.get(10));
        editor.setText("ls");
        editor.onEditorAction(EditorInfo.IME_ACTION_SEND);
        assertEquals("ls\n", writes.get(11));
        editor.setText("offline");
        send.performClick();
        assertEquals("offline", editor.getText().toString());
    }
}
