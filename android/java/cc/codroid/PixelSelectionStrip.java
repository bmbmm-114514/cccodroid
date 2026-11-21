package cc.codroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;

/** Paints the selection behind stable, individually focusable rows or chips. */
final class PixelSelectionStrip extends LinearLayout {
    private final int baseColor;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PixelSelectionIndicator indicator;
    private int selected = -1;

    PixelSelectionStrip(Context context, int baseColor, int activeColor, float radiusPx) {
        super(context);
        this.baseColor = baseColor;
        indicator = new PixelSelectionIndicator(this, activeColor, radiusPx);
        radius = radiusPx;
    }

    void select(int index, boolean animate) {
        selected = index;
        View child = getChildAt(index);
        if (child != null && child.getVisibility() == VISIBLE && child.getWidth() > 0) {
            indicator.moveTo(child.getLeft(), child.getTop(), child.getRight(), child.getBottom(), animate);
        } else {
            indicator.moveTo(0, 0, 0, 0, false);
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        select(selected, !changed);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (Color.alpha(baseColor) != 0) {
            paint.setColor(baseColor);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == VISIBLE) canvas.drawRoundRect(child.getLeft(), child.getTop(),
                        child.getRight(), child.getBottom(), radius, radius, paint);
            }
        }
        indicator.draw(canvas);
        super.dispatchDraw(canvas);
    }

    @Override protected void onDetachedFromWindow() {
        select(selected, false);
        super.onDetachedFromWindow();
    }

}
