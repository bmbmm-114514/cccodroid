package cc.codroid;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.WeakHashMap;
import android.text.SpannableStringBuilder;
import cc.codroid.NativeTerminalStyle.Style;

public class NativeTerminalBuffer {
    private static final int MAX_SCROLLBACK = 2000;
    private static final int S_NORMAL = 0, S_ESC = 1, S_CSI = 2, S_OSC = 3, S_OSC_ESC = 4, S_CHARSET = 5;

    private int cols, rows, curCol, curRow;
    private final Cursor savedMain = new Cursor();
    private final Cursor savedAlt = new Cursor();
    private final Cursor parkedMain = new Cursor();

    private boolean isAlt;
    private char[][] mainScreen, altScreen;
    private final ArrayDeque<char[]> scrollback = new ArrayDeque<>();
    // Row objects survive scrollback and are discarded on eviction or resize.
    private final WeakHashMap<char[], Style[]> styles = new WeakHashMap<>();
    private Style activeStyle;
    private int state = S_NORMAL;
    private final StringBuilder escBuf = new StringBuilder(64);

    public NativeTerminalBuffer(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.mainScreen = createGrid(this.rows, this.cols);
        this.altScreen = createGrid(this.rows, this.cols);
    }

    public synchronized void reset() {
        curCol = curRow = 0;
        savedMain.set(0, 0);
        savedAlt.set(0, 0);
        parkedMain.set(0, 0);
        isAlt = false;
        state = S_NORMAL;
        escBuf.setLength(0);
        scrollback.clear();
        activeStyle = null;
        styles.clear();
        clearGrid(mainScreen);
        clearGrid(altScreen);
    }

    public synchronized void resize(int cols, int rows) {
        if (cols <= 0 || rows <= 0 || (this.cols == cols && this.rows == rows)) return;
        int mainCursorRow = isAlt ? parkedMain.row : curRow;
        int mainShift = topShift(mainScreen, mainCursorRow, rows);
        if (rows > this.rows) mainShift = -Math.min(rows - this.rows, scrollback.size());
        int altShift = isAlt ? topShift(altScreen, curRow, rows) : 0;
        mainScreen = resizeGrid(mainScreen, cols, rows, mainShift, true);
        altScreen = resizeGrid(altScreen, cols, rows, altShift, false);

        savedMain.resize(mainShift, rows, this.cols, cols);
        savedAlt.resize(altShift, rows, this.cols, cols);
        parkedMain.resize(mainShift, rows, this.cols, cols);
        curRow -= isAlt ? altShift : mainShift;
        curCol = resizedColumn(curCol, this.cols, cols);
        this.cols = cols;
        this.rows = rows;
    }

    // Remove unused space below the cursor first. Keep it on screen even when an
    // application has drawn more rows below it than will fit in the new viewport.
    private int topShift(char[][] screen, int cursorRow, int newRows) {
        if (newRows >= screen.length) return 0;
        int last = screen.length - 1;
        while (last > cursorRow && trimmedLength(screen[last]) == 0) last--;
        return Math.min(cursorRow, Math.max(0, last + 1 - newRows));
    }

    // A column one past the margin means the next printable character must wrap.
    private static int resizedColumn(int col, int oldCols, int newCols) {
        return Math.min(col, col == oldCols ? newCols : newCols - 1);
    }

    private static final class Cursor {
        int row, col;

        void set(int row, int col) {
            this.row = row;
            this.col = col;
        }

        void resize(int shift, int rows, int oldCols, int newCols) {
            row = Math.max(0, Math.min(row - shift, rows - 1));
            col = resizedColumn(col, oldCols, newCols);
        }
    }

    private Cursor savedCursor() { return isAlt ? savedAlt : savedMain; }

    private void restoreCursor(Cursor cursor) {
        curRow = cursor.row;
        curCol = cursor.col;
    }

    public synchronized void feed(String rawChunk) {
        if (rawChunk == null || rawChunk.isEmpty()) return;
        int len = rawChunk.length();
        for (int i = 0; i < len; i++) {
            char ch = rawChunk.charAt(i);
            switch (state) {
                case S_NORMAL:
                    if (ch == 0x1B) state = S_ESC;
                    else if (ch == '\r') curCol = 0;
                    else if (ch == '\n') newLine();
                    else if (ch == '\b') { if (curCol > 0) curCol--; }
                    else if (ch == '\t') curCol = Math.min(cols - 1, (curCol + 8) & ~7);
                    else if (ch >= 32 && ch != 127) putChar(ch);
                    break;
                case S_ESC:
                    if (ch == '[') { state = S_CSI; escBuf.setLength(0); }
                    else if (ch == ']') state = S_OSC;
                    else if (ch == '(' || ch == ')') state = S_CHARSET;
                    else if (ch == '7') { savedCursor().set(curRow, curCol); state = S_NORMAL; }
                    else if (ch == '8') {
                        restoreCursor(savedCursor());
                        state = S_NORMAL;
                    } else if (ch == 'c') reset();
                    else state = S_NORMAL;
                    break;
                case S_CHARSET:
                    state = S_NORMAL;
                    break;
                case S_CSI:
                    if ((ch >= 0x30 && ch <= 0x3F) || (ch >= 0x20 && ch <= 0x2F)) {
                        if (escBuf.length() < 64) escBuf.append(ch);
                    } else if (ch >= 0x40 && ch <= 0x7E) {
                        handleCsi(ch);
                        state = S_NORMAL;
                    } else if (ch == 0x1B) {
                        state = S_ESC;
                    } else {
                        state = S_NORMAL;
                        if (ch == '\r') curCol = 0; else if (ch == '\n') newLine();
                    }
                    break;
                case S_OSC:
                    if (ch == 0x07) state = S_NORMAL;
                    else if (ch == 0x1B) state = S_OSC_ESC;
                    break;
                case S_OSC_ESC:
                    if (ch == '\\') state = S_NORMAL;
                    else if (ch == '[') { state = S_CSI; escBuf.setLength(0); }
                    else state = S_NORMAL;
                    break;
            }
        }
    }

    private void putChar(char ch) {
        boolean wide = cols > 1 && (ch >= 0x1100 && ch <= 0x115f || ch >= 0x2e80 && ch <= 0xa4cf
                || ch >= 0xac00 && ch <= 0xd7a3 || ch >= 0xf900 && ch <= 0xfaff || ch >= 0xff01 && ch <= 0xff60);
        if (curCol >= cols || (wide && curCol + 1 == cols)) { curCol = 0; newLine(); }
        char[] row = (isAlt ? altScreen : mainScreen)[curRow];
        if (row[curCol] == 0 && curCol > 0) row[curCol - 1] = ' ';
        if (curCol + 1 < cols && row[curCol + 1] == 0) row[curCol + 1] = ' ';
        Style[] style = styles.get(row);
        if (style == null && activeStyle != null) {
            style = new Style[row.length]; styles.put(row, style);
        }
        if (style != null) {
            style[curCol] = activeStyle;
            if (wide) style[curCol + 1] = activeStyle;
        }
        row[curCol++] = ch;
        if (wide) row[curCol++] = 0;
    }

    private void newLine() {
        if (curRow < rows - 1) curRow++;
        else scrollUp();
    }

    private void scrollUp() {
        char[][] scr = isAlt ? altScreen : mainScreen;
        char[] first = scr[0];
        System.arraycopy(scr, 1, scr, 0, rows - 1);
        if (!isAlt) {
            rememberRow(first);
            scr[rows - 1] = createRow(cols);
        } else {
            Arrays.fill(first, ' ');
            styles.remove(first);
            scr[rows - 1] = first;
        }
    }

    private void handleCsi(char cmd) {
        String s = escBuf.toString();
        boolean qmark = s.startsWith("?");
        if (qmark) s = s.substring(1);
        int semi = s.indexOf(';');
        int p1 = parseInt(semi >= 0 ? s.substring(0, semi) : s, 0);
        int p2 = semi >= 0 ? parseInt(s.substring(semi + 1), 0) : 0;
        char[][] scr = isAlt ? altScreen : mainScreen;

        switch (cmd) {
            case 'H': case 'f':
                curRow = Math.max(0, Math.min(rows - 1, (p1 <= 0 ? 1 : p1) - 1));
                curCol = Math.max(0, Math.min(cols - 1, (p2 <= 0 ? 1 : p2) - 1));
                break;
            case 'A': curRow = Math.max(0, curRow - (p1 <= 0 ? 1 : p1)); break;
            case 'B': curRow = Math.min(rows - 1, curRow + (p1 <= 0 ? 1 : p1)); break;
            case 'C': curCol = Math.min(cols - 1, curCol + (p1 <= 0 ? 1 : p1)); break;
            case 'D': curCol = Math.max(0, curCol - (p1 <= 0 ? 1 : p1)); break;
            case 'G': curCol = Math.max(0, Math.min(cols - 1, (p1 <= 0 ? 1 : p1) - 1)); break;
            case 'd': curRow = Math.max(0, Math.min(rows - 1, (p1 <= 0 ? 1 : p1) - 1)); break;
            case 'J':
                if (p1 == 0) {
                    erase(scr[curRow], Math.min(curCol, cols), cols);
                    for (int r = curRow + 1; r < rows; r++) erase(scr[r], 0, cols);
                } else if (p1 == 1) {
                    for (int r = 0; r < curRow; r++) erase(scr[r], 0, cols);
                    erase(scr[curRow], 0, Math.min(curCol + 1, cols));
                } else if (p1 == 2) clearGrid(scr);
                else if (p1 == 3) scrollback.clear();
                break;
            case 'K':
                if (p1 == 0) erase(scr[curRow], Math.min(curCol, cols), cols);
                else if (p1 == 1) erase(scr[curRow], 0, Math.min(curCol + 1, cols));
                else if (p1 == 2) erase(scr[curRow], 0, cols);
                break;
            case 'h': if (qmark && isAltScreenMode(p1)) switchScreen(true); break;
            case 'l': if (qmark && isAltScreenMode(p1)) switchScreen(false); break;
            case 's': savedCursor().set(curRow, curCol); break;
            case 'u': restoreCursor(savedCursor()); break;
            case 'm':
                String[] tokens = s.split(";", -1);
                int[] params = new int[tokens.length];
                for (int i = 0; i < params.length; i++) params[i] = parseInt(tokens[i], 0);
                Style next = NativeTerminalStyle.apply(activeStyle,params);
                activeStyle = next.bold || next.bg != null || next.fg != null ? next : null;
                break;
        }
    }

    private boolean isAltScreenMode(int p) {
        return p == 1049 || p == 47 || p == 1047;
    }

    private void switchScreen(boolean toAlt) {
        if (this.isAlt == toAlt) return;
        this.isAlt = toAlt;
        if (toAlt) {
            parkedMain.set(curRow, curCol);
            savedAlt.set(0, 0);
            clearGrid(altScreen);
            curRow = curCol = 0;
        } else {
            restoreCursor(parkedMain);
        }
    }

    private void erase(char[] row, int from, int to) {
        Arrays.fill(row, from, to, ' ');
        Style[] existing = styles.get(row);
        if (existing != null) Arrays.fill(existing, from, to, null);
    }

    public synchronized String text() { return styledText().toString(); }

    public synchronized CharSequence styledText() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!isAlt) {
            for (char[] line : scrollback) {
                appendTrimmed(sb, line);
                sb.append('\n');
            }
        }
        char[][] scr = isAlt ? altScreen : mainScreen;
        int last = rows - 1;
        while (last > curRow && trimmedLength(scr[last]) == 0) last--;
        for (int r = 0; r <= last; r++) {
            appendTrimmed(sb, scr[r]);
            if (r < last) sb.append('\n');
        }
        return sb;
    }

    private static int trimmedLength(char[] line) {
        int len = line.length;
        while (len > 0 && line[len - 1] == ' ') len--;
        return len;
    }

    private void appendTrimmed(SpannableStringBuilder sb, char[] line) {
        int length = trimmedLength(line), start = sb.length();
        Style[] cells = styles.get(line);
        Style current = null;
        for (int col = 0; col < length; col++) {
            if (line[col] == 0) continue;
            Style next = cells == null ? null : cells[col];
            if (next != current) {
                NativeTerminalStyle.paint(sb,start,sb.length(),current);
                start = sb.length();
                current = next;
            }
            sb.append(line[col]);
        }
        NativeTerminalStyle.paint(sb,start,sb.length(),current);
    }

    private int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Math.min(1000000, Math.max(0, Integer.parseInt(s.trim()))); } catch (NumberFormatException e) { return def; }
    }

    private char[] createRow(int c) {
        char[] row = new char[c];
        Arrays.fill(row, ' ');
        return row;
    }

    private char[][] createGrid(int r, int c) {
        char[][] g = new char[r][];
        for (int i = 0; i < r; i++) g[i] = createRow(c);
        return g;
    }

    private void clearGrid(char[][] g) {
        for (char[] row : g) erase(row, 0, row.length);
    }

    private void rememberRow(char[] line) {
        scrollback.addLast(line);
        if (scrollback.size() > MAX_SCROLLBACK) scrollback.removeFirst();
    }

    private char[][] resizeGrid(char[][] old, int nc, int nr, int shift, boolean main) {
        char[][] next = new char[nr][];
        if (main) {
            for (int i = 0; i < shift; i++) rememberRow(old[i]);
            for (int i = -shift - 1; i >= 0; i--) next[i] = resizedRow(scrollback.removeLast(), nc);
        }
        for (int i = Math.max(0, -shift); i < nr; i++) {
            int source = i + shift;
            next[i] = source < old.length ? resizedRow(old[source], nc) : createRow(nc);
        }
        return next;
    }

    private char[] resizedRow(char[] old, int width) {
        if (old.length == width) return old;
        char[] row = createRow(width);
        System.arraycopy(old, 0, row, 0, Math.min(old.length, width));
        Style[] previous = styles.get(old);
        if (previous != null) styles.put(row, Arrays.copyOf(previous, width));
        // Don't leave half of a wide glyph at the right margin.
        if (width < old.length && old[width] == 0) erase(row, width - 1, width);
        return row;
    }
}
