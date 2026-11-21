package cc.codroid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.bottomsheet.BottomSheetDragHandleView;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

final class PixelChoiceSheet {

    private PixelChoiceSheet() {}

    // Measure against the dialog's current constraints so rotation and split-screen resize the sheet.
    static LinearLayout content(Context ui, boolean fixedHeight) {
        LinearLayout layout = new LinearLayout(ui) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                if (mode != MeasureSpec.UNSPECIFIED) {
                    int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
                    float ratio = parentHeight < PixelUi.dp(ui, 450) ? 0.92f : 0.80f;
                    int targetHeight = (int) (parentHeight * ratio);
                    int targetMode = fixedHeight ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(targetHeight, targetMode);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(new BottomSheetDragHandleView(ui));
        return layout;
    }

    private static final class SheetDialog extends BottomSheetDialog {
        boolean closing;

        SheetDialog(Context ui) {
            super(ui);
            setDismissWithAnimation(true);
        }

        @Override public void cancel() {
            closing = true;
            View sheet = findViewById(com.google.android.material.R.id.design_bottom_sheet);
            // A hidden-state animation cannot finish before the first layout.
            if (sheet == null || !sheet.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
                setDismissWithAnimation(false);
            }
            super.cancel();
        }
    }

    static BottomSheetDialog createDialog(Context ui) { return new SheetDialog(ui); }

    static void configure(BottomSheetDialog dialog, Context ui, int maxWidthDp) {
        dialog.getWindow().setWindowAnimations(cc.agentui.R.style.PixelModelSheetWindowMotion);
        View surface = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        surface.setBackgroundTintList(ColorStateList.valueOf(PixelUi.color(ui, "surfaceContainerLow") | 0xff000000));
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setMaxWidth(PixelUi.dp(ui, maxWidthDp));
        behavior.setShouldRemoveExpandedCorners(false);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    static BottomSheetDialog show(Context ui, String title, List<String> labels, int checked,
                                  IntConsumer choose, Runnable addCustom) {
        return show(ui, title, labels, checked, choose, addCustom, true, i -> true);
    }

    static BottomSheetDialog actions(Context ui, String title, List<String> labels, IntConsumer choose) {
        return show(ui, title, labels, -1, choose, null, false, i -> true);
    }

    static BottomSheetDialog actions(Context ui, String title, List<String> labels, IntConsumer choose,
                                     IntPredicate enabled) {
        return show(ui, title, labels, -1, choose, null, false, enabled);
    }

    static BottomSheetDialog field(Context ui, String title, List<String> labels, int checked,
                                   IntConsumer choose, IntPredicate enabled) {
        return show(ui, title, labels, checked, choose, null, true, enabled);
    }

    private static BottomSheetDialog show(Context ui, String title, List<String> labels, int checked,
                                          IntConsumer choose, Runnable addCustom, boolean singleChoice, IntPredicate enabled) {
        BottomSheetDialog dialog = createDialog(ui);
        LinearLayout root = content(ui, false);
        Selection selection = new Selection(dialog);
        dialog.setOnDismissListener(ignored -> selection.dismissed());

        addHeader(root, dialog, title);

        List<String> choices = labels == null ? Collections.emptyList() : labels;
        ChoiceList list = new ChoiceList(ui, choices, checked, singleChoice, enabled, choose, selection);
        root.addView(list, new LinearLayout.LayoutParams(-1, -2, 1f));

        if (addCustom != null) {
            LinearLayout addRow = new LinearLayout(ui);
            addRow.setOrientation(LinearLayout.HORIZONTAL);
            addRow.setGravity(Gravity.CENTER_VERTICAL);
            addRow.setMinimumHeight(PixelUi.dp(ui, 52));
            addRow.setPadding(PixelUi.dp(ui, 20), 0, PixelUi.dp(ui, 20), 0);
            addRow.setBackground(PixelUi.clickable(ui, null, 0));

            View addIcon = PixelUi.icon(ui, "add", "+", null);
            addIcon.setFocusable(false);
            addIcon.setClickable(false);
            addIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addRow.addView(addIcon);

            TextView addTv = PixelUi.label(ui, "添加自定义接口", 16, "primary");
            addTv.setPadding(PixelUi.dp(ui, 12), 0, 0, 0);
            addRow.addView(addTv);

            addRow.setOnClickListener(v -> {
                selection.pick(addCustom, v, false);
            });
            root.addView(addRow);
        }

        dialog.setContentView(root);

        configure(dialog, ui, 600);

        dialog.show();
        if (checked >= 0) list.post(() -> {
            if (checked > list.getLastVisiblePosition()) list.setSelection(checked);
        });
        return dialog;
    }

    private static void addHeader(LinearLayout root, BottomSheetDialog dialog, String title) {
        Context ui = root.getContext();
        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(PixelUi.dp(ui, 48));
        header.setPadding(PixelUi.dp(ui, 20), PixelUi.dp(ui, 4), PixelUi.dp(ui, 8), PixelUi.dp(ui, 4));

        TextView titleTv = PixelUi.label(ui, title, 24, "onSurface");
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleTv, true);
        header.addView(titleTv);
        header.addView(PixelUi.icon(ui, "close", "关闭", dialog::cancel));
        root.addView(header);
    }

    /** Form checkboxes hold a draft. Only the explicit confirm control applies it. */
    static BottomSheetDialog confirm(Context ui, String title, View rows, String confirm, Runnable apply) {
        BottomSheetDialog dialog = createDialog(ui);
        LinearLayout root = content(ui, false);
        addHeader(root, dialog, title);
        NestedScrollView scroll = new NestedScrollView(ui);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        Selection selection = new Selection(dialog);
        dialog.setOnDismissListener(ignored -> selection.dismissed());
        View done = PixelUi.button(ui, confirm, () -> selection.pick(apply, root, false));
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, -2);
        button.gravity = Gravity.END;
        button.setMargins(PixelUi.dp(ui, 16), PixelUi.dp(ui, 4), PixelUi.dp(ui, 16), PixelUi.dp(ui, 12));
        root.addView(done, button);
        dialog.setContentView(root);
        configure(dialog, ui, 600);
        dialog.show();
        return dialog;
    }

    /** Archive entries and history can contain thousands of labels. Only visible rows own views. */
    private static final class ChoiceList extends ListView {
        private final PixelSelectionIndicator highlight;
        private final Rect lastBounds = new Rect();
        private int selected;

        ChoiceList(Context ui, List<String> labels, int checked, boolean singleChoice,
                   IntPredicate enabled, IntConsumer choose, Selection selection) {
            super(ui);
            selected = checked;
            highlight = new PixelSelectionIndicator(this, PixelUi.color(ui, "primaryContainer"), PixelUi.dp(ui, 16));
            setSelector(new ColorDrawable(Color.TRANSPARENT));
            setDivider(new ColorDrawable(Color.TRANSPARENT));
            setDividerHeight(PixelUi.dp(ui, 2));
            setNestedScrollingEnabled(true);
            setItemsCanFocus(true);
            setOnItemClickListener((parent, row, index, id) -> row.performClick());
            setVerticalScrollBarEnabled(false);
            int edge = PixelUi.dp(ui, 12);
            setPadding(edge, PixelUi.dp(ui, 4), edge, PixelUi.dp(ui, 8));
            setAdapter(new BaseAdapter() {
                @Override public int getCount() { return labels.size(); }
                @Override public String getItem(int position) { return labels.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int position) { return enabled.test(position); }

                @Override public View getView(int index, View reused, ViewGroup parent) {
                    ChoiceRow row = reused == null ? new ChoiceRow(ui, singleChoice) : (ChoiceRow) reused;
                    row.text.setText(getItem(index));
                    row.checked(index == selected);
                    row.setOnClickListener(v -> {
                        if (selection.tapped || !isEnabled(index)) return;
                        ChoiceRow previous = visibleRow(selected);
                        if (previous != null) previous.checked(false);
                        int oldIndex = selected;
                        selected = index;
                        row.checked(true);
                        place(row, singleChoice);
                        selection.pick(() -> choose.accept(index), row, singleChoice && index != oldIndex);
                    });
                    row.setEnabled(isEnabled(index));
                    row.setAlpha(row.isEnabled() ? 1f : .38f);
                    return row;
                }
            });
        }

        private ChoiceRow visibleRow(int index) {
            return (ChoiceRow) getChildAt(index - getFirstVisiblePosition());
        }

        private void place(View row, boolean animate) {
            if (row == null) {
                if (lastBounds.isEmpty()) return;
                lastBounds.setEmpty();
            } else {
                if (lastBounds.left == row.getLeft() && lastBounds.top == row.getTop()
                        && lastBounds.right == row.getRight() && lastBounds.bottom == row.getBottom()) return;
                lastBounds.set(row.getLeft(), row.getTop(), row.getRight(), row.getBottom());
            }
            highlight.moveTo(lastBounds.left, lastBounds.top, lastBounds.right, lastBounds.bottom, animate);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            place(visibleRow(selected), false);
            int saved = canvas.save();
            canvas.clipRect(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
            highlight.draw(canvas);
            canvas.restoreToCount(saved);
            super.dispatchDraw(canvas);
        }

        @Override protected void onDetachedFromWindow() {
            place(null, false);
            super.onDetachedFromWindow();
        }
    }

    private static final class ChoiceRow extends LinearLayout {
        private final TextView text;
        private final MaterialRadioButton radio;

        ChoiceRow(Context ui, boolean singleChoice) {
            super(ui);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(PixelUi.dp(ui, 52));
            setPaddingRelative(PixelUi.dp(ui, 12), PixelUi.dp(ui, 6), PixelUi.dp(ui, 16), PixelUi.dp(ui, 6));
            setBackground(PixelUi.clickable(ui, null, 16));
            setFocusable(true);
            radio = singleChoice ? new MaterialRadioButton(ui) : null;
            if (radio != null) {
                radio.setFocusable(false);
                radio.setClickable(false);
                radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(radio);
            }
            text = PixelUi.label(ui, "", 16, "onSurface");
            text.setPaddingRelative(PixelUi.dp(ui, 8), 0, 0, 0);
            addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        }

        void checked(boolean checked) {
            text.setTextColor(PixelUi.color(getContext(), checked ? "onPrimaryContainer" : "onSurface"));
            if (radio != null) {
                radio.setChecked(checked);
                ProviderSettingsDialog.markChoice(this, text.getText().toString(), checked);
            } else {
                setContentDescription(text.getText());
            }
        }
    }

    private static final class Selection {
        private final BottomSheetDialog dialog;
        private boolean tapped;
        private Runnable committed;
        private ValueAnimator feedback;

        Selection(BottomSheetDialog dialog) { this.dialog = dialog; }

        void pick(Runnable action, View row, boolean animate) {
            if (tapped) return;
            tapped = true;
            Runnable close = () -> {
                if (!dialog.isShowing() || ((SheetDialog) dialog).closing) return;
                committed = action;
                dialog.cancel();
            };
            if (animate && row.isLaidOut() && ValueAnimator.areAnimatorsEnabled()) {
                // Give the moving selection a visible frame before sliding the sheet away.
                feedback = ValueAnimator.ofFloat(0f, 1f);
                feedback.setDuration(150);
                feedback.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) { close.run(); }
                });
                feedback.start();
            } else {
                close.run();
            }
        }

        void dismissed() {
            if (feedback != null) {
                feedback.removeAllListeners();
                feedback.cancel();
            }
            if (committed != null) {
                Runnable action = committed;
                committed = null;
                action.run();
            }
        }
    }
}
