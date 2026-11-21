package cc.codroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.IntConsumer;

/** A shared sliding indicator for inline, mutually exclusive destinations. */
final class PixelSegmentedTabs {
    final PixelSelectionStrip view;
    private final Context context;
    private final boolean equalWidths;
    private final IntConsumer choose;
    private int selected = -1;

    PixelSegmentedTabs(Context context, boolean equalWidths, IntConsumer choose) {
        this.context = context;
        this.equalWidths = equalWidths;
        this.choose = choose;
        view = new PixelSelectionStrip(context, Color.TRANSPARENT, PixelUi.color(context, "primaryContainer"), PixelUi.dp(context, 24));
        view.setBackground(PixelUi.shape(context, "surfaceContainerHigh", 28));
        int pad = PixelUi.dp(context, 4);
        view.setPadding(pad, pad, pad, pad);
    }

    void labels(List<String> labels) {
        while (view.getChildCount() > labels.size()) view.removeViewAt(view.getChildCount() - 1);
        for (int i = 0; i < labels.size(); i++) {
            TextView tab = (TextView) view.getChildAt(i);
            if (tab == null) {
                tab = PixelUi.label(context, "", 14, "onSurfaceVariant");
                tab.setFocusable(true);
                tab.setGravity(Gravity.CENTER);
                tab.setSingleLine(true);
                tab.setEllipsize(TextUtils.TruncateAt.END);
                tab.setMinimumHeight(PixelUi.dp(context, 48));
                tab.setMinimumWidth(PixelUi.dp(context, 48));
                int horizontalPad = PixelUi.dp(context, equalWidths ? 2 : 12);
                tab.setPadding(horizontalPad, 0, horizontalPad, 0);
                tab.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected},{}},
                        new int[]{PixelUi.color(context,"onPrimaryContainer"),PixelUi.color(context,"onSurfaceVariant")}));
                tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                tab.setBackground(PixelUi.clickable(context, null, 24));
                int index = i;
                tab.setOnClickListener(v -> { if (index != selected) choose.accept(index); });
                view.addView(tab, equalWidths ? new LinearLayout.LayoutParams(0,-2,1f) : new LinearLayout.LayoutParams(-2,-2));
            }
            if (!labels.get(i).contentEquals(tab.getText())) tab.setText(labels.get(i));
        }
    }

    void select(int index, boolean animate) {
        for (int i = 0; i < view.getChildCount(); i++) {
            TextView tab = (TextView) view.getChildAt(i);
            ProviderSettingsDialog.markChoice(tab, tab.getText().toString(), i == index);
        }
        if (index != selected || !animate) view.select(index, animate);
        selected = index;
    }

    static void slideContent(View content, int direction) {
        content.animate().cancel();
        if (direction == 0 || !content.isLaidOut() || !content.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            content.setTranslationX(0f);
            return;
        }
        if (content.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
        content.setTranslationX(PixelUi.dp(content.getContext(), 40) * Integer.signum(direction));
        content.animate().translationX(0f).setDuration(PixelSelectionIndicator.DURATION).setInterpolator(PixelSelectionIndicator.EASING).start();
    }
}
