package cc.codroid;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** The toolbar and content move together. The tab bar and rail stay anchored. */
final class PixelPageMotion {
    private static final PathInterpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);
    private final FrameLayout preview;
    private final View page;
    private final ImageView image;
    private final TextView title;
    private ValueAnimator transition;
    private float progress;
    private boolean fromRight;
    private boolean tracking;
    private float corner;
    private final LruCache<String, Bitmap> frames = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap frame) { return frame.getAllocationByteCount(); }
    };

    PixelPageMotion(FrameLayout viewport, View page) {
        this.page = page;
        Context c = page.getContext();
        int surface = PixelUi.color(c, "surface");
        viewport.setBackgroundColor(surface);
        page.setBackgroundColor(surface);
        preview = new FrameLayout(c);
        preview.setBackgroundColor(surface);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        preview.setVisibility(View.INVISIBLE);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
        title = PixelUi.label(c, "", 24, "onSurface");
        int inset = PixelUi.dp(c, 24);
        title.setPaddingRelative(inset, 0, inset, 0);
        preview.addView(title, new FrameLayout.LayoutParams(-1, PixelUi.dp(c, 72), Gravity.TOP));
        viewport.addView(preview, 0, new FrameLayout.LayoutParams(-1, -1));
        ViewOutlineProvider outline = new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
            }
        };
        page.setOutlineProvider(outline);
        preview.setOutlineProvider(outline);
    }

    private boolean enabled() {
        return page.getWidth() > 0 && page.getHeight() > 0 && ValueAnimator.areAnimatorsEnabled();
    }

    void capture(String route) {
        if (!enabled() || route.isEmpty()) return;
        int w = page.getWidth(), h = page.getHeight();
        // The preview is transient and never written to disk. Bound memory on large/foldable displays.
        float scale = Math.min(1f, (float) Math.sqrt(450000d / ((double) w * h)));
        Bitmap frame = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.scale(scale, scale);
        try {
            page.draw(canvas);
            frames.put(route, frame);
        } catch (IllegalArgumentException | IllegalStateException hardwareOnly) {
            // Software Canvas cannot draw a hardware-only image from some document previews.
            frame.recycle();
            frames.remove(route);
        }
    }

    private void showFrame(String route, String fallback) {
        Bitmap frame = frames.get(route);
        boolean fits = frame != null && Math.abs((float) frame.getWidth() / frame.getHeight()
                - (float) page.getWidth() / Math.max(1, page.getHeight())) < .08f;
        image.setImageBitmap(fits ? frame : null);
        title.setText(fallback);
        title.setVisibility(fits ? View.GONE : View.VISIBLE);
        preview.setVisibility(View.VISIBLE);
    }

    void start(String route, String name, boolean right) {
        reset();
        if (!enabled()) return;
        tracking = true;
        fromRight = right;
        showFrame(route, name);
        page.bringToFront();
    }

    void seek(float fraction) {
        if (!tracking) return;
        progress = Math.max(0f, Math.min(1f, fraction));
        transform(page, progress, fromRight);
    }

    private void transform(View v, float fraction, boolean right) {
        corner = PixelUi.dp(v.getContext(), 28) * fraction;
        v.setClipToOutline(fraction > 0f);
        v.invalidateOutline();
        v.setElevation(PixelUi.dp(v.getContext(), 5) * fraction);
        float scale = 1f - fraction * .1f;
        v.setPivotX(v.getWidth() * .5f);
        v.setPivotY(v.getHeight() * .5f);
        v.setScaleX(scale);
        v.setScaleY(scale);
        float distance = Math.min(page.getWidth() * .24f, PixelUi.dp(v.getContext(), 112));
        v.setTranslationX((right ? -1 : 1) * fraction * distance);
    }

    void cancel() {
        if (!tracking) return;
        tracking = false;
        if (!enabled() || progress == 0) { reset(); return; }
        float start = progress;
        animateFrames(150, f -> transform(page, start * (1f - EASE.getInterpolation(f)), fromRight));
    }

    void navigate(String oldRoute, String oldTitle, boolean back, boolean interactive, int direction) {
        float initial = interactive && tracking ? progress : 0f;
        boolean right = interactive && tracking ? fromRight : direction < 0;
        reset();
        if (oldRoute.isEmpty() || !enabled()) return;
        showFrame(oldRoute, oldTitle);
        if (back && interactive) {
            preview.bringToFront();
            transform(preview, initial, right);
            float startX = preview.getTranslationX();
            float endX = (right ? -1 : 1) * page.getWidth();
            animateFrames(180, f -> preview.setTranslationX(startX + (endX - startX) * EASE.getInterpolation(f)));
        } else {
            page.bringToFront();
            float distance = (back ? -direction : direction) * PixelUi.dp(page.getContext(), 16);
            animateFrames(220, f -> fadeThrough(preview, page, f, distance));
        }
    }

    // Material fade-through hands the surface from one page to the next before its text appears.
    private static void fadeThrough(View outgoing, View incoming, float fraction, float distance) {
        float exit = Math.min(1f, fraction / .35f);
        float enter = EASE.getInterpolation(Math.max(0f, (fraction - .35f) / .65f));
        outgoing.setAlpha(1f - exit);
        incoming.setAlpha(enter);
        incoming.setTranslationX(distance * (1f - enter));
    }

    private void animateFrames(long duration, java.util.function.Consumer<Float> frame) {
        transition = ValueAnimator.ofFloat(0f, 1f);
        transition.setInterpolator(null);
        transition.setDuration(duration);
        frame.accept(0f);
        transition.addUpdateListener(a -> frame.accept((Float) a.getAnimatedValue()));
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                if (transition == animator) reset();
            }
        });
        transition.start();
    }

    void reset() {
        if (transition != null) {
            ValueAnimator active = transition;
            transition = null;
            active.removeAllListeners();
            active.removeAllUpdateListeners();
            active.cancel();
        }
        for (View v : new View[]{page, preview}) {
            v.setTranslationX(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setAlpha(1f);
            v.setElevation(0f);
            v.setClipToOutline(false);
        }
        tracking = false;
        progress = 0f;
        corner = 0f;
        image.setImageDrawable(null);
        preview.setVisibility(View.INVISIBLE);
        page.bringToFront();
    }

    void dispose() { reset(); frames.evictAll(); }
}
