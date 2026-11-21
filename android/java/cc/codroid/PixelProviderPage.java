package cc.codroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import androidx.activity.BackEventCompat;
import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;

/** Full-screen provider settings sit above the activity so the original conversation or settings
 * page remains visible through a predictive gesture. Nested windows handle their own back. */
final class PixelProviderPage extends ComponentDialog {
    private static final PathInterpolator EASING = new PathInterpolator(.2f, 0f, 0f, 1f);
    private BackSurface page;
    private ValueAnimator restore;
    private float progress;
    private int direction = 1;
    private boolean tracking;
    private boolean closing;

    PixelProviderPage(Context context, int theme) {
        super(context, theme);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
    }

    @Override public void setContentView(View view) {
        page = new BackSurface(view.getContext());
        page.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        super.setContentView(page);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackStarted(BackEventCompat event) {
                if (closing || page == null) return;
                stopMotion();
                tracking = true;
                direction = event.getSwipeEdge() == BackEventCompat.EDGE_RIGHT ? -1 : 1;
                applyProgress(event.getProgress());
            }

            @Override public void handleOnBackProgressed(BackEventCompat event) {
                if (tracking && !closing) applyProgress(event.getProgress());
            }

            @Override public void handleOnBackCancelled() {
                if (!tracking || closing) return;
                tracking = false;
                if (!ValueAnimator.areAnimatorsEnabled() || progress == 0f) {
                    applyProgress(0f);
                    return;
                }
                restore = ValueAnimator.ofFloat(progress, 0f);
                restore.setDuration(160);
                restore.setInterpolator(EASING);
                restore.addUpdateListener(frame -> applyProgress((float) frame.getAnimatedValue()));
                restore.start();
            }

            @Override public void handleOnBackPressed() { closePage(); }
        });
    }

    void enter() {
        if (page == null || !ValueAnimator.areAnimatorsEnabled()) return;
        int start = PixelUi.dp(getContext(), 28);
        page.setTranslationX(page.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -start : start);
        // Keep the foreground opaque while the underlying settings or chat is still visible.
        page.animate().translationX(0).setDuration(220).setInterpolator(EASING).start();
    }

    private void applyProgress(float value) {
        progress = ValueAnimator.areAnimatorsEnabled() ? Math.max(0f, Math.min(1f, value)) : 0f;
        float distance = Math.min(page.getWidth() * .24f, PixelUi.dp(getContext(), 112));
        page.setTranslationX(direction * distance * progress);
        page.setScaleX(1f - .05f * progress);
        page.setScaleY(1f - .05f * progress);
        page.setCornerProgress(progress);
    }

    private void stopMotion() {
        if (restore != null) {
            restore.cancel();
            restore = null;
        }
        if (page != null) page.animate().setUpdateListener(null).withEndAction(null).cancel();
    }

    void closePage() {
        if (closing || !isShowing()) return;
        closing = true;
        stopMotion();
        if (page == null || !ValueAnimator.areAnimatorsEnabled() || !page.isLaidOut()) {
            dismiss();
            return;
        }
        if (!tracking && progress == 0f) {
            direction = page.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
        }
        float initial = progress;
        page.animate().translationX(direction * page.getWidth()).scaleX(.95f).scaleY(.95f)
                .setDuration(220).setInterpolator(EASING)
                .setUpdateListener(frame -> page.setCornerProgress(
                        initial + (1f - initial) * frame.getAnimatedFraction()))
                .withEndAction(this::dismiss).start();
    }

    @Override protected void onStop() {
        stopMotion();
        tracking = false;
        closing = false;
        if (page != null) applyProgress(0f);
        super.onStop();
    }

    /** The outline clips the hardware render node. Software snapshots use the identical bounds. */
    private static final class BackSurface extends FrameLayout {
        private final Path clip = new Path();
        private final int maxRadius;
        private float radius;

        BackSurface(Context context) {
            super(context);
            maxRadius = PixelUi.dp(context, 28);
            setWillNotDraw(false);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, getWidth(), getHeight(), radius);
                }
            });
        }

        void setCornerProgress(float progress) {
            float next = maxRadius * progress;
            if (next == radius) return;
            radius = next;
            setClipToOutline(radius > 0f);
            invalidateOutline();
            invalidate();
        }

        @Override public void draw(Canvas canvas) {
            if (canvas.isHardwareAccelerated() || radius == 0f) {
                super.draw(canvas);
                return;
            }
            clip.rewind();
            clip.addRoundRect(0f, 0f, getWidth(), getHeight(), radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);
            super.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
