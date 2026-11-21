package cc.codroid;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowChoreographer;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w412dp-h860dp-mdpi", shadows = NativeNavigationMotionTest.Gateway.class)
public class NativeNavigationSelectionTest {
    private static void layOut(View v, int width, int height) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, width, height);
    }

    private static void paintMenu(NavigationBarView bar) {
        View menu = bar.getMenuViewGroup();
        Bitmap bitmap = Bitmap.createBitmap(menu.getWidth(), menu.getHeight(), Bitmap.Config.ARGB_8888);
        menu.draw(new Canvas(bitmap));
        bitmap.recycle();
    }

    private static void tap(NativeHome home, NavigationBarView bar, String route) {
        View item = bar.findViewById(PixelNav.tabIndex(route));
        Rect bounds = new Rect(0, 0, item.getWidth(), item.getHeight());
        home.root.offsetDescendantRectToMyCoords(item, bounds);
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(time, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0);
            assertTrue(home.root.dispatchTouchEvent(event));
            event.recycle();
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48));
    }

    private static void checkTouch(boolean wide, boolean reduced) {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        ReflectionHelpers.callInstanceMethod(activity, "showFrontend");
        NativeHome home = ReflectionHelpers.getField(activity, "nativeHome");
        int width = wide ? 740 : 412;
        try {
            layOut(home.root, width, 860);
            shadowOf(Looper.getMainLooper()).idle();
            layOut(home.root, width, 860);
            NavigationBarView bar = ReflectionHelpers.getField(home, "navigation");
            assertEquals(wide, bar instanceof NavigationRailView);
            assertTrue(bar.isAttachedToWindow());
            ShadowChoreographer.setPaused(true);
            paintMenu(bar);
            PixelSelectionIndicator indicator = ReflectionHelpers.getField(bar.getMenuViewGroup().getBackground(), "indicator");
            float origin = wide ? indicator.current.centerY() : indicator.current.centerX();
            assertTrue(origin > 0);
            tap(home, bar, "settings");
            assertEquals("settings", home.screen);
            layOut(home.root, width, 860);
            paintMenu(bar);
            PixelPageMotion pages = ReflectionHelpers.getField(home, "motion");
            View page = (View)home.body.getParent();
            ValueAnimator pill = ReflectionHelpers.getField(indicator, "motion");
            if (reduced) {
                assertNull(pill);
                assertEquals(0f, page.getTranslationX(), 0);
            } else {
                assertNotNull(pill);
                float x = indicator.current.centerX(), y = indicator.current.centerY();
                pill.setCurrentFraction(.35f);
                float mid = wide ? indicator.current.centerY() : indicator.current.centerX();
                assertTrue(mid > (wide ? y : x));
                home.root.getViewTreeObserver().dispatchOnPreDraw();
                ValueAnimator slide = ReflectionHelpers.getField(pages, "transition");
                assertNotNull(slide);
                slide.setCurrentFraction(.35f);
                assertTrue(page.getTranslationX() > 0 && page.getTranslationX() < page.getWidth());
                slide.end();
                pill.end();
                assertTrue(mid < (wide ? indicator.current.centerY() : indicator.current.centerX()));
            }
            home.nav("set-workspace");
            tap(home, bar, "files");
            assertEquals("files", home.screen);
            home.nav("git");
            assertEquals(PixelNav.tabIndex("files"), bar.getSelectedItemId());
            tap(home, bar, "projects");
            assertEquals("projects", home.screen);
        } finally {
            home.dispose();
            controller.pause().stop().destroy();
            ShadowChoreographer.setPaused(false);
        }
    }

    @Test public void touchesMovePageAndBottomIndicatorFromWorkspaceAndSettings() {
        checkTouch(false, false);
    }

    @Test @Config(qualifiers = "w740dp-h860dp-mdpi")
    public void railIndicatorTravelsVertically() { checkTouch(true, false); }

    @Test public void reducedMotionSettlesPageAndBarImmediately() {
        float scale = ValueAnimator.getDurationScale();
        ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, 0f));
        try { checkTouch(false, true); }
        finally {
            ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                    ReflectionHelpers.ClassParameter.from(float.class, scale));
        }
    }
}
