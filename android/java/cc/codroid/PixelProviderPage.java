package cc.codroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.animation.PathInterpolator;
import androidx.activity.BackEventCompat;
import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;

/** Full-screen provider settings sit above the activity so the original conversation or settings
 * page remains visible through a predictive gesture. Nested windows handle their own back. */
final class PixelProviderPage extends ComponentDialog {
    private static final PathInterpolator EASING = new PathInterpolator(.2f, 0f, 0f, 1f);
    private View page;
    private int direction = 1;
    private boolean closing;

    PixelProviderPage(Context context, int theme) {
        super(context, theme);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.setWindowAnimations(0);
    }

    @Override public void setContentView(View view) {
        super.setContentView(view);
        page = view;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackStarted(BackEventCompat event) {
                if (closing || page == null) return;
                page.animate().cancel();
                direction = event.getSwipeEdge() == BackEventCompat.EDGE_RIGHT ? -1 : 1;
                applyProgress(event.getProgress());
            }

            @Override public void handleOnBackProgressed(BackEventCompat event) {
                if (!closing) applyProgress(event.getProgress());
            }

            @Override public void handleOnBackCancelled() {
                if (closing || page == null) return;
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    reset();
                    return;
                }
                page.animate().translationX(0).scaleX(1).scaleY(1).alpha(1)
                        .setDuration(160).setInterpolator(EASING).start();
            }

            @Override public void handleOnBackPressed() { closePage(); }
        });
    }

    void enter() {
        if (page == null || !ValueAnimator.areAnimatorsEnabled()) return;
        int start = PixelUi.dp(getContext(), 28);
        page.setTranslationX(page.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -start : start);
        page.setAlpha(.25f);
        page.animate().translationX(0).alpha(1).setDuration(240).setInterpolator(EASING).start();
    }

    private void applyProgress(float progress) {
        if (page == null || !ValueAnimator.areAnimatorsEnabled()) return;
        float t = Math.max(0f, Math.min(1f, progress));
        page.setAlpha(1f);
        page.setTranslationX(direction * page.getWidth() * .24f * t);
        page.setScaleX(1f - .05f * t);
        page.setScaleY(1f - .05f * t);
    }

    private void reset() {
        page.setTranslationX(0);
        page.setScaleX(1);
        page.setScaleY(1);
        page.setAlpha(1);
    }

    void closePage() {
        if (closing || !isShowing()) return;
        closing = true;
        if (page == null || !ValueAnimator.areAnimatorsEnabled() || !page.isLaidOut()) {
            dismiss();
            return;
        }
        page.animate().cancel();
        page.animate().translationX(direction * Math.max(PixelUi.dp(getContext(), 40),
                        Math.abs(page.getTranslationX()) + PixelUi.dp(getContext(), 32)))
                .alpha(0).setDuration(160).setInterpolator(EASING).withEndAction(this::dismiss).start();
    }
}
