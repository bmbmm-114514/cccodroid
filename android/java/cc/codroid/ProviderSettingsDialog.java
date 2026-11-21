package cc.codroid;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
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

    private static class SpinnerOption {
        final String id;
        final String label;

        SpinnerOption(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Activity host;
    private final Runnable onChanged;
    private final float density;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isDismissed;
    private int modelRead;
    private boolean hasChanges = false;
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

    private boolean isBindingModel = false;
    private boolean isBindingEffort = false;
    private boolean isBindingTier = false;

    private AlertDialog dialog;
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
    private Spinner spinnerModel;
    private Button btnCustomModel;
    private LinearLayout layoutEffort;
    private Spinner spinnerEffort;
    private LinearLayout layoutTier;
    private Spinner spinnerTier;

    private String lastOAuthUri = "";

    public static void show(Activity host, Runnable onChanged) {
        if (host == null || host.isFinishing() || host.isDestroyed()) {
            return;
        }
        ProviderSettingsDialog instance = new ProviderSettingsDialog(host, onChanged);
        instance.present();
    }

    private ProviderSettingsDialog(Activity host, Runnable onChanged) {
        this.host = host;
        this.onChanged = onChanged;
        this.density = host.getResources().getDisplayMetrics().density;
    }

    private int dp(float val) {
        return (int) (val * density + 0.5f);
    }

    private void present() {
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        builder.setTitle("接口与模型");

        ScrollView scrollView = new ScrollView(host);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnPicker = createStyledButton("正在读取接口…");
        btnPicker.setOnClickListener(v -> showProviderChoiceDialog());
        root.addView(btnPicker);

        layoutError = new LinearLayout(host);
        layoutError.setOrientation(LinearLayout.HORIZONTAL);
        layoutError.setGravity(Gravity.CENTER_VERTICAL);
        layoutError.setPadding(0, dp(6), 0, dp(6));
        layoutError.setVisibility(View.GONE);

        tvError = new TextView(host);

        tvError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        layoutError.addView(tvError, errLp);

        Button btnRetry = createStyledButton("重试");
        btnRetry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnRetry.setOnClickListener(v -> reloadAll());
        layoutError.addView(btnRetry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(layoutError);

        progressBar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        cardAccount = createCardContainer();
        TextView tvAccTitle = createSectionTitle("官方账户登录");
        cardAccount.addView(tvAccTitle);

        tvAccountStatus = new TextView(host);
        tvAccountStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        tvAccountStatus.setPadding(0, dp(4), 0, dp(8));
        cardAccount.addView(tvAccountStatus);

        btnAccountAction = createStyledButton("登录");
        cardAccount.addView(btnAccountAction);

        layoutCodeVerify = new LinearLayout(host);
        layoutCodeVerify.setOrientation(LinearLayout.VERTICAL);
        layoutCodeVerify.setVisibility(View.GONE);
        layoutCodeVerify.setPadding(0, dp(6), 0, 0);

        tvUserCode = new TextView(host);
        tvUserCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvUserCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        tvUserCode.setPadding(0, dp(4), 0, dp(6));
        layoutCodeVerify.addView(tvUserCode);

        LinearLayout rowCodeButtons = new LinearLayout(host);
        rowCodeButtons.setOrientation(LinearLayout.HORIZONTAL);

        btnCopyCode = createStyledButton("复制验证码");
        LinearLayout.LayoutParams btnHalfLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        btnHalfLp1.setMargins(0, 0, dp(4), 0);
        rowCodeButtons.addView(btnCopyCode, btnHalfLp1);

        btnOpenUri = createStyledButton("打开验证网址");
        LinearLayout.LayoutParams btnHalfLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        btnHalfLp2.setMargins(dp(4), 0, 0, 0);
        rowCodeButtons.addView(btnOpenUri, btnHalfLp2);

        layoutCodeVerify.addView(rowCodeButtons);
        cardAccount.addView(layoutCodeVerify);

        layoutNeedsCode = new LinearLayout(host);
        layoutNeedsCode.setOrientation(LinearLayout.VERTICAL);
        layoutNeedsCode.setVisibility(View.GONE);
        layoutNeedsCode.setPadding(0, dp(8), 0, 0);

        etManualCode = createStyledEditText("在此输入或粘贴授权码...", false);
        layoutNeedsCode.addView(etManualCode);

        btnSubmitManualCode = createStyledButton("提交授权码");
        layoutNeedsCode.addView(btnSubmitManualCode);

        cardAccount.addView(layoutNeedsCode);
        root.addView(cardAccount);

        cardApiKey = createCardContainer();
        TextView tvKeyTitle = createSectionTitle("API 凭证设置");
        cardApiKey.addView(tvKeyTitle);

        tvKeyStatus = new TextView(host);
        tvKeyStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        tvKeyStatus.setPadding(0, dp(4), 0, dp(6));
        cardApiKey.addView(tvKeyStatus);

        etApiKey = createStyledEditText("输入新 API Key（留空不修改）...", true);
        cardApiKey.addView(etApiKey);

        btnSaveKey = createStyledButton("保存密钥");
        btnSaveKey.setOnClickListener(v -> saveApiKey());
        cardApiKey.addView(btnSaveKey);

        root.addView(cardApiKey);

        cardModel = createCardContainer();
        TextView tvModelTitle = createSectionTitle("模型与参数偏好");
        cardModel.addView(tvModelTitle);

        TextView tvModelLbl = createLabel("选择模型：");
        cardModel.addView(tvModelLbl);

        spinnerModel = new Spinner(host);
        cardModel.addView(spinnerModel);

        btnCustomModel = createStyledButton("输入自定义模型名称...");
        btnCustomModel.setVisibility(View.GONE);
        btnCustomModel.setOnClickListener(v -> showCustomModelDialog());
        cardModel.addView(btnCustomModel);

        layoutEffort = new LinearLayout(host);
        layoutEffort.setOrientation(LinearLayout.VERTICAL);
        layoutEffort.setVisibility(View.GONE);
        layoutEffort.setPadding(0, dp(6), 0, 0);

        TextView tvEffortLbl = createLabel("思考程度（Reasoning Effort）：");
        layoutEffort.addView(tvEffortLbl);

        spinnerEffort = new Spinner(host);
        layoutEffort.addView(spinnerEffort);
        cardModel.addView(layoutEffort);

        layoutTier = new LinearLayout(host);
        layoutTier.setOrientation(LinearLayout.VERTICAL);
        layoutTier.setVisibility(View.GONE);
        layoutTier.setPadding(0, dp(6), 0, 0);

        TextView tvTierLbl = createLabel("速度（Fast）");
        layoutTier.addView(tvTierLbl);

        spinnerTier = new Spinner(host);
        layoutTier.addView(spinnerTier);
        cardModel.addView(layoutTier);

        root.addView(cardModel);

        cardAccount.setVisibility(View.GONE);
        cardApiKey.setVisibility(View.GONE);
        cardModel.setVisibility(View.GONE);
        setupSpinners();

        builder.setView(scrollView);
        builder.setPositiveButton("完成", (d, which) -> d.dismiss());

        dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            isDismissed = true;
            generation++;
            stopPolling();
            if (hasChanges && onChanged != null) {
                executor.execute(() -> mainHandler.post(() -> {
                    if (!host.isFinishing() && !host.isDestroyed()) onChanged.run();
                }));
            }
            executor.shutdown();
        });

        dialog.show();
        reloadAll();
    }

    private LinearLayout createCardContainer() {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView createSectionTitle(String text) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);

        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private TextView createLabel(String text) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        tv.setPadding(0, dp(4), 0, dp(2));
        return tv;
    }

    private Button createStyledButton(String text) {
        Button btn = new Button(host);
        btn.setText(text);

        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTransformationMethod(null);
        int padH = dp(12);
        int padV = dp(8);
        btn.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    private EditText createStyledEditText(String hint, boolean isPassword) {
        EditText et = new EditText(host);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (isPassword) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            et.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        int pad = dp(10);
        et.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        et.setLayoutParams(lp);
        return et;
    }

    private void setupSpinners() {
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isBindingModel) return;
                SpinnerOption opt = (SpinnerOption) parent.getItemAtPosition(position);
                if (opt != null && !opt.id.equals(curModel)) {
                    curModel = opt.id;
                    updatePreferenceField("model", curModel);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerEffort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isBindingEffort) return;
                SpinnerOption opt = (SpinnerOption) parent.getItemAtPosition(position);
                if (opt != null && !opt.id.equals(curEffort)) {
                    curEffort = opt.id;
                    updatePreferenceField("effort", curEffort);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerTier.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isBindingTier) return;
                SpinnerOption opt = (SpinnerOption) parent.getItemAtPosition(position);
                if (opt != null && !opt.id.equals(curServiceTier)) {
                    curServiceTier = opt.id;
                    updatePreferenceField("serviceTier", curServiceTier);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void showProviderChoiceDialog() {
        if (providerList.isEmpty()) {
            Toast.makeText(host, "接口列表尚未加载", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> names = new ArrayList<>();
        int checkedIndex = -1;
        for (int i = 0; i < providerList.size(); i++) {
            ProviderItem item = providerList.get(i);
            names.add(item.label);
            if (currentItem != null && item.entryKey.equals(currentItem.entryKey)) {
                checkedIndex = i;
            }
        }
        names.add("＋ 添加自定义接口…");

        String[] itemsArray = names.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(host);
        b.setTitle("选择接口");
        b.setSingleChoiceItems(itemsArray, checkedIndex, (dialogInterface, which) -> {
            dialogInterface.dismiss();
            if (which == providerList.size()) {
                showAddCustomProviderDialog();
            } else {
                ProviderItem selected = providerList.get(which);
                selectProvider(selected);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void showAddCustomProviderDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(host);
        b.setTitle("添加自定义厂商");

        LinearLayout layout = new LinearLayout(host);
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
        b.show();
    }

    private void showCustomModelDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(host);
        b.setTitle("输入自定义模型");

        LinearLayout layout = new LinearLayout(host);
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
        b.show();
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

        selectProviderInternal(target, false);
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
        btnPicker.setText(item.label);
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
            hasChanges = true;
            progressBar.setVisibility(View.VISIBLE);
            executor.execute(() -> {
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

                            renderAccountState(stateObj);

                            boolean keepPolling = false;
                            if (currentItem.officialType == TYPE_CHATGPT) {
                                JSONObject cx = stateObj.optJSONObject("codex");
                                if (cx != null && cx.optJSONObject("login") != null) {
                                    keepPolling = true;
                                }
                            } else if (currentItem.officialType == TYPE_GOOGLE) {
                                JSONObject agy = stateObj.optJSONObject("agy");
                                if (agy != null && !agy.optBoolean("loggedIn", false) && agy.optJSONObject("login") != null) {
                                    keepPolling = true;
                                }
                            }

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
                String path = "/api/multi/models?agent=" + Uri.encode(item.agent) + "&model=" + Uri.encode(modelParam);
                path += "&provider=" + Uri.encode(item.providerId);
                String resp = requestHttp("GET", path, null);
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
        isBindingModel = true;
        bindSpinner(spinnerModel, obj.optJSONArray("models"), curModel,
                obj.optString("defaultModel", currentItem.defaultModel), true);
        isBindingModel = false;
        JSONArray efforts = obj.optJSONArray("efforts");
        boolean showEffort = efforts != null && efforts.length() > 0;
        layoutEffort.setVisibility(showEffort ? View.VISIBLE : View.GONE);
        if (showEffort) {
            isBindingEffort = true;
            bindSpinner(spinnerEffort, efforts, curEffort, obj.optString("defaultEffort"), false);
            isBindingEffort = false;
        }
        JSONArray tiers = obj.optJSONArray("serviceTiers");
        boolean showTier = currentItem.officialType == TYPE_CHATGPT && tiers != null && tiers.length() > 0;
        layoutTier.setVisibility(showTier ? View.VISIBLE : View.GONE);
        if (showTier) {
            isBindingTier = true;
            bindSpinner(spinnerTier, tiers, curServiceTier, obj.optString("defaultServiceTier"), false);
            isBindingTier = false;
        }
    }

    private void bindSpinner(Spinner spinner, JSONArray entries, String current, String defaultValue, boolean customModel) {
        List<SpinnerOption> options = new ArrayList<>();
        options.add(new SpinnerOption("", defaultValue.isEmpty() ? "引擎默认" : "默认（" + defaultValue + "）"));
        int selected = 0;
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id");
            if (id.isEmpty()) continue;
            if (id.equals(current)) selected = options.size();
            options.add(new SpinnerOption(id, entry.optString("label", id)));
        }
        if (!current.isEmpty() && selected == 0) {
            selected = options.size();
            options.add(new SpinnerOption(current, current + (customModel ? "（自定义）" : "（已保存）")));
        }
        ArrayAdapter<SpinnerOption> adapter = new ArrayAdapter<>(host, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
    }

    private void bindFallbackCapabilities() {
        List<SpinnerOption> list = new ArrayList<>();
        list.add(new SpinnerOption("", "默认模型"));
        if (!TextUtils.isEmpty(curModel)) {
            list.add(new SpinnerOption(curModel, curModel));
        }
        isBindingModel = true;
        ArrayAdapter<SpinnerOption> adapter = new ArrayAdapter<>(host, android.R.layout.simple_spinner_dropdown_item, list);
        spinnerModel.setAdapter(adapter);
        spinnerModel.setSelection(list.size() > 1 ? 1 : 0);
        isBindingModel = false;

        layoutEffort.setVisibility(View.GONE);
        layoutTier.setVisibility(View.GONE);
    }

    private void updatePreferenceField(String key, String value) {
        if (currentItem == null) return;
        hasChanges = true;
        final int gen = generation;
        final ProviderItem item = currentItem;

        final String model = curModel;
        if ("model".equals(key)) {
            layoutEffort.setVisibility(View.GONE);
            layoutTier.setVisibility(View.GONE);
        }
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("agent", item.agent).put("provider", item.providerId)
                        .put("model", model).put(key, value);
                JSONObject saved = new JSONObject(requestHttp("POST", "/api/multi/preferences", body.toString()));
                if ("model".equals(key)) mainHandler.post(() -> {
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

        executor.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("id", target.providerId);
                b.put("apiKey", key);
                requestHttp("POST", "/api/providers/save", b.toString());

                mainHandler.post(() -> {
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    btnSaveKey.setEnabled(true);
                    currentItem.hasKey = true;
                    tvKeyStatus.setText("已配置 API 密钥");
                    etApiKey.setText("");
                    hasChanges = true;
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

        executor.execute(() -> {
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
                    if (stale(gen)) return;
                    progressBar.setVisibility(View.GONE);
                    providerList.add(newItem);
                    hasChanges = true;
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
