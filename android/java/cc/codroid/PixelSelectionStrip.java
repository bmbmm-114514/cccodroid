package cc.codroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

/** Paints the selection behind stable, individually focusable rows or chips. */
final class PixelSelectionStrip extends LinearLayout {
    static final PathInterpolator EASING = new PathInterpolator(.2f, 0f, 0f, 1f);
    private final int baseColor;
    private final int activeColor;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF current = new RectF();
    private final RectF start = new RectF();
    private final RectF target = new RectF();
    private int selected = -1;
    private ValueAnimator motion;

    PixelSelectionStrip(Context context, int baseColor, int activeColor, float radiusPx) {
        super(context);
        this.baseColor = baseColor;
        this.activeColor = activeColor;
        radius = radiusPx;
    }

    void select(int index, boolean animate) {
        if (index == selected && animate) return;
        selected = index;
        cancelMotion();
        View targetView = getChildAt(index);
        if (targetView == null || targetView.getWidth() == 0 || targetView.getVisibility() != VISIBLE) {
            current.setEmpty();
        } else {
            target.set(targetView.getLeft(), targetView.getTop(), targetView.getRight(), targetView.getBottom());
            if (animate && !current.isEmpty() && !current.equals(target) && isAttachedToWindow()
                    && ValueAnimator.areAnimatorsEnabled()) {
                start.set(current);
                motion = ValueAnimator.ofFloat(0f, 1f);
                motion.setDuration(280);
                motion.setInterpolator(EASING);
                motion.addUpdateListener(animation -> {
                    float f = (float) animation.getAnimatedValue();
                    current.set(start.left + (target.left - start.left) * f,
                            start.top + (target.top - start.top) * f,
                            start.right + (target.right - start.right) * f,
                            start.bottom + (target.bottom - start.bottom) * f);
                    invalidate();
                });
                motion.start();
            } else {
                current.set(target);
            }
        }
        invalidate();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View child = getChildAt(selected);
        if (child != null && (current.isEmpty() || child.getVisibility() != VISIBLE
                || target.left != child.getLeft() || target.top != child.getTop()
                || target.right != child.getRight() || target.bottom != child.getBottom())) {
            select(selected, false);
        }
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
        if (!current.isEmpty()) {
            paint.setColor(activeColor);
            canvas.drawRoundRect(current, radius, radius, paint);
        }
        super.dispatchDraw(canvas);
    }

    @Override protected void onDetachedFromWindow() {
        select(selected, false);
        super.onDetachedFromWindow();
    }

    private void cancelMotion() {
        if (motion != null) motion.cancel();
        motion = null;
    }
}
