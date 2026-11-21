package cc.codroid;

import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

public class NativePairingPanel {
    public static void add(NativeHome h, LinearLayout root) {
        new NativePairingPanel(h, root);
    }

    private final NativeHome h;
    private final LinearLayout root;
    private final int gen;
    private final TextView statusText;
    private final LinearLayout actionsRow, pairBox;
    private final Button installBtn, activateBtn, repairBtn;
    private final EditText codeEdit, portEdit;

    private NativePairingPanel(NativeHome h, LinearLayout root) {
        this.h = h;
        this.root = root;
        this.gen = h.generation;

        LinearLayout panel = new LinearLayout(h.a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, h.dp(8), 0, h.dp(8));
        root.addView(panel);

        statusText = new TextView(h.a);
        statusText.setVisibility(View.GONE);
        statusText.setPadding(0, 0, 0, h.dp(8));
        panel.addView(statusText);

        actionsRow = new LinearLayout(h.a);
        actionsRow.setOrientation(LinearLayout.VERTICAL);
        panel.addView(actionsRow);

        installBtn = new Button(h.a);
        installBtn.setText("安装 Shizuku");
        installBtn.setVisibility(View.GONE);
        installBtn.setOnClickListener(v -> h.post("/api/app/shz_install", NativeHome.json(), this::update));
        actionsRow.addView(installBtn);

        activateBtn = new Button(h.a);
        activateBtn.setVisibility(View.GONE);
        activateBtn.setOnClickListener(v -> h.post("/api/app/shz_activate", NativeHome.json(), this::update));
        actionsRow.addView(activateBtn);

        repairBtn = new Button(h.a);
        repairBtn.setVisibility(View.GONE);
        repairBtn.setOnClickListener(v -> h.post("/api/app/shz_repair", NativeHome.json(), this::update));
        actionsRow.addView(repairBtn);

        pairBox = new LinearLayout(h.a);
        pairBox.setOrientation(LinearLayout.VERTICAL);
        pairBox.setVisibility(View.GONE);

        TextView pairHint = new TextView(h.a);
        pairHint.setText("输入无线调试配对码与端口：");
        pairBox.addView(pairHint);

        codeEdit = new EditText(h.a);
        codeEdit.setHint("6 位配对码");
        codeEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        codeEdit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        codeEdit.setSaveEnabled(false);
        pairBox.addView(codeEdit);

        portEdit = new EditText(h.a);
        portEdit.setHint("配对端口（无线调试配对框中显示）");
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEdit.setSaveEnabled(false);
        pairBox.addView(portEdit);

        Button pairSubmitBtn = new Button(h.a);
        pairSubmitBtn.setText("完成配对");
        pairSubmitBtn.setOnClickListener(v -> submitPair());
        pairBox.addView(pairSubmitBtn);

        panel.addView(pairBox);
        poll();
    }

    private void submitPair() {
        String code = codeEdit.getText().toString().replaceAll("\\D", "");
        if (code.length() != 6) { h.notice("配对码是 6 位数字"); return; }
        String port = portEdit.getText().toString().replaceAll("\\D", "");
        if (port.length() < 4 || port.length() > 5 || Integer.parseInt(port) > 65535 || Integer.parseInt(port) < 1) { h.notice("把配对框里的端口也填上（冒号后面那串数字）"); return; }
        codeEdit.setText("");
        portEdit.setText("");
        h.post("/api/app/shz_pair", NativeHome.json("code", code + " " + port), this::update);
    }

    private final Runnable delayedPoll = this::poll;

    private void poll() {
        if (h.generation != gen) return;
        h.get("/api/priv/status", this::update);
    }

    private void update(JSONObject st) {
        if (h.generation != gen || st == null) return;
        root.removeCallbacks(delayedPoll);
        if (st.has("ok") && !st.optBoolean("ok") && st.optString("state").isEmpty()) { h.notice(st.optString("message", "操作失败")); return; }
        String state = st.optString("state");
        boolean wl = st.optBoolean("wireless", true), paired = st.optBoolean("paired");
        JSONObject su = st.optJSONObject("setup");
        String phase = su != null ? su.optString("phase", "idle") : "idle";
        boolean busy = !"idle".equals(phase) && !"done".equals(phase) && !"error".equals(phase);
        boolean isPairing = "pairing".equals(phase);

        String msg = "";
        if (su != null && su.has("message") && su.optString("message").length() > 0 && !("ok".equals(state) && "done".equals(phase))) {
            msg = su.optString("message");
            if ("downloading".equals(phase) && su.optLong("total") > 0) {
                msg += " (" + (su.optLong("done") / 1048576) + "MB / " + (su.optLong("total") / 1048576) + "MB)";
            }
        } else if ("ok".equals(state) && wl && !paired && !busy) {
            msg = st.optInt("pairOk") == 0
                ? "无线调试里 cccodroid 的配对已被取消：现在还能用，但重启手机后没法自动激活。"
                : "Shizuku 不是由 cccodroid 激活的：重启手机后 cccodroid 没法自动激活它。配对一次即可。";
        }
        statusText.setText(msg);
        statusText.setVisibility(msg.length() > 0 ? View.VISIBLE : View.GONE);
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
