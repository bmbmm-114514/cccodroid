package cc.codroid;

import android.text.InputType;
import android.graphics.Color;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.material.radiobutton.MaterialRadioButton;
import android.view.View;
import android.widget.*;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

public class NativeTaskOptionsPanel {
    final NativeHome h;
    final JSONObject orig;
    final Set<String> validProjects = new HashSet<>();
    final String initProjId, initSession, initPerm, initTools, initProv, initModel, initEffort;
    final String initBusy, initMissed, initNotify, initQuiet, initUntilKind, initUntilDate;
    final boolean initSave, initCarry, initWifi, initChg;
    final int initBat, initMin, initRetry, initUntilCount;
    final double initCost;

    final Spinner projSpinner, effortSpinner, batSpinner, busySpinner, retrySpinner, missedSpinner, notifySpinner, untilSpinner;
    final RadioOptions sessionOptions, permissionOptions;
    final EditText toolsEdit, provEdit, modelEdit, minEdit, costEdit, quietEdit, untilDateEdit, untilCountEdit;
    final CheckBox cbWifi, cbChg, cbSave, cbCarry;
    final View toolsRow, quietRow, untilDateRow, untilCountRow;

    static class Item {
        final String id, label;
        Item(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
        @Override public boolean equals(Object other) {
            return other instanceof Item && Objects.equals(id, ((Item) other).id);
        }
        @Override public int hashCode() { return Objects.hashCode(id); }
    }

    public NativeTaskOptionsPanel(NativeHome h, LinearLayout form, JSONObject existingOrNull) {
        this.h = h;
        this.orig = existingOrNull;

        initProjId = (orig != null && !orig.isNull("projectId")) ? orig.optString("projectId", null) : null;
        initSession = orig != null ? orig.optString("session", "new") : "new";
        initPerm = orig != null ? orig.optString("perm", "ro") : "ro";
        initTools = orig != null ? orig.optString("tools", "") : "";
        initProv = orig != null ? orig.optString("provider", "") : "";
        initModel = orig != null ? orig.optString("model", "") : "";
        initEffort = orig != null ? orig.optString("effort", "") : "";
        initBusy = orig != null ? orig.optString("busy", "queue") : "queue";
        initMissed = orig != null ? orig.optString("missed", "ask") : "ask";
        initNotify = orig != null ? orig.optString("notify", "all") : "all";
        initQuiet = orig != null ? orig.optString("quiet", "") : "";
        initSave = orig == null || orig.optBoolean("saveSession", true);
        initCarry = orig != null && orig.optBoolean("carryLast", false);
        initCost = orig != null ? orig.optDouble("limitCost", 0) : 0;
        initMin = orig != null ? orig.optInt("limitMin", 15) : 15;
        initRetry = orig != null ? orig.optInt("retry", 0) : 0;

        JSONObject oc = orig != null ? orig.optJSONObject("cond") : null;
        initWifi = oc != null && oc.optBoolean("wifi", false);
        initChg = oc != null && oc.optBoolean("charging", false);
        initBat = oc != null ? oc.optInt("battery", 0) : 0;

        JSONObject ou = orig != null ? orig.optJSONObject("until") : null;
        initUntilKind = ou != null ? ou.optString("kind", "ever") : "ever";
        initUntilDate = ou != null ? ou.optString("date", "") : "";
        initUntilCount = ou != null ? ou.optInt("count", 10) : 10;

        // 1. 所属项目
        List<Item> pItems = new ArrayList<>();
        pItems.add(new Item(null, "工作区"));
        if (initProjId != null && !initProjId.isEmpty()) pItems.add(new Item(initProjId, "原项目 (" + initProjId + ")"));
        projSpinner = spinner(form, "在哪跑（所属项目）", pItems, initProjId);
        loadProjects();

        // 2. 会话
        sessionOptions = new RadioOptions(form, "会话", new String[][]{{"new", "每次新开一个对话"}, {"same", "接着同一个对话"}}, initSession);

        // 3. 权限与工具
        permissionOptions = new RadioOptions(form, "权限", new String[][]{
            {"ro", "只读（按任务的只读工具清单运行）"},
            {"proj", "可以改项目目录里的文件"},
            {"custom", "自己挑允许的工具"}
        }, initPerm);
        toolsEdit = new EditText(h.a);
        toolsEdit.setText(initTools);
        toolsEdit.setHint("如：Bash(git *),WebFetch,Read");
        toolsRow = row(form, "允许工具（白名单）", toolsEdit);
        toolsRow.setVisibility("custom".equals(initPerm) ? View.VISIBLE : View.GONE);
        permissionOptions.changed = () -> toolsRow.setVisibility("custom".equals(permissionOptions.value()) ? View.VISIBLE : View.GONE);

        // 4. 高级折叠面板
        Button btnAdv = new Button(h.a);
        btnAdv.setText("▶ 更多设置");
        LinearLayout adv = new LinearLayout(h.a);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        btnAdv.setOnClickListener(v -> {
            boolean vis = adv.getVisibility() == View.VISIBLE;
            adv.setVisibility(vis ? View.GONE : View.VISIBLE);
            btnAdv.setText(vis ? "▶ 更多设置" : "▼ 收起更多设置");
        });
        form.addView(btnAdv);
        form.addView(adv);

        // 型号与接口
        provEdit = edit(adv, "接口 ID（留空跟随项目或全局）", initProv, "填写已配置的接口 ID", 0);
        modelEdit = edit(adv, "型号（Model，留空跟随全局）", initModel, "填写完整型号 ID", 0);
        effortSpinner = spinner(adv, "思考强度", items("", "跟随项目 / 全局", "low", "low", "medium", "medium", "high", "high", "xhigh", "xhigh", "max", "max"), initEffort);

        // 运行条件
        h.text(adv, "运行条件（不满足则跳过）");
        cbWifi = PixelChoiceField.checkbox(h.a); cbWifi.setText("只在 Wi-Fi 下"); cbWifi.setChecked(initWifi); adv.addView(cbWifi);
        cbChg = PixelChoiceField.checkbox(h.a); cbChg.setText("只在充电时"); cbChg.setChecked(initChg); adv.addView(cbChg);
        batSpinner = spinner(adv, "电量低于多少跳过", items("0", "不管", "15", "15%", "30", "30%", "50", "50%"), String.valueOf(initBat));

        // 冲突处理
        busySpinner = spinner(adv, "到点时有别的对话在跑", items("queue", "排队等它跑完", "skip", "跳过这次", "run", "照跑"), initBusy);

        // 限制与重试
        minEdit = edit(adv, "单次时长上限（分钟，1–600）", String.valueOf(initMin), "15", InputType.TYPE_CLASS_NUMBER);
        costEdit = edit(adv, "单次花费上限（美元，0–100，0为不限）", initCost == (long) initCost ? String.valueOf((long) initCost) : String.valueOf(initCost), "0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        retrySpinner = spinner(adv, "失败了重试", items("0", "不重试", "1", "重试 1 次", "2", "重试 2 次", "3", "重试 3 次"), String.valueOf(initRetry));
        missedSpinner = spinner(adv, "错过了（关机或无网）", items("ask", "下次打开问我", "run", "开机后立刻补跑一次", "skip", "跳过"), initMissed);

        // 结果与通知
        notifySpinner = spinner(adv, "跑完通知", items("all", "每次都通知", "fail", "只在失败时", "found", "只在有发现时", "none", "不通知"), initNotify);
        quietEdit = new EditText(h.a);
        quietEdit.setText(initQuiet);
        quietEdit.setHint("回复正好是这句就不通知（如：全绿）");
        quietRow = row(adv, "没发现时它回的那句话", quietEdit);
        quietRow.setVisibility("found".equals(initNotify) ? View.VISIBLE : View.GONE);
        notifySpinner.setOnItemSelectedListener(new SimpleSelectedListener(() -> quietRow.setVisibility("found".equals(getSpinnerId(notifySpinner)) ? View.VISIBLE : View.GONE)));

        cbSave = PixelChoiceField.checkbox(h.a); cbSave.setText("结果存成对话（列表带标记）"); cbSave.setChecked(initSave); adv.addView(cbSave);
        cbCarry = PixelChoiceField.checkbox(h.a); cbCarry.setText("把上一次的结果带上"); cbCarry.setChecked(initCarry); adv.addView(cbCarry);

        // 有效期
        untilSpinner = spinner(adv, "有效期", items("ever", "一直有效", "date", "到某天为止", "count", "跑满几次就关"), initUntilKind);
        String defDate = initUntilDate.isEmpty() ? LocalDate.now().plusDays(1).toString() : initUntilDate;
        untilDateEdit = new EditText(h.a); untilDateEdit.setText(defDate); untilDateEdit.setHint("YYYY-MM-DD");
        untilDateRow = row(adv, "到期日 (YYYY-MM-DD)", untilDateEdit);

        untilCountEdit = new EditText(h.a); untilCountEdit.setText(String.valueOf(initUntilCount)); untilCountEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        untilCountRow = row(adv, "跑满次数（至少 1 次）", untilCountEdit);

        Runnable updateUntil = () -> {
            String k = getSpinnerId(untilSpinner);
            untilDateRow.setVisibility("date".equals(k) ? View.VISIBLE : View.GONE);
            untilCountRow.setVisibility("count".equals(k) ? View.VISIBLE : View.GONE);
        };
        updateUntil.run();
        untilSpinner.setOnItemSelectedListener(new SimpleSelectedListener(updateUntil));
    }

    void loadProjects() {
        h.get("/api/projects", res -> {
            if (res == null) return;
            JSONArray arr = res.optJSONArray("projects");
            if (arr == null) return;
            List<Item> updated = new ArrayList<>();
            updated.add(new Item(null, "工作区"));
            boolean hasOrig = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) {
                    String id = p.optString("id");
                    validProjects.add(id);
                    updated.add(new Item(id, "项目：" + p.optString("name", id)));
                    if (id.equals(initProjId)) hasOrig = true;
                }
            }
            if (initProjId != null && !initProjId.isEmpty() && !hasOrig) {
                updated.add(new Item(initProjId, "已删除/未知项目 (" + initProjId + ")"));
            }
            String cur = getSpinnerId(projSpinner);
            ArrayAdapter<Item> ad = new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_item, updated);
            projSpinner.setAdapter(ad);
            for (int i = 0; i < updated.size(); i++) {
                if (Objects.equals(updated.get(i).id, cur)) { projSpinner.setSelection(i); break; }
            }
        });
    }

    public void apply(JSONObject body) throws IllegalArgumentException {
        int curMin;
        try {
            curMin = Integer.parseInt(minEdit.getText().toString().trim());
        } catch (Exception e) { throw new IllegalArgumentException("单次时长上限须填 1–600 的整数（分钟）"); }
        if (curMin < 1 || curMin > 600) throw new IllegalArgumentException("单次时长上限须填 1–600 的整数（分钟）");

        double curCost;
        try {
            curCost = Double.parseDouble(costEdit.getText().toString().trim());
        } catch (Exception e) { throw new IllegalArgumentException("单次花费上限须填 0–100 的数值（美元）"); }
        if (Double.isNaN(curCost) || Double.isInfinite(curCost) || curCost < 0 || curCost > 100) throw new IllegalArgumentException("单次花费上限须填 0–100 的数值（美元）");

        String curUntilKind = getSpinnerId(untilSpinner);
        String curUntilDate = untilDateEdit.getText().toString().trim();
        int curUntilCount = 10;
        if ("date".equals(curUntilKind)) {
            try { LocalDate.parse(curUntilDate); } catch (Exception e) { throw new IllegalArgumentException("到期日要填有效日期 (YYYY-MM-DD)"); }
        } else if ("count".equals(curUntilKind)) {
            try {
                curUntilCount = Integer.parseInt(untilCountEdit.getText().toString().trim());
            } catch (Exception e) { throw new IllegalArgumentException("次数至少填 1"); }
            if (curUntilCount < 1) throw new IllegalArgumentException("次数至少填 1");
        }

        Item selProj = (Item) projSpinner.getSelectedItem();
        String curProjId = selProj != null ? selProj.id : null;
        if (curProjId != null && !curProjId.isEmpty()) {
            boolean isPreserved = orig != null && curProjId.equals(initProjId);
            if (!isPreserved && !validProjects.contains(curProjId)) throw new IllegalArgumentException("所选项目不在有效项目列表中");
        }

        String curSession = sessionOptions.value(), curPerm = permissionOptions.value();
        String curTools = toolsEdit.getText().toString().trim(), curProv = provEdit.getText().toString().trim();
        String curModel = modelEdit.getText().toString().trim(), curEffort = getSpinnerId(effortSpinner);
        boolean curWifi = cbWifi.isChecked(), curChg = cbChg.isChecked();
        String batStr = getSpinnerId(batSpinner);
        int curBat = batStr.isEmpty() ? 0 : Integer.parseInt(batStr);
        String curBusy = getSpinnerId(busySpinner), curMissed = getSpinnerId(missedSpinner);
        String curNotify = getSpinnerId(notifySpinner), curQuiet = quietEdit.getText().toString().trim();
        boolean curSave = cbSave.isChecked(), curCarry = cbCarry.isChecked();
        String retryStr = getSpinnerId(retrySpinner);
        int curRetry = retryStr.isEmpty() ? 0 : Integer.parseInt(retryStr);

        try {
            if (orig != null) {
                if (!Objects.equals(curProjId, initProjId)) body.put("projectId", curProjId != null ? curProjId : JSONObject.NULL);
                if (!curSession.equals(initSession)) body.put("session", curSession);
                if (!curPerm.equals(initPerm)) body.put("perm", curPerm);
                if (!curTools.equals(initTools)) body.put("tools", curTools);
                if (!curProv.equals(initProv)) body.put("provider", curProv);
                if (!curModel.equals(initModel)) body.put("model", curModel);
                if (!curEffort.equals(initEffort)) body.put("effort", curEffort);
                if (!curBusy.equals(initBusy)) body.put("busy", curBusy);
                if (!curMissed.equals(initMissed)) body.put("missed", curMissed);
                if (!curNotify.equals(initNotify)) body.put("notify", curNotify);
                if (!curQuiet.equals(initQuiet)) body.put("quiet", curQuiet);
                if (curSave != initSave) body.put("saveSession", curSave);
                if (curCarry != initCarry) body.put("carryLast", curCarry);
                if (Double.compare(curCost, initCost) != 0) body.put("limitCost", curCost);
                if (curMin != initMin) body.put("limitMin", curMin);
                if (curRetry != initRetry) body.put("retry", curRetry);

                boolean condChanged = (curWifi != initWifi) || (curChg != initChg) || (curBat != initBat);
                if (condChanged) {
                    JSONObject oc = orig.optJSONObject("cond");
                    JSONObject nc = oc != null ? new JSONObject(oc.toString()) : new JSONObject();
                    nc.put("wifi", curWifi).put("charging", curChg).put("battery", curBat);
                    body.put("cond", nc);
                }
                boolean untilChanged = !curUntilKind.equals(initUntilKind);
                if ("date".equals(curUntilKind) && !curUntilDate.equals(initUntilDate)) untilChanged = true;
                if ("count".equals(curUntilKind) && curUntilCount != initUntilCount) untilChanged = true;
                if (untilChanged) {
                    JSONObject ou = orig.optJSONObject("until");
                    JSONObject nu = ou != null ? new JSONObject(ou.toString()) : new JSONObject();
                    nu.put("kind", curUntilKind);
                    if ("date".equals(curUntilKind)) nu.put("date", curUntilDate); else nu.remove("date");
                    if ("count".equals(curUntilKind)) nu.put("count", curUntilCount); else nu.remove("count");
                    body.put("until", nu);
                }
            } else {
                body.put("projectId", curProjId != null ? curProjId : JSONObject.NULL);
                body.put("session", curSession);
                body.put("perm", curPerm);
                if (!curTools.isEmpty()) body.put("tools", curTools);
                if (!curProv.isEmpty()) body.put("provider", curProv);
                if (!curModel.isEmpty()) body.put("model", curModel);
                if (!curEffort.isEmpty()) body.put("effort", curEffort);
                body.put("busy", curBusy);
                body.put("missed", curMissed);
                body.put("notify", curNotify);
                if (!curQuiet.isEmpty()) body.put("quiet", curQuiet);
                body.put("saveSession", curSave);
                body.put("carryLast", curCarry);
                body.put("limitCost", curCost);
                body.put("limitMin", curMin);
                body.put("retry", curRetry);
                body.put("cond", new JSONObject().put("wifi", curWifi).put("charging", curChg).put("battery", curBat));
                JSONObject nu = new JSONObject().put("kind", curUntilKind);
                if ("date".equals(curUntilKind)) nu.put("date", curUntilDate);
                if ("count".equals(curUntilKind)) nu.put("count", curUntilCount);
                body.put("until", nu);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    EditText edit(LinearLayout p, String label, String val, String hint, int inputType) {
        if (label != null) h.text(p, label);
        EditText et = new EditText(h.a);
        if (val != null) et.setText(val);
        if (hint != null) et.setHint(hint);
        if (inputType != 0) et.setInputType(inputType);
        p.addView(et);
        return et;
    }

    LinearLayout row(LinearLayout p, String label, View field) {
        LinearLayout r = new LinearLayout(h.a);
        r.setOrientation(LinearLayout.VERTICAL);
        if (label != null) h.text(r, label);
        r.addView(field);
        p.addView(r);
        return r;
    }

    Spinner spinner(LinearLayout p, String label, List<Item> items, String cur) {
        if (label != null) h.text(p, label);
        Spinner sp = new PixelChoiceField(h.a, label);
        if (cur != null && !cur.isEmpty()) {
            boolean found = false;
            for (Item it : items) { if (cur.equals(it.id)) { found = true; break; } }
            if (!found) items.add(new Item(cur, cur));
        }
        ArrayAdapter<Item> ad = new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_item, items);
        sp.setAdapter(ad);
        if (cur != null) {
            for (int i = 0; i < items.size(); i++) {
                if (cur.equals(items.get(i).id)) { sp.setSelection(i); break; }
            }
        }
        p.addView(sp);
        return sp;
    }

    private final class RadioOptions {
        final PixelSelectionStrip strip;
        private MaterialRadioButton selected;
        Runnable changed;

        RadioOptions(LinearLayout parent, String label, String[][] options, String initial) {
            h.text(parent, label);
            strip = new PixelSelectionStrip(h.a, Color.TRANSPARENT, PixelUi.color(h.a, "primaryContainer"), h.dp(16));
            strip.setOrientation(LinearLayout.VERTICAL);
            strip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo node) {
                    super.onInitializeAccessibilityNodeInfo(host, node);
                    node.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(strip.getChildCount(), 1,
                            false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
                }
            });
            parent.addView(strip, -1, -2);
            for (String[] option : options) add(option[0], option[1], option[0].equals(initial));
            if (selected == null) add(initial, "保留原设置 (" + initial + ")", true);
        }

        private void add(String id, String label, boolean checked) {
            MaterialRadioButton button = new MaterialRadioButton(h.a);
            button.setTag(id);
            button.setText(label);
            button.setMinHeight(h.dp(48));
            button.setPaddingRelative(h.dp(12), h.dp(4), h.dp(12), h.dp(4));
            button.setBackground(PixelUi.clickable(h.a, null, 16));
            button.setOnClickListener(v -> select(button, true));
            strip.addView(button, -1, -2);
            if (checked) select(button, false);
        }

        private void select(MaterialRadioButton button, boolean animate) {
            if (selected == button) return;
            if (selected != null) selected.setChecked(false);
            selected = button;
            button.setChecked(true);
            strip.select(strip.indexOfChild(button), animate);
            if (changed != null) changed.run();
        }

        String value() { return (String) selected.getTag(); }
    }

    static String getSpinnerId(Spinner sp) {
        Object o = sp.getSelectedItem();
        return (o instanceof Item) ? ((Item) o).id : "";
    }

    static List<Item> items(String... pairs) {
        List<Item> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) list.add(new Item(pairs[i], pairs[i + 1]));
        return list;
    }

    static class SimpleSelectedListener implements AdapterView.OnItemSelectedListener {
        final Runnable r;
        SimpleSelectedListener(Runnable r) { this.r = r; }
        public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { r.run(); }
        public void onNothingSelected(AdapterView<?> p) {}
    }
}
