package cc.codroid;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static cc.codroid.TerminalKeyInput.Key.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TerminalKeyInputTest {
    private final List<String> bytes = new ArrayList<>();
    private final AtomicBoolean online = new AtomicBoolean(true);
    private final TerminalKeyInput input = new TerminalKeyInput(data -> online.get() && bytes.add(data));

    @Test public void controlKeysAndImeLatinWordApplyToOneCharacter() {
        for (String c : Arrays.asList("c", "D", "z", "/", "cn")) {
            input.toggleCtrl();
            assertTrue(input.text(c));
            assertFalse(input.isCtrl());
        }
        input.toggleAlt();
        input.text("你好");
        input.text("é");
        assertEquals(Arrays.asList("\3", "\4", "\032", "\037", "\3n", "\033你好", "é"), bytes);
    }

    @Test public void functionSequencesCombinePhysicalAndLatchedModifiers() {
        for (TerminalKeyInput.Key key : new TerminalKeyInput.Key[] {ESC, TAB, HOME, END, UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN})
            input.key(key);
        input.toggleCtrl(); input.key(LEFT);
        input.toggleAlt(); input.key(PAGE_UP);
        input.toggleCtrl(); input.toggleAlt(); input.key(HOME);
        input.toggleCtrl();
        int mods = KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON;
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            assertTrue(input.hardware(new KeyEvent(0, 1, action, KeyEvent.KEYCODE_PAGE_DOWN, 0, mods)));
        }
        input.hardware(new KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON));
        assertEquals(Arrays.asList("\033", "\t", "\033[H", "\033[F", "\033[A", "\033[B", "\033[D", "\033[C",
                "\033[5~", "\033[6~", "\033[1;5D", "\033[5;3~", "\033[1;7H", "\033[6;8~", "\3"), bytes);
    }

    @Test public void onlyASuccessfulWriteConsumesModifiersAndSessionsAreIndependent() {
        AtomicInteger changed = new AtomicInteger();
        input.onModifiersChanged(changed::incrementAndGet);
        input.toggleCtrl();
        input.toggleAlt();
        online.set(false);
        assertFalse(input.text("c"));
        assertTrue(input.text(""));
        assertTrue(input.isCtrl()); assertTrue(input.isAlt());
        TerminalKeyInput other = new TerminalKeyInput(bytes::add);
        other.text("c");
        assertTrue(input.isCtrl()); assertTrue(input.isAlt());
        online.set(true);
        assertTrue(input.text("c"));
        assertEquals(Arrays.asList("c", "\033\3"), bytes);
        assertEquals(4, changed.get());
        assertFalse(input.isCtrl()); assertFalse(input.isAlt());
    }

    @Test public void directEditorSharesModifiersWithToolbarAndPastesLiteralBytes() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView viewport = new ScrollView(activity);
        TerminalInputView output = TerminalInputView.attach(viewport, input);
        activity.setContentView(viewport);
        InputConnection ime = output.onCreateInputConnection(new EditorInfo());
        input.toggleCtrl();
        ime.setComposingText("c", 1);
        output.display("PTY echo");
        assertTrue(input.isCtrl()); assertTrue(bytes.isEmpty());
        ime.commitText("c", 1);
        input.toggleAlt();
        assertTrue(ime.deleteSurroundingText(2, 0));
        assertTrue(ime.commitText("中文", 1));
        activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("test", "pwd\n中文"));
        input.toggleCtrl();
        assertTrue(output.onTextContextMenuItem(android.R.id.paste));
        assertFalse(input.isCtrl());
        input.toggleAlt();
        assertTrue(output.onKeyDown(KeyEvent.KEYCODE_MOVE_END, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END)));
        ime.performEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals(Arrays.asList("\3", "\033\177\177", "中文", "pwd\n中文", "\033[1;3F", "\r"), bytes);
        assertEquals("PTY echo", output.getText().toString());
    }
}
