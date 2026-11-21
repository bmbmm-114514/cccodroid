package cc.codroid;

import android.app.Activity;
import android.window.BackEvent;
import android.widget.TextView;
import android.os.Looper;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.time.Duration;
import static org.robolectric.Shadows.shadowOf;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowWindowManagerGlobal;
import org.robolectric.shadows.ShadowWindowManagerGlobal.PredictiveBackGesture;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class PixelBackDispatcherTest {
    private static class Gesture implements PixelBack.Handler {
        boolean fromRight;
        float progress;
        int started, cancelled, committed;

        @Override public void start(boolean fromRight) { started++; this.fromRight = fromRight; }
        @Override public void progress(float fraction) { progress = fraction; }
        @Override public void cancel() { cancelled++; }
        @Override public void commit() { committed++; }
    }

    @Test public void platformGestureCanCancelThenCommitFromTheOtherEdge() {
        ShadowApplication.setEnableOnBackInvokedCallback(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.setup().get();
            Gesture events = new Gesture();
            Runnable detach = PixelBack.attach(activity, events);

            try (PredictiveBackGesture left = ShadowWindowManagerGlobal.startPredictiveBackGesture(BackEvent.EDGE_LEFT)) {
                assertNotNull(left);
                left.moveBy(160f, 0f);
                assertFalse(events.fromRight);
                assertTrue(events.progress > 0f);
                assertEquals(0, events.committed);
                left.cancel();
            }
            assertEquals(1, events.cancelled);
            assertEquals(0, events.committed);

            try (PredictiveBackGesture right = ShadowWindowManagerGlobal.startPredictiveBackGesture(BackEvent.EDGE_RIGHT)) {
                assertNotNull(right);
                right.moveBy(-160f, 0f);
                assertTrue(events.fromRight);
            }
            assertEquals(2, events.started);
            assertEquals(1, events.committed);
            detach.run();
            assertNull(ShadowWindowManagerGlobal.startPredictiveBackGesture(BackEvent.EDGE_LEFT));
        }
    }

    @Test public void openSheetOwnsBackBeforeTheUnderlyingPage() {
        ShadowApplication.setEnableOnBackInvokedCallback(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.setup().get();
            activity.setTheme(NativeAppearance.theme(activity));
            Gesture page = new Gesture();
            Runnable detach = PixelBack.attach(activity, page);
            BottomSheetDialog sheet = new BottomSheetDialog(activity);
            sheet.setContentView(new TextView(activity));
            sheet.show();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            try (PredictiveBackGesture gesture = ShadowWindowManagerGlobal.startPredictiveBackGesture(BackEvent.EDGE_LEFT)) {
                assertNotNull(gesture);
                gesture.moveBy(150f, 0f);
                gesture.cancel();
            }
            assertTrue(sheet.isShowing());
            try (PredictiveBackGesture gesture = ShadowWindowManagerGlobal.startPredictiveBackGesture(BackEvent.EDGE_RIGHT)) {
                assertNotNull(gesture);
                gesture.moveBy(-150f, 0f);
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertFalse(sheet.isShowing());
            assertEquals(0, page.started);
            assertEquals(0, page.committed);
            detach.run();
        }
    }
}
