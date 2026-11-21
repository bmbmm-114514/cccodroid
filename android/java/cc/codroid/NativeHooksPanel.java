package cc.codroid;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import org.json.*;

public class NativeHooksPanel {
    private static final String[] SCOPES = {"user", "project", "local"};
    private static final String[] LABELS = {"所有项目 (user)", "本项目 (project)", "本项目·本机 (local)"};
    private final NativeHome h;
    private final LinearLayout root;
    private JSONObject data;
    private String curScope = "user";
    private JSONObject origHooks = new JSONObject();
    private final java.util.Map<String,String> retained = new java.util.HashMap<>();
    private JSONObject visibleHooks = new JSONObject();
    private int scopeIndex = 0;
    private EditText editor;
    private TextView statusText;
    private Button saveBtn, pauseBtn;
    private Spinner scopeSpinner;

    public static void show(NativeHome h, LinearLayout root) { new NativeHooksPanel(h, root).init(); }
    private NativeHooksPanel(NativeHome h, LinearLayout root) { this.h = h; this.root = root; }

    private void init() {
        root.removeAllViews();
        LinearLayout top = PixelUi.group(root);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        scopeSpinner = new Spinner(h.a);
        scopeSpinner.setAdapter(new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item, LABELS));
        top.addView(scopeSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.setPadding(h.dp(8), h.dp(6), h.dp(8), h.dp(6));

        LinearLayout bar = new LinearLayout(h.a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        saveBtn = new Button(h.a);
        pauseBtn = new Button(h.a);
        Button testBtn = new Button(h.a);
        Button[] actions = {saveBtn, pauseBtn, testBtn};
        String[] titles = {"保存配置", "暂停/恢复", "测试钩子"};
        for (int i = 0; i < actions.length; i++) {
            Button action = actions[i];
            action.setText(titles[i]);
            action.setMinWidth(0);
            action.setMinimumWidth(0);
            action.setMinHeight(h.dp(48));
            action.setPadding(h.dp(2), h.dp(2), h.dp(2), h.dp(2));
            action.setTextSize(14);
            action.setStateListAnimator(null);
            action.setBackground(PixelUi.clickable(h.a, "secondaryContainer", 20));
            action.setTextColor(PixelUi.color(h.a, "onSecondaryContainer"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMarginEnd(i < actions.length - 1 ? h.dp(4) : 0);
            bar.addView(action, lp);
        }
        root.addView(bar);

        statusText = new TextView(h.a);
        statusText.setPadding(0, h.dp(4), 0, h.dp(4));
        root.addView(statusText);

        h.text(root, "已保存的命令、地址和凭据用「保留」标记代替。保留标记会保留原值，也可直接输入新值。");
        editor = new EditText(h.a);
        editor.setMinLines(8);
        editor.setBackground(PixelUi.shape(h.a, "surfaceContainerLow", 16));
        editor.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(12));
        editor.setTextColor(PixelUi.color(h.a, "onSurface"));
        editor.setSaveEnabled(false);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setTypeface(Typeface.MONOSPACE); editor.setGravity(Gravity.TOP);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(editor);
        saveBtn.setEnabled(false); pauseBtn.setEnabled(false);

        scopeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == scopeIndex) return;
                if (isDirty()) {
                    new AlertDialog.Builder(h.a).setTitle("放弃修改？").setMessage("当前配置有未保存修改，切换将丢失。")
                        .setPositiveButton("放弃并切换", (d, w) -> { scopeIndex = pos; curScope = SCOPES[pos]; renderScope(); })
                        .setNegativeButton("保留", (d, w) -> scopeSpinner.setSelection(scopeIndex)).setCancelable(false).show();
                } else { scopeIndex = pos; curScope = SCOPES[pos]; renderScope(); }
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        saveBtn.setOnClickListener(v -> save());
        pauseBtn.setOnClickListener(v -> togglePause());
        testBtn.setOnClickListener(v -> showTestDialog());
        h.button(root, "重新载入", () -> {
            if (!isDirty()) load();
            else new AlertDialog.Builder(h.a).setMessage("放弃未保存的修改并重新载入？")
                    .setPositiveButton("重新载入", (d,w) -> load()).setNegativeButton("取消", null).show();
        });
        load();
    }

    private void load() {
        h.get("/api/ext/hooks", res -> {
            if (!root.isAttachedToWindow() || res == null) return;
            data = res; renderScope();
        });
    }

    private JSONObject getScopeObj(JSONObject d, String scope) {
        if (d == null) return null;
        JSONArray arr = d.optJSONArray("scopes");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && scope.equals(s.optString("scope"))) return s;
            }
        }
        return null;
    }

    private void renderScope() {
        if (data == null) return;
        JSONObject sc = getScopeObj(data, curScope);
        boolean sealed = data.optBoolean("sealed", false);
        if (sc == null) {
            statusText.setText("未找到配置范围"); editor.setText("{}");
            saveBtn.setEnabled(false); pauseBtn.setEnabled(false);
            return;
        }
        String err = sc.optString("error", "");
        boolean badJson = sc.optBoolean("badJson", false);
        boolean disabled = sc.optBoolean("disableAll", false);
        JSONObject hks = sc.optJSONObject("hooks");
        origHooks = hks != null ? hks : new JSONObject();
        retained.clear();
        try {
            visibleHooks = (JSONObject) copyValues(origHooks, "", false);
            editor.setText(visibleHooks.toString(2));
        } catch (JSONException e) {
            editor.setText(""); saveBtn.setEnabled(false); pauseBtn.setEnabled(false);
            statusText.setText("无法读取规则"); return;
        }
        if (!err.isEmpty() || badJson) {
            statusText.setText("状态：配置错误 " + (badJson ? "(无效 JSON)" : err));
            saveBtn.setEnabled(false); pauseBtn.setEnabled(false);
        } else {
            statusText.setText(sealed ? "发行版保存规则，自动会话不会加载这些 Hook。" : (disabled ? "已暂停" : "正常生效中"));
            saveBtn.setEnabled(true); pauseBtn.setEnabled(true);
            pauseBtn.setText(disabled ? "恢复生效" : "暂停所有");
        }
    }

    private Object copyValues(Object value, String key, boolean restore) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject before = (JSONObject) value, after = new JSONObject();
            for (Iterator<String> it = before.keys(); it.hasNext();) {
                String k = it.next();
                after.put(k, copyValues(before.get(k), k, restore));
            }
            return after;
        }
        if (value instanceof JSONArray) {
            JSONArray before = (JSONArray) value, after = new JSONArray();
            for (int i = 0; i < before.length(); i++) after.put(copyValues(before.get(i), key, restore));
            return after;
        }
        if (!(value instanceof String) || ((String) value).isEmpty()) return value;
        String text = (String) value;
        if (restore) {
            if (retained.containsKey(text)) return retained.get(text);
            if (text.startsWith("〈保留:")) throw new JSONException("无效的保留标记");
            return text;
        }
        if ("type".equals(key) && java.util.Arrays.asList("command","http","prompt","agent").contains(text)) return text;
        String token = "〈保留:" + (retained.size() + 1) + "〉";
        retained.put(token, text);
        return token;
    }

    private boolean isDirty() {
        if (data == null) return false;
        try { return !astEquals(new JSONObject(editor.getText().toString()), visibleHooks); } catch (Exception e) { return true; }
    }

    private void save() {
        String txt = editor.getText().toString().trim();
        final JSONObject parsed;
        try {
            parsed = (JSONObject) copyValues(new JSONObject(txt), "", true);
            for (Iterator<String> it = parsed.keys(); it.hasNext();) {
                String ev = it.next();
                JSONArray groups = parsed.getJSONArray(ev);
                if (!ev.matches("[A-Za-z]{1,40}") || groups.length() == 0) throw new JSONException("无效的事件或空事件数组");
                for (int i = 0; i < groups.length(); i++) {
                    JSONArray actions = groups.getJSONObject(i).getJSONArray("hooks");
                    if (actions.length() == 0) throw new JSONException("空规则组");
                    for (int j = 0; j < actions.length(); j++) {
                        JSONObject action = actions.getJSONObject(j);
                        String type = action.getString("type");
                        if (type.trim().isEmpty() || ("command".equals(type) && action.optString("command").trim().isEmpty())) throw new JSONException("缺少动作内容");
                    }
                }
            }
        } catch (JSONException invalid) { h.notice("请输入有效的 Hook JSON，检查事件、规则组和保留标记"); return; }
        String scope = curScope;
        JSONObject state = getScopeObj(data, scope);
        if (state == null) return;
        JSONObject body = NativeHome.json("scope", scope, "dir", data.optString("dir"), "hooks", parsed,
                "expectedHooks", origHooks, "expectedDisabled", state.optBoolean("disableAll"));
        h.post("/api/ext/hooks/save", body, res -> {
            if (!root.isAttachedToWindow() || !scope.equals(curScope)) return;
            if (!res.optBoolean("ok")) { h.notice(res.optString("message", "保存失败")); return; }
            h.notice("已保存");
            if (txt.equals(editor.getText().toString().trim())) load();
        });
    }

    private void togglePause() {
        if (data == null) return;
        if (isDirty()) { h.notice("请先保存规则修改"); return; }
        JSONObject sc = getScopeObj(data, curScope);
        if (sc == null) return;
        boolean targetDisabled = !sc.optBoolean("disableAll", false);
        String action = targetDisabled ? "暂停所有钩子" : "恢复生效钩子";
        new AlertDialog.Builder(h.a).setTitle("确认" + action).setMessage("确定要" + action + "吗？")
            .setPositiveButton("确定", (dia, w) -> {
                JSONObject body = NativeHome.json("scope", curScope, "dir", data.optString("dir"), "disableAll", targetDisabled, "expectedHooks", origHooks, "expectedDisabled", !targetDisabled);
                h.post("/api/ext/hooks/save", body, res -> {
                    if (!root.isAttachedToWindow() || res == null) return;
                    if (!res.optBoolean("ok")) { h.notice(res.optString("message", "操作失败")); return; }
                    h.notice("已" + (targetDisabled ? "暂停" : "恢复")); load();
                });
            }).setNegativeButton("取消", null).show();
    }

    private void showTestDialog() {
        if (data == null) return;
        LinearLayout layout = new LinearLayout(h.a); layout.setOrientation(LinearLayout.VERTICAL);
        int p = h.dp(16); layout.setPadding(p, p, p, p);
        TextView evLbl = new TextView(h.a); evLbl.setText("选择事件："); layout.addView(evLbl);
        Spinner evSp = new Spinner(h.a);
        List<String> evList = new ArrayList<>();
        JSONArray events = data.optJSONArray("events");
        if (events != null) for (int i = 0; i < events.length(); i++) evList.add(events.optString(i));
        if (evList.isEmpty()) evList.addAll(Arrays.asList("PreToolUse", "PostToolUse", "UserMessage", "AssistantMessage", "Stop"));
        evSp.setAdapter(new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item, evList));
        layout.addView(evSp);
        EditText matEd = new EditText(h.a); matEd.setHint("匹配器 (可选，如 Bash / .* )"); layout.addView(matEd);
        EditText cmdEd = new EditText(h.a); cmdEd.setHint("执行命令 (必填，初始为空)"); layout.addView(cmdEd);
        EditText inEd = new EditText(h.a); inEd.setHint("输入 JSON (可选，留空使用样例)"); inEd.setMinLines(2); layout.addView(inEd);
        EditText toEd = new EditText(h.a); toEd.setHint("超时秒数 (1-600)"); toEd.setText("60");
        toEd.setInputType(InputType.TYPE_CLASS_NUMBER); layout.addView(toEd);
        ScrollView sv = new ScrollView(h.a); sv.addView(layout);
        new AlertDialog.Builder(h.a).setTitle("测试钩子命令").setView(sv)
            .setPositiveButton("测试", (dia, w) -> {
                String cmd = cmdEd.getText().toString().trim();
                if (cmd.isEmpty()) { h.notice("命令不能为空"); return; }
                String ev = (String) evSp.getSelectedItem(), mat = matEd.getText().toString().trim(), inp = inEd.getText().toString().trim();
                int to = 60;
                try { to = Math.max(1, Math.min(600, Integer.parseInt(toEd.getText().toString().trim()))); } catch (Exception ignored) {}
                final int fTo = to;
                new AlertDialog.Builder(h.a).setTitle("确认执行命令")
                    .setMessage("即将执行以下命令：\n\n" + cmd + "\n\n事件：" + ev + "\n超时：" + fTo + "秒\n\n警告：将在本机执行，确定继续？")
                    .setPositiveButton("立即执行", (d2, w2) -> {
                        JSONObject req = NativeHome.json("command", cmd, "event", ev, "matcher", mat, "dir", data.optString("dir"), "input", inp, "timeout", fTo);
                        h.notice("正在测试...");
                        h.post("/api/ext/hooks/test", req, res -> {
                            if (!root.isAttachedToWindow() || res == null) return;
                            if (res.optBoolean("ok")) showTestResult(res); else h.notice(res.optString("message", "测试失败"));
                        });
                    }).setNegativeButton("取消", null).show();
            }).setNegativeButton("取消", null).show();
    }

    private void showTestResult(JSONObject res) {
        int code = res.optInt("code", 0);
        boolean timedOut = res.optBoolean("timedOut", false);
        long ms = res.optLong("ms", 0);
        String out = res.optString("stdout", "");
        String err = res.optString("stderr", "");
        String rep = "退出码: " + code + (timedOut ? " (已超时)" : "") + "\n耗时: " + ms + " ms\n\n--- 标准输出 ---\n" + (out.isEmpty() ? "(空)" : out) + "\n\n--- 标准错误 ---\n" + (err.isEmpty() ? "(空)" : err);
        TextView tv = new TextView(h.a); tv.setText(rep); tv.setTypeface(Typeface.MONOSPACE);
        int pad = h.dp(16); tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(h.a); sv.addView(tv);
        new AlertDialog.Builder(h.a).setTitle("测试结果").setView(sv).setPositiveButton("关闭", null).show();
    }

    private static boolean astEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject ja = (JSONObject) a, jb = (JSONObject) b;
            if (ja.length() != jb.length()) return false;
            Iterator<String> it = ja.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!jb.has(k) || !astEquals(ja.opt(k), jb.opt(k))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray ja = (JSONArray) a, jb = (JSONArray) b;
            if (ja.length() != jb.length()) return false;
            for (int i = 0; i < ja.length(); i++) if (!astEquals(ja.opt(i), jb.opt(i))) return false;
            return true;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
