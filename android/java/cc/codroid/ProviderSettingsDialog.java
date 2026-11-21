package cc.codroid;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ProviderSettingsDialog {

    private static final int TYPE_CLAUDE = 1;
    private static final int TYPE_CHATGPT = 2;
    private static final int TYPE_GOOGLE = 3;

    private static class ProviderItem {
        final String entryKey;
        final String label;
        final String agent;
        final String providerId;
        final boolean isOfficial;
        final int officialType;
        boolean hasKey;
        boolean local;
        final String defaultModel;

        ProviderItem(String entryKey, String label, String agent, String providerId,
                     boolean isOfficial, int officialType, boolean hasKey, String defaultModel) {
            this.entryKey = entryKey;
            this.label = label;
            this.agent = agent;
            this.providerId = providerId;
            this.isOfficial = isOfficial;
            this.officialType = officialType;
            this.hasKey = hasKey;
            this.defaultModel = defaultModel;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class Choice {
        final String id;
        final String label;

        Choice(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Activity host;
    private final Context ui;
    private final Consumer<Boolean> onClosed;
    private final float density;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isDismissed;
    private boolean closeNotified;
    private int modelRead;
    private boolean hasChanges;
    private int pendingWrites;
    private int generation = 0;

    private Runnable pollRunnable = null;

    private final List<ProviderItem> providerList = new ArrayList<>();
    private ProviderItem currentItem = null;

    private String curModel = "";
    private String curEffort = "";
    private String curServiceTier = "";

    private boolean stale(int gen) {
        return isDismissed || host.isFinishing() || host.isDestroyed() || gen != generation;
    }

    private PixelProviderPage dialog;
    private Dialog child;
    private LinearLayout officialGroup;
    private SelectRow modelRow, effortRow, tierRow;
    private Button btnPicker;
    private LinearLayout layoutError;
    private TextView tvError;
    private ProgressBar progressBar;

    private LinearLayout cardAccount;
    private TextView tvAccountStatus;
    private Button btnAccountAction;
    private LinearLayout layoutCodeVerify;
    private TextView tvUserCode;
    private Button btnCopyCode;
    private Button btnOpenUri;
    private LinearLayout layoutNeedsCode;
    private EditText etManualCode;
    private Button btnSubmitManualCode;

    private LinearLayout cardApiKey;
    private TextView tvKeyStatus;
    private EditText etApiKey;
    private Button btnSaveKey;

    private LinearLayout cardModel;
    private Button btnCustomModel;
    private LinearLayout layoutEffort;
    private LinearLayout layoutTier;

    private String lastOAuthUri = "";

    public static void show(Activity host, Runnable onChanged) {
        if (host == null || host.isFinishing() || host.isDestroyed()) {
            return;
        }
        ProviderSettingsDialog instance = new ProviderSettingsDialog(host, onChanged);
        instance.present();
    }

    private ProviderSettingsDialog(Activity host, Runnable onChanged) {
        this(host, changed -> { if (changed && onChanged != null) onChanged.run(); });
    }

    private ProviderSettingsDialog(Activity host, Consumer<Boolean> onClosed) {
        this.host = host;
        this.ui = new android.view.ContextThemeWrapper(host, NativeAppearance.theme(host));
        this.onClosed = onClosed;
        this.density = host.getResources().getDisplayMetrics().density;
    }

    private int dp(float val) {
        return (int) (val * density + 0.5f);
    }

    /** A provider tap previews its catalog. Only tapping a model changes the conversation defaults. */
    private PixelModelSheet quickSheet;
    private boolean quickSaving;
    private boolean detailsOpen;
    private boolean quickRefreshing;
    private int quickRead;
    private ProviderItem quickProvider;

    public static void quick(Activity host, Runnable onChanged) {
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        new ProviderSettingsDialog(host, onChanged).presentQuick();
    }

    private void presentQuick() {
        quickSheet = new PixelModelSheet(host, ui, this::openQuickSettings, this::closed);
        quickSheet.present();
        reloadQuick(null);
    }

    private void openQuickSettings() {
        if (detailsOpen || quickSaving || isDismissed) return;
        detailsOpen = true;
        ProviderSettingsDialog fullSettings = new ProviderSettingsDialog(host, savedChanges -> {
            child = null;
            detailsOpen = false;
            if (savedChanges) {
                hasChanges = true;
                if (!isDismissed) {
                    String previewKey = quickProvider != null ? quickProvider.entryKey : null;
                    reloadQuick(previewKey);
                }
            }
            notifyWhenClosed();
        });
        // Leave the quick sheet visible beneath the full page during predictive back.
        fullSettings.present();
        child = fullSettings.dialog;
    }

    private void reloadQuick(String previewKey) {
        quickRefreshing = true;
        if (previewKey == null) quickSheet.message("正在读取接口…");
        final int gen = ++generation;
        executor.execute(() -> {
            try {
                String pref = requestHttp("GET", "/api/multi/preferences", null);
                String catalog = requestHttp("GET", "/api/providers", null);
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    ProviderItem selected = readProviderCatalog(pref, catalog);
                    currentItem = selected;
                    List<ProviderItem> available = new ArrayList<>();
                    for (ProviderItem item : providerList) {
                        if (item.isOfficial || item.local || item.hasKey) available.add(item);
                    }
                    quickRefreshing = false;
                    ProviderItem preview = findProviderByEntryKey(previewKey);
                    if (!available.contains(preview)) preview = available.contains(selected) ? selected : available.get(0);
                    openQuickProvider(available, preview);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    quickRefreshing = false;
                    quickSheet.message("无法读取接口：" + e.getMessage());
                });
            }
        });
    }

    private void openQuickProvider(List<ProviderItem> available, ProviderItem item) {
        if (item == null || quickSaving || quickRefreshing || isDismissed) return;
        boolean samePreview = quickProvider != null && quickProvider.entryKey.equals(item.entryKey);
        quickProvider = item;
        final int gen = generation, read = ++quickRead;
        List<String> names = new ArrayList<>();
        for (ProviderItem p : available) names.add(p.isOfficial ? officialName(p) : p.label);
        quickSheet.accounts(names, available.indexOf(item), samePreview, i -> openQuickProvider(available, available.get(i)));
        if (!samePreview) {
            quickSheet.models(java.util.Collections.emptyList(), -1, i -> {});
            quickSheet.message("正在读取模型…");
        }
        NativeRequests.call(host, "GET", "/api/multi/preferences?provider=" + Uri.encode(item.providerId), null, saved -> {
            if (stale(gen) || read != quickRead) return;
            final String model = saved.optString("model");
            NativeRequests.call(host, "GET", modelPath(item, model), null, catalog -> {
                if (stale(gen) || read != quickRead) return;
                quickModels(item, model, catalog, "");
            }, error -> {
                if (!stale(gen) && read == quickRead) quickModels(item, model, new JSONObject(), "模型列表暂不可用");
            });
        }, error -> {
            if (stale(gen) || read != quickRead) return;
            quickSheet.models(java.util.Collections.emptyList(), -1, i -> {});
            quickSheet.message(error);
        });
    }

    private void quickModels(ProviderItem item, String model, JSONObject catalog, String message) {
        List<Choice> values = options(catalog.optJSONArray("models"), model, catalog.optString("defaultModel", item.defaultModel), true);
        List<String> labels = new ArrayList<>();
        int active = -1;
        for (int i = 0; i < values.size(); i++) {
            labels.add(values.get(i).label);
            if (item == currentItem && values.get(i).id.equals(model)) active = i;
        }
        quickSheet.message(message);
        quickSheet.models(labels, active, i -> saveQuick(item, values.get(i).id));
    }

    private void saveQuick(ProviderItem item, String model) {
        if (isDismissed || quickSaving || detailsOpen || quickRefreshing || item != quickProvider) return;
        quickSaving = true;
        quickSheet.busy(true);
        final int gen = generation;
        executeWrite(() -> {
            try {
                String body = new JSONObject().put("agent", item.agent).put("provider", item.providerId).put("model", model).toString();
                requestHttp("POST", "/api/multi/preferences", body);
                mainHandler.post(() -> {
                    hasChanges = true;
                    if (stale(gen)) return;
                    quickSaving = false;
                    quickSheet.dismiss();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    quickSaving = false;
                    quickSheet.busy(false);
                    quickSheet.message(e.getMessage());
                });
            }
        });
    }

    private String modelPath(ProviderItem p, String model) {
        return "/api/multi/models?agent=" + Uri.encode(p.agent) + "&model=" + Uri.encode(model)
                + "&provider=" + Uri.encode(p.providerId);
    }

    private void present() {
        dialog = new PixelProviderPage(ui, NativeAppearance.theme(host));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            int surfaceColor = PixelUi.color(ui, "surface");
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true);
            window.setStatusBarColor(surfaceColor);
            window.setNavigationBarColor(surfaceColor);

            // Material surface luminance controls the two platform bar icon palettes.
            int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility() & ~lightBars;
            decor.setSystemUiVisibility(Color.luminance(surfaceColor) > .5f ? flags | lightBars : flags);
        }

        LinearLayout root = vertical();
        root.setBackgroundColor(PixelUi.color(ui, "surface"));
        LinearLayout appbar = new LinearLayout(ui);
        appbar.setOrientation(LinearLayout.HORIZONTAL);
        appbar.setGravity(Gravity.CENTER_VERTICAL);
        appbar.setPadding(dp(4), dp(8), dp(16), dp(8));
        appbar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        View btnBack = PixelUi.icon(ui, "back", "返回", dialog::closePage);
        appbar.addView(btnBack);

        TextView tvHeading = new TextView(ui);
        tvHeading.setText("接口与模型");
        tvHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        tvHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvHeading.setTextColor(PixelUi.color(ui, "onSurface"));
        tvHeading.setPadding(dp(8), 0, 0, 0);
        appbar.addView(tvHeading);
        root.addView(appbar);

        layoutError = new LinearLayout(ui);
        layoutError.setOrientation(LinearLayout.HORIZONTAL);
        layoutError.setGravity(Gravity.CENTER_VERTICAL);
        layoutError.setPadding(dp(16), dp(4), dp(16), dp(4));
        layoutError.setVisibility(View.GONE);

        tvError = new TextView(ui);
        tvError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvError.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tvError.setTextColor(PixelUi.color(ui, "onSurfaceVariant"));
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        layoutError.addView(tvError, errLp);

        Button btnRetry = action("重试");
        btnRetry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnRetry.setOnClickListener(v -> reloadAll());
        layoutError.addView(btnRetry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(layoutError);

        progressBar = new ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        ScrollView scrollView = new ScrollView(ui);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout scrollContent = vertical();
        scrollContent.setPadding(0, 0, 0, dp(24));
        scrollView.addView(scrollContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout officialCard = card();
        officialGroup = vertical();
        officialCard.addView(officialGroup);


        cardAccount = vertical();
        cardAccount.setPadding(dp(8), dp(4), dp(8), dp(4));

        tvAccountStatus = bodyLabel("");
        tvAccountStatus.setPadding(0, dp(4), 0, dp(8));
        LinearLayout accountLine = new LinearLayout(ui);
        accountLine.setGravity(Gravity.CENTER_VERTICAL);
        accountLine.addView(tvAccountStatus, new LinearLayout.LayoutParams(0, -2, 1));
        btnAccountAction = action("登录");
        accountLine.addView(btnAccountAction, new LinearLayout.LayoutParams(-2, -2));
        cardAccount.addView(accountLine);

        layoutCodeVerify = vertical();
        layoutCodeVerify.setVisibility(View.GONE);
        layoutCodeVerify.setPadding(0, dp(8), 0, 0);

        tvUserCode = new TextView(ui);
        tvUserCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvUserCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvUserCode.setTextColor(PixelUi.color(ui, "primary"));
        tvUserCode.setGravity(Gravity.CENTER);
        tvUserCode.setPadding(0, dp(4), 0, dp(8));
        layoutCodeVerify.addView(tvUserCode);

        LinearLayout rowCodeButtons = new LinearLayout(ui);
        rowCodeButtons.setOrientation(LinearLayout.HORIZONTAL);

        btnCopyCode = action("复制验证码");
        LinearLayout.LayoutParams btnHalfLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        btnHalfLp1.setMargins(0, 0, dp(4), 0);
        rowCodeButtons.addView(btnCopyCode, btnHalfLp1);

        btnOpenUri = action("打开验证网址");
        LinearLayout.LayoutParams btnHalfLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        btnHalfLp2.setMargins(dp(4), 0, 0, 0);
        rowCodeButtons.addView(btnOpenUri, btnHalfLp2);

        layoutCodeVerify.addView(rowCodeButtons);
        cardAccount.addView(layoutCodeVerify);

        layoutNeedsCode = vertical();
        layoutNeedsCode.setVisibility(View.GONE);
        layoutNeedsCode.setPadding(0, dp(8), 0, 0);

        etManualCode = createStyledEditText("粘贴授权码", false);
        layoutNeedsCode.addView(etManualCode);

        btnSubmitManualCode = action("提交授权码");
        layoutNeedsCode.addView(btnSubmitManualCode);

        cardAccount.addView(layoutNeedsCode);
        officialCard.addView(cardAccount);
        group(scrollContent, "官方账号", officialCard);

        LinearLayout customGroup = vertical();

        LinearLayout pickerCard = card();
        btnPicker = action("选择 API 接口");
        btnPicker.setTextColor(PixelUi.color(ui, "onSurface"));
        btnPicker.setBackground(PixelUi.clickable(ui, "surfaceContainerHigh", 16));
        btnPicker.setOnClickListener(v -> showProviderChoiceDialog());
        pickerCard.addView(btnPicker);
        customGroup.addView(pickerCard);

        cardApiKey = card();
        tvKeyStatus = bodyLabel("");
        tvKeyStatus.setPadding(0, dp(4), 0, dp(6));
        cardApiKey.addView(tvKeyStatus);

        etApiKey = createStyledEditText("输入 API Key", true);
        cardApiKey.addView(etApiKey);

        btnSaveKey = action("保存密钥");
        btnSaveKey.setOnClickListener(v -> saveApiKey());
        cardApiKey.addView(btnSaveKey);
        customGroup.addView(cardApiKey);

        cardModel = card();

        modelRow = new SelectRow("默认模型", "model");
        effortRow = new SelectRow("思考强度", "effort");
        tierRow = new SelectRow("处理速度", "serviceTier");
        layoutEffort = effortRow.view;
        layoutTier = tierRow.view;
        cardModel.addView(modelRow.view);
        cardModel.addView(layoutEffort);
        cardModel.addView(layoutTier);
        btnCustomModel = action("输入自定义模型");
        btnCustomModel.setOnClickListener(v -> showCustomModelDialog());
        cardModel.addView(btnCustomModel);
        group(scrollContent, "模型偏好", cardModel);
        group(scrollContent, "第三方接口", customGroup);

        cardAccount.setVisibility(View.GONE);
        cardApiKey.setVisibility(View.GONE);
        cardModel.setVisibility(View.GONE);

        dialog.setContentView(root);
        watchWindow(dialog, this::closed);
        dialog.show();
        if (window != null) window.setLayout(-1, -1);
        dialog.enter();
        reloadAll();
    }

    static void watchWindow(Dialog dialog, Runnable closed) {
        dialog.setOnDismissListener(unused -> closed.run());
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                view.removeOnAttachStateChangeListener(this);
                closed.run();
            }
        });
    }

    private void closed() {
        if (isDismissed) return;
        isDismissed = true;
        generation++;
        stopPolling();
        if (child != null && child.isShowing()) child.dismiss();
        child = null;
        executor.shutdown();
        notifyWhenClosed();
    }

    private void notifyWhenClosed() {
        if (!isDismissed || closeNotified || detailsOpen || pendingWrites != 0
                || host.isFinishing() || host.isDestroyed()) return;
        closeNotified = true;
        onClosed.accept(hasChanges);
    }

    private void executeWrite(Runnable write) {
        pendingWrites++;
        executor.execute(() -> {
            try {
                write.run();
            } finally {
                mainHandler.post(() -> {
                    pendingWrites--;
                    notifyWhenClosed();
                });
            }
        });
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout c = vertical();
        c.setBackground(PixelUi.shape(ui, "surfaceContainer", 20));
        int pad = dp(8);
        c.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(4), dp(16), dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView caption(String text) {
        TextView tv = new TextView(ui);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(PixelUi.color(ui, "onSurfaceVariant"));
        return tv;
    }

    private TextView bodyLabel(String text) {
        TextView tv = new TextView(ui);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tv.setTextColor(PixelUi.color(ui, "onSurface"));
        return tv;
    }

    private Button action(String text) {
        Button btn = new Button(ui);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTransformationMethod(null);
        btn.setTextColor(PixelUi.color(ui, "onPrimaryContainer"));
        btn.setBackgroundTintList(null);
        btn.setBackground(PixelUi.clickable(ui, "primaryContainer", 24));
        btn.setMinHeight(dp(48));
        btn.setMinimumHeight(dp(48));
        int padH = dp(16);
        int padV = dp(12);
        btn.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    private LinearLayout group(ViewGroup parent, String label, View content) {
        LinearLayout container = vertical();
        if (label != null && !label.isEmpty()) {
            TextView tv = caption(label);
            tv.setPadding(dp(20), dp(12), dp(20), dp(4));
            container.addView(tv);
        }
        if (content != null) {
            container.addView(content);
        }
        if (parent != null) {
            parent.addView(container);
        }
        return container;
    }

    private EditText createStyledEditText(String hint, boolean isPassword) {
        EditText et = new EditText(ui);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        et.setTextColor(PixelUi.color(ui, "onSurface"));
        et.setHintTextColor(PixelUi.color(ui, "onSurfaceVariant"));
        et.setBackground(PixelUi.shape(ui, "surfaceContainerHigh", 12));
        et.setMinHeight(dp(48));
        et.setMinimumHeight(dp(48));
        if (isPassword) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            et.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        int pad = dp(12);
        et.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        et.setLayoutParams(lp);
        return et;
    }

    private void renderProviderRows() {
        officialGroup.removeAllViews();

        final String[] titles = {"Claude 登录", "ChatGPT 登录", "Google 登录"};
        final String[] subtitles = {"Claude Code", "Codex", "Antigravity"};
        final String[] monograms = {"C", "O", "G"};
        for (ProviderItem item : providerList) {
            if (!item.isOfficial) continue;
            int i = item.officialType - 1;
            final boolean isSelected = (currentItem != null && item.entryKey.equals(currentItem.entryKey));

            final LinearLayout row = new LinearLayout(ui);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            row.setFocusable(true);
            row.setClickable(true);
            markChoice(row, titles[i] + "，" + subtitles[i], isSelected);

            row.setBackground(PixelUi.clickable(ui, isSelected ? "primaryContainer" : null, 20));
            row.setPadding(dp(12), dp(8), dp(16), dp(8));

            TextView mono = new TextView(ui);
            mono.setText(monograms[i]);
            mono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            mono.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mono.setGravity(Gravity.CENTER);
            mono.setTextColor(PixelUi.color(ui, isSelected ? "onPrimaryContainer" : "onSurfaceVariant"));
            GradientDrawable monoBg = new GradientDrawable();
            monoBg.setShape(GradientDrawable.OVAL);
            monoBg.setColor(PixelUi.color(ui, isSelected ? "surface" : "surfaceContainerHigh"));
            mono.setBackground(monoBg);
            LinearLayout.LayoutParams monoLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            monoLp.setMarginEnd(dp(16));
            row.addView(mono, monoLp);

            LinearLayout textCol = new LinearLayout(ui);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

            TextView tvTitle = new TextView(ui);
            tvTitle.setText(titles[i]);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tvTitle.setTextColor(PixelUi.color(ui, isSelected ? "onPrimaryContainer" : "onSurface"));
            textCol.addView(tvTitle);

            TextView tvSub = new TextView(ui);
            tvSub.setText(subtitles[i]);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvSub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            tvSub.setTextColor(PixelUi.color(ui, isSelected ? "onPrimaryContainer" : "onSurfaceVariant"));
            textCol.addView(tvSub);

            row.addView(textCol, textLp);

            RadioButton radio = new RadioButton(ui);
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            radio.setChecked(isSelected);
            radio.setClickable(false);
            radio.setFocusable(false);
            int radioTint = PixelUi.color(ui, isSelected ? "primary" : "outlineVariant");
            radio.setButtonTintList(ColorStateList.valueOf(radioTint));
            LinearLayout.LayoutParams radioLp = new LinearLayout.LayoutParams(dp(48), dp(48));
            radioLp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(radio, radioLp);

            row.setOnClickListener(v -> { if (item != currentItem) selectProvider(item); });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dp(2), 0, dp(2));
            officialGroup.addView(row, rowLp);
        }
    }

    private final class SelectRow {
        final String title;
        final String key;
        final LinearLayout view;
        final TextView titleView;
        final TextView subtitleView;
        final List<Choice> options = new ArrayList<>();

        private String selectedId = "";
        private int boundGen = 0;
        private ProviderItem boundItem = null;

        SelectRow(String title, String key) {
            this.title = title;
            this.key = key;

            view = new LinearLayout(ui);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(56));
            view.setFocusable(true);
            view.setClickable(true);
            view.setPadding(dp(16), dp(10), dp(16), dp(10));

            view.setBackground(PixelUi.clickable(ui, null, 16));

            LinearLayout textCol = new LinearLayout(ui);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);

            titleView = new TextView(ui);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleView.setTextColor(PixelUi.color(ui, "onSurface"));
            textCol.addView(titleView);

            subtitleView = new TextView(ui);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            subtitleView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            subtitleView.setTextColor(PixelUi.color(ui, "onSurfaceVariant"));
            textCol.addView(subtitleView);

            view.addView(textCol, textLp);

            TextView chevron = new TextView(ui);
            chevron.setText("›");
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            chevron.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            chevron.setTextColor(PixelUi.color(ui, "onSurfaceVariant"));
            chevron.setGravity(Gravity.CENTER);
            view.addView(chevron, new LinearLayout.LayoutParams(dp(48), dp(48)));

            view.setOnClickListener(v -> show());
        }

        void bind(JSONArray entries, String current, String defaultValue, boolean model) {
            boundGen = generation;
            boundItem = currentItem;
            options.clear();

            options.addAll(options(entries, current, defaultValue, model));
            selectedId = current;
            for (Choice option : options) {
                if (option.id.equals(current)) subtitleView.setText(option.label);
            }
            view.setContentDescription(title + "，" + subtitleView.getText());
            view.setVisibility(View.VISIBLE);
        }

        void show() {
            if (options.isEmpty() || stale(boundGen) || currentItem != boundItem) return;
            final int genAtShow = boundGen;
            final ProviderItem itemAtShow = boundItem;

            final List<Choice> snapshot = new ArrayList<>(options);
            final String[] labels = new String[snapshot.size()];
            int selectedIndex = -1;
            for (int i = 0; i < snapshot.size(); i++) {
                Choice opt = snapshot.get(i);
                labels[i] = opt.label;
                if (opt.id.equals(selectedId)) {
                    selectedIndex = i;
                }
            }

            child = PixelChoiceSheet.show(ui, title, java.util.Arrays.asList(labels), selectedIndex, which -> {
                if (stale(genAtShow) || currentItem != itemAtShow) return;
                if (which < 0 || which >= snapshot.size()) return;
                Choice chosen = snapshot.get(which);
                if (chosen.id.equals(selectedId)) return;
                selectedId = chosen.id;
                subtitleView.setText(chosen.label);
                view.setContentDescription(title + "，" + chosen.label);
                if ("model".equals(key)) {
                    curModel = chosen.id;
                } else if ("effort".equals(key)) {
                    curEffort = chosen.id;
                } else if ("serviceTier".equals(key)) {
                    curServiceTier = chosen.id;
                }
                updatePreferenceField(key, chosen.id);
            }, null);
        }
    }

    static void markChoice(View view, String label, boolean checked) {
        view.setContentDescription(label);
        view.setSelected(checked);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
    }

    private String officialName(ProviderItem item) {
        if (item.officialType == TYPE_CHATGPT) return "ChatGPT";
        if (item.officialType == TYPE_GOOGLE) return "Google";
        return "Claude";
    }

    private void showProviderChoiceDialog() {
        if (providerList.isEmpty()) {
            Toast.makeText(host, "接口列表尚未加载", Toast.LENGTH_SHORT).show();
            return;
        }

        List<ProviderItem> apis = new ArrayList<>();
        for (ProviderItem item : providerList) if (!item.isOfficial) apis.add(item);
        List<String> names = new ArrayList<>();
        int checkedIndex = -1;
        for (int i = 0; i < apis.size(); i++) {
            ProviderItem item = apis.get(i);
            names.add(item.label);
            if (currentItem != null && item.entryKey.equals(currentItem.entryKey)) {
                checkedIndex = i;
            }
        }
        final int gen = generation;
        child = PixelChoiceSheet.show(ui, "选择 API 接口", names, checkedIndex, index -> {
            if (!stale(gen)) selectProvider(apis.get(index));
        }, () -> { if (!stale(gen)) showAddCustomProviderDialog(); });
    }

    private void showAddCustomProviderDialog() {
        AlertDialog.Builder b = new MaterialAlertDialogBuilder(ui);
        b.setTitle("添加自定义厂商");

        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, pad);

        EditText etLabel = createStyledEditText("厂商名称 (例如: DeepSeek)", false);
        layout.addView(etLabel);

        EditText etBaseUrl = createStyledEditText("接口地址 (例如: https://api.deepseek.com/v1)", false);
        layout.addView(etBaseUrl);

        EditText etKey = createStyledEditText("API 密钥 (API Key)", true);
        layout.addView(etKey);

        b.setView(layout);
        b.setPositiveButton("保存并选择", (d, which) -> {
            String label = etLabel.getText().toString().trim();
            String baseUrl = etBaseUrl.getText().toString().trim();
            String apiKey = etKey.getText().toString().trim();
            if (TextUtils.isEmpty(label)) {
                Toast.makeText(host, "厂商名称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            saveNewCustomProvider(label, baseUrl, apiKey);
        });
        b.setNegativeButton("取消", null);
        child = b.show();
    }

    private void showCustomModelDialog() {
        AlertDialog.Builder b = new MaterialAlertDialogBuilder(ui);
        b.setTitle("输入自定义模型");

        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, pad);

        EditText etModel = createStyledEditText("模型 ID (例如: deepseek-chat)", false);
        if (!TextUtils.isEmpty(curModel)) {
            etModel.setText(curModel);
            etModel.setSelection(curModel.length());
        }
        layout.addView(etModel);

        b.setView(layout);
        b.setPositiveButton("应用", (d, which) -> {
            String m = etModel.getText().toString().trim();
            curModel = m;
            updatePreferenceField("model", curModel);
        });
        b.setNegativeButton("取消", null);
        child = b.show();
    }

    private void reloadAll() {
        final int gen = ++generation;
        stopPolling();
        layoutError.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                String prefJson = requestHttp("GET", "/api/multi/preferences", null);
                String provJson = requestHttp("GET", "/api/providers", null);

                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    parseInitialData(prefJson, provJson);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    layoutError.setVisibility(View.VISIBLE);
                    tvError.setText("连接本地网关失败，请重试");

                });
            }
        });
    }

    private void buildOfficialProviders() {
        providerList.clear();
        providerList.add(new ProviderItem("official:claude", "Claude 账户登录", "claude", "anthropic", true, TYPE_CLAUDE, false, ""));
        providerList.add(new ProviderItem("official:chatgpt", "ChatGPT 账户登录", "codex", "chatgpt", true, TYPE_CHATGPT, false, ""));
        providerList.add(new ProviderItem("official:google", "Google 账户登录", "agy", "google", true, TYPE_GOOGLE, false, ""));
    }

    private void parseInitialData(String prefJson, String provJson) {
        selectProviderInternal(readProviderCatalog(prefJson, provJson), false);
    }

    private ProviderItem readProviderCatalog(String prefJson, String provJson) {
        JSONObject prefObj = null;
        try {
            prefObj = new JSONObject(prefJson);
        } catch (Exception ignored) {}

        JSONObject provObj = null;
        try {
            provObj = new JSONObject(provJson);
        } catch (Exception ignored) {}

        buildOfficialProviders();

        Set<String> seenApiIds = new HashSet<>();
        if (provObj != null) {
            JSONArray arr = provObj.optJSONArray("providers");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p == null) continue;
                    String id = p.optString("id", "").trim();
                    if (id.isEmpty() || id.equals("anthropic") || p.optBoolean("login")) continue;
                    if (seenApiIds.contains(id)) continue;
                    seenApiIds.add(id);

                    String label = p.optString("label", id);
                    boolean hasKey = p.optBoolean("hasKey", false);
                    String defModel = p.optString("defaultModel", "");
                    ProviderItem item = new ProviderItem("api:" + id, label, "claude", id, false, 0, hasKey, defModel);
                    item.local = p.optBoolean("local");
                    providerList.add(item);
                }
            }
        }

        String activeAgent = prefObj != null ? prefObj.optString("agent", "") : "";
        String activeProvider = prefObj != null ? prefObj.optString("provider", "") : "";
        curModel = prefObj != null ? prefObj.optString("model", "") : "";
        curEffort = prefObj != null ? prefObj.optString("effort", "") : "";
        curServiceTier = prefObj != null ? prefObj.optString("serviceTier", "") : "";

        ProviderItem target = null;
        if ("codex".equalsIgnoreCase(activeAgent) && "chatgpt".equalsIgnoreCase(activeProvider)) {
            target = findProviderByEntryKey("official:chatgpt");
        } else if ("agy".equalsIgnoreCase(activeAgent) && "google".equalsIgnoreCase(activeProvider)) {
            target = findProviderByEntryKey("official:google");
        } else if ("claude".equalsIgnoreCase(activeAgent)) {
            if ("anthropic".equalsIgnoreCase(activeProvider)) {
                target = findProviderByEntryKey("official:claude");
            } else {
                target = findProviderByEntryKey("api:" + activeProvider);
            }
        }

        if (target == null && !providerList.isEmpty()) {
            target = providerList.get(0);
        }

        return target;
    }

    private ProviderItem findProviderByEntryKey(String key) {
        for (ProviderItem item : providerList) {
            if (item.entryKey.equals(key)) return item;
        }
        return null;
    }

    private void selectProvider(ProviderItem item) {
        if (item == null) return;
        selectProviderInternal(item, true);
    }

    private void selectProviderInternal(ProviderItem item, boolean sendPreference) {
        if (item == null) return;
        final int gen = ++generation;
        cardModel.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        btnSaveKey.setEnabled(true);
        btnSubmitManualCode.setEnabled(true);
        currentItem = item;
        btnPicker.setText(item.isOfficial ? "选择 API 接口" : item.label);
        renderProviderRows();
        stopPolling();

        lastOAuthUri = "";
        layoutCodeVerify.setVisibility(View.GONE);
        layoutNeedsCode.setVisibility(View.GONE);
        etManualCode.setText("");
        etApiKey.setText("");

        if (item.isOfficial) {
            cardAccount.setVisibility(View.VISIBLE);
            cardApiKey.setVisibility(View.GONE);
            btnCustomModel.setVisibility(View.VISIBLE);

            btnAccountAction.setContentDescription(officialName(item) + " 账号登录");
            setupAccountActions();

            tvAccountStatus.setText("正在检查登录状态…");
            btnAccountAction.setEnabled(false);
            fetchAndRenderState();
        } else {
            cardAccount.setVisibility(View.GONE);
            cardApiKey.setVisibility(item.local ? View.GONE : View.VISIBLE);
            btnCustomModel.setVisibility(View.VISIBLE);
            tvKeyStatus.setText(item.hasKey ? "已配置 API 密钥" : "未配置 API 密钥（输入后保存）");
        }

        if (sendPreference) {
            progressBar.setVisibility(View.VISIBLE);
            executeWrite(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("agent", item.agent);
                    body.put("provider", item.providerId);
                    String resp = requestHttp("POST", "/api/multi/preferences", body.toString());
                    JSONObject obj = new JSONObject(resp);
                    String model = obj.optString("model", "");
                    String effort = obj.optString("effort", "");
                    String tier = obj.optString("serviceTier", "");

                    mainHandler.post(() -> {
                        hasChanges = true;
                        if (stale(gen)) return;
                        progressBar.setVisibility(View.GONE);
                        curModel = model;
                        curEffort = effort;
                        curServiceTier = tier;
                        loadCapabilities();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (stale(gen)) return;
                        progressBar.setVisibility(View.GONE);
                        reloadAll();
                        Toast.makeText(host, "未能切换接口", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } else {
            loadCapabilities();
        }
    }

    private void setupAccountActions() {
        btnAccountAction.setOnClickListener(v -> {
            if (currentItem == null || !currentItem.isOfficial) return;
            if (currentItem.officialType == TYPE_CLAUDE) {
                startClaudeLogin();
            } else if (currentItem.officialType == TYPE_CHATGPT) {
                startSubscriptionLogin("ChatGPT", "/api/multi/login");
            } else if (currentItem.officialType == TYPE_GOOGLE) {
                startSubscriptionLogin("Google", "/api/multi/agy-login");
            }
        });

        btnCopyCode.setOnClickListener(v -> copyToClipboard(tvUserCode.getText().toString()));
        btnOpenUri.setOnClickListener(v -> {
            if (currentItem != null && currentItem.isOfficial) {
                openOAuthUrl(currentItem.officialType, lastOAuthUri);
            }
        });
        btnSubmitManualCode.setOnClickListener(v -> submitManualGoogleCode());
    }

    private void startClaudeLogin() {
        final int gen = generation;
        stopPolling();
        progressBar.setVisibility(View.VISIBLE);
        btnAccountAction.setEnabled(false);
        tvAccountStatus.setText("正在发起 Claude 登录...");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("method", "claudeai");
                String res = requestHttp("POST", "/api/login/start", body.toString());
                JSONObject obj = new JSONObject(res);
                String url = obj.isNull("url") ? "" : obj.optString("url");

                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnAccountAction.setEnabled(true);
                    if (!TextUtils.isEmpty(url)) {
                        lastOAuthUri = url;
                        openOAuthUrl(TYPE_CLAUDE, url);
                    }
                    startPollingClaude();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnAccountAction.setEnabled(true);
                    tvAccountStatus.setText(e.getMessage());
                });
            }
        });
    }

    private void startPollingClaude() {
        stopPolling();
        final int targetGen = generation;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (stale(targetGen)) return;
                if (currentItem == null || currentItem.officialType != TYPE_CLAUDE) {
                    return;
                }

                executor.execute(() -> {
                    try {
                        String res = requestHttp("GET", "/api/login/status", null);
                        JSONObject obj = new JSONObject(res);
                        boolean loggedIn = obj.optBoolean("loggedIn", false);
                        boolean pending = obj.optBoolean("pending", false);
                        String email = obj.optString("email", "");
                        String url = obj.isNull("url") ? "" : obj.optString("url");

                        mainHandler.post(() -> {
                            if (stale(targetGen)) return;
                            if (currentItem == null || currentItem.officialType != TYPE_CLAUDE) {
                                return;
                            }

                            if (loggedIn) {
                                tvAccountStatus.setText("已登录: " + (email.isEmpty() ? "已连接" : email));
                                btnAccountAction.setText("重新登录");
                                btnAccountAction.setEnabled(true);
                                hasChanges = true;
                                stopPolling();
                            } else if (pending) {
                                tvAccountStatus.setText("等待授权完成...（已在浏览器打开）");
                                btnAccountAction.setText("等待授权");
                                btnAccountAction.setEnabled(false);
                                if (!TextUtils.isEmpty(url) && lastOAuthUri.isEmpty()) {
                                    lastOAuthUri = url;
                                    openOAuthUrl(TYPE_CLAUDE, url);
                                }
                                if (pollRunnable != null) {
                                    mainHandler.postDelayed(pollRunnable, 1500);
                                }
                            } else {
                                tvAccountStatus.setText("未登录");
                                btnAccountAction.setText("登录");
                                btnAccountAction.setEnabled(true);
                                stopPolling();
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (stale(targetGen)) return;
                            if (pollRunnable != null) {
                                mainHandler.postDelayed(pollRunnable, 2500);
                            }
                        });
                    }
                });
            }
        };
        mainHandler.post(pollRunnable);
    }

    private void startSubscriptionLogin(String account, String path) {
        final int gen = generation;
        stopPolling();
        progressBar.setVisibility(View.VISIBLE);
        btnAccountAction.setEnabled(false);
        tvAccountStatus.setText("正在发起 " + account + " 登录…");
        executor.execute(() -> {
            try {
                requestHttp("POST", path, "{}");
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnAccountAction.setEnabled(true);
                    startPollingMultiState();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnAccountAction.setEnabled(true);
                    tvAccountStatus.setText(e.getMessage());
                });
            }
        });
    }

    private void submitManualGoogleCode() {
        String code = etManualCode.getText().toString().trim();
        if (TextUtils.isEmpty(code)) {
            Toast.makeText(host, "授权码不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        final int gen = generation;
        progressBar.setVisibility(View.VISIBLE);
        btnSubmitManualCode.setEnabled(false);

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("code", code);
                requestHttp("POST", "/api/multi/agy-login", body.toString());

                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnSubmitManualCode.setEnabled(true);
                    etManualCode.setText("");
                    startPollingMultiState();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnSubmitManualCode.setEnabled(true);
                    Toast.makeText(host, "提交授权码失败，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startPollingMultiState() {
        stopPolling();
        final int targetGen = generation;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (stale(targetGen)) return;
                if (currentItem == null || !currentItem.isOfficial) {
                    return;
                }

                executor.execute(() -> {
                    try {
                        String res = requestHttp("GET", "/api/multi/state", null);
                        JSONObject stateObj = new JSONObject(res);

                        mainHandler.post(() -> {
                            if (stale(targetGen)) return;
                            if (currentItem == null || !currentItem.isOfficial) {
                                return;
                            }

                            JSONObject accountState = stateObj.optJSONObject(currentItem.agent);
                            JSONObject account = accountState == null ? null : accountState.optJSONObject("account");
                            boolean loggedIn = accountState != null && (currentItem.officialType == TYPE_CHATGPT
                                    ? account != null && "chatgpt".equalsIgnoreCase(account.optString("type"))
                                    : accountState.optBoolean("loggedIn"));
                            if (loggedIn) hasChanges = true;
                            renderAccountState(stateObj);

                            boolean keepPolling = !loggedIn && accountState != null
                                    && accountState.optJSONObject("login") != null;
                            if (keepPolling && pollRunnable != null) {
                                mainHandler.postDelayed(pollRunnable, 1500);
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (stale(targetGen)) return;
                            if (pollRunnable != null) {
                                mainHandler.postDelayed(pollRunnable, 2500);
                            }
                        });
                    }
                });
            }
        };
        mainHandler.post(pollRunnable);
    }

    private void fetchAndRenderState() {
        final int gen = generation;
        NativeRequests.call(host, "GET", "/api/multi/state", null, state -> {
            if (stale(gen)) return;
            btnAccountAction.setEnabled(true);
            renderAccountState(state);
            JSONObject account = state.optJSONObject(currentItem.agent);
            if (currentItem.officialType != TYPE_CLAUDE && account != null && account.optJSONObject("login") != null) {
                startPollingMultiState();
            }
        }, error -> {
            if (stale(gen)) return;
            btnAccountAction.setEnabled(true);
            tvAccountStatus.setText(error);
        });
    }

    private void renderAccountState(JSONObject state) {
        if (currentItem == null || !currentItem.isOfficial || state == null) return;

        if (currentItem.officialType == TYPE_CLAUDE) {
            JSONObject c = state.optJSONObject("claude");
            boolean loggedIn = c != null && c.optBoolean("loggedIn", false);
            if (loggedIn) {
                String email = c != null ? c.optString("email", "") : "";
                tvAccountStatus.setText("已登录: " + (email.isEmpty() ? "已连接" : email));
                btnAccountAction.setText("重新登录");
            } else {
                tvAccountStatus.setText("未登录");
                btnAccountAction.setText("登录");
            }
            layoutCodeVerify.setVisibility(View.GONE);
            layoutNeedsCode.setVisibility(View.GONE);
        } else if (currentItem.officialType == TYPE_CHATGPT) {
            JSONObject cx = state.optJSONObject("codex");
            JSONObject acc = cx != null ? cx.optJSONObject("account") : null;
            JSONObject login = cx != null ? cx.optJSONObject("login") : null;

            if (acc != null && "chatgpt".equalsIgnoreCase(acc.optString("type"))) {
                String email = acc.optString("email", "");
                tvAccountStatus.setText("已登录: " + (email.isEmpty() ? "已连接" : email));
                btnAccountAction.setText("重新登录");
                layoutCodeVerify.setVisibility(View.GONE);
                layoutNeedsCode.setVisibility(View.GONE);
            } else if (login != null) {
                String userCode = login.optString("userCode", "");
                String uri = login.optString("verificationUri", "");
                tvAccountStatus.setText("请在浏览器中完成验证并输入下方验证码：");
                btnAccountAction.setText("重新发起登录");
                tvUserCode.setText(userCode);
                btnCopyCode.setVisibility(userCode.isEmpty() ? View.GONE : View.VISIBLE);
                layoutCodeVerify.setVisibility(View.VISIBLE);
                layoutNeedsCode.setVisibility(View.GONE);

                if (!TextUtils.isEmpty(uri) && !uri.equals(lastOAuthUri)) {
                    lastOAuthUri = uri;
                    openOAuthUrl(TYPE_CHATGPT, uri);
                }
            } else {
                String err = cx != null ? cx.optString("error", "") : "";
                tvAccountStatus.setText(err.isEmpty() ? "未登录" : ("状态: " + err));
                btnAccountAction.setText("登录");
                layoutCodeVerify.setVisibility(View.GONE);
                layoutNeedsCode.setVisibility(View.GONE);
            }
        } else if (currentItem.officialType == TYPE_GOOGLE) {
            JSONObject agy = state.optJSONObject("agy");
            boolean loggedIn = agy != null && agy.optBoolean("loggedIn", false);
            JSONObject login = agy != null ? agy.optJSONObject("login") : null;

            if (loggedIn) {
                tvAccountStatus.setText("已登录 Google 账户");
                btnAccountAction.setText("重新登录");
                layoutCodeVerify.setVisibility(View.GONE);
                layoutNeedsCode.setVisibility(View.GONE);
            } else if (login != null) {
                String userCode = login.optString("userCode", "");
                String uri = login.optString("verificationUri", "");
                boolean needsCode = login.optBoolean("needsCode", false);
                String msg = login.optString("message", "");

                tvAccountStatus.setText(msg.isEmpty() ? "请在浏览器完成 Google 登录授权：" : msg);
                btnAccountAction.setText("重新发起登录");

                tvUserCode.setText(userCode);
                btnCopyCode.setVisibility(userCode.isEmpty() ? View.GONE : View.VISIBLE);
                if (!TextUtils.isEmpty(userCode) || !TextUtils.isEmpty(uri)) {
                    layoutCodeVerify.setVisibility(View.VISIBLE);
                } else {
                    layoutCodeVerify.setVisibility(View.GONE);
                }

                layoutNeedsCode.setVisibility(needsCode ? View.VISIBLE : View.GONE);

                if (!TextUtils.isEmpty(uri) && !uri.equals(lastOAuthUri)) {
                    lastOAuthUri = uri;
                    openOAuthUrl(TYPE_GOOGLE, uri);
                }
            } else {
                String err = agy != null ? agy.optString("error", "") : "";
                tvAccountStatus.setText(err.isEmpty() ? "未登录" : ("状态: " + err));
                btnAccountAction.setText("登录");
                layoutCodeVerify.setVisibility(View.GONE);
                layoutNeedsCode.setVisibility(View.GONE);
            }
        }
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void copyToClipboard(String text) {
        if (TextUtils.isEmpty(text)) return;
        try {
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("userCode", text));
                Toast.makeText(host, "验证码已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }

    private boolean isAllowedAuthDomain(int officialType, String urlString) {
        if (TextUtils.isEmpty(urlString)) return false;
        try {
            Uri uri = Uri.parse(urlString);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) || uri.getUserInfo() != null || uri.getPort() != -1) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);

            if (officialType == TYPE_CLAUDE) {
                return host.equals("claude.ai") || host.endsWith(".claude.ai")
                        || host.equals("platform.claude.com")
                        || host.equals("console.anthropic.com")
                        || host.equals("anthropic.com") || host.endsWith(".anthropic.com");
            } else if (officialType == TYPE_CHATGPT) {
                return host.equals("auth.openai.com") || host.equals("chatgpt.com")
                        || host.endsWith(".openai.com") || host.endsWith(".chatgpt.com");
            } else if (officialType == TYPE_GOOGLE) {
                return host.equals("accounts.google.com")
                        || host.equals("antigravity.google") || host.endsWith(".antigravity.google");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void openOAuthUrl(int officialType, String urlString) {
        if (TextUtils.isEmpty(urlString)) return;
        if (!isAllowedAuthDomain(officialType, urlString)) {
            Toast.makeText(host, "拒绝打开非授权域名", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(host, "无法调起系统浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCapabilities() {
        if (currentItem == null) return;
        final int gen = generation;
        final int read = ++modelRead;
        final ProviderItem item = currentItem;
        final String modelParam = curModel;

        executor.execute(() -> {
            try {
                String resp = requestHttp("GET", modelPath(item, modelParam), null);
                JSONObject obj = new JSONObject(resp);

                mainHandler.post(() -> {
                    if (stale(gen) || read != modelRead || !modelParam.equals(curModel)) return;
                    cardModel.setVisibility(View.VISIBLE);
                    bindCapabilities(obj);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen) || read != modelRead || !modelParam.equals(curModel)) return;
                    cardModel.setVisibility(View.VISIBLE);
                    bindFallbackCapabilities();
                    showError("读取模型能力失败，请重试");
                });
            }
        });
    }

    private void bindCapabilities(JSONObject obj) {
        modelRow.bind(obj.optJSONArray("models"), curModel, obj.optString("defaultModel", currentItem.defaultModel), true);
        JSONArray efforts = obj.optJSONArray("efforts");
        if (efforts != null && efforts.length() > 0) {
            effortRow.bind(efforts, curEffort, obj.optString("defaultEffort"), false);
        } else layoutEffort.setVisibility(View.GONE);
        JSONArray tiers = obj.optJSONArray("serviceTiers");
        if (currentItem.officialType == TYPE_CHATGPT && tiers != null && tiers.length() > 0) {
            tierRow.bind(tiers, curServiceTier, obj.optString("defaultServiceTier"), false);
        } else layoutTier.setVisibility(View.GONE);
    }

    private List<Choice> options(JSONArray entries, String current, String defaultValue, boolean customModel) {
        List<Choice> values = new ArrayList<>();
        values.add(new Choice("", defaultValue.isEmpty() ? "引擎默认" : "默认（" + defaultValue + "）"));
        boolean matched = current.isEmpty();
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id");
            if (id.isEmpty()) continue;
            if (id.equals(current)) matched = true;
            values.add(new Choice(id, entry.optString("label", id)));
        }
        if (!matched) values.add(new Choice(current, current + (customModel ? "（自定义）" : "（已保存）")));
        return values;
    }

    private void bindFallbackCapabilities() {
        modelRow.bind(null, curModel, currentItem.defaultModel, true);
        layoutEffort.setVisibility(View.GONE);
        layoutTier.setVisibility(View.GONE);
    }

    private void updatePreferenceField(String key, String value) {
        if (currentItem == null) return;
        final int gen = generation;
        final ProviderItem item = currentItem;

        final String model = curModel;
        if ("model".equals(key)) {
            layoutEffort.setVisibility(View.GONE);
            layoutTier.setVisibility(View.GONE);
        }
        executeWrite(() -> {
            try {
                JSONObject body = new JSONObject().put("agent", item.agent).put("provider", item.providerId)
                        .put("model", model).put(key, value);
                JSONObject saved = new JSONObject(requestHttp("POST", "/api/multi/preferences", body.toString()));
                mainHandler.post(() -> {
                    hasChanges = true;
                    if (!"model".equals(key)) return;
                    if (stale(gen) || !model.equals(curModel)) return;
                    curEffort = saved.optString("effort");
                    curServiceTier = saved.optString("serviceTier");
                    loadCapabilities();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    Toast.makeText(host, e.getMessage(), Toast.LENGTH_SHORT).show();
                    reloadAll();
                });
            }
        });
    }

    private void showError(String message) {
        layoutError.setVisibility(View.VISIBLE);
        tvError.setText(message);
    }

    private void saveApiKey() {
        if (currentItem == null || currentItem.isOfficial) return;
        final ProviderItem target = currentItem;
        String key = etApiKey.getText().toString().trim();
        if (TextUtils.isEmpty(key)) {
            Toast.makeText(host, "API 密钥不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        final int gen = generation;
        progressBar.setVisibility(View.VISIBLE);
        btnSaveKey.setEnabled(false);

        executeWrite(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("id", target.providerId);
                b.put("apiKey", key);
                requestHttp("POST", "/api/providers/save", b.toString());

                mainHandler.post(() -> {
                    hasChanges = true;
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnSaveKey.setEnabled(true);
                    currentItem.hasKey = true;
                    tvKeyStatus.setText("已配置 API 密钥");
                    etApiKey.setText("");
                    Toast.makeText(host, "密钥已保存", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnSaveKey.setEnabled(true);
                    Toast.makeText(host, "保存密钥失败，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void saveNewCustomProvider(String label, String baseUrl, String apiKey) {
        final int gen = generation;
        progressBar.setVisibility(View.VISIBLE);

        executeWrite(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("label", label);
                b.put("baseUrl", baseUrl);
                if (!TextUtils.isEmpty(apiKey)) {
                    b.put("apiKey", apiKey);
                }
                String resp = requestHttp("POST", "/api/providers/save", b.toString());
                JSONObject resObj = new JSONObject(resp);
                String newId = resObj.optString("id", "");
                if (newId.isEmpty()) throw new IOException("网关未返回已保存的接口名称");

                final ProviderItem newItem = new ProviderItem(
                        "api:" + newId,
                        label,
                        "claude",
                        newId,
                        false,
                        0,
                        !TextUtils.isEmpty(apiKey),
                        ""
                );

                mainHandler.post(() -> {
                    hasChanges = true;
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    providerList.add(newItem);
                    selectProvider(newItem);
                    Toast.makeText(host, "已添加并切换至 " + label, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(host, "添加自定义厂商失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String requestHttp(String method, String localPath, String jsonBody) throws Exception {
        byte[] body = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : null;
        HttpURLConnection conn = Env.gateway(method, localPath, body);
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(90000);
            conn.setInstanceFollowRedirects(false);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            }
            int status = conn.getResponseCode();
            boolean success = status >= 200 && status < 300;
            try (InputStream in = success ? conn.getInputStream() : conn.getErrorStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                if (in != null) for (int count; (count = in.read(chunk)) != -1;) {
                    if (output.size() + count > (16 << 20)) throw new IOException("网关返回内容过大");
                    output.write(chunk, 0, count);
                }
                String response = output.toString("UTF-8");
                JSONObject result = new JSONObject(response.isEmpty() ? "{}" : response);
                if (!success || (result.has("ok") && !result.optBoolean("ok"))) {
                    throw new IOException(result.optString("message", result.optString("error", "网关请求失败 (HTTP " + status + ")")));
                }
                return result.toString();
            }
        } finally { conn.disconnect(); }
    }
}
