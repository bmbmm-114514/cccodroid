package cc.codroid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

final class PixelModelSheet {
    private final Activity host;
    private final Context ui;
    private boolean saving;
    private int dp(float amount) { return Math.round(host.getResources().getDisplayMetrics().density * amount); }

    private final Dialog dialog;

    private final LinearLayout sheetView;
    private final LinearLayout accountsContainer;
    private final LinearLayout modelsContainer;
    private final TextView messageView;
    private final ProgressBar progressBar;

    private List<String> currentAccounts;
    private int selectedAccount = -1;
    private java.util.function.IntConsumer accountConsumer;

    private List<String> currentModels;
    private int selectedModel = -1;
    private java.util.function.IntConsumer modelConsumer;

    PixelModelSheet(Activity host, Context ui, Runnable settings, Runnable close) {
        this.host = host;
        this.ui = ui;

        this.dialog = new Dialog(ui, android.R.style.Theme_Translucent_NoTitleBar);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.38f);
            int surface = PixelUi.color(ui, "surfaceContainer");
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setNavigationBarColor(surface);
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true);
            if (Build.VERSION.SDK_INT >= 26 && Color.luminance(surface) > .5f)
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false);
            }
        }

        FrameLayout rootContainer = new FrameLayout(ui);
        rootContainer.setFocusable(true);
        rootContainer.setFocusableInTouchMode(true);
        rootContainer.setOnClickListener(v -> { if (!saving) dismiss(); });

        sheetView = new LinearLayout(ui) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int availableH = MeasureSpec.getSize(heightMeasureSpec);
                int height = Math.min(dp(560), (int) (availableH * (availableH < dp(450) ? .91f : .70f)));
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
                int width = Math.min(MeasureSpec.getSize(widthMeasureSpec), dp(560));
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        sheetView.setOrientation(LinearLayout.VERTICAL);
        sheetView.setClickable(true);
        sheetView.setFocusable(true);

        float r = dp(28);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(PixelUi.color(ui, "surfaceContainer"));
        sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetView.setBackground(sheetBg);
        sheetView.setElevation(dp(8));

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        rootContainer.addView(sheetView, sheetLp);

        FrameLayout grip = new FrameLayout(ui);
        grip.setContentDescription("收起模型选择");
        grip.setFocusable(true);
        grip.setOnClickListener(v -> { if (!saving) dismiss(); });
        View handle = new View(ui);
        handle.setBackground(PixelUi.shape(ui, "outlineVariant", 2));
        grip.addView(handle, new FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER));
        sheetView.addView(grip, new LinearLayout.LayoutParams(-1, dp(28)));
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (saving) return true;
                float delta = event.getRawY() - startY;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sheetView.animate().cancel();
                        startY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        sheetView.setTranslationY(Math.max(0, delta));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(delta) < slop) { v.performClick(); return true; }
                        if (delta > dp(48)) { dismiss(); return true; }
                        // A short or cancelled drag settles in place.
                    case MotionEvent.ACTION_CANCEL:
                        sheetView.animate().translationY(0).setDuration(160).start();
                        return true;
                    default:
                        return false;
                }
            }
        });

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

        progressBar = new ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        sheetView.addView(progressBar, progressLp);

        messageView = new TextView(ui);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        messageView.setTextColor(PixelUi.color(ui, "onSurfaceVariant"));
        messageView.setPadding(dp(20), dp(4), dp(20), dp(8));
        messageView.setVisibility(View.GONE);
        sheetView.addView(messageView);

        HorizontalScrollView hScroll = new HorizontalScrollView(ui);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        accountsContainer = new LinearLayout(ui);
        accountsContainer.setOrientation(LinearLayout.HORIZONTAL);
        accountsContainer.setPadding(dp(16), dp(4), dp(16), dp(8));
        hScroll.addView(accountsContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetView.addView(hScroll);

        View divider = new View(ui);
        divider.setBackgroundColor(PixelUi.color(ui, "outlineVariant"));
        sheetView.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView vScroll = new ScrollView(ui);
        vScroll.setVerticalScrollBarEnabled(false);
        modelsContainer = new LinearLayout(ui);
        modelsContainer.setOrientation(LinearLayout.VERTICAL);
        modelsContainer.setPadding(0, dp(4), 0, dp(12));
        vScroll.addView(modelsContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetView.addView(vScroll, scrollLp);

        dialog.setContentView(rootContainer);
        ProviderSettingsDialog.watchWindow(dialog, close);
    }

    public void present() {
        if (!host.isFinishing() && !host.isDestroyed()) {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) window.setLayout(-1, -1);
            sheetView.announceForAccessibility("切换模型面板已展开");
        }
    }

    void accounts(List<String> labels, int selected, java.util.function.IntConsumer choose) {
        this.currentAccounts = labels;
        this.selectedAccount = selected;
        this.accountConsumer = choose;
        rebuildAccounts();
    }

    void models(List<String> labels, int selected, java.util.function.IntConsumer choose) {
        this.currentModels = labels;
        this.selectedModel = selected;
        this.modelConsumer = choose;
        rebuildModels();
    }

    void message(String text) {
        if (text == null || text.trim().isEmpty()) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(text);
            messageView.setVisibility(View.VISIBLE);
            messageView.announceForAccessibility(text);
        }
    }

    void busy(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        saving = loading;
        dialog.setCancelable(!loading);
    }

    public void dismiss() {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void rebuildAccounts() {
        accountsContainer.removeAllViews();
        if (currentAccounts == null || currentAccounts.isEmpty()) {
            ((View) accountsContainer.getParent()).setVisibility(View.GONE);
            return;
        }
        ((View) accountsContainer.getParent()).setVisibility(View.VISIBLE);

        for (int i = 0; i < currentAccounts.size(); i++) {
            final int index = i;
            final String label = currentAccounts.get(i);
            final boolean isSelected = (i == selectedAccount);

            TextView chip = new TextView(ui);
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(16), dp(6), dp(16), dp(6));
            chip.setMinimumHeight(dp(48));

            int bgColor = isSelected ? PixelUi.color(ui, "primaryContainer") : PixelUi.color(ui, "surfaceContainerHigh");
            int textColor = isSelected ? PixelUi.color(ui, "onPrimaryContainer") : PixelUi.color(ui, "onSurfaceVariant");
            chip.setTextColor(textColor);
            chip.setBackground(createTonalRipple(bgColor, PixelUi.color(ui, "primary"), dp(18)));

            ProviderSettingsDialog.markChoice(chip, label, isSelected);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                if (saving || index == selectedAccount) return;
                if (accountConsumer != null) {
                    accountConsumer.accept(index);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            accountsContainer.addView(chip, lp);
        }
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
                if (saving) return;
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
