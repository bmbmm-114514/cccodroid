package cc.codroid;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NativeSchedulePanel {

    public static void show(NativeHome h) {
        LinearLayout root = h.scrolling();
        root.setPadding(h.dp(14), h.dp(12), h.dp(14), h.dp(16));

        LinearLayout budgetGroup = PixelUi.group(root);
        LinearLayout budgetRow = new LinearLayout(h.a);
        budgetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        budgetRow.setPadding(h.dp(16), h.dp(8), h.dp(8), h.dp(8));
        budgetGroup.addView(budgetRow);
        TextView budgetText = new TextView(h.a);
        budgetText.setTextSize(15);
        budgetText.setTextColor(PixelUi.color(h.a, "onSurface"));
        budgetRow.addView(budgetText, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout missedBox = new LinearLayout(h.a);
        missedBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(missedBox);

        EditText searchEd = new EditText(h.a);
        searchEd.setHint("搜索任务名称或提示词");
        searchEd.setMinHeight(h.dp(48));
        searchEd.setBackground(PixelUi.shape(h.a, "surfaceContainerHigh", 24));
        searchEd.setPadding(h.dp(16), 0, h.dp(16), 0);
        searchEd.setSingleLine(true);
        root.addView(searchEd);

        LinearLayout listContainer = new LinearLayout(h.a);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, h.dp(8), 0, 0);
        root.addView(listContainer);

        List<JSONObject> allTasks = new ArrayList<>();
        Set<String> runningIds = new HashSet<>();
        final double[] dailyBudget = {0};

        Runnable render = new Runnable() {
            @Override public void run() {
                String q = searchEd.getText().toString().trim().toLowerCase(Locale.getDefault());
                listContainer.removeAllViews();
                int count = 0;
                for (JSONObject t : allTasks) {
                    String name = t.optString("name", "");
                    String prompt = t.optString("prompt", "");
                    if (q.isEmpty() || name.toLowerCase(Locale.getDefault()).contains(q) || prompt.toLowerCase(Locale.getDefault()).contains(q)) {
                        renderTaskCard(h, listContainer, t, runningIds.contains(t.optString("id")), () -> load(h, allTasks, runningIds, dailyBudget, budgetText, missedBox, this));
                        count++;
                    }
                }
                if (count == 0) {
                    TextView empty = new TextView(h.a);
                    empty.setText(allTasks.isEmpty() ? "暂无定时任务" : "无匹配任务");
                    empty.setPadding(0, h.dp(20), 0, 0);
                    listContainer.addView(empty);
                }
            }
        };

        Runnable reload = () -> load(h, allTasks, runningIds, dailyBudget, budgetText, missedBox, render);

        h.headerAction("add", "新建任务", () -> showEditDialog(h, null, reload));
        budgetRow.addView(PixelUi.icon(h.a, "tune", "设置预算", () -> showBudgetDialog(h, dailyBudget[0], reload)));
        budgetRow.addView(PixelUi.icon(h.a, "refresh", "刷新任务", reload));

        searchEd.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int b, int c, int a) {}
            public void onTextChanged(CharSequence s, int b, int c, int a) { render.run(); }
            public void afterTextChanged(Editable s) {}
        });

        reload.run();
    }

    private static void load(NativeHome h, List<JSONObject> tasks, Set<String> running, double[] budget, TextView budgetTv, LinearLayout missedBox, Runnable render) {
        h.get("/api/sched", res -> {
            if (res == null) return;
            tasks.clear();
            JSONArray arr = res.optJSONArray("tasks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null) tasks.add(item);
                }
            }
            running.clear();
            JSONArray rArr = res.optJSONArray("running");
            if (rArr != null) {
                for (int i = 0; i < rArr.length(); i++) running.add(rArr.optString(i));
            }
            JSONObject bObj = res.optJSONObject("budget");
            budget[0] = bObj != null ? bObj.optDouble("daily", 0) : 0;
            double today = bObj != null ? bObj.optDouble("today", 0) : 0;
            budgetTv.setText(String.format(Locale.getDefault(), "每日预算 $%.2f\n今日消耗 $%.2f", budget[0], today));

            missedBox.removeAllViews();
            JSONArray missed = res.optJSONArray("missed");
            if (missed != null && missed.length() > 0) {
                TextView warn = new TextView(h.a);
                warn.setText("⚠️ 存在 " + missed.length() + " 项错过的执行任务");
                warn.setPadding(0, h.dp(4), 0, h.dp(4));
                missedBox.addView(warn);

                Button handleMissed = new Button(h.a);
                handleMissed.setText("处理错过任务 (" + missed.length() + ")");
                handleMissed.setOnClickListener(v -> showMissedDialog(h, missed, () -> load(h, tasks, running, budget, budgetTv, missedBox, render)));
                missedBox.addView(handleMissed);
            }
            render.run();
        });
    }

    private static void renderTaskCard(NativeHome h, LinearLayout parent, JSONObject t, boolean isRunning, Runnable reload) {
        LinearLayout card = PixelUi.group(parent);
        card.setPadding(h.dp(16), h.dp(14), h.dp(16), h.dp(14));
        card.setClickable(true);
        card.setFocusable(true);

        boolean on = t.optBoolean("on", true);
        String name = t.optString("name", "未命名任务");
        String status = (isRunning ? "[运行中] " : "") + (on ? "已启用" : "已暂停") + (t.optBoolean("skipNext") ? " [跳过下次]" : "");

        TextView title = new TextView(h.a);
        title.setText(name);
        title.setTextColor(PixelUi.color(h.a,"onSurface"));
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        title.setTextSize(16);
        card.addView(title);

        TextView freqTv = new TextView(h.a);
        freqTv.setText(status + " · " + fmtFreq(t.optJSONObject("freq")));
        freqTv.setTextColor(PixelUi.color(h.a,"primary"));
        freqTv.setTextSize(13);
        card.addView(freqTv);

        TextView nextTv = new TextView(h.a);
        nextTv.setText("下次: " + fmtTime(t.optLong("nextAt", 0)) + " | 累计: " + t.optInt("runs", 0) + "次");
        nextTv.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
        nextTv.setTextSize(13);
        card.addView(nextTv);

        JSONObject last = t.optJSONObject("last");
        if (last != null) {
            TextView lastTv = new TextView(h.a);
            lastTv.setText("上次: " + last.optString("st", "") + " " + fmtTime(last.optLong("at", 0)) + (last.has("msg") ? " - " + last.optString("msg") : ""));
            lastTv.setTextSize(12);
            card.addView(lastTv);
        }

        card.setOnClickListener(v -> showTaskActions(h, t, isRunning, reload));
    }

    private static void showTaskActions(NativeHome h, JSONObject t, boolean isRunning, Runnable reload) {
        String id = t.optString("id");
        String name = t.optString("name", "");
        boolean on = t.optBoolean("on", true);

        String[] items = new String[] {
            "查看详情与运行记录",
            isRunning ? "任务运行中 (无法重复执行)" : "立即运行一次",
            on ? "暂停任务" : "启用任务",
            "跳过下一次运行",
            "编辑任务",
            "删除任务"
        };

        new AlertDialog.Builder(h.a)
            .setTitle(name)
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    showDetails(h, id);
                } else if (which == 1) {
                    if (!isRunning) {
                        h.post("/api/sched/" + id + "/run", new JSONObject(), res -> {
                            if (!res.optBoolean("ok")) { h.notice(res.optString("message", "任务未能运行")); return; }
                            h.notice(res.optBoolean("queued") ? "已排队等待执行" : "任务已触发");
                            reload.run();
                        });
                    }
                } else if (which == 2) {
                    h.req("PATCH", "/api/sched/" + id, NativeHome.json("on", !on), res -> reload.run());
                } else if (which == 3) {
                    h.post("/api/sched/" + id + "/pause", NativeHome.json("mode", "skip"), res -> {
                        h.notice("已标记跳过下次");
                        reload.run();
                    });
                } else if (which == 4) {
                    showEditDialog(h, t, reload);
                } else if (which == 5) {
                    new AlertDialog.Builder(h.a)
                        .setTitle("确认删除")
                        .setMessage("确定要删除定时任务「" + name + "」吗？")
                        .setPositiveButton("删除", (cd, cw) -> h.req("DELETE", "/api/sched/" + id, new JSONObject(), res -> reload.run()))
                        .setNegativeButton("取消", null)
                        .show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showDetails(NativeHome h, String taskId) {
        h.get("/api/sched/" + taskId, res -> {
            if (res == null) return;
            JSONObject task = res.optJSONObject("task");
            JSONArray runs = res.optJSONArray("runs");

            ScrollView sv = new ScrollView(h.a);
            LinearLayout layout = new LinearLayout(h.a);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(16));
            sv.addView(layout);

            if (task != null) {
                TextView promptTv = new TextView(h.a);
                promptTv.setText("指令内容:\n" + task.optString("prompt", ""));
                promptTv.setTextSize(14);
                promptTv.setPadding(0, 0, 0, h.dp(8));
                layout.addView(promptTv);
            }

            TextView header = new TextView(h.a);
            header.setText("近期执行记录 (共 " + res.optInt("runsTotal", runs != null ? runs.length() : 0) + " 次):");
            header.setTextSize(14);
            header.setPadding(0, h.dp(4), 0, h.dp(6));
            layout.addView(header);

            if (runs != null && runs.length() > 0) {
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject r = runs.optJSONObject(i);
                    if (r == null) continue;
                    TextView tv = new TextView(h.a);
                    String sid = r.optString("sid", "");
                    tv.setText(fmtTime(r.optLong("at", 0)) + "  [" + r.optString("st", "") + "]  " + r.optString("msg", ""));
                    layout.addView(tv);
                    if (!sid.isEmpty()) {
                        Button openBtn = new Button(h.a);
                        openBtn.setText("查看对应会话 (" + sid + ")");
                        openBtn.setOnClickListener(v -> h.openSession(sid));
                        layout.addView(openBtn);
                    }
                }
            } else {
                TextView none = new TextView(h.a);
                none.setText("暂无执行记录");
                layout.addView(none);
            }

            new AlertDialog.Builder(h.a)
                .setTitle(task != null ? task.optString("name", "任务详情") : "详情")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
        });
    }

    private static void showEditDialog(NativeHome h, JSONObject existing, Runnable reload) {
        ScrollView sv = new ScrollView(h.a);
        LinearLayout form = new LinearLayout(h.a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(16));
        sv.addView(form);

        EditText nameEd = new EditText(h.a);
        nameEd.setHint("任务名称");
        if (existing != null) nameEd.setText(existing.optString("name", ""));
        form.addView(nameEd);

        EditText promptEd = new EditText(h.a);
        promptEd.setHint("提示词 / 执行指令");
        promptEd.setMinLines(3);
        if (existing != null) promptEd.setText(existing.optString("prompt", ""));
        form.addView(promptEd);

        JSONObject oldFreq = existing != null ? existing.optJSONObject("freq") : null;
        NativeFrequencyPanel frequency = new NativeFrequencyPanel(h, form, oldFreq);

        NativeTaskOptionsPanel options = new NativeTaskOptionsPanel(h, form, existing);
        String initialFrequency = "";
        try {
            JSONObject frequencyValue = frequency.read();
            if (frequencyValue != null) initialFrequency = frequencyValue.toString();
        } catch (IllegalArgumentException ignored) {
            // A legacy task may have a frequency that needs to be corrected in the form.
        }
        final String displayedFrequency = initialFrequency;
        AlertDialog editor = new AlertDialog.Builder(h.a)
            .setTitle(existing == null ? "新建定时任务" : "编辑任务")
            .setView(sv)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
        editor.show();
        Button save = editor.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(v -> {
            String name = nameEd.getText().toString();
            String prompt = promptEd.getText().toString();
            if (name.trim().isEmpty() || prompt.trim().isEmpty()) {
                h.notice("名称和指令内容均不能为空");
                return;
            }
            JSONObject patch = new JSONObject();
            try {
                if (existing == null || !existing.optString("name").equals(name)) patch.put("name", name);
                if (existing == null || !existing.optString("prompt").equals(prompt)) patch.put("prompt", prompt);
                JSONObject value = frequency.read();
                if (value != null && (existing == null || !value.toString().equals(displayedFrequency))) patch.put("freq", value);
                options.apply(patch);
                if (existing != null) {
                    if (patch.length() == 0) { editor.dismiss(); return; }
                    JSONObject expected = new JSONObject();
                    for (java.util.Iterator<String> it = patch.keys(); it.hasNext();) {
                        String field = it.next();
                        if (existing.has(field)) expected.put(field, existing.get(field));
                    }
                    patch.put("expected", expected);
                }
            } catch (IllegalArgumentException invalid) { h.notice(invalid.getMessage()); return; }
              catch (org.json.JSONException invalid) { h.notice("任务参数格式错误"); return; }
            save.setEnabled(false);
            String path = existing == null ? "/api/sched" : "/api/sched/" + existing.optString("id");
            h.req(existing == null ? "POST" : "PATCH", path, patch,
                res -> { editor.dismiss(); reload.run(); },
                message -> { save.setEnabled(true); h.notice(message); });
        });
    }

    private static void showBudgetDialog(NativeHome h, double currentBudget, Runnable reload) {
        EditText input = new EditText(h.a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("每日预算金额 (USD)");
        input.setText(String.format(Locale.US, "%.2f", currentBudget));

        new AlertDialog.Builder(h.a)
            .setTitle("设置每日预算 (USD)")
            .setView(input)
            .setPositiveButton("保存", (d, w) -> {
                double val;
                try { val = Double.parseDouble(input.getText().toString().trim()); }
                catch (NumberFormatException ex) { h.notice("请输入 0 到 1000 的美元金额"); return; }
                if (!(val >= 0 && val <= 1000)) { h.notice("预算范围是 0 到 1000 美元"); return; }
                h.req("PUT", "/api/sched/budget", NativeHome.json("daily", val), res -> reload.run());
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showMissedDialog(NativeHome h, JSONArray missed, Runnable reload) {
        String[] items = new String[missed.length()];
        for (int i = 0; i < items.length; i++) {
            JSONObject m = missed.optJSONObject(i);
            items[i] = m == null ? "—" : m.optString("name", m.optString("taskId")) + " · " + fmtTime(m.optLong("at"));
        }
        new AlertDialog.Builder(h.a).setTitle("错过的任务").setItems(items, (d, index) -> {
            JSONObject m = missed.optJSONObject(index);
            if (m == null) return;
            new AlertDialog.Builder(h.a).setTitle(items[index])
                .setPositiveButton("补跑", (dialog, which) -> h.post("/api/sched/missed",
                    NativeHome.json("taskId", m.optString("taskId"), "act", "run", "at", m.optLong("at")), r -> reload.run()))
                .setNeutralButton("跳过", (dialog, which) -> h.post("/api/sched/missed",
                    NativeHome.json("taskId", m.optString("taskId"), "act", "skip", "at", m.optLong("at")), r -> reload.run()))
                .setNegativeButton("取消", null).show();
        }).setNegativeButton("关闭", null).show();
    }

    private static String fmtFreq(JSONObject f) {
        if (f == null) return "未指定";
        String k = f.optString("kind", "");
        if ("daily".equals(k)) return "每天 " + f.optString("time", "09:00");
        if ("monthly".equals(k)) return "每月 " + ("last".equals(f.optString("day")) ? "最后一天" : f.optInt("day", 1) + " 日") + " " + f.optString("time", "09:00");
        if ("weekly".equals(k)) return "每周 " + f.optString("days", "[1]") + " " + f.optString("time", "09:00");
        if ("every".equals(k)) return "每 " + f.optInt("hours", 1) + " 小时 (" + f.optString("from", "00:00") + "-" + f.optString("to", "23:59") + ")";
        if ("once".equals(k)) return "单次 " + f.optString("date", "") + " " + f.optString("time", "");
        if ("cron".equals(k)) return "Cron: " + f.optString("cron", "");
        return k.isEmpty() ? "未知" : k;
    }

    private static String fmtTime(long ts) {
        if (ts <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }
}
