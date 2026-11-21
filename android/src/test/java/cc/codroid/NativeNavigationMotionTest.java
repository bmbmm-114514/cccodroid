package cc.codroid;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
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
