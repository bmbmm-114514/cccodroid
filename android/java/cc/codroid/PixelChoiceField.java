package cc.codroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keeps Spinner adapter and selected-item semantics while opening the common choice sheet. */
final class PixelChoiceField extends Spinner {
    private final String label;
    private final Drawable arrow;
    private WeakReference<BottomSheetDialog> picker = new WeakReference<>(null);

    PixelChoiceField(Context context, String label) {
        super(context, Spinner.MODE_DIALOG);
        this.label = label;
        arrow = PixelUi.drawable(context, "expand");
        if (arrow != null) arrow.setTint(PixelUi.color(context, "onSurfaceVariant"));
        setBackgroundTintList(null);
        setBackground(PixelUi.clickable(context, "surfaceContainerHigh", 14));
        setMinimumHeight(PixelUi.dp(context, 48));
        setPaddingRelative(PixelUi.dp(context, 12), PixelUi.dp(context, 4),
                PixelUi.dp(context, 40), PixelUi.dp(context, 4));
    }

    @Override public boolean performClick() {
        SpinnerAdapter adapter = getAdapter();
        if (!isEnabled() || adapter == null || adapter.getCount() == 0) return false;
        BottomSheetDialog open = picker.get();
        if (open != null && open.isShowing()) return true;
        List<Object> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) {
            Object value = adapter.getItem(i);
            values.add(value);
            names.add(value == null ? "" : value.toString());
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        playSoundEffect(SoundEffectConstants.CLICK);
        BottomSheetDialog dialog = PixelChoiceSheet.field(getContext(), label, names, getSelectedItemPosition(), index -> {
            SpinnerAdapter live = getAdapter();
            if (live == null || !isEnabled() || !isAttachedToWindow()) return;
            // An asynchronously populated form may replace or reorder the available options.
            Object chosen = values.get(index);
            int position = index;
            if (position >= live.getCount() || !Objects.equals(chosen, live.getItem(position))) {
                position = 0;
                while (position < live.getCount() && !Objects.equals(chosen, live.getItem(position))) position++;
            }
            if (position < live.getCount() && enabled(live, position)) setSelection(position);
        }, index -> enabled(adapter, index));
        picker = new WeakReference<>(dialog);
        return true;
    }

    private static boolean enabled(SpinnerAdapter adapter, int pos) {
        return !(adapter instanceof ListAdapter) || ((ListAdapter) adapter).isEnabled(pos);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (arrow == null) return;
        int size = PixelUi.dp(getContext(), 20);
        int inset = PixelUi.dp(getContext(), 12);
        int x = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? inset : getWidth() - inset - size;
        int y = (getHeight() - size) / 2;
        arrow.setBounds(x, y, x + size, y + size);
        arrow.draw(canvas);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        Object value = getSelectedItem();
        info.setContentDescription(value == null ? label : label + "，" + value);
    }

    @Override protected void onDetachedFromWindow() {
        BottomSheetDialog open = picker.get();
        if (open != null && open.isShowing()) open.dismiss();
        picker.clear();
        super.onDetachedFromWindow();
    }

    static MaterialCheckBox checkbox(Context context) {
        MaterialCheckBox box = new MaterialCheckBox(context);
        box.setMinHeight(PixelUi.dp(context, 48));
        box.setPaddingRelative(PixelUi.dp(context, 8), PixelUi.dp(context, 4),
                PixelUi.dp(context, 12), PixelUi.dp(context, 4));
        StateListDrawable state = new StateListDrawable();
        if (ValueAnimator.areAnimatorsEnabled()) {
            state.setEnterFadeDuration(140);
            state.setExitFadeDuration(140);
        }
        state.addState(new int[]{android.R.attr.state_checked}, PixelUi.shape(context, "primaryContainer", 16));
        state.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        int ink = ColorUtils.setAlphaComponent(PixelUi.color(context, "onSurface"), 34);
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(ink), state, PixelUi.shape(context, "surface", 16)));
        return box;
    }
}
