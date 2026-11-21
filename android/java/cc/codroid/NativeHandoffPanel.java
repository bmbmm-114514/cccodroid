package cc.codroid;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

/** Context handoff choices stay with the conversation while its state polls. */
final class NativeHandoffPanel {
    private static final List<String> MODE_IDS = Arrays.asList("outgoing", "manual", "third-party");
    private static final List<String> MODE_NAMES = Arrays.asList("自动交接", "手动选择", "第三方接口");
    private final Activity host;
    private final Context ui;
    private final Function<String,String> agentName;
    private final LinearLayout card;
    private final TextView route;
    private final MaterialButton modeButton, historyButton, providerButton;
    private final LinearLayout thirdParty;
    private final TextInputLayout modelField;
    private final TextInputEditText model;
    private final List<JSONObject> providers = new ArrayList<>();
    private final List<JSONObject> turns = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private String source = "", provider = "", preferredMode = "outgoing";
    private boolean disposed;
    private int modelRead;
    private BottomSheetDialog sheet;

    NativeHandoffPanel(Activity host, LinearLayout parent, Function<String,String> agentName) {
        this.host = host;
        ui = parent.getContext();
        this.agentName = agentName;
        card = new LinearLayout(ui);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(PixelUi.shape(ui, "surfaceContainer", 20));
        card.setPaddingRelative(dp(12), dp(4), dp(12), dp(8));
        card.setVisibility(View.GONE);
        parent.addView(card, -1, -2);

        LinearLayout heading = new LinearLayout(ui);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(heading, -1, -2);
        route = PixelUi.label(ui, "", 14, "onSurface");
        heading.addView(route, new LinearLayout.LayoutParams(0, -2, 1));
        modeButton = picker(heading, this::chooseMode);
        historyButton = picker(card, this::chooseHistory);
        thirdParty = new LinearLayout(ui);
        thirdParty.setOrientation(LinearLayout.VERTICAL);
        card.addView(thirdParty, -1, -2);
        providerButton = picker(thirdParty, this::chooseProvider);
        modelField = new TextInputLayout(ui);
        modelField.setHint("交接模型");
        modelField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        float radius = dp(12);
        modelField.setBoxCornerRadii(radius, radius, radius, radius);
        thirdParty.addView(modelField, -1, -2);
        model = new TextInputEditText(modelField.getContext());
        model.setSingleLine(true);
        model.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        modelField.addView(model, -1, -2);
        modelField.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        modelField.setEndIconDrawable(PixelUi.drawable(ui, "expand"));
        modelField.setEndIconContentDescription("选择交接模型");
        modelField.setEndIconOnClickListener(v -> chooseModel());
        updateMode();
    }

    private int dp(int value) { return PixelUi.dp(ui, value); }

    private MaterialButton picker(LinearLayout parent, Runnable action) {
        MaterialButton button = PixelUi.button(ui, "", action);
        button.setIcon(PixelUi.drawable(ui, "expand"));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        button.setIconSize(dp(18));
        button.setMinHeight(dp(48));
        if (parent == card || parent == thirdParty) parent.addView(button, -1, -2);
        else parent.addView(button, -2, -2);
        return button;
    }

    void route(String from, String to, boolean busy) {
        if (!source.equals(from)) {
            source = from;
            modelField.setHelperText("agy".equals(from) ? "所选接口将接收本对话的可见记录。" : null);
        }
        boolean switching = !source.isEmpty() && !source.equals(to) && !busy;
        card.setVisibility(switching ? View.VISIBLE : View.GONE);
        if (!switching) return;
        route.setText(agentName.apply(from) + " → " + agentName.apply(to));
        updateMode();
    }

    String mode() { return preferredMode; }

    private void updateMode() {
        String mode = preferredMode;
        String label = MODE_NAMES.get(MODE_IDS.indexOf(mode));
        modeButton.setText(label);
        modeButton.setContentDescription("交接方式，" + label);
        historyButton.setVisibility("manual".equals(mode) ? View.VISIBLE : View.GONE);
        thirdParty.setVisibility("third-party".equals(mode) ? View.VISIBLE : View.GONE);
        updateHistoryLabel();
    }

    private void chooseMode() {
        sheet = PixelChoiceSheet.show(ui, "交接方式", MODE_NAMES, MODE_IDS.indexOf(preferredMode), which -> {
            if (disposed) return;
            preferredMode = MODE_IDS.get(which);
            updateMode();
        }, null);
    }

    void providers(JSONArray available) {
        providers.clear();
        for (int i = 0; available != null && i < available.length(); i++) {
            JSONObject p = available.optJSONObject(i);
            if (p != null && !p.optString("id").isEmpty()) providers.add(p);
        }
        if (provider.isEmpty() && !providers.isEmpty()) selectProvider(providers.get(0));
        else displayProvider(findProvider(provider));
    }

    private JSONObject findProvider(String id) {
        for (JSONObject p : providers) if (id.equals(p.optString("id"))) return p;
        return null;
    }

    private void selectProvider(JSONObject item) {
        String id = item.optString("id");
        if (!provider.equals(id)) {
            ++modelRead;
            modelField.setError(null);
            modelField.setEndIconVisible(true);
            provider = id;
            model.setText(item.optString("model"));
        }
        displayProvider(item);
    }

    private void displayProvider(JSONObject item) {
        String label = item != null ? item.optString("label", provider)
                : providers.isEmpty() ? "暂无可用交接接口" : "选择交接接口";
        providerButton.setText(label);
        providerButton.setContentDescription("交接接口，" + label);
        providerButton.setEnabled(!providers.isEmpty());
        modelField.setEnabled(item != null);
    }

    private void chooseProvider() {
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        for (JSONObject p : providers) {
            ids.add(p.optString("id"));
            labels.add(p.optString("label", p.optString("id")));
        }
        if (ids.isEmpty()) return;
        sheet = PixelChoiceSheet.show(ui, "交接接口", labels, ids.indexOf(provider), which -> {
            JSONObject choice = findProvider(ids.get(which));
            if (!disposed && choice != null) selectProvider(choice);
        }, null);
    }

    private void chooseModel() {
        String id = provider();
        if (id.isEmpty()) return;
        final int read = ++modelRead;
        String typed = model.getText().toString();
        String path = "/api/multi/models?agent=claude&provider=" + Uri.encode(id) + "&refresh=1";
        modelField.setError(null);
        modelField.setEndIconVisible(false);
        Consumer<String> failed = message -> {
            if (disposed || read != modelRead) return;
            modelField.setEndIconVisible(true);
            if (!id.equals(provider()) || !"third-party".equals(mode()) || !typed.contentEquals(model.getText())) return;
            modelField.setError(message == null || message.isEmpty() ? "无法获取模型列表，可以手动输入模型 ID" : message);
        };
        NativeRequests.call(host, "GET", path, null, result -> {
            if (disposed || read != modelRead) return;
            modelField.setEndIconVisible(true);
            if (!"third-party".equals(mode()) || !id.equals(provider()) || !typed.contentEquals(model.getText()) || card.getVisibility() != View.VISIBLE) return;
            JSONArray entries = result.optJSONArray("models");
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String modelId = entry.optString("id");
                if (modelId.isEmpty()) continue;
                ids.add(modelId);
                labels.add(entry.optString("label", modelId));
            }
            if (ids.isEmpty()) { failed.accept("接口未返回模型，可以手动输入模型 ID"); return; }
            sheet = PixelChoiceSheet.show(ui, "选择交接模型", labels, ids.indexOf(typed.trim()), which -> {
                if (!disposed && read == modelRead && id.equals(provider())) model.setText(ids.get(which));
            }, null);
        }, failed);
    }

    String provider() { return findProvider(provider) == null ? "" : provider; }
    String model() { return model.getText() == null ? "" : model.getText().toString().trim(); }

    void turns(List<JSONObject> values) {
        turns.clear();
        turns.addAll(values);
        selected.retainAll(turnIds());
        updateHistoryLabel();
    }

    private Set<String> turnIds() {
        Set<String> ids = new HashSet<>();
        for (JSONObject turn : turns) ids.add(turn.optString("id"));
        return ids;
    }

    JSONArray selected() {
        JSONArray ids = new JSONArray();
        for (JSONObject turn : turns) {
            String id = turn.optString("id");
            if (selected.contains(id)) ids.put(id);
        }
        return ids;
    }

    void clearSelected() {
        selected.clear();
        updateHistoryLabel();
    }

    private void updateHistoryLabel() {
        String label = turns.isEmpty() ? "暂无可交接的回合" :
                selected.isEmpty() ? "选择历史回合" : "已选 " + selected.size() + " 个历史回合";
        historyButton.setText(label);
        historyButton.setEnabled(!turns.isEmpty());
    }

    private void chooseHistory() {
        Set<String> draft = new HashSet<>(selected);
        LinearLayout rows = new LinearLayout(ui);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (JSONObject turn : turns) {
            String id = turn.optString("id");
            MaterialCheckBox check = PixelChoiceField.checkbox(ui);
            String summary = turn.optString("user");
            check.setText(agentName.apply(turn.optString("agent")) + " · " + summary);
            check.setMaxLines(2);
            check.setEllipsize(android.text.TextUtils.TruncateAt.END);
            check.setTextColor(PixelUi.color(ui, "onSurface"));
            check.setMinHeight(dp(48));
            check.setChecked(draft.contains(id));
            check.setOnCheckedChangeListener((unused, checked) -> {
                if (checked) draft.add(id); else draft.remove(id);
            });
            rows.addView(check, -1, -2);
        }
        sheet = PixelChoiceSheet.confirm(ui, "选择历史回合", rows, "确定", () -> {
            if (disposed) return;
            draft.retainAll(turnIds());
            selected.clear();
            selected.addAll(draft);
            updateHistoryLabel();
        });
    }

    void dispose() {
        disposed = true;
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        sheet = null;
    }
}
