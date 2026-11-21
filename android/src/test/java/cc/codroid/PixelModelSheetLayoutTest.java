package cc.codroid;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "zh-rCN-w412dp-h891dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PixelModelSheetLayoutTest {
    private final List<String> accounts = Arrays.asList("Claude", "ChatGPT", "Google", "端侧小模型（本机）");
    private final AtomicInteger preview = new AtomicInteger(-1);
    private final AtomicInteger saves = new AtomicInteger();
    private PixelModelSheet sheet;
    private BottomSheetDialog dialog;
    private View root;
    private View surface;

    private void open() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        host.setTheme(NativeAppearance.theme(host));
        sheet = new PixelModelSheet(host, host, () -> {}, () -> {});
        sheet.present();
        dialog = (BottomSheetDialog) ShadowDialog.getLatestDialog();
        root = dialog.findViewById(android.R.id.content);
        surface = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        select(0);
    }

    private void select(int account) {
        boolean samePreview = preview.getAndSet(account) == account;
        sheet.accounts(accounts, account, samePreview, this::select);
        sheet.models(Collections.emptyList(), -1, i -> {});
        sheet.message("正在读取模型…");
    }

    private void models(int count) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) labels.add("Model " + i);
        sheet.message("");
        sheet.models(labels, 0, i -> saves.incrementAndGet());
    }

    private void layout(int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private TextView label(String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        }
        throw new AssertionError("Missing label " + text);
    }

    private Rect bounds(View v) {
        Rect bounds = new Rect(0, 0, v.getWidth(), v.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(v, bounds);
        return bounds;
    }

    private <T extends View> T ancestor(View child, Class<T> type) {
        while (!type.isInstance(child)) child = (View) child.getParent();
        return type.cast(child);
    }

    @Test public void providerPreviewsKeepSurfaceAndTabsAnchored() {
        open();
        int width = 360, height = 800;
        models(28);
        layout(width, height);
        Rect initialSheet = bounds(surface);
        Rect initialHeader = bounds(label("切换模型"));
        TextView local = label(accounts.get(3));
        HorizontalScrollView tabs = ancestor(local, HorizontalScrollView.class);
        tabs.scrollTo(tabs.getChildAt(0).getWidth(), 0);
        Rect initialLocal = bounds(local);
        int horizontalScroll = tabs.getScrollX();
        assertTrue(horizontalScroll > 0);
        NestedScrollView list = ancestor(label("Model 0"), NestedScrollView.class);
        assertTrue(list.canScrollVertically(1));
        list.scrollTo(0, list.getChildAt(0).getHeight());
        assertTrue(list.getScrollY() > 0);
        for (int provider : Arrays.asList(1, 2, 3, 0)) {
            label(accounts.get(provider)).performClick();
            assertEquals(provider, preview.get());
            layout(width, height);
            assertEquals(initialSheet, bounds(surface));
            assertEquals(initialHeader, bounds(label("切换模型")));
            assertSame(local, label(accounts.get(3)));
            assertEquals(initialLocal, bounds(local));
            assertEquals(horizontalScroll, tabs.getScrollX());
            assertEquals(0, list.getScrollY());
            int count = provider == 3 ? 1 : provider == 2 ? 5 : 28;
            models(count);
            layout(width, height);
            assertEquals(initialSheet, bounds(surface));
            assertEquals(initialLocal, bounds(local));
            assertEquals(count == 28, list.canScrollVertically(1));
        }
        list.scrollTo(0, list.getChildAt(0).getHeight());
        int previousScroll = list.getScrollY();
        List<String> updatedAccounts = new ArrayList<>(accounts);
        updatedAccounts.add("DeepSeek");
        sheet.accounts(updatedAccounts, 0, true, this::select);
        models(28);
        layout(width, height);
        assertEquals(previousScroll, list.getScrollY());
        local = label(accounts.get(3));
        initialLocal = bounds(local);
        sheet.busy(true);
        sheet.message("正在保存模型…");
        layout(width, height);
        assertEquals(initialLocal, bounds(local));
        local.performClick();
        View firstModel = (View) label("Model 0").getParent();
        firstModel.performClick();
        assertEquals(0, saves.get());
        assertEquals(0, preview.get());
        sheet.busy(false);
        firstModel.performClick();
        assertEquals(1, saves.get());
    }

    @Test public void rotationAndLargeTypeKeepScrollableStatusAndModelsBelowTheTabs() {
        RuntimeEnvironment.setFontScale(1.8f);
        open();
        models(28);
        for (int[] size : new int[][]{{320, 700}, {820, 350}, {1000, 880}}) {
            layout(size[0], size[1]);
            Rect panel = bounds(surface);
            Rect tabs = bounds(label("Google"));
            NestedScrollView list = ancestor(label("Model 0"), NestedScrollView.class);
            assertTrue(panel.width() > 0 && panel.width() <= Math.min(size[0], 560));
            assertTrue(panel.height() > size[1] / 2 && panel.height() <= size[1]);
            assertTrue(list.getHeight() > 0);
            assertTrue(bounds(list).top >= tabs.bottom);
            assertTrue(list.canScrollVertically(1));
            sheet.message(String.join(" ", Collections.nCopies(60, "无法读取接口")));
            layout(size[0], size[1]);
            assertEquals(panel, bounds(surface));
            assertEquals(tabs, bounds(label("Google")));
            assertTrue(list.getHeight() > 0);
            assertTrue(list.canScrollVertically(1));
            sheet.message("");
        }
    }
}
