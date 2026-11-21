package cc.codroid;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.ScrollView;
import android.view.ViewGroup;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import java.util.function.Predicate;

/** The PTY owns the screen text. IME pre-edit text lives in a separate editable. */
final class TerminalInputView extends TextView {
    private final Predicate<String> send;
    private final GestureDetector taps;
    private final SpannableStringBuilder preedit = new SpannableStringBuilder();
    private DirectConnection currentConnection;

    TerminalInputView(Context context, Predicate<String> send) {
        super(context);
        this.send = send;
        setTextIsSelectable(true);
        setFocusableInTouchMode(true);
        Selection.setSelection(preedit, 0);
        setOnClickListener(view -> showKeyboard());
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                // The first touch can be consumed by focus/selection before performClick.
                showKeyboard();
                return true;
            }
        });
    }

    private void showKeyboard() {
        if (!requestFocus()) return;
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        return taps.onTouchEvent(event) || handled;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect rect) {
        super.onFocusChanged(focused, direction, rect);
        if (!focused) abandonInput();
    }

    @Override protected void onDetachedFromWindow() {
        abandonInput();
        super.onDetachedFromWindow();
    }

    private void abandonInput() {
        currentConnection = null;
        if (preedit != null) {
            preedit.clear();
            preedit.clearSpans();
            Selection.setSelection(preedit, 0);
        }
    }

    static TerminalInputView attach(ScrollView viewport, Predicate<String> send) {
        TerminalInputView output = new TerminalInputView(viewport.getContext(), send);
        viewport.setFillViewport(true);
        viewport.addView(output, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return output;
    }

    void display(CharSequence text) {
        ScrollView viewport = (ScrollView) getParent();
        if (viewport != null && !viewport.canScrollVertically(1)) {
            androidx.core.view.OneShotPreDrawListener.add(viewport, () -> {
                if (getParent() == viewport) viewport.scrollTo(0, getBottom());
            });
        }
        setText(text);
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        info.initialSelStart = Selection.getSelectionStart(preedit);
        info.initialSelEnd = Selection.getSelectionEnd(preedit);
        currentConnection = new DirectConnection();
        return currentConnection;
    }

    private final class DirectConnection extends BaseInputConnection {
        DirectConnection() { super(TerminalInputView.this, true); }

        // A screen repaint may restart the IME. The focused view retains its candidate
        // across that restart; an obsolete connection only sees its own empty editable.
        @Override public Editable getEditable() {
            return this == currentConnection ? preedit : super.getEditable();
        }

        @Override public boolean commitText(CharSequence text, int cursor) {
            if (this != currentConnection || text == null) return false;
            if (text.length() > 0 && !send.test("\n".contentEquals(text) ? "\r" : text.toString())) return false;
            Editable pending = getEditable();
            removeComposingSpans(pending);
            pending.clear();
            return true;
        }

        @Override public boolean finishComposingText() {
            return commitText(getEditable().toString(), 1);
        }

        @Override public boolean deleteSurroundingText(int before, int after) {
            return deleteAroundCursor(before, after, false);
        }

        @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            return deleteAroundCursor(before, after, true);
        }

        private boolean deleteAroundCursor(int before, int after, boolean codePoints) {
            if (this != currentConnection || before < 0 || after < 0) return false;
            Editable pending = getEditable();
            int head = Math.max(0, Selection.getSelectionStart(pending));
            int tail = Math.max(0, Selection.getSelectionEnd(pending));
            int start = Math.min(head, tail), end = Math.max(head, tail);
            int left = Math.min(before, codePoints ? Character.codePointCount(pending, 0, start) : start);
            int right = Math.min(after, codePoints ? Character.codePointCount(pending, end, pending.length()) : pending.length() - end);
            int from = codePoints ? Character.offsetByCodePoints(pending, start, -left) : start - left;
            int to = codePoints ? Character.offsetByCodePoints(pending, end, right) : end + right;
            if (!deleteScreenText(before - left, after - right)) return false;
            pending.delete(end, to);
            pending.delete(from, start);
            return true;
        }

        @Override public boolean sendKeyEvent(KeyEvent event) {
            if (this != currentConnection) return false;
            if (getEditable().length() > 0) {
                int key = event.getKeyCode();
                if (key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL) {
                    return event.getAction() != KeyEvent.ACTION_DOWN || deleteAroundCursor(
                            key == KeyEvent.KEYCODE_DEL ? 1 : 0, key == KeyEvent.KEYCODE_FORWARD_DEL ? 1 : 0, true);
                }
                if ((key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        && event.getAction() == KeyEvent.ACTION_DOWN && !finishComposingText()) return false;
            }
            return dispatchKeyEvent(event);
        }

        @Override public boolean performEditorAction(int action) {
            return finishComposingText() && send.test("\r");
        }

        @Override public boolean performContextMenuAction(int id) {
            return this == currentConnection && TerminalInputView.this.onTextContextMenuItem(id);
        }

        @Override public void closeConnection() {
            if (this == currentConnection) currentConnection = null;
            super.closeConnection();
        }
    }

    private boolean deleteScreenText(int before, int after) {
        // IMEs can request unbounded deletion. The PTY has no editable document to measure.
        if (before > 4096 || after > 4096) return false;
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < before; i++) keys.append('\u007f');
        for (int i = 0; i < after; i++) keys.append("\u001b[3~");
        return keys.length() == 0 || send.test(keys.toString());
    }

    @Override public boolean onTextContextMenuItem(int id) {
        if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) return super.onTextContextMenuItem(id);
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return false;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        return text != null && send.test(text.toString());
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (event.isCtrlPressed() && event.isShiftPressed() && code == KeyEvent.KEYCODE_V)
            return onTextContextMenuItem(android.R.id.paste);
        String key = keyData(code, event);
        if (key == null) return super.onKeyDown(code, event);
        send.test(key);
        return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        return keyData(code, event) != null || super.onKeyUp(code, event);
    }

    private String keyData(int code, KeyEvent event) {
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "\r";
            case KeyEvent.KEYCODE_DEL: return "\u007f";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "\u001b[3~";
            case KeyEvent.KEYCODE_DPAD_UP: return "\u001b[A";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "\u001b[B";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "\u001b[C";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "\u001b[D";
            case KeyEvent.KEYCODE_TAB: return event.isShiftPressed() ? "\u001b[Z" : "\t";
            case KeyEvent.KEYCODE_ESCAPE: return "\u001b";
            default:
                if (event.isCtrlPressed() && code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
                    return String.valueOf((char) (code - KeyEvent.KEYCODE_A + 1));
                int cp = event.getUnicodeChar();
                return cp > 0 && Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : null;
        }
    }
}
