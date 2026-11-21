package cc.codroid;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.time.Duration;
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = NativeChoiceSurfacesTest.Gateway.class)
public class NativeChoiceSurfacesTest {
    @Implements(value = NativeRequests.class, isInAndroidSdk = false)
    public static class Gateway {
        static int materials;
        @Implementation protected static void call(Activity owner, String method, String path, JSONObject body,
                                                  Consumer<JSONObject> success, Consumer<String> error) {
            JSONObject data = new JSONObject();
            if (path.startsWith("/api/multi/state")) {
                data = NativeHome.json("projects", new JSONArray().put(NativeHome.json("id", "notes", "name", "笔记")));
            }
            if (path.startsWith("/api/lineage/material?")) materials++;
            success.accept(data);
        }
    }

    private static BottomSheetDialog sheet() {
        shadowOf(Looper.getMainLooper()).idle();
        BottomSheetDialog dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        dialog.setDismissWithAnimation(false);
        return dialog;
    }

    private static View find(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (label.equals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View button(View root, String label) {
        View v = find(root, label);
        assertNotNull(label, v);
        while (!v.isClickable()) v = (View) v.getParent();
        return v;
    }

    private static void settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550)); }

    @Test public void chatProjectMenuAndHistoryActionsUseTheSameSheet() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        host.setTheme(NativeAppearance.theme(host));
        NativeHome home = new NativeHome(host, () -> {});
        try {
            home.showChat("");
            button(home.root, "选择项目").performClick();
            BottomSheetDialog project = sheet();
            View list = project.getWindow().getDecorView();
            View current = button(list, "默认工作区");
            assertTrue(current.createAccessibilityNodeInfo().isChecked());
            View next = button(list, "笔记");
            next.performClick();
            current.performClick();
            settle();
            assertEquals("notes", home.projectId);
            assertFalse(project.isShowing());
            home.nav("sessions");
            button(home.root, "管理会话").performClick();
            BottomSheetDialog history = sheet();
            assertEquals(project.getWindow().getAttributes().windowAnimations, history.getWindow().getAttributes().windowAnimations);
            assertFalse(button(history.getWindow().getDecorView(), "搜索 Claude 历史").createAccessibilityNodeInfo().isCheckable());
            history.cancel();
            settle();
            assertEquals("sessions", home.screen);
        } finally { home.dispose(); }
    }

    @Test public void formSelectionResolvesTheTappedItemAfterItsAdapterReorders() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(NativeAppearance.theme(host));
        PixelChoiceField field = new PixelChoiceField(host, "传输协议");
        host.setContentView(field);
        field.setAdapter(new ArrayAdapter<>(host, android.R.layout.simple_spinner_item, Arrays.asList("stdio", "http", "sse")));
        field.performClick();
        BottomSheetDialog dialog = sheet();
        View http = button(dialog.getWindow().getDecorView(), "http");
        field.setAdapter(new ArrayAdapter<>(host, android.R.layout.simple_spinner_item, Arrays.asList("sse", "stdio", "http")));
        http.performClick();
        settle();
        assertEquals("http", field.getSelectedItem());
        assertEquals(2, field.getSelectedItemPosition());
        field.performClick();
        BottomSheetDialog cancel = sheet();
        cancel.cancel();
        settle();
        assertEquals("http", field.getSelectedItem());
    }

    @Test public void choiceMovesBeforeDismissalAndBackDuringFeedbackCancelsThePendingWrite() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(NativeAppearance.theme(host));
        int[] commits = {0};
        BottomSheetDialog dialog = PixelChoiceSheet.show(host, "选择项目", Arrays.asList("项目一", "项目二", "项目三"),
                0, index -> commits[0]++, null);
        sheet();
        View root = dialog.getWindow().getDecorView();
        View first = button(root, "项目一");
        View third = button(root, "项目三");
        ListView list = (ListView) first.getParent();
        Bitmap image = Bitmap.createBitmap(list.getWidth(), list.getHeight(), Bitmap.Config.ARGB_8888);
        list.draw(new Canvas(image));
        image.recycle();
        PixelSelectionIndicator indicator = ReflectionHelpers.getField(list, "highlight");
        assertEquals(first.getTop(), indicator.current.top, .1f);
        // Let Android advance the shared indicator on actual vsync ticks instead of fast-forwarding to dismissal.
        boolean paused = ShadowChoreographer.isPaused();
        ShadowChoreographer.setPaused(true);
        try {
            third.performClick();
            for (int tick = 0; tick < 8; tick++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
            assertTrue(dialog.isShowing());
            assertTrue(indicator.current.top > first.getTop());
            assertTrue(indicator.current.top < third.getTop());
            assertTrue(third.createAccessibilityNodeInfo().isChecked());
            dialog.cancel();
            settle();
            assertEquals(0, commits[0]);
        } finally {
            ShadowChoreographer.setPaused(paused);
        }
    }

    @Test public void longCatalogRendersOnlyVisibleRowsAndOpensAtTheSavedChoice() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(NativeAppearance.theme(host));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 4096; i++) names.add("条目 " + i);
        int[] selected = {-1};
        BottomSheetDialog dialog = PixelChoiceSheet.show(host, "归档条目", names, 2100, i -> selected[0] = i, null);
        sheet();
        View current = button(dialog.getWindow().getDecorView(), "条目 2100");
        ListView list = (ListView) current.getParent();
        assertEquals(names.size(), list.getAdapter().getCount());
        assertTrue(list.getChildCount() < 100);
        assertTrue(current.createAccessibilityNodeInfo().isChecked());
        button(dialog.getWindow().getDecorView(), "条目 2101").performClick();
        settle();
        assertEquals(2101, selected[0]);
    }

    @Test public void lineageMergeKeepsMultipleDraftChoicesUntilConfirmed() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        host.setTheme(NativeAppearance.theme(host));
        NativeHome home = new NativeHome(host, () -> {});
        try {
            LinearLayout form = new LinearLayout(host);
            JSONArray nodes = new JSONArray().put(NativeHome.json("id", "1", "title", "甲"))
                    .put(NativeHome.json("id", "2", "title", "乙"));
            NativeLineagePanel.add(home, form, nodes, new JSONArray());
            Gateway.materials = 0;
            for (boolean apply : new boolean[]{false, true}) {
                button(form, "汇合会话").performClick();
                BottomSheetDialog merge = sheet();
                View root = merge.getWindow().getDecorView();
                assertFalse(((CheckBox) button(root, "甲")).isChecked());
                button(root, "甲").performClick();
                button(root, "乙").performClick();
                assertFalse(button(root, "压缩").isEnabled());
                assertTrue(merge.isShowing());
                assertEquals(0, Gateway.materials);
                button(root, apply ? "提取材料" : "关闭").performClick();
                settle();
                assertEquals(apply ? 2 : 0, Gateway.materials);
            }
        } finally { home.dispose(); }
    }
}
