package cc.codroid;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w412dp-h860dp-mdpi", shadows = NativeEnergyPeriodTest.Gateway.class)
public class NativeEnergyPeriodTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static final Map<String, Consumer<JSONObject>> replies = new HashMap<>();
        static final Map<String, Consumer<String>> errors = new HashMap<>();

        @Implementation protected static void call(Activity owner, String method, String route, JSONObject body,
                                                  Consumer<JSONObject> success, Consumer<String> error) {
            String prefix = "/api/energy?view=";
            if (route.startsWith(prefix)) {
                replies.put(route.substring(prefix.length()), success);
                errors.put(route.substring(prefix.length()), error);
            } else {
                success.accept(new JSONObject());
            }
        }
    }

    private static TextView find(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = find(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static JSONObject report(String sample) throws Exception {
        JSONArray bars = new JSONArray();
        if (sample != null) bars.put(new JSONObject().put("t", sample).put("c", 5));
        return new JSONObject().put("ok", true).put("bars", bars)
                .put("battery", new JSONObject().put("level", 75))
                .put("sys", new JSONObject().put("used", 13));
    }

    @Test public void slowResponsesErrorsAndRefreshesKeepPopulatedHistoryOnScreen() throws Exception {
        Gateway.replies.clear();
        Gateway.errors.clear();
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(NativeAppearance.theme(activity));
        NativeHome home = new NativeHome(activity, () -> {});
        try {
            home.nav("usage");
            assertEquals(View.VISIBLE, find(home.root, "正在读取近 14 天的记录…").getVisibility());
            Gateway.replies.get("day").accept(report("日采样"));
            TextView firstDay = find(home.root, "日采样");
            TextView battery = find(home.root, "75%");
            TextView bill = find(home.root, "整机耗电");
            assertNotNull(firstDay);
            assertNotNull(battery);
            assertNotNull(bill);

            find(home.root, "小时").performClick();
            assertSame(firstDay, find(home.root, "日采样"));
            assertNull(find(home.root, "正在读取近 24 小时的记录…"));
            find(home.root, "周").performClick();
            Gateway.replies.get("hour").accept(report("过时的采样"));
            assertSame(firstDay, find(home.root, "日采样"));
            assertNull(find(home.root, "过时的采样"));
            Gateway.replies.get("week").accept(report("周采样"));
            assertNotNull(find(home.root, "周采样"));
            assertNull(find(home.root, "日采样"));

            find(home.root, "日").performClick();
            TextView cachedDay = find(home.root, "日采样");
            assertNotNull(cachedDay);
            Gateway.replies.get("day").accept(report("日采样"));
            assertSame(cachedDay, find(home.root, "日采样"));
            assertSame(battery, find(home.root, "75%"));
            assertSame(bill, find(home.root, "整机耗电"));

            find(home.root, "小时").performClick();
            Gateway.errors.get("hour").accept("连接中断");
            assertSame(cachedDay, find(home.root, "日采样"));
            assertNotNull(find(home.root, "近 14 天"));
            TextView retry = find(home.root, "近 24 小时 · 连接中断 · 点按重试");
            assertTrue(retry.performClick());
            Gateway.replies.get("hour").accept(report(null));
            assertEquals(View.GONE, retry.getVisibility());
            assertNotNull(find(home.root, "近 24 小时"));
            assertNotNull(find(home.root, "暂无采样"));
            assertNull(find(home.root, "日采样"));
        } finally {
            home.dispose();
        }
    }
}
