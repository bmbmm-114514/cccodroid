package cc.codroid;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

/** One tonal highlight. Retargeting starts at the rectangle already on screen. */
final class PixelSelectionIndicator {
    static final PathInterpolator EASING = new PathInterpolator(.35f, 0f, .2f, 1f);
    static final int DURATION = 340;
    private final View host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    final RectF current = new RectF();
    private final RectF start = new RectF();
    private final RectF target = new RectF();
    private ValueAnimator motion;

    PixelSelectionIndicator(View host, int color, float radius) {
        this.host = host;
        paint.setColor(color);
        this.radius = radius;
    }

    void moveTo(float left, float top, float right, float bottom, boolean animate) {
        if (target.left == left && target.top == top && target.right == right && target.bottom == bottom
                && (animate || current.equals(target))) return;
        if (motion != null) motion.cancel();
        motion = null;
        target.set(left, top, right, bottom);
        if (animate && !current.isEmpty() && !target.isEmpty() && !current.equals(target)
                && host.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled()) {
            start.set(current);
            motion = ValueAnimator.ofFloat(0f, 1f);
            motion.setInterpolator(EASING);
            motion.setDuration(DURATION);
            motion.addUpdateListener(animator -> {
                float f = (float) animator.getAnimatedValue();
                current.set(start.left + (target.left - start.left) * f,
                        start.top + (target.top - start.top) * f,
                        start.right + (target.right - start.right) * f,
                        start.bottom + (target.bottom - start.bottom) * f);
                host.invalidate();
            });
            motion.start();
        } else {
            current.set(target);
        }
        host.invalidate();
    }

    void draw(Canvas canvas) {
        if (!current.isEmpty()) canvas.drawRoundRect(current, radius, radius, paint);
    }
}
