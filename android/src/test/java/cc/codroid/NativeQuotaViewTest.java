package cc.codroid;

import android.app.Activity;
import android.widget.LinearLayout;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NativeQuotaViewTest {
    @Test public void unknownQuotaHasNoProgressTrackAndZeroIsARealReading() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(NativeAppearance.theme(activity));
        LinearLayout card = new LinearLayout(activity);
        for (double remaining : new double[]{Double.NaN, -1, 101, 0, 52.5, 100}) {
            card.removeAllViews();
            NativeStatusViews.quota(card, "每周", remaining, 0);
            LinearProgressIndicator progress = null;
            for (int i = 0; i < card.getChildCount(); i++) {
                if (card.getChildAt(i) instanceof LinearProgressIndicator) {
                    progress = (LinearProgressIndicator) card.getChildAt(i);
                }
            }
            if (remaining >= 0 && remaining <= 100) {
                assertNotNull(progress);
                assertEquals((int)Math.round(remaining * 10), progress.getProgress());
            } else {
                assertNull(progress);
            }
        }
    }
}
