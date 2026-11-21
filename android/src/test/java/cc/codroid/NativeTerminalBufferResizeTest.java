package cc.codroid;

import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NativeTerminalBufferResizeTest {
    @Test public void keyboardShrinkAndGrowPreservePromptHistoryAndStyles() {
        NativeTerminalBuffer term = new NativeTerminalBuffer(12, 4);
        term.feed("\033[31mold\033[0m\r\nsecond\r\nthird\r\n$ xy");
        term.resize(12, 2);
        term.feed("z");
        assertEquals("old\nsecond\nthird\n$ xyz", term.text());
        term.resize(12, 4);
        term.feed("\033[1;1HO");
        assertEquals("Old\nsecond\nthird\n$ xyz", term.text());
        Spanned text = (Spanned) term.styledText();
        assertEquals(1, text.getSpans(1, 3, ForegroundColorSpan.class).length);
    }

    @Test public void savedCursorsAndPendingWrapBelongToTheirOwnScreens() {
        NativeTerminalBuffer term = new NativeTerminalBuffer(5, 4);
        term.feed("head\r\nmid\r\n\033[skeep\r\n12345\033[?1049h");
        term.feed("ALT\r\nrow2\r\nrow3\r\nABCDE\0337\r\0338");
        term.resize(5, 2);
        term.feed("!");
        assertEquals("ABCDE\n!", term.text());
        term.feed("\033[?1049lX");
        assertEquals("head\nmid\nkeep\n12345\nX", term.text());
    }

    @Test public void altCursorSaveDoesNotReplaceTheShellsSavedPosition() {
        NativeTerminalBuffer term = new NativeTerminalBuffer(10, 4);
        term.feed("one\r\ntwo\r\n\0337three\r\n$ \033[?1049h");
        term.feed("\033[1;3H\0337\033[4;1Halt");
        term.resize(10, 2);
        term.feed("\033[?1049l\0338T");
        assertEquals("one\ntwo\nThree\n$", term.text());
    }

    @Test public void savedRowMovesWithScreenAndFullLineStillWrapsAfterRestore() {
        NativeTerminalBuffer term = new NativeTerminalBuffer(4, 5);
        term.feed("old\r\nx\r\ny\r\n1234\033[s\r\n$ ");
        term.resize(4, 2);
        term.feed("\033[uZ");
        assertEquals("old\nx\ny\n1234\nZ", term.text());
    }
}
