package cc.codroid;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

public final class NativeTerminalStyle {
    public static final class Style {
        public final Integer fg, bg;
        public final boolean bold;

        public Style(Integer fg, Integer bg, boolean bold) {
            this.fg = fg;
            this.bg = bg;
            this.bold = bold;
        }
    }

    private static final int[] ANSI = {
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
    };

    private static int color256(int i) {
        if (i >= 0 && i < 16) return ANSI[i];
        if (i >= 16 && i < 232) {
            int r = (i - 16) / 36, g = ((i - 16) / 6) % 6, b = (i - 16) % 6;
            return 0xFF000000 | (r > 0 ? r * 40 + 55 : 0) << 16
                             | (g > 0 ? g * 40 + 55 : 0) << 8
                             | (b > 0 ? b * 40 + 55 : 0);
        }
        if (i >= 232 && i <= 255) {
            int c = 8 + (i - 232) * 10;
            return 0xFF000000 | (c << 16) | (c << 8) | c;
        }
        return 0xFFFFFFFF;
    }

    public static Style apply(Style current, int[] s) {
        Integer fg = current != null ? current.fg : null;
        Integer bg = current != null ? current.bg : null;
        boolean bold = current != null && current.bold;
        int n = s != null ? s.length : 0;
        if (n == 0) return new Style(null, null, false);

        for (int i = 0; i < n; i++) {
            int c = s[i];
            if (c == 0) { fg = null; bg = null; bold = false; }
            else if (c == 1) bold = true;
            else if (c == 22) bold = false;
            else if (c >= 30 && c <= 37) fg = ANSI[c - 30];
            else if (c == 39) fg = null;
            else if (c >= 40 && c <= 47) bg = ANSI[c - 40];
            else if (c == 49) bg = null;
            else if (c >= 90 && c <= 97) fg = ANSI[c - 90 + 8];
            else if (c >= 100 && c <= 107) bg = ANSI[c - 100 + 8];
            else if ((c == 38 || c == 48) && i + 2 < n && s[i + 1] == 5) {
                int col = color256(s[i + 2]);
                if (c == 38) fg = col; else bg = col;
                i += 2;
            } else if ((c == 38 || c == 48) && i + 4 < n && s[i + 1] == 2) {
                int col = 0xFF000000 | ((s[i + 2] & 0xFF) << 16) | ((s[i + 3] & 0xFF) << 8) | (s[i + 4] & 0xFF);
                if (c == 38) fg = col; else bg = col;
                i += 4;
            }
        }
        return new Style(fg, bg, bold);
    }

    public static void paint(SpannableStringBuilder sb, int start, int end, Style s) {
        if (s == null || start >= end) return;
        int flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        if (s.fg != null) sb.setSpan(new ForegroundColorSpan(s.fg), start, end, flag);
        if (s.bg != null) sb.setSpan(new BackgroundColorSpan(s.bg), start, end, flag);
        if (s.bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, flag);
    }
}
