package cc.codroid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.navigation.NavigationBarView;
import java.time.Duration;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w412dp-h860dp-mdpi", shadows = NativeNavigationMotionTest.Gateway.class)
public class NativeNavigationMotionTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static int calls;
        @Implementation protected static void call(Activity owner, String method, String route, JSONObject body,
                                                  Consumer<JSONObject> success, Consumer<String> error) {
            calls++;
            success.accept(new JSONObject());
        }
    }

    private NativeHome newHome() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(NativeAppearance.theme(activity));
        NativeHome home = new NativeHome(activity, () -> {});
        layout(home);
        return home;
    }

    private void layout(NativeHome home) {
        home.root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY),
                          View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY));
        home.root.layout(0, 0, 412, 860);
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    @Test public void cancellationPreservesPageAndDraftAndCommitPopsExactlyOnce() {
        NativeHome home = newHome();
        try {
            assertFalse(home.canGoBack());
            home.share("未发送的内容");
            home.selectTab("settings");
            layout(home);
            settle();
            home.nav("set-workspace");
            layout(home);
            settle();
            int generation = home.generation;
            int requests = Gateway.calls;
            View originalContent = home.body.getChildAt(0);
            View page = (View) home.body.getParent();

            home.startBack(false);
            home.progressBack(.5f);
            assertTrue(page.getTranslationX() > 0f);
            assertTrue(page.getScaleX() < 1f);
            home.cancelBack();
            settle();
            assertEquals(0f, page.getTranslationX(), .01f);
            assertEquals(1f, page.getScaleX(), .01f);
            assertEquals(generation, home.generation);
            assertEquals(requests, Gateway.calls);
            assertSame(originalContent, home.body.getChildAt(0));
            assertEquals("set-workspace", home.screen);

            home.startBack(true);
            home.progressBack(.7f);
            assertTrue(page.getTranslationX() < 0f);
            home.commitBack();
            assertEquals("settings", home.screen);
            assertTrue(home.canGoBack());
            settle();
            assertTrue(home.back());
            assertEquals("chat", home.screen);
            assertFalse(home.canGoBack());
            assertEquals("未发送的内容", NativeChat.readThemeDraft(home.a, home.chatId));
        } finally {
            home.dispose();
        }
    }

    @Test public void tabsSlideEdgeToEdgeInBothDirectionsWhileNavigationStaysAnchored() {
        NativeHome home = newHome();
        try {
            View page = (View) home.body.getParent();
            ViewGroup viewport = (ViewGroup) page.getParent();
            View outgoing = viewport.getChildAt(0);
            NavigationBarView navigation = ReflectionHelpers.getField(home, "navigation");
            PixelPageMotion motion = ReflectionHelpers.getField(home, "motion");
            int width = viewport.getWidth();
            for (String destination : new String[]{"settings", "chat"}) {
                int direction = destination.equals("settings") ? 1 : -1;
                navigation.setSelectedItemId(PixelNav.tabIndex(destination));
                layout(home);
                assertEquals(destination, home.screen);
                assertEquals(View.VISIBLE, outgoing.getVisibility());
                assertEquals(0f, outgoing.getTranslationX(), 0f);
                assertEquals(direction * width, page.getTranslationX(), 0f);
                ValueAnimator transition = ReflectionHelpers.getField(motion, "transition");
                transition.setCurrentFraction(.25f);
                assertTrue(Math.abs(page.getTranslationX()) > 0f && Math.abs(page.getTranslationX()) < width);
                for (int percent = 0; percent < 100; percent++) {
                    transition.setCurrentFraction(percent / 100f);
                    assertEquals((float) direction * width, page.getTranslationX() - outgoing.getTranslationX(), 0f);
                    assertEquals(1f, outgoing.getAlpha(), 0f);
                    assertEquals(1f, page.getAlpha(), 0f);
                    assertEquals(0f, navigation.getTranslationX(), 0f);
                }
                transition.end();
                assertEquals(View.INVISIBLE, outgoing.getVisibility());
                assertEquals(0f, page.getTranslationX(), 0f);
            }
        } finally {
            home.dispose();
        }
    }

    @Test public void systemBackWithoutAnEdgeGestureReturnsWithTheSameTabMotion() {
        NativeHome home = newHome();
        try {
            NavigationBarView navigation = ReflectionHelpers.getField(home, "navigation");
            PixelPageMotion motion = ReflectionHelpers.getField(home, "motion");
            View page = (View) home.body.getParent();
            ViewGroup viewport = (ViewGroup) page.getParent();
            navigation.setSelectedItemId(PixelNav.tabIndex("settings"));
            layout(home);
            ValueAnimator forward = ReflectionHelpers.getField(motion, "transition");
            forward.end();

            home.commitBack();
            layout(home);
            assertEquals("chat", home.screen);
            assertEquals(-viewport.getWidth(), page.getTranslationX(), 0f);
            ValueAnimator back = ReflectionHelpers.getField(motion, "transition");
            back.setCurrentFraction(.35f);
            assertEquals(1f, page.getAlpha(), 0f);
            assertEquals(1f, viewport.getChildAt(0).getAlpha(), 0f);
            assertTrue(page.getTranslationX() < 0 && page.getTranslationX() > -viewport.getWidth());
        } finally {
            home.dispose();
        }
    }

    @Test public void reversingATabSlidePreservesBothPositionsAndAThirdTabStartsFromTheVisibleFrame() {
        NativeHome home = newHome();
        try {
            NavigationBarView navigation = ReflectionHelpers.getField(home, "navigation");
            PixelPageMotion motion = ReflectionHelpers.getField(home, "motion");
            View page = (View) home.body.getParent();
            ViewGroup viewport = (ViewGroup) page.getParent();
            View outgoing = viewport.getChildAt(0);
            navigation.setSelectedItemId(PixelNav.tabIndex("settings"));
            layout(home);
            ValueAnimator forward = ReflectionHelpers.getField(motion, "transition");
            forward.setCurrentFraction(.2f);
            float settingsX = page.getTranslationX(), chatX = outgoing.getTranslationX();

            navigation.setSelectedItemId(PixelNav.tabIndex("chat"));
            layout(home);
            assertEquals(settingsX, outgoing.getTranslationX(), 1f);
            assertEquals(chatX, page.getTranslationX(), 1f);
            ValueAnimator reverse = ReflectionHelpers.getField(motion, "transition");
            reverse.setCurrentFraction(.15f);
            assertTrue(Math.abs(page.getTranslationX()) < Math.abs(chatX));

            navigation.setSelectedItemId(PixelNav.tabIndex("projects"));
            layout(home);
            assertEquals("projects", home.screen);
            assertEquals(0f, outgoing.getTranslationX(), 0f);
            assertEquals(1f, outgoing.getAlpha(), 0f);
            assertEquals(viewport.getWidth(), page.getTranslationX(), 0f);
            // Cancelled animators cannot move or reset the latest destination.
            forward.end();
            reverse.end();
            assertEquals(View.VISIBLE, outgoing.getVisibility());
            ValueAnimator third = ReflectionHelpers.getField(motion, "transition");
            third.end();
            assertEquals(0f, page.getTranslationX(), 0f);
            assertEquals(View.INVISIBLE, outgoing.getVisibility());
        } finally {
            home.dispose();
        }
    }

    @Test public void replacingThePageDuringAGestureCannotPopTheNewRoute() {
        NativeHome home = newHome();
        try {
            home.selectTab("settings");
            home.nav("set-workspace");
            home.startBack(false);
            home.progressBack(.3f);
            home.selectTab("projects");
            int changed = home.generation;
            home.progressBack(.9f);
            home.commitBack();
            assertEquals(changed, home.generation);
            assertEquals("projects", home.screen);
        } finally {
            home.dispose();
        }
    }
}
