package cc.codroid;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;

import static cc.codroid.PixelUi.dp;

final class NativeStatusViews {
    private NativeStatusViews() {}

    static LinearLayout section(LinearLayout parent, String title) {
        Context c = parent.getContext();
        TextView label = PixelUi.label(c, title, 14, "primary");
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPaddingRelative(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 4));
        parent.addView(label);
        return PixelUi.group(parent);
    }

    static TextView detail(LinearLayout card, String text) {
        Context c = card.getContext();
        TextView label = PixelUi.label(c, text, 14, "onSurfaceVariant");
        label.setPaddingRelative(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        card.addView(label, -1, -2);
        return label;
    }

    static TextView metric(LinearLayout card, String name, String value) {
        Context c = card.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        TextView label = PixelUi.label(c, name, 15, "onSurface");
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMarginEnd(dp(c, 12));
        row.addView(label, left);
        TextView number = PixelUi.label(c, value, 16, "onSurface");
        number.setGravity(Gravity.END);
        number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(number);
        card.addView(row, -1, -2);
        return number;
    }

    static void quota(LinearLayout card, String window, double remaining, long resetsAt) {
        Context c = card.getContext();
        boolean known = remaining >= 0 && remaining <= 100;
        NumberFormat format = NumberFormat.getPercentInstance();
        format.setMaximumFractionDigits(1);
        TextView percent = metric(card, window, known ? format.format(remaining / 100.0) : "未知");
        percent.setTextColor(PixelUi.color(c, known ? "primary" : "onSurfaceVariant"));
        if (known) {
            LinearProgressIndicator progress = new LinearProgressIndicator(c);
            progress.setTrackThickness(dp(c, 6));
            progress.setTrackCornerRadius(dp(c, 3));
            progress.setMax(1000);
            progress.setProgress((int) Math.round(remaining * 10));
            progress.setTrackColor(PixelUi.color(c, "surfaceContainerHighest"));
            progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(c, 16), 0, dp(c, 16), 0);
            card.addView(progress, lp);
        }
        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        TextView reset = detail(card, resetsAt > 0 ? when.format(new Date(resetsAt)) + " 重置" : "重置时间未知");
        reset.setTextSize(12);
    }

    static MaterialSwitch toggle(LinearLayout card, String title) {
        Context c = card.getContext();
        MaterialSwitch sw = new MaterialSwitch(c);
        sw.setText(title);
        sw.setTextSize(16);
        sw.setTextColor(PixelUi.color(c, "onSurface"));
        sw.setMinimumHeight(dp(c, 56));
        sw.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        sw.setPaddingRelative(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        card.addView(sw, -1, -2);
        return sw;
    }
}
