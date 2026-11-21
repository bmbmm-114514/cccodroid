package cc.codroid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import static cc.codroid.PixelUi.dp;

final class TerminalControls {
    private TerminalControls() {}

    static View add(LinearLayout parent, TerminalKeyInput input) {
        Context c = parent.getContext();
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setBackgroundColor(PixelUi.color(c, "surfaceContainer"));
        scroll.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));

        // Both rows share column widths, including at large font scales and when scrolled.
        TableLayout keys = new TableLayout(c);
        keys.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keys.setStretchAllColumns(true);
        TableRow first = new TableRow(c);
        TableRow second = new TableRow(c);
        keys.addView(first);
        keys.addView(second);
        key(first, "Esc", "转义（Esc）", () -> input.key(TerminalKeyInput.Key.ESC));
        key(first, "/", "斜杠", () -> input.text("/"));
        key(first, "–", "连字符", () -> input.text("-"));
        key(first, "Home", "行首（Home）", () -> input.key(TerminalKeyInput.Key.HOME));
        key(first, "↑", "方向键上", () -> input.key(TerminalKeyInput.Key.UP));
        key(first, "End", "行尾（End）", () -> input.key(TerminalKeyInput.Key.END));
        key(first, "PgUp", "向上翻页（PgUp）", () -> input.key(TerminalKeyInput.Key.PAGE_UP));

        key(second, "Tab", "制表符（Tab）", () -> input.key(TerminalKeyInput.Key.TAB));
        MaterialButton ctrl = key(second, "Ctrl", "Control 组合键", input::toggleCtrl);
        MaterialButton alt = key(second, "Alt", "Alt 组合键", input::toggleAlt);
        key(second, "←", "方向键左", () -> input.key(TerminalKeyInput.Key.LEFT));
        key(second, "↓", "方向键下", () -> input.key(TerminalKeyInput.Key.DOWN));
        key(second, "→", "方向键右", () -> input.key(TerminalKeyInput.Key.RIGHT));
        key(second, "PgDn", "向下翻页（PgDn）", () -> input.key(TerminalKeyInput.Key.PAGE_DOWN));

        for (MaterialButton modifier : new MaterialButton[]{ctrl, alt}) {
            modifier.setCheckable(true);
            modifier.setToggleCheckedStateOnClick(false);
        }
        input.onModifiersChanged(() -> {
            showModifier(ctrl, input.isCtrl());
            showModifier(alt, input.isAlt());
        });
        scroll.addView(keys);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private static MaterialButton key(TableRow row, String label, String description, Runnable action) {
        Context c = row.getContext();
        MaterialButton button = PixelUi.button(c, label, action);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextSize(13);
        button.setLetterSpacing(0f);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(1);
        button.setHorizontallyScrolling(false);
        button.setFocusableInTouchMode(false);
        button.setMinWidth(dp(c, 48));
        button.setMinimumWidth(dp(c, 48));
        button.setMinHeight(dp(c, 48));
        button.setMinimumHeight(dp(c, 48));
        button.setInsetTop(dp(c, 3));
        button.setInsetBottom(dp(c, 3));
        button.setCornerRadius(dp(c, 12));
        button.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        button.setBackgroundTintList(ColorStateList.valueOf(PixelUi.color(c, "surfaceContainerHigh")));
        button.setTextColor(PixelUi.color(c, "onSurface"));
        button.setContentDescription(description);
        button.setTooltipText(description);
        TableRow.LayoutParams lp = new TableRow.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(c, 2));
        row.addView(button, lp);
        return button;
    }

    private static void showModifier(MaterialButton button, boolean active) {
        Context c = button.getContext();
        button.setChecked(active);
        button.setBackgroundTintList(ColorStateList.valueOf(PixelUi.color(c, active ? "primary" : "surfaceContainerHigh")));
        button.setTextColor(PixelUi.color(c, active ? "onPrimary" : "onSurface"));
        button.setStrokeColor(ColorStateList.valueOf(PixelUi.color(c, "onPrimary")));
        button.setStrokeWidth(active ? dp(c, 2) : 0);
        ViewCompat.setStateDescription(button, active ? "已启用一次，再点取消" : "未启用");
    }
}
