package cc.codroid;

import android.animation.ValueAnimator;
import android.app.Activity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import java.util.List;
import java.util.Objects;

final class PixelModelSheet {
    private final Activity host;
    private final Context ui;
    private boolean saving;
    private int dp(float amount) { return Math.round(host.getResources().getDisplayMetrics().density * amount); }

    private final BottomSheetDialog dialog;

    private final LinearLayout sheetView;
    private final PixelSelectionStrip accountsContainer;
    private final LinearLayout modelsContainer;
    private final NestedScrollView modelScroll;
    private final TextView messageView;
    private final ProgressBar progressBar;

    private final TextView retryButton;
    private final Runnable showProgress = this::showSlowProgress;
    private boolean pending;
    private boolean slowRequest;
    private int renderedAccount = -1;
    private ValueAnimator modelMotion;

    private List<String> currentAccounts;
    private int selectedAccount = -1;
    private java.util.function.IntConsumer accountConsumer;

    private List<String> currentModels;
    private int selectedModel = -1;
    private java.util.function.IntConsumer modelConsumer;

    PixelModelSheet(Activity host, Context ui, Runnable settings, Runnable close) {
        this.host = host;
        this.ui = ui;

        dialog = PixelChoiceSheet.createDialog(ui);
        sheetView = PixelChoiceSheet.content(ui, true);

        LinearLayout headerBar = new LinearLayout(ui);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setPadding(dp(20), dp(4), dp(16), dp(4));

        TextView titleView = new TextView(ui);
        titleView.setText("切换模型");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setTextColor(PixelUi.color(ui, "onSurface"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        headerBar.addView(titleView, titleLp);

        TextView settingsBtn = new TextView(ui);
        settingsBtn.setText("接口与模型");
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        settingsBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        settingsBtn.setTextColor(PixelUi.color(ui, "primary"));
        settingsBtn.setGravity(Gravity.CENTER);
        settingsBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        settingsBtn.setMinimumHeight(dp(48));
        settingsBtn.setMinimumWidth(dp(48));
        settingsBtn.setBackground(createTonalRipple(Color.TRANSPARENT, PixelUi.color(ui, "primary"), dp(16)));
        settingsBtn.setOnClickListener(v -> {
            if (!saving && settings != null) {
                settings.run();
            }
        });
        headerBar.addView(settingsBtn);
        sheetView.addView(headerBar);

        HorizontalScrollView hScroll = new HorizontalScrollView(ui);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        accountsContainer = new PixelSelectionStrip(ui, PixelUi.color(ui, "surfaceContainerHigh"),
                PixelUi.color(ui, "primaryContainer"), dp(18));
        accountsContainer.setOrientation(LinearLayout.HORIZONTAL);
        accountsContainer.setPadding(dp(16), dp(4), dp(16), dp(8));
        hScroll.addView(accountsContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetView.addView(hScroll);

        View divider = new View(ui);
        divider.setBackgroundColor(PixelUi.color(ui, "outlineVariant"));
        sheetView.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        progressBar = new ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(PixelUi.color(ui, "primary")));
        progressBar.setContentDescription("正在获取模型列表");
        progressBar.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        sheetView.addView(progressBar, progressLp);

        modelScroll = new NestedScrollView(ui);
        modelScroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(ui);
        body.setOrientation(LinearLayout.VERTICAL);

        messageView = new TextView(ui);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        messageView.setTextColor(PixelUi.color(ui, "onSurfaceVariant"));
        messageView.setPadding(dp(20), dp(4), dp(20), dp(8));
        messageView.setVisibility(View.GONE);
        body.addView(messageView);
        retryButton = PixelUi.button(ui, "重试", () -> {});
        retryButton.setMinimumHeight(dp(48));
        retryButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.setMarginStart(dp(20));
        body.addView(retryButton, retryParams);

        modelsContainer = new LinearLayout(ui);
        modelsContainer.setOrientation(LinearLayout.VERTICAL);
        modelsContainer.setPadding(0, dp(4), 0, dp(12));
        body.addView(modelsContainer);
        modelScroll.addView(body);
        sheetView.addView(modelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(sheetView);
        PixelChoiceSheet.configure(dialog, ui, 560);
        ProviderSettingsDialog.watchWindow(dialog, () -> {
            stopUpdates();
            close.run();
        });
    }

    public void present() {
        if (!host.isFinishing() && !host.isDestroyed()) {
            dialog.show();
            sheetView.announceForAccessibility("切换模型面板已展开");
        }
    }

    void accounts(List<String> labels, int selected, boolean samePreview, java.util.function.IntConsumer choose) {
        boolean sameLabels = Objects.equals(currentAccounts, labels);
        int previous = selectedAccount;
        currentAccounts = labels;
        if (samePreview && previous >= 0 && renderedAccount == previous) renderedAccount = selected;
        selectedAccount = selected;
        accountConsumer = choose;
        if (sameLabels) {
            if (selected != previous) {
                styleAccount(previous);
                styleAccount(selected);
                accountsContainer.select(selected, true);
            }
        } else {
            rebuildAccounts();
        }
    }

    void models(List<String> labels, int selected, java.util.function.IntConsumer choose) {
        int previous = renderedAccount;
        boolean changedAccount = previous != selectedAccount;
        boolean sameRows = !changedAccount && selectedModel == selected && Objects.equals(currentModels, labels);
        setPending(false);
        currentModels = labels;
        selectedModel = selected;
        modelConsumer = choose;
        renderedAccount = selectedAccount;
        if (sameRows) return;
        rebuildModels();
        if (changedAccount) {
            modelScroll.scrollTo(0, 0);
            stopModelMotion();
            if (previous >= 0 && modelScroll.isLaidOut() && dialog.isShowing()
                    && ValueAnimator.areAnimatorsEnabled() && modelsContainer.getChildCount() > 0) {
                int direction = selectedAccount > previous ? 1 : -1;
                if (sheetView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
                float distance = dp(40) * direction;
                modelsContainer.setTranslationX(distance);
                modelMotion = ValueAnimator.ofFloat(distance, 0f);
                modelMotion.setDuration(240);
                modelMotion.setInterpolator(PixelSelectionIndicator.EASING);
                modelMotion.addUpdateListener(a -> modelsContainer.setTranslationX((float) a.getAnimatedValue()));
                modelMotion.start();
            }
        }
    }

    void pendingModels() {
        modelConsumer = null;
        message("");
        setPending(true);
    }

    private void setPending(boolean value) {
        sheetView.removeCallbacks(showProgress);
        if (pending != value) {
            modelsContainer.setImportantForAccessibility(value ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            for (int i = 0; i < modelsContainer.getChildCount(); i++) {
                modelsContainer.getChildAt(i).setEnabled(!value);
            }
        }
        pending = value;
        slowRequest = false;
        updateProgress();
        if (value) sheetView.postDelayed(showProgress, 350);
    }

    private void showSlowProgress() {
        if (pending && dialog.isShowing()) {
            slowRequest = true;
            updateProgress();
        }
    }

    private void updateProgress() {
        progressBar.setVisibility(saving || slowRequest ? View.VISIBLE : View.INVISIBLE);
    }

    private void stopModelMotion() {
        if (modelMotion != null) modelMotion.cancel();
        modelMotion = null;
        modelsContainer.setTranslationX(0f);
    }

    private void stopUpdates() {
        setPending(false);
        modelConsumer = null;
        accountConsumer = null;
        stopModelMotion();
    }

    void retry(String text, Runnable action) {
        message(text);
        retryButton.setVisibility(View.VISIBLE);
        retryButton.setOnClickListener(v -> {
            if (!saving && dialog.isShowing()) action.run();
        });
    }

    void message(String text) {
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(null);
        if (text == null || text.trim().isEmpty()) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(text);
            messageView.setVisibility(View.VISIBLE);
            modelScroll.scrollTo(0, 0);
            messageView.announceForAccessibility(text);
        }
    }

    void busy(boolean loading) {
        saving = loading;
        updateProgress();
        dialog.setCancelable(!loading);
        dialog.getBehavior().setDraggable(!loading);
    }

    public void dismiss() {
        stopUpdates();
        if (dialog.isShowing()) {
            dialog.setCancelable(true);
            dialog.cancel();
        }
    }

    private void rebuildAccounts() {
        accountsContainer.select(-1, false);
        accountsContainer.removeAllViews();
        if (currentAccounts == null || currentAccounts.isEmpty()) {
            ((View) accountsContainer.getParent()).setVisibility(View.GONE);
            return;
        }
        ((View) accountsContainer.getParent()).setVisibility(View.VISIBLE);

        for (int i = 0; i < currentAccounts.size(); i++) {
            final int index = i;
            final String label = currentAccounts.get(i);

            TextView chip = new TextView(ui);
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(1);
            chip.setPadding(dp(16), dp(6), dp(16), dp(6));
            chip.setMinimumHeight(dp(48));

            chip.setBackground(createTonalRipple(Color.TRANSPARENT, PixelUi.color(ui, "primary"), dp(18)));
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                if (saving || index == selectedAccount || v.getParent() != accountsContainer) return;
                if (accountConsumer != null) {
                    accountConsumer.accept(index);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            accountsContainer.addView(chip, lp);
            styleAccount(i);
        }
        accountsContainer.select(selectedAccount, false);
    }

    private void styleAccount(int index) {
        if (index < 0 || index >= accountsContainer.getChildCount()) return;
        TextView chip = (TextView) accountsContainer.getChildAt(index);
        boolean selected = (index == selectedAccount);
        int textColor = PixelUi.color(ui, selected ? "onPrimaryContainer" : "onSurfaceVariant");

        chip.setTextColor(textColor);

        ProviderSettingsDialog.markChoice(chip, currentAccounts.get(index), selected);
    }

    private void rebuildModels() {
        modelsContainer.removeAllViews();
        if (currentModels == null) return;

        for (int i = 0; i < currentModels.size(); i++) {
            final int index = i;
            final String label = currentModels.get(i);
            final boolean isSelected = (i == selectedModel);

            LinearLayout row = new LinearLayout(ui);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(12), dp(4), dp(16), dp(4));
            row.setClickable(true);
            row.setFocusable(true);

            int baseBg = isSelected ? PixelUi.color(ui, "surfaceContainerHigh") : Color.TRANSPARENT;
            row.setBackground(createTonalRipple(baseBg, PixelUi.color(ui, "primary"), dp(12)));

            android.widget.RadioButton radio = new android.widget.RadioButton(ui);
            radio.setChecked(isSelected);
            radio.setClickable(false);
            radio.setFocusable(false);
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            radio.setButtonTintList(ColorStateList.valueOf(PixelUi.color(ui, isSelected ? "primary" : "onSurfaceVariant")));
            row.addView(radio, new LinearLayout.LayoutParams(dp(40), dp(48)));

            TextView modelTitle = new TextView(ui);
            modelTitle.setText(label);
            modelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            modelTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            modelTitle.setTextColor(PixelUi.color(ui, isSelected ? "onSurface" : "onSurfaceVariant"));
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            row.addView(modelTitle, textLp);

            ProviderSettingsDialog.markChoice(row, label, isSelected);
            row.setOnClickListener(v -> {
                if (saving || pending || v.getParent() != modelsContainer) return;
                if (modelConsumer != null) {
                    modelConsumer.accept(index);
                }
            });

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.leftMargin = dp(8);
            itemLp.rightMargin = dp(8);
            itemLp.topMargin = dp(1);
            modelsContainer.addView(row, itemLp);
        }
    }

    private Drawable createTonalRipple(int contentColor, int rippleColor, float radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(contentColor);
        content.setCornerRadius(radius);

        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);

        int alphaRipple = Color.argb(38, Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor));
        return new RippleDrawable(ColorStateList.valueOf(alphaRipple), content, mask);
    }
}
