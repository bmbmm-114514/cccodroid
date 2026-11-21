package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
    private String conversationTitle, selectedModel;
    private LinearLayout historyList;
    private TextView quotaBannerView;
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
    private ImageButton sendButton, stopButton;
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

        conversationTitle = currentChatId.isEmpty() ? "新对话" : "对话";
        selectedModel = "";
        updateHeading();
        h.onHeadingClick(this::showConversationActions);
        h.headerModel("选择模型", h::quickModel);

        quotaBannerView = text("", 14, "onSecondaryContainer");
        quotaBannerView.setVisibility(View.GONE);
        quotaBannerView.setMinHeight(h.dp(48));
        quotaBannerView.setGravity(Gravity.CENTER_VERTICAL);
        quotaBannerView.setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(8));
        quotaBannerView.setBackground(PixelUi.shape(h.a, "secondaryContainer", 16));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
        bannerLp.setMargins(h.dp(16), h.dp(4), h.dp(16), 0);
        h.body.addView(quotaBannerView, bannerLp);

        chatScrollView = new ScrollView(h.a);
        chatScrollView.setFillViewport(true);
        chatScrollView.setClipToPadding(false);
        chatScrollView.setVerticalScrollBarEnabled(false);
        h.body.addView(chatScrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        turnsContainer = new LinearLayout(h.a);
        turnsContainer.setOrientation(LinearLayout.VERTICAL);
        turnsContainer.setPadding(h.dp(20), h.dp(12), h.dp(20), h.dp(12));
        chatScrollView.addView(turnsContainer, new ViewGroup.LayoutParams(-1, -2));
        approvalsContainer = new LinearLayout(h.a);
        approvalsContainer.setOrientation(LinearLayout.VERTICAL);
        liveRunContainer = new LinearLayout(h.a);
        liveRunContainer.setOrientation(LinearLayout.VERTICAL);
        liveRunContainer.setVisibility(View.GONE);
        turnsContainer.addView(approvalsContainer);
        turnsContainer.addView(liveRunContainer);
        if (currentChatId.isEmpty()) renderEmptyConversation();

        LinearLayout dock = new LinearLayout(h.a);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(h.dp(12), h.dp(4), h.dp(12), h.dp(8));
        h.body.addView(dock, new LinearLayout.LayoutParams(-1, -2));
        buildHandoffSection(dock);
        LinearLayout composer = new LinearLayout(h.a);
        composer.setGravity(Gravity.BOTTOM);
        composer.setBackground(PixelUi.shape(h.a, "surfaceContainerHigh", 28));
        composer.setPadding(h.dp(4), h.dp(4), h.dp(4), h.dp(4));
        dock.addView(composer, new LinearLayout.LayoutParams(-1, -2));

        ImageButton attach = PixelUi.icon(h.a, "add", "附件与操作", null);
        attach.setOnClickListener(v -> NativeChatActions.show(h, v));
        composer.addView(attach, new LinearLayout.LayoutParams(h.dp(48), h.dp(48)));
        inputField = new EditText(h.a);
        inputField.setHint("输入消息");
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        inputField.setMinLines(1);
        inputField.setMaxLines(5);
        inputField.setMinHeight(h.dp(48));
        inputField.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        inputField.setBackground(null);
        inputField.setPadding(h.dp(4), h.dp(10), h.dp(8), h.dp(10));
        inputField.setTextColor(PixelUi.color(h.a,"onSurface"));
        inputField.setHintTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
        inputField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { saveDraft(); updateComposerButtons(); }
        });
        composer.addView(inputField, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout action = new FrameLayout(h.a);
        composer.addView(action, new LinearLayout.LayoutParams(h.dp(48), h.dp(48)));
        sendButton = composerAction("send", "发送", this::performSend);
        stopButton = composerAction("stop", "停止", this::confirmStop);
        action.addView(sendButton); action.addView(stopButton);
        sendProgress = new ProgressBar(h.a, null, android.R.attr.progressBarStyleSmall);
        sendProgress.setContentDescription("正在发送");
        sendProgress.setVisibility(View.GONE);
        action.addView(sendProgress, new FrameLayout.LayoutParams(h.dp(24), h.dp(24), Gravity.CENTER));
    }

    private ImageButton composerAction(String icon, String label, Runnable action) {
        ImageButton b = PixelUi.icon(h.a, icon, label, action);
        boolean stop = "stop".equals(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(PixelUi.color(h.a, stop ? "onPrimaryContainer" : "onPrimary")));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(PixelUi.color(h.a,"primaryContainer")),
                PixelUi.shape(h.a, stop ? "primaryContainer" : "primary", 24), null));
        return b;
    }

    private TextView text(String value, int sp, String role) {
        TextView label = new TextView(h.a);
        label.setText(value);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        label.setTextColor(PixelUi.color(h.a, role));
        return label;
    }

    private void updateHeading() {
        String project = h.projectId.isEmpty() ? "" : getProjectName(h.projectId);
        h.setHeading(conversationTitle, project);
    }

    private void showConversationActions() {
        String[] actions = currentChatId.isEmpty()
            ? new String[]{"历史会话", "选择项目", "接口与模型"}
            : new String[]{"历史会话", "在项目中新建会话", "接口与模型", "新建会话", "重命名"};
        new MaterialAlertDialogBuilder(h.a).setTitle(conversationTitle).setItems(actions,(dialog,i) -> {
            if (i == 0) h.nav("sessions");
            else if (i == 1) showProjectPickerDialog();
            else if (i == 2) h.nav("provider");
            else if (i == 3) h.showChat("");
            else showRenameDialog(currentChatId, conversationTitle);
        }).show();
    }

    private void renderEmptyConversation() {
        turnsContainer.setGravity(Gravity.CENTER);
        LinearLayout group = new LinearLayout(h.a);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER);
        TextView title = text("今天想开发什么？", 28, "onSurface");
        title.setGravity(Gravity.CENTER);
        group.addView(title);
        LinearLayout choices = new LinearLayout(h.a);
        choices.setGravity(Gravity.CENTER);
        choices.setPadding(0,h.dp(16),0,0);
        choices.addView(actionPill("选择项目", this::showProjectPickerDialog));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2,-2);
        gap.setMarginStart(h.dp(8));
        choices.addView(actionPill("历史会话", () -> h.nav("sessions")), gap);
        group.addView(choices);
        turnsContainer.addView(group, 0);
    }

    private Button actionPill(String label, Runnable action) {
        Button b = PixelUi.button(h.a, label, action);
        b.setMinHeight(h.dp(48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private void buildHandoffSection(LinearLayout parent) {
        handoffSection = new LinearLayout(h.a);
        handoffSection.setOrientation(LinearLayout.VERTICAL);
        handoffSection.setVisibility(View.GONE);
        handoffSection.setPadding(h.dp(12), h.dp(8), h.dp(12), h.dp(12));
        handoffSection.setBackground(PixelUi.shape(h.a,"surfaceContainer",20));
        parent.addView(handoffSection, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout modeRow = new LinearLayout(h.a);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        handoffSection.addView(modeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handoffTransferLabel = new TextView(h.a);
        handoffTransferLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        handoffTransferLabel.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
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
        manualNotice.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
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
            h.setHeading(conversationTitle, error);
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

        JSONObject c = d.optJSONObject("conversation");
        JSONObject run = c != null ? c.optJSONObject("activeRun") : null;
        isBusy = run != null;
        conversationTitle = c == null ? "新对话" : c.optString("title", "对话");
        if (c != null) h.projectId = c.optString("projectId", h.projectId);
        updateHeading();

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
            turnsContainer.setGravity(Gravity.TOP);
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
            if (run == null && (turns == null || turns.length() == 0)) renderEmptyConversation();
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
            String text = run.optString("text");
            if (text.isEmpty()) text = "正在处理…";

            TextView who = text("", 13, "primary");
            who.setText(title);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            who.setTypeface(Typeface.DEFAULT_BOLD);

            liveRunContainer.addView(who);

            TextView bubble = new TextView(h.a);
            bubble.setText(renderMarkdown(text));
            bubble.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            bubble.setLineSpacing(h.dp(3),1f);
            bubble.setTextColor(PixelUi.color(h.a,"onSurface"));
            bubble.setPadding(0, h.dp(4), 0, 0);
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
        row.setPadding(0, h.dp(8), 0, h.dp(12));
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
            userBubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            userBubble.setLineSpacing(h.dp(2), 1f);
            userBubble.setTextColor(PixelUi.color(h.a,"onPrimaryContainer"));
            userBubble.setBackground(PixelUi.shape(h.a,"primaryContainer",22));
            userBubble.setPadding(h.dp(16), h.dp(10), h.dp(16), h.dp(10));

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
                botWrapperLp.topMargin = h.dp(20);

                TextView who = text("", 13, "primary");
                who.setText(multiName(agent));
                who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                who.setTypeface(Typeface.DEFAULT_BOLD);

                botWrapper.addView(who);

                if (!assistantText.isEmpty()) {
                    TextView botBubble = new TextView(h.a);
                    botBubble.setText(renderMarkdown(assistantText));
                    turnActions(botBubble, turn, "assistant");
                    botBubble.setMovementMethod(LinkMovementMethod.getInstance());
                    botBubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    botBubble.setLineSpacing(h.dp(3),1f);
                    botBubble.setTextColor(PixelUi.color(h.a,"onSurface"));
                    botBubble.setPadding(0, h.dp(4), 0, 0);

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
            content.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));

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
                toolLabel.setTextColor(PixelUi.color(h.a,"onSurface"));
                toolsLayout.addView(toolLabel);

                TextView toolText = new TextView(h.a);
                toolText.setText(tool.optString("text", ""));
                toolText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                toolText.setTypeface(Typeface.MONOSPACE);
                toolText.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
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
            errTv.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
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
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(PixelUi.shape(h.a,"secondaryContainer",20));
        row.setPadding(h.dp(10), h.dp(6), h.dp(10), h.dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = h.dp(4);
        row.setLayoutParams(lp);

        TextView label = new TextView(h.a);
        label.setText("操作授权: " + app.optString("label", "请求确认"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(PixelUi.color(h.a,"onSurface"));
        row.addView(label, new LinearLayout.LayoutParams(-1,-2));

        LinearLayout choices = new LinearLayout(h.a);
        choices.setGravity(Gravity.END);
        row.addView(choices);
        final String appId = app.optString("id");
        choices.addView(actionPill("拒绝", () -> sendApproval(appId, false)));
        choices.addView(actionPill("允许", () -> sendApproval(appId, true)));

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
            String next = modelName.isEmpty() ? multiName(target) : modelName;
            if (!selectedModel.equals(next)) {
                selectedModel = next;
                h.headerModel(selectedModel, h::quickModel);
            }
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
        if (sendButton == null || stopButton == null || sendProgress == null) return;
        sendButton.setVisibility(!isBusy && !isSending ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(isBusy && !isSending ? View.VISIBLE : View.GONE);
        sendProgress.setVisibility(isSending ? View.VISIBLE : View.GONE);
        boolean enabled = (currentChatId.isEmpty() || lastState != null) && inputField.getText().toString().trim().length() != 0;
        sendButton.setEnabled(enabled);
        sendButton.setAlpha(enabled ? 1f : 0.38f);
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
        new MaterialAlertDialogBuilder(h.a)
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
        h.setHeading("历史会话", "");
        h.headerAction("add","新建会话", () -> h.showChat(""));
        h.onHeadingClick(() -> NativeHistoryActions.show(h,null));
        // List ScrollView
        ScrollView listScroll = new ScrollView(h.a);
        listScroll.setFillViewport(true);
        h.body.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        historyList = new LinearLayout(h.a);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyList.setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(16));
        listScroll.addView(historyList, new ViewGroup.LayoutParams(-1,-2));

        // Loading notice
        TextView loading = new TextView(h.a);
        loading.setText("正在加载会话列表…");
        loading.setPadding(h.dp(12), h.dp(20), h.dp(12), h.dp(20));
        loading.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
        historyList.addView(loading);
    }

    private void refreshSessions() {
        if (isDestroyedOrNavigated()) return;
        h.get("/api/sessions", d -> {
            if (isDestroyedOrNavigated() || !isSessionsMode) return;
            handleSessionsResponse(d);
        });
    }

    private void handleSessionsResponse(JSONObject d) {
        if (d == null || historyList == null) return;
        LinearLayout listContainer = historyList;
        listContainer.removeAllViews();
        LinearLayout controls = new LinearLayout(h.a);
        controls.setGravity(Gravity.END);
        Button manage = new Button(h.a,null,android.R.attr.borderlessButtonStyle);
        manage.setText("管理会话");
        manage.setTextColor(PixelUi.color(h.a,"primary"));
        manage.setOnClickListener(v -> NativeHistoryActions.show(h,v));
        controls.addView(manage);
        listContainer.addView(controls);

        JSONArray convs = d.optJSONArray("conversations");
        JSONArray legacy = d.optJSONArray("sessions");
        if ((convs == null || convs.length() == 0) && (legacy == null || legacy.length() == 0)) {
            TextView empty = new TextView(h.a);
            empty.setText("暂无会话记录");
            empty.setPadding(h.dp(12), h.dp(30), h.dp(12), h.dp(30));
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
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

        String meta = item.optString("activeAgent", "");
        meta = "null".equals(meta) || meta.isEmpty() ? "" : multiName(meta);
        if (!pId.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + getProjectName(pId);
        if (updatedAt > 0) meta += (meta.isEmpty() ? "" : " · ") + android.text.format.DateUtils.getRelativeTimeSpanString(updatedAt);
        if (pinned) meta = "已置顶" + (meta.isEmpty() ? "" : " · " + meta);
        Runnable more = () -> {
            List<String> options = new ArrayList<>();
            options.add(pinned ? "取消置顶" : "置顶");
            options.add("重命名");
            if (!claudeSid.isEmpty()) options.add("打开原 Claude 会话");
            new MaterialAlertDialogBuilder(h.a).setTitle(title).setItems(options.toArray(new String[0]), (dialog,i) -> {
                if (i == 0) togglePin(sid,pinned);
                else if (i == 1) showRenameDialog(sid,title);
                else h.openSession(claudeSid);
            }).show();
        };
        return NativeHistoryActions.sessionRow(h,title,meta,() -> h.showChat(sid),more);
    }

    // ==========================================
    // Dialogs & Metadata Editing
    // ==========================================

    private void showRenameDialog(final String id, String currentTitle) {
        final EditText et = new EditText(h.a);
        et.setText(currentTitle != null ? currentTitle : "");
        et.setSelection(et.getText().length());
        et.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(12));

        new MaterialAlertDialogBuilder(h.a)
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

        new MaterialAlertDialogBuilder(h.a)
                .setTitle(currentChatId.isEmpty() ? "选择项目" : "在项目中新建会话")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String selId = ids.get(which);
                    h.projectId = selId;
                    if (!currentChatId.isEmpty()) {
                        h.showChat("");
                    } else {
                        h.remember();
                        updateHeading();
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

    private GradientDrawable makeBoxDrawable() {
        return PixelUi.shape(h.a,"surfaceContainerLow",16);
    }

    private GradientDrawable makeInputDrawable() {
        return PixelUi.shape(h.a,"surfaceContainerHigh",12);
    }

    private View createCollapsible(String title, View content) {
        LinearLayout box = new LinearLayout(h.a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(makeBoxDrawable());
        box.setPadding(h.dp(14), h.dp(10), h.dp(14), h.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = h.dp(4);
        box.setLayoutParams(lp);

        TextView header = new TextView(h.a);
        header.setText("▶ " + title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
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

        int textColor = PixelUi.color(h.a,"onSurface");
        int textSecColor = PixelUi.color(h.a,"onSurfaceVariant");
        int accentColor = PixelUi.color(h.a,"primary");

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
