package cc.codroid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Looper;
import android.view.View;
import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedDispatcher;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProviderBackSurfaceTest {
    private PixelProviderPage dialog;
    private OnBackPressedDispatcher back;
    private View surface;

    @Before public void openPage() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(NativeAppearance.theme(host));
        dialog = new PixelProviderPage(host, NativeAppearance.theme(host));
        View content = new View(host);
        content.setBackgroundColor(Color.MAGENTA);
        dialog.setContentView(content);
        dialog.show();
        surface = (View) content.getParent();
        surface.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        surface.layout(0, 0, 320, 640);
        back = dialog.getOnBackPressedDispatcher();
    }

    @Test public void leftSwipeRoundsThePaintedSurfaceAndCancellationRestoresEveryCorner() {
        assertEquals(Color.MAGENTA, snapshot().getPixel(0, 0));
        back.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_LEFT));
        back.dispatchOnBackProgressed(event(.25f, BackEventCompat.EDGE_LEFT));
        assertEquals(7f, outlineRadius(), .01f);
        assertEquals(Color.MAGENTA, snapshot().getPixel(4, 4));
        back.dispatchOnBackProgressed(event(1f, BackEventCompat.EDGE_LEFT));
        assertEquals(28f, outlineRadius(), .01f);
        assertTrue(surface.getTranslationX() > 0);
        Bitmap rounded = snapshot();
        assertEquals(Color.TRANSPARENT, rounded.getPixel(4, 4));
        assertCorners(rounded, Color.TRANSPARENT);
        assertEquals(Color.MAGENTA, rounded.getPixel(160, 0));
        assertEquals(Color.MAGENTA, rounded.getPixel(160, 320));

        back.dispatchOnBackCancelled();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(dialog.isShowing());
        assertFalse(surface.getClipToOutline());
        assertEquals(0f, surface.getTranslationX(), .01f);
        assertEquals(1f, surface.getScaleX(), .01f);
        assertCorners(snapshot(), Color.MAGENTA);
    }

    @Test public void newRightSwipeInterruptsReturnAndReleasesWithoutFadingThePage() {
        back.dispatchOnBackStarted(event(.7f, BackEventCompat.EDGE_LEFT));
        back.dispatchOnBackCancelled();
        back.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_RIGHT));
        back.dispatchOnBackProgressed(event(.8f, BackEventCompat.EDGE_RIGHT));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertTrue(surface.getTranslationX() < 0);
        assertEquals(22.4f, outlineRadius(), .01f);
        back.onBackPressed();
        assertEquals(1f, surface.getAlpha(), .01f);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertFalse(dialog.isShowing());
    }

    @Test public void disablingAnimationsKeepsFullSizeAndCommitsImmediately() {
        float previous = ValueAnimator.getDurationScale();
        try {
            setDurationScale(0f);
            dialog.enter();
            back.dispatchOnBackStarted(event(.8f, BackEventCompat.EDGE_LEFT));
            back.dispatchOnBackProgressed(event(1f, BackEventCompat.EDGE_LEFT));
            assertEquals(0f, surface.getTranslationX(), .01f);
            assertEquals(1f, surface.getScaleX(), .01f);
            assertFalse(surface.getClipToOutline());
            back.onBackPressed();
            assertFalse(dialog.isShowing());
        } finally {
            setDurationScale(previous);
        }
    }

    private void setDurationScale(float scale) {
        ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, scale));
    }

    private float outlineRadius() {
        assertTrue(surface.getClipToOutline());
        Outline outline = new Outline();
        surface.getOutlineProvider().getOutline(surface, outline);
        return outline.getRadius();
    }

    private Bitmap snapshot() {
        Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
        surface.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void assertCorners(Bitmap bitmap, int color) {
        int right = bitmap.getWidth() - 1;
        int bottom = bitmap.getHeight() - 1;
        assertEquals(color, bitmap.getPixel(0, 0));
        assertEquals(color, bitmap.getPixel(right, 0));
        assertEquals(color, bitmap.getPixel(0, bottom));
        assertEquals(color, bitmap.getPixel(right, bottom));
    }

    private BackEventCompat event(float progress, int edge) {
        return new BackEventCompat(0, 80, progress, edge);
    }
}
