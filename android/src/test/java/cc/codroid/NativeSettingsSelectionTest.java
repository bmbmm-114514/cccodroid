package cc.codroid;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.time.Duration;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NativeSettingsSelectionTest.Gateway.class)
public class NativeSettingsSelectionTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static JSONObject saved;

        @Implementation protected static void call(Activity owner, String method, String path, JSONObject body,
                Consumer<JSONObject> result, Consumer<String> error) {
            if ("POST".equals(method)) saved = body;
            JSONObject data = NativeHome.json("ok", true);
            if ("/api/storage".equals(path)) data = NativeHome.json("auto", NativeHome.json("enabled", true, "every", "week"));
            result.accept(data);
        }
    }

    private NativeHome home;
    private Dialog window;

    @Before public void mount() {
        Gateway.saved = null;
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        host.setTheme(NativeAppearance.theme(host));
        home = new NativeHome(host, () -> {});
        window = new Dialog(host, NativeAppearance.theme(host));
        ((ViewGroup) home.root.getParent()).removeView(home.root);
        window.setContentView(home.root);
        window.show();
    }

    @After public void close() {
        home.dispose();
        window.dismiss();
    }

    @Test public void storageFrequencyUsesTheCheckedSheetAndOnlyWritesOnSave() {
        home.nav("set-storage");
        settle();
        PixelChoiceField field = find(home.root, PixelChoiceField.class, null);
        assertEquals("每周", field.getSelectedItem());
        field.performClick();
        BottomSheetDialog picker = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        settle();
        View option = find(picker.getWindow().getDecorView(), TextView.class, "每月");
        while (!option.isClickable()) option = (View) option.getParent();
        option.performClick();
        settle();
        assertEquals("每月", field.getSelectedItem());
        assertNull(Gateway.saved);
        find(home.root, TextView.class, "保存清理频次").performClick();
        assertEquals("month", Gateway.saved.optJSONObject("autoClean").optString("every"));
    }

    @Test public void editingLegacyTaskPreservesUnknownValuesUntilAnotherRowIsChosen() {
        JSONObject legacy = NativeHome.json("session", "legacy-session", "perm", "legacy-permission");
        LinearLayout form = new LinearLayout(home.a);
        form.setOrientation(LinearLayout.VERTICAL);
        window.setContentView(form);
        NativeTaskOptionsPanel options = new NativeTaskOptionsPanel(home, form, legacy);
        RadioButton original = find(form, RadioButton.class, "保留原设置 (legacy-permission)");
        assertTrue(original.isChecked());
        JSONObject patch = new JSONObject();
        options.apply(patch);
        assertFalse(patch.has("session"));
        assertFalse(patch.has("perm"));
        RadioButton custom = find(form, RadioButton.class, "自己挑允许的工具");
        assertTrue(custom.getParent() instanceof PixelSelectionStrip);
        custom.performClick();
        assertTrue(custom.isChecked());
        assertFalse(original.isChecked());
        assertEquals(View.VISIBLE, options.toolsRow.getVisibility());
        options.toolsEdit.setText("Read");
        options.apply(patch);
        assertEquals("custom", patch.optString("perm"));
        assertEquals("Read", patch.optString("tools"));
        assertFalse(patch.has("session"));
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650));
    }

    private static <T extends View> T find(View view, Class<T> type, String label) {
        if (type.isInstance(view) && (label == null || view instanceof TextView && label.contentEquals(((TextView) view).getText()))) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T match = find(group.getChildAt(i), type, label);
                if (match != null) return match;
            }
        }
        return null;
    }
}
