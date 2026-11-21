package cc.codroid;

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
        corner = PixelUi.dp(page.getContext(), 28) * progress;
        page.setElevation(PixelUi.dp(page.getContext(), 5) * progress);
        page.setClipToOutline(true);
        page.invalidateOutline();
    }

    private void transform(View v, float fraction, boolean right) {
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
        page.animate().translationX(0).scaleX(1).scaleY(1).setDuration(150)
                .setInterpolator(EASE).withEndAction(this::reset).start();
    }

    void navigate(String oldRoute, String oldTitle, boolean back, boolean interactive, int direction) {
        float initial = interactive && tracking ? progress : 0f;
        boolean right = interactive && tracking ? fromRight : direction < 0;
        reset();
        if (oldRoute.isEmpty() || !enabled()) return;
        showFrame(oldRoute, oldTitle);
        if (back) {
            preview.bringToFront();
            transform(preview, initial, right);
            corner = PixelUi.dp(page.getContext(), 28) * initial;
            preview.setClipToOutline(true);
            preview.invalidateOutline();
            float end = (right ? -1 : 1) * Math.min(page.getWidth() * .34f, PixelUi.dp(page.getContext(), 160));
            preview.animate().translationX(end).scaleX(.91f).scaleY(.91f).alpha(0f)
                    .setDuration(160).setInterpolator(EASE).withEndAction(this::reset).start();
        } else {
            page.bringToFront();
            page.setTranslationX(direction * PixelUi.dp(page.getContext(), 32));
            page.setAlpha(0f);
            page.animate().translationX(0).alpha(1).setDuration(220).setInterpolator(EASE)
                    .withEndAction(this::reset).start();
        }
    }

    void reset() {
        page.animate().withEndAction(null).cancel();
        preview.animate().withEndAction(null).cancel();
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
