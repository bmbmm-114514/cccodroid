package cc.codroid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import com.google.android.material.button.MaterialButton;

/** Primitives shared by native pages. All colors resolve in the current Material 3 theme. */
final class PixelUi {
    private PixelUi() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
    static int color(Context c, String role) {
        String attrName = "color" + Character.toUpperCase(role.charAt(0)) + role.substring(1);
        int id = c.getResources().getIdentifier(attrName, "attr", c.getPackageName());
        int fallback = role.startsWith("on") || "primary".equals(role)
                ? android.R.attr.textColorPrimary : android.R.attr.colorBackground;
        return NativeAppearance.resolveColor(c, id != 0 ? id : fallback, 0xff73777f);
    }
    static GradientDrawable shape(Context c, String role, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(c, role));
        bg.setCornerRadius(dp(c, radius));
        return bg;
    }
    static Drawable clickable(Context c, String role, int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(dp(c, radius));
        int ink = ColorUtils.setAlphaComponent(color(c, "onSurface"), 34);
        return new RippleDrawable(ColorStateList.valueOf(ink), role == null ? null : shape(c,role,radius), mask);
    }
    static Drawable drawable(Context c, String name) {
        int id = c.getResources().getIdentifier("pixel_" + name, "drawable", c.getPackageName());
        if (id == 0) id = c.getResources().getIdentifier("pixel_tune", "drawable", c.getPackageName());
        return id == 0 ? null : c.getDrawable(id).mutate();
    }
    static ImageButton icon(Context c, String name, String label, Runnable action) {
        ImageButton v = new ImageButton(c);
        v.setBackground(clickable(c, null, 24));
        v.setImageDrawable(drawable(c,name));
        v.setImageTintList(ColorStateList.valueOf(color(c,"onSurfaceVariant")));
        v.setContentDescription(label);
        v.setTooltipText(label);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(c,12);
        v.setPadding(p,p,p,p);
        v.setMinimumWidth(dp(c,48));
        v.setMinimumHeight(dp(c,48));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c,48),dp(c,48)));
        if (action != null) v.setOnClickListener(view -> action.run());
        return v;
    }
    static TextView label(Context c, String value, int size, String role) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color(c,role));
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    static MaterialButton button(Context c, String label, Runnable action) {
        int style = c.getResources().getIdentifier("materialButtonTonalStyle","attr",c.getPackageName());
        MaterialButton button = style == 0 ? new MaterialButton(c) : new MaterialButton(c,null,style);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    static LinearLayout group(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(shape(c,"surfaceContainerLow",20));
        group.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0, dp(c,4), 0, dp(c,12));
        parent.addView(group,lp);
        return group;
    }
    static LinearLayout row(LinearLayout parent,String name,String title,String description,Runnable action) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, description == null || description.isEmpty() ? 56 : 72));
        row.setPaddingRelative(dp(c,16),dp(c,10),dp(c,16),dp(c,10));
        if (name != null && !name.isEmpty()) {
            ImageView image = new ImageView(c);
            image.setImageDrawable(drawable(c,name));
            image.setImageTintList(ColorStateList.valueOf(color(c,"onSurfaceVariant")));
            LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(c,24),dp(c,24));
            icon.setMarginEnd(dp(c,16));
            row.addView(image,icon);
        }
        LinearLayout labels = new LinearLayout(c);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(label(c,title,16,"onSurface"));
        if (description != null && !description.isEmpty()) {
            TextView supporting = label(c,description,14,"onSurfaceVariant");
            supporting.setPadding(0,dp(c,3),0,0);
            labels.addView(supporting);
        }
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if (action != null) {
            row.setBackground(clickable(c,null,12));
            row.setFocusable(true);
            row.setOnClickListener(v->action.run());
            ImageView end = new ImageView(c);
            end.setImageDrawable(drawable(c,"next"));
            end.setImageTintList(ColorStateList.valueOf(color(c,"onSurfaceVariant")));
            row.addView(end,new LinearLayout.LayoutParams(dp(c,20),dp(c,20)));
        }
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        return row;
    }
}
