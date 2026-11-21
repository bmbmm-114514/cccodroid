package cc.codroid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
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
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.robolectric.Shadows.shadowOf;
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
        sheet.pendingModels();
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
            int count = provider == 3 ? 1 : provider == 2 ? 5 : 28;
            models(count);
            layout(width, height);
            assertEquals(0, list.getScrollY());
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

    @Test public void slowPreviewPreservesRowsAndScrollAndDiscardsStaleTaps() {
        open();
        models(28);
        layout(360, 800);
        View old = (View) label("Model 15").getParent();
        NestedScrollView list = ancestor(old, NestedScrollView.class);
        list.scrollTo(0, 240);
        label("Google").performClick();
        assertSame(old, label("Model 15").getParent());
        assertEquals(240, list.getScrollY());
        assertFalse(old.isEnabled());
        old.performClick();
        assertEquals(0, saves.get());
        ProgressBar progress = ReflectionHelpers.getField(sheet, "progressBar");
        assertEquals(View.INVISIBLE, progress.getVisibility());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(View.VISIBLE, progress.getVisibility());
        models(2);
        assertEquals(0, list.getScrollY());
        assertEquals(View.INVISIBLE, progress.getVisibility());
        assertNull(old.getParent());
        old.performClick();
        assertEquals(0, saves.get());
        ((View) label("Model 1").getParent()).performClick();
        assertEquals(1, saves.get());

        label("Claude").performClick();
        models(28);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(View.INVISIBLE, progress.getVisibility());
        label("Google").performClick();
        sheet.dismiss();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(View.INVISIBLE, progress.getVisibility());
    }

    @Test public void highlightRetargetsFromItsCurrentFrameAndModelsSlideOnAnOpaqueSurface() {
        open();
        shadowOf(Looper.getMainLooper()).idle();
        models(3);
        layout(360, 800);
        PixelSelectionStrip strip = ancestor(label("Claude"), PixelSelectionStrip.class);
        RectF highlight = ((PixelSelectionIndicator) ReflectionHelpers.getField(strip, "indicator")).current;
        float from = highlight.left;
        label("Google").performClick();
        ValueAnimator forward = ReflectionHelpers.getField(ReflectionHelpers.getField(strip, "indicator"), "motion");
        assertNotNull(forward);
        forward.setCurrentFraction(.3f);
        float mid = highlight.left;
        assertTrue(from < mid && mid < label("Google").getLeft());

        label("ChatGPT").performClick();
        ValueAnimator redirected = ReflectionHelpers.getField(ReflectionHelpers.getField(strip, "indicator"), "motion");
        assertNotSame(redirected, forward);
        assertEquals(mid, highlight.left, .01f);
        redirected.end();
        assertEquals(label("ChatGPT").getLeft(), highlight.left, .01f);
        models(2);
        ValueAnimator slide = ReflectionHelpers.getField(sheet, "modelMotion");
        assertNotNull(slide);
        View rows = (View) label("Model 0").getParent().getParent();
        float firstOffset = rows.getTranslationX();
        assertTrue(firstOffset > 0);
        slide.setCurrentFraction(.35f);
        assertTrue(rows.getTranslationX() > 0 && rows.getTranslationX() < firstOffset);
        assertEquals(1f, rows.getAlpha(), 0);
        assertEquals(255, android.graphics.Color.alpha(surface.getBackgroundTintList().getDefaultColor()));
        slide.end();
        assertEquals(0f, rows.getTranslationX(), .01f);
        label("Claude").performClick();
        models(3);
        assertTrue(rows.getTranslationX() < 0);
    }

    @Test public void sheetWindowEntersByTranslationAtFullOpacity() {
        open();
        int style = dialog.getWindow().getAttributes().windowAnimations;
        TypedArray attrs = dialog.getContext().obtainStyledAttributes(style,
                new int[]{android.R.attr.windowEnterAnimation});
        int resource = attrs.getResourceId(0, 0);
        attrs.recycle();
        assertTrue(resource != 0);
        Animation enter = AnimationUtils.loadAnimation(dialog.getContext(), resource);
        enter.initialize(360, 800, 360, 800);
        enter.setStartTime(0L);
        float[] matrix = new float[9];
        float previous = Float.POSITIVE_INFINITY;
        for (long time : new long[]{0L, enter.getDuration() / 2, enter.getDuration()}) {
            Transformation frame = new Transformation();
            enter.getTransformation(time, frame);
            assertEquals(1f, frame.getAlpha(), 0f);
            frame.getMatrix().getValues(matrix);
            assertTrue(matrix[Matrix.MTRANS_Y] < previous);
            previous = matrix[Matrix.MTRANS_Y];
        }
        assertEquals(0f, previous, .01f);
    }

    @Test public void zeroAnimationScaleImmediatelySettlesBothSelections() {
        float previousScale = ValueAnimator.getDurationScale();
        ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, 0f));
        try {
            open();
            shadowOf(Looper.getMainLooper()).idle();
            models(3);
            layout(360, 800);
            PixelSelectionStrip strip = ancestor(label("Claude"), PixelSelectionStrip.class);
            label("Google").performClick();
            models(6);
            assertNull(ReflectionHelpers.getField(ReflectionHelpers.getField(strip, "indicator"), "motion"));
            assertNull(ReflectionHelpers.getField(sheet, "modelMotion"));
            RectF rect = ((PixelSelectionIndicator) ReflectionHelpers.getField(strip, "indicator")).current;
            assertEquals(label("Google").getLeft(), rect.left, .01f);
            assertEquals(0f, ((View) label("Model 5").getParent().getParent()).getTranslationX(), 0);
        } finally {
            ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                    ReflectionHelpers.ClassParameter.from(float.class, previousScale));
        }
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
