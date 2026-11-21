package cc.codroid;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Earlier Android releases need a ColorStateList resource for both Material navigation bars. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NativeNavigationThemeTest {
    private void verifyNavigationTheme() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Context themed = new ContextThemeWrapper(activity, NativeAppearance.theme(activity));
        TypedValue secondary = new TypedValue();
        assertTrue(themed.getTheme().resolveAttribute(android.R.attr.textColorSecondary, secondary, true));
        assertNotEquals(0, secondary.resourceId);
        ColorStateList labels = themed.getColorStateList(secondary.resourceId);
        assertNotNull(labels);
        assertNotNull(new BottomNavigationView(themed).getMenu());
        assertNotNull(new NavigationRailView(themed).getMenu());
        int ink = PixelUi.color(themed, "onSurface");
        int background = PixelUi.color(themed, "surface");
        assertTrue(ColorUtils.calculateContrast(ink, background) >= 4.5);
    }

    @Test public void daylightNavigation() { verifyNavigationTheme(); }
    @Test @Config(qualifiers = "night") public void nightNavigation() { verifyNavigationTheme(); }
}
