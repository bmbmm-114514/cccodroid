package cc.codroid;

import android.view.KeyEvent;
import java.util.function.Predicate;

/** Encodes keys for one attached PTY. A rejected write keeps its one-shot modifiers armed. */
final class TerminalKeyInput {
    enum Key {
        ESC, TAB, ENTER, BACKSPACE,
        HOME("1", 'H'), END("1", 'F'), UP("1", 'A'), DOWN("1", 'B'),
        RIGHT("1", 'C'), LEFT("1", 'D'), PAGE_UP("5", '~'), PAGE_DOWN("6", '~'), DELETE("3", '~');

        final String parameter;
        final char suffix;

        Key() { this("", '\0'); }
        Key(String parameter, char suffix) { this.parameter = parameter; this.suffix = suffix; }
    }

    private final Predicate<String> writer;
    private boolean ctrl, alt;
    private Runnable modifiersChanged;

    TerminalKeyInput(Predicate<String> writer) { this.writer = writer; }

    boolean isCtrl() { return ctrl; }
    boolean isAlt() { return alt; }
    void toggleCtrl() { ctrl = !ctrl; notifyModifiers(); }
    void toggleAlt() { alt = !alt; notifyModifiers(); }

    void onModifiersChanged(Runnable listener) {
        modifiersChanged = listener;
        notifyModifiers();
    }

    private void notifyModifiers() {
        if (modifiersChanged != null) modifiersChanged.run();
    }

    private boolean write(String bytes) {
        if (bytes.isEmpty()) return true;
        if (!writer.test(bytes)) return false;
        if (ctrl || alt) {
            ctrl = alt = false;
            notifyModifiers();
        }
        return true;
    }

    boolean key(Key key) { return write(sequence(key, ctrl, alt, false)); }
    boolean text(String text) { return write(encodeText(text, ctrl, alt)); }
    boolean paste(String literal) { return write(literal); }

    boolean delete(int before, int after) {
        if (before == 0 && after == 0) return true;
        StringBuilder bytes = new StringBuilder();
        for (int i = 0; i < before; i++)
            bytes.append(i == 0 ? sequence(Key.BACKSPACE, ctrl, alt, false) : "\u007f");
        for (int i = 0; i < after; i++)
            bytes.append(before == 0 && i == 0 ? sequence(Key.DELETE, ctrl, alt, false) : "\u001b[3~");
        return write(bytes.toString());
    }

    private static String sequence(Key key, boolean ctrl, boolean alt, boolean shift) {
        String prefix = alt ? "\u001b" : "";
        switch (key) {
            case ESC: return prefix + "\u001b";
            case TAB: return prefix + (shift ? "\u001b[Z" : "\t");
            case ENTER: return prefix + "\r";
            case BACKSPACE: return prefix + (ctrl ? "\b" : "\u007f");
            default:
                int modifier = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
                String parameter = key.parameter;
                if (modifier != 1) parameter += ";" + modifier;
                else if (key.suffix != '~') parameter = "";
                return "\u001b[" + parameter + key.suffix;
        }
    }

    private static String encodeText(String text, boolean ctrl, boolean alt) {
        if (text.isEmpty() || (!ctrl && !alt)) return text;
        int first = text.codePointAt(0);
        StringBuilder bytes = new StringBuilder();
        if (alt) bytes.append('\u001b');
        bytes.appendCodePoint(ctrl ? controlCode(first) : first);
        // An IME can commit an entire word. The latch applies to its first keystroke.
        return bytes.append(text, Character.charCount(first), text.length()).toString();
    }

    private static int controlCode(int c) {
        if (c >= 'a' && c <= 'z' || c >= '@' && c <= '_') return c & 31;
        switch (c) {
            case ' ': case '2': return 0;
            case '3': return 27;
            case '4': return 28;
            case '5': return 29;
            case '6': return 30;
            case '/': case '-': case '7': return 31;
            case '?': case '8': return 127;
            default: return c;
        }
    }

    boolean hardware(KeyEvent event) {
        Key special = namedKey(event.getKeyCode());
        boolean withCtrl = ctrl || event.isCtrlPressed(), withAlt = alt || event.isAltPressed();
        String bytes;
        if (special != null) {
            bytes = sequence(special, withCtrl, withAlt, event.isShiftPressed());
        } else {
            // Alt forms the terminal Meta prefix and Ctrl transforms the unmodified character.
            int meta = event.getMetaState();
            int cp = event.getUnicodeChar(meta & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
            if ((meta & KeyEvent.META_ALT_RIGHT_ON) != 0) {
                // On international hardware layouts AltGr can produce a printable symbol of its own.
                int altGr = event.getUnicodeChar(meta & ~KeyEvent.META_CTRL_MASK);
                if (altGr > 0 && altGr != cp) {
                    cp = altGr;
                    withAlt = alt;
                    withCtrl = ctrl;
                }
            }
            int code = event.getKeyCode();
            if (cp == 0 && withCtrl && code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
                cp = 'a' + code - KeyEvent.KEYCODE_A;
            if (cp <= 0 || !Character.isValidCodePoint(cp)) return false;
            bytes = encodeText(new String(Character.toChars(cp)), withCtrl, withAlt);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) write(bytes);
        return true;
    }

    private static Key namedKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_ESCAPE: return Key.ESC;
            case KeyEvent.KEYCODE_TAB: return Key.TAB;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return Key.ENTER;
            case KeyEvent.KEYCODE_DEL: return Key.BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return Key.DELETE;
            case KeyEvent.KEYCODE_MOVE_HOME: return Key.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return Key.END;
            case KeyEvent.KEYCODE_PAGE_UP: return Key.PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return Key.PAGE_DOWN;
            case KeyEvent.KEYCODE_DPAD_UP: return Key.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Key.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Key.RIGHT;
            default: return null;
        }
    }
}
