package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class NativeFrequencyPanel {
    private final List<String> kinds = new ArrayList<>(Arrays.asList("daily", "weekly", "monthly", "every", "once", "cron"));
    private final Spinner sp;
    private final EditText timeEdit, dayEdit, hoursEdit, fromEdit, toEdit, dateEdit, cronEdit;
    private final CheckBox[] dayChecks = new CheckBox[7];

    public NativeFrequencyPanel(NativeHome h, LinearLayout form, JSONObject existing) {
        h.text(form, "调度类型");
        sp = new Spinner(h.a);
        form.addView(sp);
        List<String> labels = new ArrayList<>(Arrays.asList("每天 (daily)", "每周 (weekly)", "每月 (monthly)", "每隔几小时 (every)", "单次运行 (once)", "Cron 表达式 (cron)"));
        String curKind = existing != null ? existing.optString("kind", "daily") : "daily";
        if (!curKind.isEmpty() && !kinds.contains(curKind)) {
            kinds.add(curKind);
            labels.add("保留未知类型 (" + curKind + ")");
        }
        sp.setAdapter(new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item, labels));

        LinearLayout timeBox = box(form);
        h.text(timeBox, "执行时间 (HH:mm)");
        timeEdit = edit(timeBox, "09:00", existing != null ? existing.optString("time", "09:00") : "09:00");

        LinearLayout weeklyBox = box(form);
        h.text(weeklyBox, "重复星期 (至少选一天)");
        String[] wNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        JSONArray arr = existing != null ? existing.optJSONArray("days") : null;
        for (int i = 0; i < 7; i++) {
            dayChecks[i] = new CheckBox(h.a);
            dayChecks[i].setText(wNames[i]);
            weeklyBox.addView(dayChecks[i]);
            if (arr != null) {
                for (int j = 0; j < arr.length(); j++) if (arr.optInt(j) == i + 1) dayChecks[i].setChecked(true);
            } else if (i == 0) dayChecks[i].setChecked(true);
        }

        LinearLayout monthlyBox = box(form);
        h.text(monthlyBox, "每月几号 (1–31 或 last)");
        dayEdit = edit(monthlyBox, "1–31 或 last", existing != null ? existing.optString("day", "1") : "1");

        LinearLayout everyBox = box(form);
        h.text(everyBox, "间隔小时 (1–24)");
        hoursEdit = edit(everyBox, "1–24", existing != null ? existing.optString("hours", "2") : "2");
        h.text(everyBox, "开始时间 (HH:mm)");
        fromEdit = edit(everyBox, "09:00", existing != null ? existing.optString("from", "00:00") : "00:00");
        h.text(everyBox, "结束时间 (HH:mm)");
        toEdit = edit(everyBox, "18:00", existing != null ? existing.optString("to", "23:59") : "23:59");

        LinearLayout onceBox = box(form);
        h.text(onceBox, "执行日期 (YYYY-MM-DD)");
        dateEdit = edit(onceBox, "YYYY-MM-DD", existing != null ? existing.optString("date", "") : "");

        LinearLayout cronBox = box(form);
        h.text(cronBox, "Cron 表达式 (分 时 日 月 周)");
        cronEdit = edit(cronBox, "0 9 * * 1-5", existing != null ? existing.optString("cron", "0 9 * * 1-5") : "0 9 * * 1-5");

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String k = kinds.get(pos);
                timeBox.setVisibility(k.equals("daily") || k.equals("weekly") || k.equals("monthly") || k.equals("once") ? View.VISIBLE : View.GONE);
                weeklyBox.setVisibility(k.equals("weekly") ? View.VISIBLE : View.GONE);
                monthlyBox.setVisibility(k.equals("monthly") ? View.VISIBLE : View.GONE);
                everyBox.setVisibility(k.equals("every") ? View.VISIBLE : View.GONE);
                onceBox.setVisibility(k.equals("once") ? View.VISIBLE : View.GONE);
                cronBox.setVisibility(k.equals("cron") ? View.VISIBLE : View.GONE);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        int idx = kinds.indexOf(curKind);
        if (idx >= 0) sp.setSelection(idx);

        h.button(form, "预览下次执行时间", () -> {
            try {
                JSONObject f = read();
                if (f == null) { h.notice("未知类型无法预览"); return; }
                h.post("/api/sched/preview", NativeHome.json("freq", f), r -> {
                    JSONArray times = r.optJSONArray("next");
                    if (times == null || times.length() == 0) { h.notice(r.optString("error", "未来没有匹配的时间")); return; }
                    StringBuilder next = new StringBuilder();
                    for (int i = 0; i < times.length(); i++) {
                        if (next.length() > 0) next.append("\n");
                        next.append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(times.optLong(i))));
                    }
                    new MaterialAlertDialogBuilder(h.a).setTitle("下次执行").setMessage(next).setPositiveButton("关闭", null).show();
                });
            } catch (IllegalArgumentException e) {
                h.notice(e.getMessage());
            }
        });
    }

    private LinearLayout box(LinearLayout parent) {
        LinearLayout l = new LinearLayout(parent.getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        parent.addView(l);
        return l;
    }

    private EditText edit(LinearLayout parent, String hint, String val) {
        EditText e = new EditText(parent.getContext());
        e.setHint(hint);
        if (val != null && !val.isEmpty()) e.setText(val);
        parent.addView(e);
        return e;
    }

    private static int[] parseHm(String s, String errMsg) {
        if (s == null) throw new IllegalArgumentException(errMsg);
        String[] p = s.trim().split(":");
        if (p.length != 2) throw new IllegalArgumentException(errMsg);
        try {
            int h = Integer.parseInt(p[0].trim()), m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) throw new IllegalArgumentException(errMsg);
            return new int[]{h, m};
        } catch (Exception e) {
            throw new IllegalArgumentException(errMsg);
        }
    }

    public JSONObject read() throws IllegalArgumentException {
        String kind = kinds.get(sp.getSelectedItemPosition());
        if (!Arrays.asList("daily", "weekly", "monthly", "every", "once", "cron").contains(kind)) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("kind", kind);
            if (kind.equals("daily") || kind.equals("weekly") || kind.equals("monthly") || kind.equals("once")) {
                String t = timeEdit.getText().toString().trim();
                parseHm(t, "时间要填 0–23 时、0–59 分");
                o.put("time", t);
            }
            if (kind.equals("weekly")) {
                JSONArray days = new JSONArray();
                for (int i = 0; i < 7; i++) if (dayChecks[i].isChecked()) days.put(i + 1);
                if (days.length() == 0) throw new IllegalArgumentException("星期要选 1–7 之间的整数值，至少一天");
                o.put("days", days);
            } else if (kind.equals("monthly")) {
                String d = dayEdit.getText().toString().trim();
                if (d.equals("last") || d.equals("最后一天")) o.put("day", "last");
                else {
                    try {
                        int num = Integer.parseInt(d);
                        if (num < 1 || num > 31) throw new Exception();
                        o.put("day", num);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("每月几号要填 1–31 或「最后一天」");
                    }
                }
            } else if (kind.equals("every")) {
                try {
                    int h = Integer.parseInt(hoursEdit.getText().toString().trim());
                    if (h < 1 || h > 24) throw new Exception();
                    o.put("hours", h);
                } catch (Exception e) {
                    throw new IllegalArgumentException("每隔几小时要填 1–24 的整数");
                }
                String f = fromEdit.getText().toString().trim(), to = toEdit.getText().toString().trim();
                int[] a = parseHm(f, "开始时间要填 0–23 时、0–59 分"), b = parseHm(to, "结束时间要填 0–23 时、0–59 分");
                if (a[0] * 60 + a[1] > b[0] * 60 + b[1]) {
                    throw new IllegalArgumentException("「每隔几小时」的开始时间要早于结束时间；跨午夜请拆成两个任务");
                }
                o.put("from", f);
                o.put("to", to);
            } else if (kind.equals("once")) {
                String dt = dateEdit.getText().toString().trim();
                if (!dt.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new IllegalArgumentException("只跑一次要填一个日期");
                try {
                    LocalDate.parse(dt);
                } catch (Exception e) {
                    throw new IllegalArgumentException("只跑一次要填一个日期");
                }
                o.put("date", dt);
            } else if (kind.equals("cron")) {
                String c = cronEdit.getText().toString().trim();
                if (c.isEmpty() || c.split("\\s+").length != 5) throw new IllegalArgumentException("cron 表达式要填五段：分 时 日 月 周");
                o.put("cron", c);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return o;
    }
}
