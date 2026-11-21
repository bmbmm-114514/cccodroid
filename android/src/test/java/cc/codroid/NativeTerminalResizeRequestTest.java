package cc.codroid;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
@Config(sdk = 36, qualifiers = "w412dp-h860dp-mdpi", shadows = NativeTerminalResizeRequestTest.Gateway.class)
public class NativeTerminalResizeRequestTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static final List<Integer> rows = new ArrayList<>();
        static Consumer<JSONObject> reply;
        @Implementation protected static void call(Activity owner, String method, String route, JSONObject body,
                                                  Consumer<JSONObject> success, Consumer<String> error) {
            if (route.endsWith("/resize")) {
                rows.add(body.optInt("rows"));
                reply = success;
            } else {
                success.accept(new JSONObject());
            }
        }
    }

    private static void layout(ScrollView output, int height) {
        output.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                       View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        output.layout(0, 0, 390, height);
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }

    @Test public void intermittentKeyboardLayoutsCannotApplyAnOlderPtySizeLast() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(NativeAppearance.theme(activity));
        NativeHome home = new NativeHome(activity, () -> {});
        NativeFiles files = new NativeFiles();
        Gateway.rows.clear();
        try {
            ReflectionHelpers.setField(files, "activeTermId", "test");
            ReflectionHelpers.setField(files, "lastGeneration", home.generation);
            ReflectionHelpers.setField(files, "activeMode", ReflectionHelpers.<Integer>getStaticField(NativeFiles.class, "MODE_TERM"));
            ReflectionHelpers.callInstanceMethod(files, "render",
                    ReflectionHelpers.ClassParameter.from(NativeHome.class, home));
            ScrollView output = ReflectionHelpers.getField(files, "termScrollView");
            layout(output, 600);
            layout(output, 550);
            settle();
            assertEquals(1, Gateway.rows.size());

            layout(output, 480);
            layout(output, 250);
            settle();
            assertEquals(1, Gateway.rows.size());
            Gateway.reply.accept(new JSONObject());
            settle();
            assertEquals(2, Gateway.rows.size());
            assertTrue(Gateway.rows.get(1) < Gateway.rows.get(0));
            layout(output, 580);
            files.leave();
            Gateway.reply.accept(new JSONObject());
            settle();
            assertEquals(2, Gateway.rows.size());
        } finally {
            files.leave();
            home.dispose();
        }
    }
}
