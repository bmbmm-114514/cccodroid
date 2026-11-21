package cc.codroid;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.content.res.ColorStateList;
import android.widget.ImageButton;
import com.google.android.material.button.MaterialButton;
import static cc.codroid.PixelUi.dp;
import android.widget.LinearLayout;

import java.util.function.Predicate;

final class TerminalControls {
    private TerminalControls() {}

    private static final class KeySpec {
        final String label;
        final String payload;
        final String desc;

        KeySpec(String label, String payload, String desc) {
            this.label = label;
            this.payload = payload;
            this.desc = desc;
        }
    }

    private static final KeySpec[][] KEY_GROUPS = {
        {
            new KeySpec("Ctrl+C", "\u0003", "中断 (Ctrl+C)"),
            new KeySpec("Ctrl+D", "\u0004", "结束输入/退出 (Ctrl+D)"),
            new KeySpec("Ctrl+Z", "\u001A", "挂起 (Ctrl+Z)")
        },
        {
            new KeySpec("Tab", "\t", "制表符补全 (Tab)"),
            new KeySpec("Esc", "\u001B", "取消/退出 (Esc)")
        },
        {
            new KeySpec("↑", "\u001B[A", "方向键上"),
            new KeySpec("↓", "\u001B[B", "方向键下"),
            new KeySpec("←", "\u001B[D", "方向键左"),
            new KeySpec("→", "\u001B[C", "方向键右")
        }
    };

    static void add(LinearLayout parent, Predicate<String> send) {
        Context c = parent.getContext();

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setFillViewport(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(c, 16));
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(c, 12), 0, dp(c, 12), 0);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollLp.topMargin = dp(c, 4);
        scrollLp.bottomMargin = dp(c, 4);
        scroll.setLayoutParams(scrollLp);

        LinearLayout keyBar = new LinearLayout(c);
        keyBar.setOrientation(LinearLayout.HORIZONTAL);
        keyBar.setGravity(Gravity.CENTER_VERTICAL);
        keyBar.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        for (int g = 0; g < KEY_GROUPS.length; g++) {
            KeySpec[] group = KEY_GROUPS[g];
            boolean isLastGroup = (g == KEY_GROUPS.length - 1);
            for (int i = 0; i < group.length; i++) {
                KeySpec spec = group[i];
                boolean isLastInGroup = (i == group.length - 1);

                MaterialButton key = PixelUi.button(c, spec.label, () -> send.test(spec.payload));
                key.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                key.setMaxLines(1);
                // A horizontally scrolling TextView would center the label in its virtual layout.
                key.setHorizontallyScrolling(false);
                key.setTextSize(13);
                key.setMinWidth(dp(c, 48));
                key.setMinHeight(dp(c, 48));
                key.setMinimumWidth(dp(c, 48));
                key.setMinimumHeight(dp(c, 48));
                key.setInsetTop(dp(c, 4));
                key.setInsetBottom(dp(c, 4));
                key.setCornerRadius(dp(c, 14));
                key.setPadding(dp(c, 12), 0, dp(c, 12), 0);
                key.setBackgroundTintList(ColorStateList.valueOf(PixelUi.color(c, "surfaceContainerHigh")));
                key.setTextColor(PixelUi.color(c, "onSurface"));
                key.setContentDescription(spec.desc);
                key.setTooltipText(spec.desc);
                LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                int marginEnd = isLastInGroup ? (isLastGroup ? 0 : dp(c, 16)) : dp(c, 6);
                keyLp.setMarginEnd(marginEnd);
                keyBar.addView(key, keyLp);
            }
        }
        scroll.addView(keyBar);
        root.addView(scroll);

        LinearLayout composer = new LinearLayout(c);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackground(PixelUi.shape(c, "surfaceContainer", 28));
        composer.setPadding(dp(c, 14), dp(c, 4), dp(c, 6), dp(c, 4));
        composer.setMinimumHeight(dp(c, 56));

        LinearLayout.LayoutParams composerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        composerLp.setMargins(dp(c, 12), 0, dp(c, 12), dp(c, 8));
        composer.setLayoutParams(composerLp);

        EditText edit = new EditText(c);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        edit.setTextColor(PixelUi.color(c, "onSurface"));
        edit.setHintTextColor(PixelUi.color(c, "onSurfaceVariant"));
        edit.setHint("输入终端命令");
        edit.setMinimumHeight(dp(c, 48));
        edit.setBackground(null);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        edit.setLayoutParams(editLp);

        Runnable executeAction = () -> {
            String text = edit.getText().toString();
            if (send.test(text + "\n")) edit.setText("");
        };

        Runnable insertAction = () -> {
            String text = edit.getText().toString();
            if (!text.isEmpty() && send.test(text)) edit.setText("");
        };

        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) executeAction.run();
                return true;
            }
            if (actionId != EditorInfo.IME_ACTION_SEND) return false;
            executeAction.run();
            return true;
        });

        ImageButton insertBtn = PixelUi.icon(c, "terminal_insert", "仅输入", insertAction);
        insertBtn.setTooltipText("仅输入，不换行");
        ImageButton executeBtn = PixelUi.icon(c, "terminal_enter", "发送", executeAction);
        executeBtn.setTooltipText("发送并换行");
        executeBtn.setBackground(PixelUi.clickable(c, "primary", 24));
        executeBtn.setImageTintList(ColorStateList.valueOf(PixelUi.color(c, "onPrimary")));
        composer.addView(edit);
        composer.addView(insertBtn);
        composer.addView(executeBtn);
        root.addView(composer);

        parent.addView(root);
    }
}
