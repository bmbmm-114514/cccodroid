package cc.codroid;

import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public class NativeEnergyPanel {
    public static void add(NativeHome h, LinearLayout root) {
        TextView qv = h.text(root, "官方配额：未查询");
        h.button(root, "查询订阅剩余额度", () -> h.get("/api/multi/state", res -> {
            JSONObject q = res != null ? res.optJSONObject("quota") : null;
            if (q == null) { qv.setText("配额：未知或无数据"); return; }
            StringBuilder sb = new StringBuilder("官方配额状态:\n");
            for (String p : new String[]{"claude", "codex", "agy"}) {
                JSONArray arr = q.optJSONArray(p);
                if (arr == null || arr.length() == 0) { sb.append(p).append(": 未知\n"); continue; }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    double rem = it.optDouble("remaining", -1);
                    long exp = it.optLong("resetsAt", -1);
                    sb.append(p).append(" · ").append(it.optString("name", p)).append(": ");
                    if (rem < 0) sb.append("未知");
                    else sb.append(String.format(Locale.ROOT, "%.1f%%", rem));
                    sb.append(exp > 0 ? " (重置: " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(exp)) + ")\n" : " (重置: 未知)\n");
                }
            }
            qv.setText(sb.toString().trim());
        }));

        String[] views = {"day", "week", "hour"};
        String[] vNames = {"按日 (近14天)", "按周 (近12周)", "按小时 (近24h)"};
        int[] vIdx = {0};
        TextView ev = h.text(root, "能耗分析：未加载");
        TextView sv = h.text(root, "周期明细：未选择");
        LinearLayout bl = new LinearLayout(h.a);
        bl.setOrientation(LinearLayout.VERTICAL);
        root.addView(bl);

        h.button(root, "查看下一时间范围", () -> {
            String v = views[vIdx[0]];
            String vn = vNames[vIdx[0]];
            vIdx[0] = (vIdx[0] + 1) % views.length;
            ev.setTag(v);
            ev.setText("正在加载 " + vn + " 能耗...");
            sv.setText("周期明细：未选择");
            bl.removeAllViews();

            h.get("/api/energy?view=" + v, d -> {
                if (!v.equals(ev.getTag())) return;
                if (d == null || !d.optBoolean("ok", true)) { ev.setText("能耗接口获取失败"); return; }
                StringBuilder b = new StringBuilder();
                JSONObject bat = d.optJSONObject("battery");
                if (bat != null) {
                    b.append("电量 ").append(bat.optInt("level") < 0 ? "未知" : bat.optInt("level") + "%")
                     .append(bat.optBoolean("charging") ? " · 充电中" : " · 放电中");
                    if (!bat.isNull("currentUa")) b.append(String.format(Locale.ROOT, " · %.0f mA", bat.optDouble("currentUa") / 1000));
                    if (!bat.isNull("temp")) b.append(String.format(Locale.ROOT, " · %.1f ℃", bat.optDouble("temp")));
                    b.append(bat.optBoolean("screen") ? " · 亮屏" : " · 息屏").append("\n");
                } else {
                    b.append("【电池硬件】采样失败/无硬件数据 (无静默假数据)\n");
                }
                JSONArray bars = d.optJSONArray("bars");
                int bc = bars != null ? bars.length() : 0;
                b.append("【历史采样】视图: ").append(vn).append("，共 ").append(bc).append(" 周期");
                ev.setText(b.toString());

                if (bars != null) {
                    for (int i = Math.max(0, bc - 6); i < bc; i++) {
                        JSONObject bar = bars.optJSONObject(i);
                        if (bar == null) continue;
                        String t = "hour".equals(v) ? java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(bar.optLong("t"))) : bar.optString("t");
                        double c = bar.optDouble("c",0), f = bar.optDouble("f",0), s = bar.optDouble("s",0), dc = bar.optDouble("d",0);
                        h.button(bl, t, () -> {
                            sv.setText(String.format(Locale.ROOT, "%s\n应用 CPU %.0f 秒 · 前台 %.0f 秒 · 亮屏 %.0f 秒 · 整机掉电 %.1f%%", t, c, f, s, dc));
                        });
                    }
                }
            });

        });
        h.button(root, "查询系统耗电账单", () -> {
            h.post("/api/energy/system", new JSONObject(), r -> {
                if (r == null || !r.optBoolean("ok")) {
                    h.notice("系统耗电统计失败: " + (r != null ? r.optString("message", "无响应") : "网络异常"));
                    return;
                }
                JSONObject sys = r.optJSONObject("sys");
                if (sys == null) return;
                JSONObject me = sys.optJSONObject("me");
                StringBuilder s = new StringBuilder("\n【系统耗电账单】");
                if (me != null) {
                    s.append(String.format(Locale.ROOT, "\n本应用: %.1fmAh (排名: %d/%d, 屏幕: %.1fmAh)",
                            me.optDouble("mah", 0), me.optInt("rank", 0), sys.optInt("apps", 0), me.optDouble("screen", 0)));
                } else {
                    s.append("\n本应用尚无耗电记录");
                }
                s.append(String.format(Locale.ROOT, "\n整机已用: %.1fmAh (统计起始: %d秒前)",
                        sys.optDouble("used", 0), sys.optLong("sinceMs", 0) / 1000));
                JSONArray top = sys.optJSONArray("top");
                if (top != null && top.length() > 0) {
                    s.append("\n耗电前列: ");
                    for (int j = 0; j < Math.min(3, top.length()); j++) {
                        JSONObject o = top.optJSONObject(j);
                        if (o != null) s.append(o.optString("name", "应用")).append(" ")
                                       .append(String.format(Locale.ROOT, "%.1fmAh", o.optDouble("mah", 0))).append("; ");
                    }
                }
                ev.append(s.toString());
            });
        });
    }
}
