package cc.codroid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
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
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w412dp-h860dp-mdpi", shadows = NativeWorkspaceSelectionTest.Gateway.class)
public class NativeWorkspaceSelectionTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        @Implementation protected static void call(Activity owner, String method, String route, JSONObject body,
                Consumer<JSONObject> done, Consumer<String> failure) throws Exception {
            JSONObject result = new JSONObject();
            if (route.startsWith("/api/files?")) {
                JSONArray entries = new JSONArray();
                for (int i = 0; i < 50; i++) entries.put(new JSONObject().put("name","File " + i));
                result.put("path","/project").put("entries",entries);
            } else if (route.startsWith("/api/git/status")) {
                result.put("files", new JSONArray().put(new JSONObject().put("path","notes.txt")));
            }
            done.accept(result);
        }
    }

    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));
        root.layout(0,0,412,860);
        root.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static TextView text(View root, String label) {
        return NativeTerminalToolbarWorkflowTest.find(root, TextView.class, label);
    }

    @Test public void clickingWorkspaceModesKeepsTheIndicatorListScrollAndDrafts() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(NativeAppearance.theme(activity));
        NativeHome home = new NativeHome(activity, () -> {});
        Activity window = Robolectric.buildActivity(Activity.class).setup().get();
        ((ViewGroup)home.root.getParent()).removeView(home.root);
        window.setContentView(home.root);
        try {
            home.selectTab("files");
            layout(home.root);
            PixelSelectionStrip tabs = (PixelSelectionStrip)text(home.body,"编辑器").getParent();
            ScrollView files = NativeTerminalToolbarWorkflowTest.find(home.body, ScrollView.class, null);
            files.scrollTo(0,320);
            assertEquals(320,files.getScrollY());
            NativeFiles workspace = home.files;
            ReflectionHelpers.setField(workspace, "editingPath", "/project/notes.txt");
            ReflectionHelpers.setField(workspace, "editingText", "one two three");
            text(tabs,"编辑器").performClick();
            layout(home.root);
            assertSame(tabs, text(home.body,"Git").getParent());
            PixelSelectionIndicator indicator = ReflectionHelpers.getField(tabs,"indicator");
            ValueAnimator moving = ReflectionHelpers.getField(indicator, "motion");
            assertNotNull(moving);
            moving.setCurrentFraction(.25f);
            RectF position = ReflectionHelpers.getField(indicator,"current");
            assertTrue(position.left > text(tabs,"文件").getLeft());
            assertTrue(position.left < text(tabs,"编辑器").getLeft());
            EditText editor = NativeTerminalToolbarWorkflowTest.find(home.body, EditText.class, null);
            editor.setText("unsaved notes");
            editor.setSelection(3,9);
            text(tabs,"Git").performClick();
            EditText draft = NativeTerminalToolbarWorkflowTest.find(home.body, EditText.class,null);
            draft.setText("commit draft");
            text(tabs,"文件").performClick();
            layout(home.root);
            assertEquals(320, NativeTerminalToolbarWorkflowTest.find(home.body, ScrollView.class,null).getScrollY());
            text(tabs,"编辑器 *").performClick();
            editor = NativeTerminalToolbarWorkflowTest.find(home.body, EditText.class,null);
            assertEquals("unsaved notes",editor.getText().toString());
            assertEquals(3,editor.getSelectionStart());
            assertEquals(9,editor.getSelectionEnd());
            text(tabs,"Git").performClick();
            assertEquals("commit draft",NativeTerminalToolbarWorkflowTest.find(home.body, EditText.class,null).getText().toString());
            text(tabs,"终端").performClick();
            assertSame(tabs, text(home.body,"终端").getParent());
            assertTrue(text(tabs,"终端").isSelected());
        } finally {
            home.dispose();
            window.finish();
        }
    }
}
