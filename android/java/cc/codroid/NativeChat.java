package cc.codroid;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class NativeChat {

    private final NativeHome h;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String currentChatId = "";
    private int activeGeneration = -1;
    private boolean isSessionsMode = false;
    private boolean isBusy = false;
    private boolean isSending = false;

    private JSONObject lastState = null;
    private String lastTurnsStamp = "";
    private String providersStamp = "";
    String bookmarkTurn;

    // Chat UI elements
    private TextView titleView;
    private TextView projectChipView;
    private TextView quotaBannerView;
    private Button providerButton;
    private ScrollView chatScrollView;
    private LinearLayout turnsContainer;
    private LinearLayout liveRunContainer;
    private LinearLayout approvalsContainer;

    // Handoff controls
    private LinearLayout handoffSection;
    private TextView handoffTransferLabel;
    private Spinner handoffModeSpinner;
    private LinearLayout thirdPartyRow;
    private Spinner handoffProviderSpinner;
    private EditText handoffModelEdit;
    private LinearLayout handoffManualLayout;
    private LinearLayout handoffChecksContainer;
    private final Set<String> selectedTurnIds = new HashSet<>();

    // Composer controls
    private EditText inputField;
    private Button sendStopButton;
    private ProgressBar sendProgress;

    // Cached lists from multi/state
    private final List<JSONObject> cachedProviders = new ArrayList<>();
    private final List<JSONObject> cachedProjects = new ArrayList<>();
    private final List<JSONObject> currentTurns = new ArrayList<>();

    public NativeChat(NativeHome h) {
        this.h = h;
    }

    public void show(String id) {
        activeGeneration = h.generation;
        handler.removeCallbacks(pollRunnable);

        if ("sessions".equalsIgnoreCase(id) || "history".equalsIgnoreCase(id)) {
            isSessionsMode = true;
            renderSessionsScreen();
            refreshSessions();
            return;
        }

        isSessionsMode = false;
        currentChatId = h.chatId;
        lastTurnsStamp = "";
        lastState = null;
        isBusy = false;
        selectedTurnIds.clear();
        providersStamp = "";
        renderChatScreen();
        updateComposerButtons();
        loadDraft();
        refreshChat();
    }

    void leave() {
        handler.removeCallbacks(pollRunnable);
        isSending = false;
        activeGeneration = -1;
    }

    public void compose(String incoming) {
        if (incoming == null) return;
        if (isSessionsMode || inputField == null) {
            show(currentChatId);
        }
        if (inputField != null) {
            String current = inputField.getText().toString();
            if (current.trim().isEmpty()) {
                inputField.setText(incoming);
            } else {
                inputField.setText(current + "\n" + incoming);
            }
            inputField.setSelection(inputField.getText().length());
            saveDraft();
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDestroyedOrNavigated()) {
                return;
            }
            if (isSessionsMode) {
                refreshSessions();
            } else {
                refreshChat();
            }
        }
    };

    private boolean isDestroyedOrNavigated() {
        return h.generation != activeGeneration || h.a.isFinishing() || h.a.isDestroyed();
    }

    private void scheduleRefresh(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        if (isDestroyedOrNavigated()) {
            return;
        }
        handler.postDelayed(pollRunnable, delayMs);
    }

    // ==========================================
    // Chat Screen Layout & Rendering
    // ==========================================

    private void renderChatScreen() {
        h.body.removeAllViews();
        h.body.setOrientation(LinearLayout.VERTICAL);

        // 1. Header Bar
        LinearLayout headerBar = new LinearLayout(h.a);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setPadding(h.dp(10), h.dp(6), h.dp(10), h.dp(6));
        headerBar.setBackground(makeDividerDrawable(false));
        h.body.addView(headerBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button sessionsBtn = createHeaderButton("会话", v -> h.nav("sessions"));
        headerBar.addView(sessionsBtn);

        LinearLayout titleArea = new LinearLayout(h.a);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setPadding(h.dp(8), 0, h.dp(8), 0);
        titleArea.setClickable(true);
        titleArea.setOnClickListener(v -> {
            if (!currentChatId.isEmpty()) {
                String curTitle = titleView != null ? titleView.getText().toString() : "";
                showRenameDialog(currentChatId, curTitle);
            }
        });
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        headerBar.addView(titleArea, titleLp);

        titleView = new TextView(h.a);
        titleView.setText("新对话");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
        titleView.setSingleLine(true);
        titleArea.addView(titleView);

        projectChipView = new TextView(h.a);
        projectChipView.setText("默认项目");
        projectChipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        projectChipView.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
        projectChipView.setSingleLine(true);
        titleArea.addView(projectChipView);

        projectChipView.setOnClickListener(v -> showProjectPickerDialog());

        providerButton = createHeaderButton("接口与模型", v -> h.nav("provider"));
        providerButton.setMaxWidth(h.dp(150));
        providerButton.setSingleLine(true);
        providerButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerBar.addView(providerButton);

        // 2. Low Quota Banner (<= 5)
        quotaBannerView = new TextView(h.a);
        quotaBannerView.setVisibility(View.GONE);
        quotaBannerView.setPadding(h.dp(12), h.dp(6), h.dp(12), h.dp(6));
        quotaBannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        quotaBannerView.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
        quotaBannerView.setBackground(makeWarningDrawable());
        h.body.addView(quotaBannerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Weighted ScrollView for Messages
        chatScrollView = new ScrollView(h.a);
        chatScrollView.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        h.body.addView(chatScrollView, scrollLp);

        turnsContainer = new LinearLayout(h.a);
        turnsContainer.setOrientation(LinearLayout.VERTICAL);
        turnsContainer.setPadding(h.dp(12), h.dp(8), h.dp(12), h.dp(8));
        chatScrollView.addView(turnsContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Approvals container
        approvalsContainer = new LinearLayout(h.a);
        approvalsContainer.setOrientation(LinearLayout.VERTICAL);
        approvalsContainer.setPadding(0, h.dp(4), 0, h.dp(4));
        turnsContainer.addView(approvalsContainer);

        // Live bot streaming container
        liveRunContainer = new LinearLayout(h.a);
        liveRunContainer.setOrientation(LinearLayout.VERTICAL);
        liveRunContainer.setVisibility(View.GONE);
        liveRunContainer.setPadding(0, h.dp(4), 0, h.dp(4));
        turnsContainer.addView(liveRunContainer);

        // 4. Composer Container at bottom (Never recreated during polling)
        LinearLayout composerContainer = new LinearLayout(h.a);
        composerContainer.setOrientation(LinearLayout.VERTICAL);
        composerContainer.setPadding(h.dp(8), h.dp(6), h.dp(8), h.dp(8));
        composerContainer.setBackground(makeDividerDrawable(true));
        h.body.addView(composerContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHandoffSection(composerContainer);

        // Input row
        LinearLayout inputRow = new LinearLayout(h.a);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        composerContainer.addView(inputRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button actions = createHeaderButton("＋", v -> NativeChatActions.show(h, v));
        actions.setContentDescription("附件与会话操作");
        actions.setMinWidth(h.dp(44));
        actions.setMinimumWidth(h.dp(44));
        inputRow.addView(actions);
        inputField = new EditText(h.a);
        inputField.setHint("输入消息…");
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        inputField.setMinLines(1);
        inputField.setMaxLines(5);
        inputField.setPadding(h.dp(10), h.dp(8), h.dp(10), h.dp(8));
        inputField.setBackground(makeInputDrawable());
        inputField.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
        inputField.setHintTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));

        inputField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { saveDraft(); }
        });

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        inputLp.rightMargin = h.dp(6);
        inputRow.addView(inputField, inputLp);

        sendProgress = new ProgressBar(h.a, null, android.R.attr.progressBarStyleSmall);
        sendProgress.setVisibility(View.GONE);
        inputRow.addView(sendProgress);

        sendStopButton = new Button(h.a);
        sendStopButton.setText("发送");
        sendStopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sendStopButton.setPadding(h.dp(12), h.dp(6), h.dp(12), h.dp(6));
        sendStopButton.setOnClickListener(v -> {
            if (isBusy) {
                confirmStop();
            } else {
                performSend();
            }
        });
        inputRow.addView(sendStopButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildHandoffSection(LinearLayout parent) {
        handoffSection = new LinearLayout(h.a);
        handoffSection.setOrientation(LinearLayout.VERTICAL);
        handoffSection.setVisibility(View.GONE);
        handoffSection.setPadding(0, 0, 0, h.dp(6));
        parent.addView(handoffSection, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout modeRow = new LinearLayout(h.a);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        handoffSection.addView(modeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handoffTransferLabel = new TextView(h.a);
        handoffTransferLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        handoffTransferLabel.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
        modeRow.addView(handoffTransferLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        handoffModeSpinner = new Spinner(h.a);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"自动交接", "手动选择", "第三方接口"});
        handoffModeSpinner.setAdapter(modeAdapter);
        handoffModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
                updateHandoffSubControls(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parentView) {}
        });
        modeRow.addView(handoffModeSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Third party row
        thirdPartyRow = new LinearLayout(h.a);
        thirdPartyRow.setOrientation(LinearLayout.HORIZONTAL);
        thirdPartyRow.setGravity(Gravity.CENTER_VERTICAL);
        thirdPartyRow.setVisibility(View.GONE);
        thirdPartyRow.setPadding(0, h.dp(4), 0, 0);
        handoffSection.addView(thirdPartyRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handoffProviderSpinner = new Spinner(h.a);
        thirdPartyRow.addView(handoffProviderSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        handoffModelEdit = new EditText(h.a);
        handoffModelEdit.setHint("模型名称");
        handoffModelEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        handoffModelEdit.setSingleLine(true);
        handoffModelEdit.setBackground(makeInputDrawable());
        handoffModelEdit.setPadding(h.dp(8), h.dp(4), h.dp(8), h.dp(4));
        thirdPartyRow.addView(handoffModelEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        handoffProviderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < cachedProviders.size()) {
                    String defModel = cachedProviders.get(position).optString("model", "");
                    handoffModelEdit.setText(defModel);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Manual turns select row
        handoffManualLayout = new LinearLayout(h.a);
        handoffManualLayout.setOrientation(LinearLayout.VERTICAL);
        handoffManualLayout.setVisibility(View.GONE);
        handoffManualLayout.setPadding(0, h.dp(4), 0, 0);
        handoffSection.addView(handoffManualLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView manualNotice = new TextView(h.a);
        manualNotice.setText("勾选需要传递给新 Agent 的上下文回合：");
        manualNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        manualNotice.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
        handoffManualLayout.addView(manualNotice);

        handoffChecksContainer = new LinearLayout(h.a);
        handoffChecksContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView checkScroll = new ScrollView(h.a);
        checkScroll.addView(handoffChecksContainer);
        handoffManualLayout.addView(checkScroll, new LinearLayout.LayoutParams(-1, h.dp(110)));
    }

    private void updateHandoffSubControls(int position) {
        if (thirdPartyRow != null) {
            thirdPartyRow.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        }
        if (handoffManualLayout != null) {
            handoffManualLayout.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        }
    }

    // ==========================================
    // Network Refresh & State Sync
    // ==========================================

    void refreshSelection() { if (!isSessionsMode) refreshChat(); }

    private void refreshChat() {
        if (isDestroyedOrNavigated()) return;
        String path = "/api/multi/state" + (currentChatId.isEmpty() ? "" : "?id=" + Uri.encode(currentChatId));

        final int screen = h.generation;
        NativeRequests.call(h.a, "GET", path, null, data -> {
            if (screen == h.generation && !isDestroyedOrNavigated()) handleChatStateResponse(data);
        }, error -> {
            if (screen != h.generation || isDestroyedOrNavigated()) return;
            titleView.setText(error);
            scheduleRefresh(5000);
        });
    }

    private void handleChatStateResponse(JSONObject d) {
        if (d == null) {
            scheduleRefresh(5000);
            return;
        }
        lastState = d;

        // Parse Providers
        JSONArray providersArr = d.optJSONArray("providers");
        cachedProviders.clear();
        List<String> providerLabels = new ArrayList<>();
        if (providersArr != null) {
            for (int i = 0; i < providersArr.length(); i++) {
                JSONObject p = providersArr.optJSONObject(i);
                if (p != null) {
                    cachedProviders.add(p);
                    providerLabels.add(p.optString("label", p.optString("id")));
                }
            }
        }
        String version = providersArr == null ? "[]" : providersArr.toString();
        if (handoffProviderSpinner != null && !providersStamp.equals(version)) {
            providersStamp = version;
            ArrayAdapter<String> pAdapter = new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item, providerLabels);
            handoffProviderSpinner.setAdapter(pAdapter);
        }

        // Parse Projects
        JSONArray projsArr = d.optJSONArray("projects");
        cachedProjects.clear();
        if (projsArr != null) {
            for (int i = 0; i < projsArr.length(); i++) {
                JSONObject pr = projsArr.optJSONObject(i);
                if (pr != null) cachedProjects.add(pr);
            }
        }

        // Quota check


        // Conversation parse
        JSONObject c = d.optJSONObject("conversation");
        JSONObject run = c != null ? c.optJSONObject("activeRun") : null;
        isBusy = run != null;

        if (c != null) {
            String title = c.optString("title", "对话");
            if (titleView != null) titleView.setText(title);
            String pId = c.optString("projectId", h.projectId);
            h.projectId = pId;
            if (projectChipView != null) {
                projectChipView.setText("项目: " + getProjectName(pId));
            }

        } else {
            if (titleView != null) titleView.setText("新对话");
            if (projectChipView != null) {
                projectChipView.setText("项目: " + getProjectName(h.projectId));
            }

        }

        paintConversation(c, run);
        paintHandoffControls(c);
        updateComposerButtons();

        scheduleRefresh(isBusy ? 700 : 5000);
    }

    private void checkQuotaReminder(JSONObject quota, String source, String target) {
        if (quotaBannerView == null) return;
        List<String> alerts = new ArrayList<>();
        for (String agent : new String[]{"claude", "codex", "agy"}) {
            if (!agent.equals(source) && !agent.equals(target)) continue;
            JSONArray windows = quota == null ? null : quota.optJSONArray(agent);
            for (int i = 0; windows != null && i < windows.length(); i++) {
                JSONObject window = windows.optJSONObject(i);
                double remaining = window == null ? -1 : window.optDouble("remaining", -1);
                if (remaining >= 0 && remaining <= 5) alerts.add(multiName(agent) + " " + window.optString("name") + "剩余 " + remaining + "%");
            }
        }
        quotaBannerView.setVisibility(alerts.isEmpty() ? View.GONE : View.VISIBLE);
        quotaBannerView.setText(android.text.TextUtils.join(" · ", alerts) + "　选择接口");
        quotaBannerView.setOnClickListener(v -> h.nav("provider"));
    }

    private void paintConversation(JSONObject c, JSONObject run) {
        if (turnsContainer == null || chatScrollView == null) return;

        JSONArray turns = c != null ? c.optJSONArray("turns") : null;
        String stamp = (turns == null ? "" : turns.toString()) + (run != null);
        boolean turnsChanged = !stamp.equals(lastTurnsStamp);

        // Check if reader is near bottom before updating
        int scrollDiff = chatScrollView.getChildAt(0).getBottom() - (chatScrollView.getHeight() + chatScrollView.getScrollY());
        boolean nearBottom = scrollDiff <= h.dp(80) || lastTurnsStamp.isEmpty();

        if (turnsChanged) {
            lastTurnsStamp = stamp;
            currentTurns.clear();

            // Clear turns but keep liveRunContainer and approvalsContainer
            turnsContainer.removeAllViews();
            turnsContainer.addView(approvalsContainer);
            turnsContainer.addView(liveRunContainer);

            if (turns != null) {
                for (int i = 0; i < turns.length(); i++) {
                    JSONObject turn = turns.optJSONObject(i);
                    if (turn == null) continue;
                    currentTurns.add(turn);

                    // If last turn is running and run is active, the assistant is live in run
                    boolean isLast = (i == turns.length() - 1);
                    boolean turnRunning = "running".equals(turn.optString("status"));
                    View turnView = buildTurnView(turn, isLast && turnRunning && run != null);
                    turnsContainer.addView(turnView, turnsContainer.indexOfChild(approvalsContainer));
                }
            }
            updateManualHandoffCheckboxes();
        }

        // Approvals
        approvalsContainer.removeAllViews();
        if (run != null) {
            JSONArray approvals = run.optJSONArray("approvals");
            if (approvals != null && approvals.length() > 0) {
                for (int i = 0; i < approvals.length(); i++) {
                    JSONObject app = approvals.optJSONObject(i);
                    if (app != null) {
                        approvalsContainer.addView(buildApprovalItem(app));
                    }
                }
            }
        }

        // Live bot run
        if (run != null) {
            liveRunContainer.setVisibility(View.VISIBLE);
            liveRunContainer.removeAllViews();

            String stage = run.optString("stage");
            String title = "handoff".equals(stage) ? "正在整理交接内容…" : multiName(run.optString("agent"));
            String text = run.optString("text", "进行中…");

            TextView who = new TextView(h.a);
            who.setText(title);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            who.setTypeface(Typeface.DEFAULT_BOLD);
            who.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
            liveRunContainer.addView(who);

            TextView bubble = new TextView(h.a);
            bubble.setText(renderMarkdown(text));
            bubble.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            bubble.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
            bubble.setBackground(makeBubbleDrawable(false));
            bubble.setPadding(h.dp(10), h.dp(8), h.dp(10), h.dp(8));
            LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleLp.topMargin = h.dp(4);
            liveRunContainer.addView(bubble, bubbleLp);
        } else {
            liveRunContainer.setVisibility(View.GONE);
        }

        View marked = bookmarkTurn == null ? null : turnsContainer.findViewWithTag(bookmarkTurn);
        if (marked != null) {
            bookmarkTurn = null;
            chatScrollView.post(() -> chatScrollView.scrollTo(0, marked.getTop()));
        } else if (nearBottom) {
            chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private View buildTurnView(JSONObject turn, boolean suppressAssistant) {
        LinearLayout row = new LinearLayout(h.a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, h.dp(6), 0, h.dp(6));
        row.setTag(turn.optString("id"));

        // User bubble
        String userText = turn.optString("user", "");
        if (!userText.isEmpty()) {
            LinearLayout userWrapper = new LinearLayout(h.a);
            userWrapper.setOrientation(LinearLayout.HORIZONTAL);
            userWrapper.setGravity(Gravity.RIGHT);

            TextView userBubble = new TextView(h.a);
            userBubble.setText(renderMarkdown(userText));
            turnActions(userBubble, turn, "user");
            userBubble.setMovementMethod(LinkMovementMethod.getInstance());
            userBubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            userBubble.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
            userBubble.setBackground(makeBubbleDrawable(true));
            userBubble.setPadding(h.dp(10), h.dp(8), h.dp(10), h.dp(8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = h.dp(40);
            userWrapper.addView(userBubble, lp);
            row.addView(userWrapper);
        }

        // Assistant bubble
        if (!suppressAssistant) {
            String assistantText = turn.optString("assistant", "");
            String agent = turn.optString("agent", "");

            if (!assistantText.isEmpty() || !agent.isEmpty()) {
                LinearLayout botWrapper = new LinearLayout(h.a);
                botWrapper.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams botWrapperLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                botWrapperLp.topMargin = h.dp(6);
                botWrapperLp.rightMargin = h.dp(30);

                TextView who = new TextView(h.a);
                who.setText(multiName(agent));
                who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                who.setTypeface(Typeface.DEFAULT_BOLD);
                who.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
                botWrapper.addView(who);

                if (!assistantText.isEmpty()) {
                    TextView botBubble = new TextView(h.a);
                    botBubble.setText(renderMarkdown(assistantText));
                    turnActions(botBubble, turn, "assistant");
                    botBubble.setMovementMethod(LinkMovementMethod.getInstance());
                    botBubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    botBubble.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
                    botBubble.setBackground(makeBubbleDrawable(false));
                    botBubble.setPadding(h.dp(10), h.dp(8), h.dp(10), h.dp(8));

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = h.dp(3);
                    botWrapper.addView(botBubble, lp);
                }
                row.addView(botWrapper, botWrapperLp);
            }
        }

        // Handoff record detail
        JSONObject handoff = turn.optJSONObject("handoff");
        if (handoff != null && !handoff.optString("text", "").isEmpty()) {
            String from = multiName(handoff.optString("from"));
            String to = multiName(turn.optString("agent"));
            String title = "交接记录 · " + from + " → " + to;
            String modeStr = "third-party".equals(handoff.optString("mode")) ? handoff.optString("provider")
                    : ("manual".equals(handoff.optString("mode")) ? "手动选择" : from);

            TextView content = new TextView(h.a);
            content.setText(modeStr + "\n" + handoff.optString("text"));
            content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            content.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));

            row.addView(createCollapsible(title, content));
        }

        // Tools detail
        JSONArray tools = turn.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            LinearLayout toolsLayout = new LinearLayout(h.a);
            toolsLayout.setOrientation(LinearLayout.VERTICAL);

            for (int t = 0; t < tools.length(); t++) {
                JSONObject tool = tools.optJSONObject(t);
                if (tool == null) continue;
                TextView toolLabel = new TextView(h.a);
                toolLabel.setText("🔧 " + tool.optString("label", "工具调用"));
                toolLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                toolLabel.setTypeface(Typeface.DEFAULT_BOLD);
                toolLabel.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
                toolsLayout.addView(toolLabel);

                TextView toolText = new TextView(h.a);
                toolText.setText(tool.optString("text", ""));
                toolText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                toolText.setTypeface(Typeface.MONOSPACE);
                toolText.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
                toolsLayout.addView(toolText);
            }
            row.addView(createCollapsible("工具调用 · " + tools.length() + " 项", toolsLayout));
        }

        // Turn error or stopped with retry
        String error = turn.optString("error", "");
        String status = turn.optString("status", "");
        String problem = !error.isEmpty() ? error : ("failed".equals(status) ? "出错" : ("stopped".equals(status) ? "已停止" : ""));
        if (!problem.isEmpty()) {
            LinearLayout errorRow = new LinearLayout(h.a);
            errorRow.setOrientation(LinearLayout.HORIZONTAL);
            errorRow.setGravity(Gravity.CENTER_VERTICAL);
            errorRow.setPadding(0, h.dp(4), 0, 0);

            TextView errTv = new TextView(h.a);
            errTv.setText("⚠️ " + problem);
            errTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            errTv.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
            errorRow.addView(errTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            Button retryBtn = new Button(h.a);
            retryBtn.setText("重发");
            retryBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            retryBtn.setOnClickListener(v -> {
                if (inputField != null) {
                    inputField.setText(userText);
                    inputField.setSelection(userText.length());
                }
                performSend();
            });
            errorRow.addView(retryBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(errorRow);
        }

        return row;
    }

    private void turnActions(TextView text, JSONObject turn, String role) {
        text.setOnLongClickListener(v -> {
            android.widget.PopupMenu actions = new android.widget.PopupMenu(h.a, text);
            actions.getMenu().add("复制").setOnMenuItemClickListener(m -> {
                android.content.ClipboardManager clipboard = h.a.getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", turn.optString(role)));
                return true;
            });
            actions.getMenu().add("添加书签").setOnMenuItemClickListener(m -> {
                h.post("/api/bookmarks", NativeHome.json("session", "multi:"+currentChatId, "uuid", turn.optString("id"), "role", role),
                    result -> h.notice("已添加书签"));
                return true;
            });
            actions.show();
            return true;
        });
    }

    private View buildApprovalItem(JSONObject app) {
        LinearLayout row = new LinearLayout(h.a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(makeBoxDrawable());
        row.setPadding(h.dp(10), h.dp(6), h.dp(10), h.dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = h.dp(4);
        row.setLayoutParams(lp);

        TextView label = new TextView(h.a);
        label.setText("操作授权: " + app.optString("label", "请求确认"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final String appId = app.optString("id");
        Button allowBtn = new Button(h.a);
        allowBtn.setText("允许");
        allowBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        allowBtn.setOnClickListener(v -> sendApproval(appId, true));
        row.addView(allowBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button denyBtn = new Button(h.a);
        denyBtn.setText("拒绝");
        denyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        denyBtn.setOnClickListener(v -> sendApproval(appId, false));
        row.addView(denyBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private void sendApproval(String approvalId, boolean allow) {
        if (currentChatId.isEmpty()) return;
        JSONObject body = NativeHome.json("id", currentChatId, "approvalId", approvalId, "allow", allow);
        h.post("/api/multi/approve", body, res -> refreshChat());
    }

    private void paintHandoffControls(JSONObject c) {
        if (handoffSection == null) return;
        String source = c != null && !c.isNull("activeAgent") ? c.optString("activeAgent") : "";
        // Check target agent from preferences
        h.get("/api/multi/preferences", pref -> {
            if (isDestroyedOrNavigated()) return;
            String target = pref != null ? pref.optString("agent", "claude") : "claude";
            String modelName = pref.optString("model");
            providerButton.setText(modelName.isEmpty() ? multiName(target) : modelName);
            providerButton.setContentDescription("接口与模型，当前 " + multiName(target) + " " + modelName);
            checkQuotaReminder(lastState.optJSONObject("quota"), source, target);
            boolean switching = !source.isEmpty() && !isBusy && !source.equals(target);

            if (switching) {
                handoffSection.setVisibility(View.VISIBLE);
                handoffTransferLabel.setText("交接: " + multiName(source) + " → " + multiName(target));
                if ("agy".equals(source) && handoffModeSpinner != null) {
                    handoffModeSpinner.setSelection(1); // manual only for agy
                    handoffModeSpinner.setEnabled(false);
                } else if (handoffModeSpinner != null) {
                    handoffModeSpinner.setEnabled(true);
                }
            } else {
                handoffSection.setVisibility(View.GONE);
            }
        });
    }

    private void updateManualHandoffCheckboxes() {
        if (handoffChecksContainer == null) return;
        handoffChecksContainer.removeAllViews();

        for (JSONObject turn : currentTurns) {
            final String tid = turn.optString("id");
            String summary = turn.optString("user", "");
            if (summary.length() > 60) summary = summary.substring(0, 60) + "…";
            String label = multiName(turn.optString("agent")) + " · " + summary;

            CheckBox cb = new CheckBox(h.a);
            cb.setText(label);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cb.setChecked(selectedTurnIds.contains(tid));
            cb.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) selectedTurnIds.add(tid);
                else selectedTurnIds.remove(tid);
            });
            handoffChecksContainer.addView(cb);
        }
    }

    private void updateComposerButtons() {
        if (sendStopButton == null) return;
        if (isBusy) {
            sendStopButton.setText("停止");
            sendStopButton.setEnabled(!isSending && (currentChatId.isEmpty() || lastState != null));
        } else {
            sendStopButton.setText("发送");
            sendStopButton.setEnabled(!isSending && (currentChatId.isEmpty() || lastState != null));
        }
        if (sendProgress != null) {
            sendProgress.setVisibility(isSending ? View.VISIBLE : View.GONE);
        }
    }

    // ==========================================
    // Send / Stop / Create Flow
    // ==========================================

    private void performSend() {
        if (isSending || isBusy) return;
        final String text = inputField != null ? inputField.getText().toString().trim() : "";
        if (text.isEmpty()) {
            h.notice("请输入内容");
            return;
        }

        isSending = true;
        updateComposerButtons();

        // 1. Get preferences
        requestSend("GET", "/api/multi/preferences", null, pref -> {
            if (isDestroyedOrNavigated()) {
                isSending = false;
                updateComposerButtons();
                return;
            }
            String prefAgent = pref != null ? pref.optString("agent", "claude") : "claude";
            String prefModel = pref != null ? pref.optString("model", "") : "";
            String prefProvider = pref != null ? pref.optString("provider", "") : "";
            String effort = pref != null ? pref.optString("effort", "") : "";
            String serviceTier = pref != null ? pref.optString("serviceTier", "") : "";

            ensureCreatedThenSend(text, prefAgent, prefModel, prefProvider, effort, serviceTier);
        });
    }

    private void ensureCreatedThenSend(String text, String agent, String model, String provider, String effort, String serviceTier) {
        if (currentChatId.isEmpty()) {
            JSONObject createBody = new JSONObject();
            try {
                if (h.projectId != null && !h.projectId.isEmpty()) {
                    createBody.put("projectId", h.projectId);
                }
                createBody.put("title", text.length() > 65 ? text.substring(0, 65) : text);
            } catch (Exception ignored) {}

            requestSend("POST", "/api/multi/create", createBody, createRes -> {
                if (isDestroyedOrNavigated()) {
                    isSending = false;
                    updateComposerButtons();
                    return;
                }
                String newId = createRes != null ? createRes.optString("id", "") : "";
                if (newId.isEmpty()) {
                    isSending = false;
                    updateComposerButtons();
                    h.notice("创建会话失败，请重试");
                    return;
                }
                clearDraft("blank");
                currentChatId = newId;
                h.chatId = newId;
                h.remember();
                saveDraft();

                doSendRequest(text, agent, model, provider, effort, serviceTier);
            });
        } else {
            doSendRequest(text, agent, model, provider, effort, serviceTier);
        }
    }

    private void requestSend(String verb, String path, JSONObject body, java.util.function.Consumer<JSONObject> success) {
        final int screen = h.generation;
        NativeRequests.call(h.a, verb, path, body, data -> {
            if (h.generation == screen) success.accept(data);
        }, error -> {
            if (h.generation != screen) return;
            isSending = false;
            updateComposerButtons();
            h.notice(error);
        });
    }

    private void doSendRequest(String text, String agent, String model, String provider, String effort, String serviceTier) {
        JSONObject body = new JSONObject();


        try {
            body.put("id", currentChatId);

            body.put("agent", agent);
            body.put("text", text);
            body.put("destinationModel", model);
            body.put("destinationProvider", provider);
            body.put("effort", effort);
            body.put("serviceTier", serviceTier);

            String source = lastState != null && lastState.optJSONObject("conversation") != null
                    ? lastState.optJSONObject("conversation").optString("activeAgent") : "";
            boolean switching = !source.isEmpty() && !source.equals(agent);

            String mode = "manual";
            if (switching && handoffModeSpinner != null) {
                int pos = handoffModeSpinner.getSelectedItemPosition();
                if (pos == 0) mode = "outgoing";
                else if (pos == 1) mode = "manual";
                else if (pos == 2) mode = "third-party";
            }
            body.put("mode", mode);

            JSONArray selArray = new JSONArray();
            if ("manual".equals(mode)) {
                for (String tid : selectedTurnIds) {
                    selArray.put(tid);
                }
            }
            body.put("selected", selArray);

            if ("third-party".equals(mode)) {
                int pPos = handoffProviderSpinner != null ? handoffProviderSpinner.getSelectedItemPosition() : -1;
                String tpProv = pPos >= 0 && pPos < cachedProviders.size() ? cachedProviders.get(pPos).optString("id") : "";
                String tpMod = handoffModelEdit != null ? handoffModelEdit.getText().toString().trim() : "";
                body.put("provider", tpProv);
                body.put("model", tpMod);
            }
        } catch (Exception ignored) {}

        final String owner = currentChatId;
        final String key = "request_" + owner;
        // Match a retry after an Activity restart to the same accepted gateway turn.
        String signature = UUID.nameUUIDFromBytes(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String saved = getPrefs().getString(key, "");
        final String request = saved.startsWith(signature + "/") ? saved.substring(signature.length()+1) : UUID.randomUUID().toString();
        getPrefs().edit().putString(key, signature + "/" + request).apply();
        try { body.put("requestId", request); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        final int screen = h.generation;
        NativeRequests.call(h.a, "POST", "/api/multi/send", body, sendRes -> {
            SharedPreferences prefs = getPrefs();
            if (prefs.getString(key, "").equals(signature + "/" + request)) prefs.edit().remove(key).apply();
            if (text.equals(prefs.getString(getDraftKey(owner), "").trim())) clearDraft(owner);
            if (screen != h.generation) return;
            isSending = false;
            if (inputField != null && text.equals(inputField.getText().toString().trim())) inputField.setText("");
            selectedTurnIds.clear();
            refreshChat();
        }, errMsg -> {
            if (screen != h.generation) return;
            isSending = false;
            if (isDestroyedOrNavigated()) return;
            h.notice("发送失败: " + (errMsg != null ? errMsg : "未知错误"));
            updateComposerButtons();
        });
    }

    private void confirmStop() {
        if (currentChatId.isEmpty()) return;
        new AlertDialog.Builder(h.a)
                .setTitle("停止生成")
                .setMessage("确定要中断当前 AI 回复的生成吗？")
                .setPositiveButton("停止", (dialog, which) -> {
                    JSONObject body = NativeHome.json("id", currentChatId);
                    h.post("/api/multi/stop", body, res -> {
                        h.notice("已请求停止生成");
                        refreshChat();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==========================================
    // Sessions / History List Screen
    // ==========================================

    private void renderSessionsScreen() {
        h.body.removeAllViews();
        h.body.setOrientation(LinearLayout.VERTICAL);

        // Header
        LinearLayout headerBar = new LinearLayout(h.a);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setPadding(h.dp(10), h.dp(6), h.dp(10), h.dp(6));
        headerBar.setBackground(makeDividerDrawable(false));
        h.body.addView(headerBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button backBtn = createHeaderButton("← 返回", v -> h.showChat(currentChatId));
        headerBar.addView(backBtn);

        TextView headTitle = new TextView(h.a);
        headTitle.setText("历史会话");
        headTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        headTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headTitle.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
        headTitle.setPadding(h.dp(8), 0, h.dp(8), 0);
        headerBar.addView(headTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button newBtn = createHeaderButton("+新建", v -> {
            h.showChat("");
        });
        headerBar.addView(newBtn);

        Button settingsBtn = createHeaderButton("管理", v -> NativeHistoryActions.show(h, v));
        headerBar.addView(settingsBtn);

        // List ScrollView
        ScrollView listScroll = new ScrollView(h.a);
        listScroll.setFillViewport(true);
        h.body.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout listContainer = new LinearLayout(h.a);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(h.dp(12), h.dp(8), h.dp(12), h.dp(12));
        listScroll.addView(listContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Loading notice
        TextView loading = new TextView(h.a);
        loading.setText("正在加载会话列表…");
        loading.setPadding(h.dp(12), h.dp(20), h.dp(12), h.dp(20));
        loading.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
        listContainer.addView(loading);
    }

    private void refreshSessions() {
        if (isDestroyedOrNavigated()) return;
        h.get("/api/sessions", d -> {
            if (isDestroyedOrNavigated() || !isSessionsMode) return;
            handleSessionsResponse(d);
        });
    }

    private void handleSessionsResponse(JSONObject d) {
        if (d == null || h.body.getChildCount() < 2) return;
        View scrollView = h.body.getChildAt(1);
        if (!(scrollView instanceof ScrollView)) return;
        LinearLayout listContainer = (LinearLayout) ((ScrollView) scrollView).getChildAt(0);
        if (listContainer == null) return;
        listContainer.removeAllViews();

        JSONArray convs = d.optJSONArray("conversations");
        JSONArray legacy = d.optJSONArray("sessions");
        if ((convs == null || convs.length() == 0) && (legacy == null || legacy.length() == 0)) {
            TextView empty = new TextView(h.a);
            empty.setText("暂无会话记录");
            empty.setPadding(h.dp(12), h.dp(30), h.dp(12), h.dp(30));
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
            listContainer.addView(empty);
            return;
        }

        Set<String> imported = new HashSet<>();
        for (int i = 0; convs != null && i < convs.length(); i++) {
            JSONObject item = convs.optJSONObject(i);
            if (item == null) continue;
            imported.add(item.optString("origin"));
            imported.add(item.optString("nativeClaude"));
            listContainer.addView(buildSessionCard(item));
        }
        for (int i = 0; legacy != null && i < legacy.length(); i++) {
            JSONObject session = legacy.optJSONObject(i);
            if (session == null || imported.contains(session.optString("id"))) continue;
            NativeHistoryActions.legacy(h, listContainer, session);
        }
    }

    private View buildSessionCard(JSONObject item) {
        final String sid = item.optString("id");
        final String title = item.optString("title", "未命名会话");
        final String pId = item.optString("projectId", "");
        final boolean pinned = item.optBoolean("pinned", false);
        final long updatedAt = item.optLong("updatedAt", 0);
        final String claudeSid = item.optString("claudeSessionId", item.optString("origin", ""));

        LinearLayout card = new LinearLayout(h.a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeBoxDrawable());
        card.setPadding(h.dp(12), h.dp(10), h.dp(12), h.dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = h.dp(8);
        card.setLayoutParams(cardLp);
        card.setClickable(true);
        card.setOnClickListener(v -> h.showChat(sid));

        LinearLayout topRow = new LinearLayout(h.a);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow);

        if (pinned) {
            TextView pinBadge = new TextView(h.a);
            pinBadge.setText("📌 ");
            pinBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            topRow.addView(pinBadge);
        }

        TextView titleTv = new TextView(h.a);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(getThemeColor(android.R.attr.textColorPrimary, 0));
        titleTv.setSingleLine(true);
        topRow.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Sub row with project and date
        LinearLayout subRow = new LinearLayout(h.a);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, h.dp(4), 0, 0);
        card.addView(subRow);

        String meta = (pId.isEmpty() ? "默认项目" : ("项目: " + getProjectName(pId)));
        if (updatedAt > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            meta += " · " + sdf.format(new Date(updatedAt));
        }

        TextView metaTv = new TextView(h.a);
        metaTv.setText(meta);
        metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        metaTv.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
        subRow.addView(metaTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Actions row
        LinearLayout actionRow = new LinearLayout(h.a);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.RIGHT);
        actionRow.setPadding(0, h.dp(4), 0, 0);
        card.addView(actionRow);

        if (!claudeSid.isEmpty()) {
            Button originBtn = createHeaderButton("原Claude会话", v -> h.openSession(claudeSid));
            actionRow.addView(originBtn);
        }

        Button pinBtn = createHeaderButton(pinned ? "取消置顶" : "置顶", v -> togglePin(sid, pinned));
        actionRow.addView(pinBtn);

        Button renameBtn = createHeaderButton("重命名", v -> showRenameDialog(sid, title));
        actionRow.addView(renameBtn);

        return card;
    }

    // ==========================================
    // Dialogs & Metadata Editing
    // ==========================================

    private void showRenameDialog(final String id, String currentTitle) {
        final EditText et = new EditText(h.a);
        et.setText(currentTitle != null ? currentTitle : "");
        et.setSelection(et.getText().length());
        et.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(12));

        new AlertDialog.Builder(h.a)
                .setTitle("重命名会话")
                .setView(et)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newTitle = et.getText().toString().trim();
                    if (newTitle.isEmpty()) return;
                    JSONObject body = NativeHome.json("id", id, "title", newTitle);
                    h.post("/api/multi/edit", body, res -> {
                        h.notice("已重命名");
                        if (isSessionsMode) refreshSessions();
                        else refreshChat();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void togglePin(final String id, boolean currentPinned) {
        JSONObject body = NativeHome.json("id", id, "pinned", !currentPinned);
        h.post("/api/multi/edit", body, res -> {
            h.notice(!currentPinned ? "已置顶" : "已取消置顶");
            if (isSessionsMode) refreshSessions();
            else refreshChat();
        });
    }

    private void showProjectPickerDialog() {
        List<String> names = new ArrayList<>();
        names.add("默认工作区");
        final List<String> ids = new ArrayList<>();
        ids.add("");

        for (JSONObject p : cachedProjects) {
            names.add(p.optString("name", p.optString("id")));
            ids.add(p.optString("id"));
        }

        new AlertDialog.Builder(h.a)
                .setTitle(currentChatId.isEmpty() ? "选择项目" : "在项目中新建会话")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String selId = ids.get(which);
                    h.projectId = selId;
                    if (!currentChatId.isEmpty()) {
                        h.showChat("");
                    } else if (projectChipView != null) {
                        projectChipView.setText("项目: " + getProjectName(selId));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getProjectName(String pId) {
        if (pId == null || pId.trim().isEmpty()) return "无";
        for (JSONObject pr : cachedProjects) {
            if (pId.equals(pr.optString("id"))) {
                return pr.optString("name", pId);
            }
        }
        return pId;
    }

    // ==========================================
    // Draft Persistence
    // ==========================================

    private SharedPreferences getPrefs() {
        return h.a.getSharedPreferences("cc_native_chat_drafts", Context.MODE_PRIVATE);
    }

    private static String getDraftKey(String chatId) {
        return "draft_" + (chatId == null || chatId.trim().isEmpty() ? "blank" : chatId.trim());
    }

    static String readThemeDraft(Context ctx, String id) {
        return ctx.getSharedPreferences("cc_native_chat_drafts", Context.MODE_PRIVATE).getString(getDraftKey(id), "");
    }

    static boolean writeThemeDraft(Context ctx, String id, String text) {
        return ctx.getSharedPreferences("cc_native_chat_drafts", Context.MODE_PRIVATE).edit().putString(getDraftKey(id), text).commit();
    }

    static void appendDraft(Context context, String id, String text) {
        SharedPreferences prefs = context.getSharedPreferences("cc_native_chat_drafts", Context.MODE_PRIVATE);
        String key = getDraftKey(id), previous = prefs.getString(key, "");
        prefs.edit().putString(key, previous.isEmpty() ? text : previous+"\n"+text).apply();
    }

    private void saveDraft() {
        if (inputField == null) return;
        String text = inputField.getText().toString();
        getPrefs().edit().putString(getDraftKey(currentChatId), text).apply();
    }

    private void loadDraft() {
        if (inputField == null) return;
        String draft = getPrefs().getString(getDraftKey(currentChatId), "");
        inputField.setText(draft);
        if (!draft.isEmpty()) {
            inputField.setSelection(draft.length());
        }
    }

    private void clearDraft(String chatId) {
        getPrefs().edit().remove(getDraftKey(chatId)).apply();
    }

    // ==========================================
    // Native Theming & Drawables
    // ==========================================

    private int getThemeColor(int attr, int defaultColor) {
        return NativeAppearance.resolveColor(h.a, attr, defaultColor);
    }

    private Button createHeaderButton(String text, View.OnClickListener action) {
        Button b = new Button(h.a);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(h.dp(8), h.dp(4), h.dp(8), h.dp(4));
        b.setOnClickListener(action);
        return b;
    }

    private GradientDrawable makeBubbleDrawable(boolean isUser) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(h.dp(12));
        int color = isUser
                ? getThemeColor(android.R.attr.colorAccent, getThemeColor(android.R.attr.textColorPrimary, 0))
                : getThemeColor(android.R.attr.textColorSecondary, 0);
        int alpha = isUser ? 0x24000000 : 0x14000000;
        gd.setColor((color & 0x00FFFFFF) | alpha);
        gd.setStroke(h.dp(1), (color & 0x00FFFFFF) | (isUser ? 0x48000000 : 0x24000000));
        return gd;
    }

    private GradientDrawable makeBoxDrawable() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(h.dp(8));
        int sec = getThemeColor(android.R.attr.textColorSecondary, 0);
        gd.setColor((sec & 0x00FFFFFF) | 0x0E000000);
        gd.setStroke(h.dp(1), (sec & 0x00FFFFFF) | 0x22000000);
        return gd;
    }

    private GradientDrawable makeInputDrawable() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(h.dp(8));
        int sec = getThemeColor(android.R.attr.textColorSecondary, 0);
        gd.setColor((sec & 0x00FFFFFF) | 0x0A000000);
        gd.setStroke(h.dp(1), (sec & 0x00FFFFFF) | 0x33000000);
        return gd;
    }

    private GradientDrawable makeWarningDrawable() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(h.dp(6));
        int accent = getThemeColor(android.R.attr.colorAccent, getThemeColor(android.R.attr.textColorPrimary, 0));
        gd.setColor((accent & 0x00FFFFFF) | 0x18000000);
        gd.setStroke(h.dp(1), (accent & 0x00FFFFFF) | 0x44000000);
        return gd;
    }

    private GradientDrawable makeDividerDrawable(boolean isTop) {
        GradientDrawable gd = new GradientDrawable();
        int sec = getThemeColor(android.R.attr.textColorSecondary, 0);
        gd.setColor((sec & 0x00FFFFFF) | 0x06000000);
        return gd;
    }

    private View createCollapsible(String title, View content) {
        LinearLayout box = new LinearLayout(h.a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(makeBoxDrawable());
        box.setPadding(h.dp(8), h.dp(6), h.dp(8), h.dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = h.dp(4);
        box.setLayoutParams(lp);

        TextView header = new TextView(h.a);
        header.setText("▶ " + title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTextColor(getThemeColor(android.R.attr.textColorSecondary, 0));
        header.setPadding(0, 0, 0, h.dp(2));

        content.setVisibility(View.GONE);
        header.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() == View.VISIBLE;
            content.setVisibility(expanded ? View.GONE : View.VISIBLE);
            header.setText((expanded ? "▶ " : "▼ ") + title);
        });

        box.addView(header);
        box.addView(content);
        return box;
    }

    private String multiName(String agent) {
        if (agent == null || agent.isEmpty()) return "AI";
        switch (agent.toLowerCase(Locale.ROOT)) {
            case "claude": return "Claude";
            case "codex": return "Codex";
            case "agy": return "Antigravity";
            default: return agent;
        }
    }

    // ==========================================
    // Markdown Readable Text Formatter
    // ==========================================

    private CharSequence renderMarkdown(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();

        int textColor = getThemeColor(android.R.attr.textColorPrimary, 0);
        int textSecColor = getThemeColor(android.R.attr.textColorSecondary, 0);
        int accentColor = getThemeColor(android.R.attr.colorAccent, textColor);

        String[] lines = raw.split("\n", -1);
        boolean inCodeBlock = false;
        StringBuilder codeBlockContent = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    int start = sb.length();
                    sb.append(codeBlockContent.toString());
                    int end = sb.length();
                    sb.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.9f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    codeBlockContent.setLength(0);
                    inCodeBlock = false;
                    if (i < lines.length - 1) sb.append("\n");
                } else {
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n");
                continue;
            }

            if (line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ")) {
                int hashes = line.startsWith("# ") ? 1 : (line.startsWith("## ") ? 2 : 3);
                String title = line.substring(hashes + 1).trim();
                int start = sb.length();
                sb.append(title);
                int end = sb.length();
                float scale = hashes == 1 ? 1.25f : (hashes == 2 ? 1.15f : 1.05f);
                sb.setSpan(new RelativeSizeSpan(scale), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                sb.append("• ");
                appendInlineFormatted(sb, line.trim().substring(2), textColor, textSecColor, accentColor);
            } else {
                appendInlineFormatted(sb, line, textColor, textSecColor, accentColor);
            }

            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        if (inCodeBlock && codeBlockContent.length() > 0) {
            int start = sb.length();
            sb.append(codeBlockContent.toString());
            int end = sb.length();
            sb.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.9f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return sb;
    }

    private void appendInlineFormatted(SpannableStringBuilder sb, String text, int textColor, int textSecColor, int accentColor) {
        if (text == null || text.isEmpty()) return;
        int len = text.length();
        int i = 0;
        while (i < len) {
            if (text.charAt(i) == '[') {
                int closeBracket = text.indexOf(']', i);
                if (closeBracket > i && closeBracket + 1 < len && text.charAt(closeBracket + 1) == '(') {
                    int closeParen = text.indexOf(')', closeBracket + 2);
                    if (closeParen > closeBracket + 2) {
                        String label = text.substring(i + 1, closeBracket);
                        final String url = text.substring(closeBracket + 2, closeParen);
                        int start = sb.length();
                        sb.append(label);
                        int end = sb.length();
                        sb.setSpan(new ClickableSpan() {
                            @Override
                            public void onClick(View widget) {
                                h.openUrl(url);
                            }
                        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new ForegroundColorSpan(accentColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = closeParen + 1;
                        continue;
                    }
                }
            }
            if (i + 1 < len && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int closeBold = text.indexOf("**", i + 2);
                if (closeBold != -1) {
                    String boldText = text.substring(i + 2, closeBold);
                    int start = sb.length();
                    sb.append(boldText);
                    int end = sb.length();
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = closeBold + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int closeCode = text.indexOf('`', i + 1);
                if (closeCode != -1) {
                    String codeText = text.substring(i + 1, closeCode);
                    int start = sb.length();
                    sb.append(codeText);
                    int end = sb.length();
                    sb.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.92f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = closeCode + 1;
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
    }
}
