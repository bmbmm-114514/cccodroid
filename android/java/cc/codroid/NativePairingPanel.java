package cc.codroid;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONObject;

public class NativePairingPanel {
    static NativePairingPanel add(NativeHome h, LinearLayout root) {
        return new NativePairingPanel(h, root);
    }

    private final NativeHome h;
    private final LinearLayout root;
    private final int gen;
    private int requestId;
    private boolean submitting;
    private final TextView statusMetricValue;
    private final TextView statusDetail;
    private final LinearLayout actionsRow, pairBox;
    private final MaterialButton installBtn, activateBtn, repairBtn, pairSubmitBtn;
    private final TextInputEditText codeEdit, portEdit;

    private NativePairingPanel(NativeHome h, LinearLayout root) {
        this.h = h;
        this.root = root;
        this.gen = h.generation;

        LinearLayout panel = new LinearLayout(h.a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, h.dp(8), 0, h.dp(8));
        root.addView(panel);

        statusMetricValue = NativeStatusViews.metric(panel, "连接状态", "未知");

        statusDetail = NativeStatusViews.detail(panel, "");
        statusDetail.setVisibility(View.GONE);

        actionsRow = new LinearLayout(h.a);
        actionsRow.setOrientation(LinearLayout.VERTICAL);
        panel.addView(actionsRow);

        installBtn = createButton("安装 Shizuku", () -> submit("/api/app/shz_install", NativeHome.json()));
        installBtn.setVisibility(View.GONE);
        actionsRow.addView(installBtn);

        activateBtn = createButton("一键安装并激活", () -> submit("/api/app/shz_activate", NativeHome.json()));
        activateBtn.setVisibility(View.GONE);
        actionsRow.addView(activateBtn);

        repairBtn = createButton("无线调试配对", () -> submit("/api/app/shz_repair", NativeHome.json()));
        repairBtn.setVisibility(View.GONE);
        actionsRow.addView(repairBtn);

        pairBox = new LinearLayout(h.a);
        pairBox.setOrientation(LinearLayout.VERTICAL);
        pairBox.setVisibility(View.GONE);

        pairBox.setPaddingRelative(h.dp(16), h.dp(4), h.dp(16), h.dp(8));
        codeEdit = addInput("6 位配对码", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        portEdit = addInput("配对端口", InputType.TYPE_CLASS_NUMBER);
        pairSubmitBtn = createButton("完成配对", this::submitPair);
        pairBox.addView(pairSubmitBtn);
        actionsRow.setPaddingRelative(h.dp(16), 0, h.dp(16), h.dp(8));

        panel.addView(pairBox);
        poll();
    }

    private TextInputEditText addInput(String hint, int type) {
        TextInputLayout field = new TextInputLayout(h.a);
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setHint(hint);
        field.setSaveEnabled(false);
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setInputType(type);
        input.setSingleLine(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false);
        field.addView(input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = h.dp(8);
        pairBox.addView(field, lp);
        return input;
    }

    private MaterialButton createButton(String label, Runnable action) {
        MaterialButton btn = PixelUi.button(h.a, label, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.START;
        lp.topMargin = h.dp(4);
        btn.setLayoutParams(lp);
        btn.setMinHeight(h.dp(48));
        return btn;
    }

    private void submitPair() {
        String code = codeEdit.getText().toString();
        String port = portEdit.getText().toString();
        if (!code.matches("[0-9]{6}")) {
            codeEdit.setError("请输入 6 位配对码");
            return;
        }
        if (!port.matches("[0-9]{4,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
            portEdit.setError("请输入无线调试配对框中的端口");
            return;
        }
        codeEdit.setText("");
        portEdit.setText("");
        submit("/api/app/shz_pair", NativeHome.json("code", code + " " + port));
    }

    private void submit(String path, JSONObject body) {
        if (submitting) return;
        submitting = true;
        ++requestId;
        root.removeCallbacks(delayedPoll);
        h.req("POST", path, body, result -> {
            submitting = false;
            update(result);
        }, error -> {
            submitting = false;
            h.notice(error);
            poll();
        });
    }

    private final Runnable delayedPoll = this::poll;

    void poll() {
        if (h.generation != gen || submitting) return;
        final int id = ++requestId;
        h.get("/api/priv/status", status -> {
            if (id == requestId) update(status);
        });
    }

    private void update(JSONObject st) {
        if (h.generation != gen || st == null) return;
        root.removeCallbacks(delayedPoll);
        if (st.has("ok") && !st.optBoolean("ok")) {
            h.notice(st.optString("message", "操作失败"));
            if (st.optString("state").isEmpty()) return;
        }

        String state = st.optString("state");
        boolean wl = st.optBoolean("wireless", true);
        boolean paired = st.optBoolean("paired");
        JSONObject su = st.optJSONObject("setup");
        String phase = su != null ? su.optString("phase", "idle") : "idle";
        boolean busy = !"idle".equals(phase) && !"done".equals(phase) && !"error".equals(phase);
        boolean isPairing = "pairing".equals(phase);

        String status;
        switch (state) {
            case "ok":
                status = "已就绪";
                int uid = st.optInt("uid", -1);
                if (uid >= 0) status += uid == 0 ? " · root" : " · uid " + uid;
                if (!st.isNull("version")) status += " · v" + st.optString("version");
                break;
            case "not_installed": status = "未安装"; break;
            case "not_running": status = "未运行"; break;
            case "no_permission": status = "尚未授权"; break;
            default: status = st.optString("message", "未知"); break;
        }
        statusMetricValue.setText(status);

        String msg = "";
        if (su != null && su.has("message") && su.optString("message").length() > 0 && !("ok".equals(state) && "done".equals(phase))) {
            msg = su.optString("message");
            if ("downloading".equals(phase) && su.optLong("total") > 0) {
                msg += " (" + (su.optLong("done") / 1048576) + "MB / " + (su.optLong("total") / 1048576) + "MB)";
            }
        } else if ("ok".equals(state) && wl && !paired && !busy) {
            msg = st.optInt("pairOk") == 0
                ? "无线调试配对已取消。重新配对后，手机重启时可自动激活。"
                : "与此手机配对后，cccodroid 可在重启后自动激活 Shizuku。";
        }
        statusDetail.setText(msg);
        statusDetail.setVisibility(msg.length() > 0 ? View.VISIBLE : View.GONE);

        pairBox.setVisibility(isPairing ? View.VISIBLE : View.GONE);
        actionsRow.setVisibility(isPairing ? View.GONE : View.VISIBLE);
        installBtn.setVisibility("not_installed".equals(state) && !busy ? View.VISIBLE : View.GONE);

        if ("not_installed".equals(state)) {
            activateBtn.setText("一键安装并激活");
            activateBtn.setVisibility(!busy ? View.VISIBLE : View.GONE);
        } else if ("not_running".equals(state)) {
            activateBtn.setText("一键激活（无线调试）");
            activateBtn.setVisibility(wl && !busy ? View.VISIBLE : View.GONE);
        } else {
            activateBtn.setVisibility(View.GONE);
        }

        if (wl && !busy) {
            if ("ok".equals(state) && !paired) {
                repairBtn.setText(st.optInt("pairOk") == 0 ? "重新配对" : "配对");
                repairBtn.setVisibility(View.VISIBLE);
            } else if ("not_running".equals(state)) {
                repairBtn.setText("无线调试配对");
                repairBtn.setVisibility(View.VISIBLE);
            } else {
                repairBtn.setVisibility(View.GONE);
            }
        } else {
            repairBtn.setVisibility(View.GONE);
        }

        if (busy) {
            root.postDelayed(delayedPoll, 1500);
        }
    }
}
