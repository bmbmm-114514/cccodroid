package cc.codroid;

import android.app.Activity;
import android.os.Looper;
import android.widget.LinearLayout;
import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedDispatcher;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProviderChoiceMotionTest {
    private Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(NativeAppearance.theme(activity));
        return activity;
    }

    @Test public void cancellingNestedPickerKeepsPageAndBackPreviewCommitsOnlyAtTheEnd() {
        Activity activity = activity();
        PixelProviderPage page = new PixelProviderPage(activity, NativeAppearance.theme(activity));
        LinearLayout content = new LinearLayout(activity);
        page.setContentView(content);
        page.show();
        content.layout(0, 0, 320, 640);
        AtomicInteger writes = new AtomicInteger();
        BottomSheetDialog picker = PixelChoiceSheet.show(activity, "选择 API 接口",
                Arrays.asList("Claude API Key", "DeepSeek"), 1, index -> writes.incrementAndGet(), null);
        picker.cancel();
        settle();
        assertFalse(picker.isShowing());
        assertTrue(page.isShowing());
        assertEquals(0, writes.get());

        OnBackPressedDispatcher back = page.getOnBackPressedDispatcher();
        back.dispatchOnBackStarted(new BackEventCompat(0, 80, 0f, BackEventCompat.EDGE_LEFT));
        back.dispatchOnBackProgressed(new BackEventCompat(64, 80, .6f, BackEventCompat.EDGE_LEFT));
        assertTrue(content.getTranslationX() > 0);
        back.dispatchOnBackCancelled();
        settle();
        assertEquals(0f, content.getTranslationX(), .1f);
        assertTrue(page.isShowing());

        back.dispatchOnBackStarted(new BackEventCompat(320, 80, 0f, BackEventCompat.EDGE_RIGHT));
        back.dispatchOnBackProgressed(new BackEventCompat(240, 80, .8f, BackEventCompat.EDGE_RIGHT));
        assertTrue(content.getTranslationX() < 0);
        back.onBackPressed();
        settle();
        assertFalse(page.isShowing());
        assertEquals(0, writes.get());
    }

    private void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
    }
}
