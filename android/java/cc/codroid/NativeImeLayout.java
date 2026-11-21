package cc.codroid;

import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.List;

/** The native window owns its insets so the keyboard can cover the bottom bar continuously. */
final class NativeImeLayout {
    private final LinearLayout root;
    private final Runnable visibilityChanged;
    private View bottomBar;
    private WindowInsetsCompat target;
    private WindowInsetsAnimationCompat animation;
    private boolean started;
    private int overlap;
    private boolean visible;

    NativeImeLayout(LinearLayout root, Runnable visibilityChanged) {
        this.root = root;
        this.visibilityChanged = visibilityChanged;
    }

    void attach(Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            target = insets;
            if (animation == null) apply(insets);
            // Material navigation must not add the same system bar insets a second time.
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.setWindowInsetsAnimationCallback(root, new WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
            @Override public void onPrepare(WindowInsetsAnimationCompat next) {
                if ((next.getTypeMask() & WindowInsetsCompat.Type.ime()) == 0) return;
                animation = next;
                started = false;
                // Android 11 can cancel after prepare without sending start or end.
                // The system starts a live animation before drawing its first frame.
                OneShotPreDrawListener.add(root, () -> root.post(() -> {
                    if (animation == next && !started) finish(next);
                }));
            }

            @Override public WindowInsetsAnimationCompat.BoundsCompat onStart(WindowInsetsAnimationCompat next,
                    WindowInsetsAnimationCompat.BoundsCompat bounds) {
                if (animation == next) started = true;
                return bounds;
            }

            @Override public WindowInsetsCompat onProgress(WindowInsetsCompat frame,
                    List<WindowInsetsAnimationCompat> running) {
                if (animation != null && running.contains(animation)) {
                    started = true;
                    apply(frame);
                }
                return frame;
            }

            @Override public void onEnd(WindowInsetsAnimationCompat finished) {
                finish(finished);
            }
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void finish(WindowInsetsAnimationCompat finished) {
        if (finished != animation) return;
        animation = null;
        if (target != null) apply(target);
    }

    void setBottomBar(View bar) {
        if (bottomBar != null) bottomBar.removeOnLayoutChangeListener(measured);
        bottomBar = bar;
        if (bottomBar != null) {
            bottomBar.addOnLayoutChangeListener(measured);
            positionBottomBar();
        }
    }

    private final View.OnLayoutChangeListener measured = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (b - t != ob - ot) positionBottomBar();
    };

    private void apply(WindowInsetsCompat frame) {
        Insets bars = frame.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        int ime = frame.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int bottom = Math.max(bars.bottom, ime);
        if (root.getPaddingLeft() != bars.left || root.getPaddingTop() != bars.top
                || root.getPaddingRight() != bars.right || root.getPaddingBottom() != bottom) {
            root.setPadding(bars.left, bars.top, bars.right, bottom);
        }
        overlap = bottom - bars.bottom;
        positionBottomBar();
        // Keep Back with the keyboard until its closing animation is completely clear.
        boolean open = frame.isVisible(WindowInsetsCompat.Type.ime()) || overlap > 0;
        if (animation != null && target != null) open |= target.isVisible(WindowInsetsCompat.Type.ime());
        if (visible != open) {
            visible = open;
            visibilityChanged.run();
        }
    }

    private void positionBottomBar() {
        if (bottomBar == null) return;
        int height = bottomBar.getMeasuredHeight();
        int covered = Math.min(height, overlap);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bottomBar.getLayoutParams();
        if (lp.bottomMargin != -covered) {
            lp.bottomMargin = -covered;
            bottomBar.setLayoutParams(lp);
        }
        bottomBar.setVisibility(height > 0 && covered == height ? View.INVISIBLE : View.VISIBLE);
    }

    boolean isVisible() { return visible; }

    boolean hide() {
        if (!visible) return false;
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(root);
        if (controller != null) controller.hide(WindowInsetsCompat.Type.ime());
        return true;
    }

    void detach() {
        animation = null;
        target = null;
        setBottomBar(null);
        ViewCompat.setWindowInsetsAnimationCallback(root, null);
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
    }
}
