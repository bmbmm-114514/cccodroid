package cc.codroid;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.bottomsheet.BottomSheetDragHandleView;
import java.util.List;
import java.util.function.IntConsumer;

final class PixelChoiceSheet {

    private PixelChoiceSheet() {}

    // Measure against the dialog's current constraints so rotation and split-screen resize the sheet.
    static LinearLayout content(Context ui) {
        LinearLayout layout = new LinearLayout(ui) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = MeasureSpec.getSize(heightSpec);
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    float fraction = height < PixelUi.dp(ui, 450) ? .92f : .8f;
                    heightSpec = MeasureSpec.makeMeasureSpec((int) (height * fraction), MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(new BottomSheetDragHandleView(ui));
        return layout;
    }

    static void configure(BottomSheetDialog dialog, Context ui, int maxWidthDp) {
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setMaxWidth(PixelUi.dp(ui, maxWidthDp));
        behavior.setShouldRemoveExpandedCorners(false);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    static BottomSheetDialog show(Context ui, String title, List<String> labels, int checked,
                                  IntConsumer choose, Runnable addCustom) {
        BottomSheetDialog dialog = new BottomSheetDialog(ui);
        dialog.setDismissWithAnimation(true);

        LinearLayout root = content(ui);
        final Runnable[] pending = new Runnable[1];
        dialog.setOnDismissListener(ignored -> {
            if (pending[0] != null) pending[0].run();
        });

        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(PixelUi.dp(ui, 48));
        header.setPadding(PixelUi.dp(ui, 20), PixelUi.dp(ui, 4), PixelUi.dp(ui, 8), PixelUi.dp(ui, 4));

        TextView titleTv = PixelUi.label(ui, title, 24, "onSurface");
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(titleTv);
        header.addView(PixelUi.icon(ui, "close", "关闭", dialog::cancel));
        root.addView(header);

        NestedScrollView scroll = new NestedScrollView(ui);
        LinearLayout list = new LinearLayout(ui);
        list.setOrientation(LinearLayout.VERTICAL);

        if (labels != null) {
            int rowMinH = PixelUi.dp(ui, 52);
            int rowPadH = PixelUi.dp(ui, 20);
            for (int i = 0; i < labels.size(); i++) {
                final int idx = i;
                final boolean sel = (i == checked);
                String label = labels.get(i);

                LinearLayout row = new LinearLayout(ui);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(rowMinH);
                row.setPadding(rowPadH, 0, rowPadH, 0);
                row.setBackground(PixelUi.clickable(ui, sel ? "secondaryContainer" : null, 16));
                row.setFocusable(true);

                MaterialRadioButton rb = new MaterialRadioButton(ui);
                rb.setChecked(sel);
                rb.setClickable(false);
                rb.setFocusable(false);
                rb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(rb);

                TextView text = PixelUi.label(ui, label, 16, "onSurface");
                text.setPadding(PixelUi.dp(ui, 12), 0, 0, 0);
                row.addView(text);

                ProviderSettingsDialog.markChoice(row, label, sel);
                row.setOnClickListener(v -> {
                    if (pending[0] != null) return;
                    pending[0] = () -> choose.accept(idx);
                    dialog.cancel();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(PixelUi.dp(ui, 12), 0, PixelUi.dp(ui, 12), PixelUi.dp(ui, 2));
                list.addView(row, lp);
            }
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
                if (pending[0] != null) return;
                pending[0] = addCustom;
                dialog.cancel();
            });
            root.addView(addRow);
        }

        dialog.setContentView(root);

        configure(dialog, ui, 600);

        dialog.show();
        return dialog;
    }
}
