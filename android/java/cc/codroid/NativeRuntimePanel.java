package cc.codroid;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class NativeRuntimePanel {
    static void dialog(NativeHome h, String title, CharSequence msg) {
        TextView tv = new TextView(h.a);
        tv.setText(msg);
        tv.setTextIsSelectable(true);
        int p = h.dp(16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(h.a);
        sv.addView(tv);
        new AlertDialog.Builder(h.a).setTitle(title).setView(sv).setPositiveButton("关闭", null).show();
    }

    static LinearLayout row(NativeHome h, LinearLayout parent) {
        LinearLayout r = new LinearLayout(h.a);
        r.setOrientation(LinearLayout.VERTICAL);
        parent.addView(r);
        return r;
    }

    static void appendList(StringBuilder sb, String label, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        sb.append(label).append(" (").append(arr.length()).append("):\n");
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof JSONObject) {
                JSONObject jo = (JSONObject) o;
                sb.append(" • ").append(jo.optString("name", jo.optString("id", o.toString())));
                String d = jo.optString("desc", jo.optString("description", ""));
                if (!d.isEmpty()) sb.append(" - ").append(d);
            } else {
                sb.append(" • ").append(o);
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    public static void show(NativeHome h, String id) {
        LinearLayout root = h.scrolling();
        int pad = h.dp(16);
        root.setPadding(pad, pad, pad, h.dp(32));


        if ("usage".equals(id)) {
            NativeEnergyPanel.add(h, root);
            h.text(root, "Claude Code 本地用量");
            TextView tvUsage = h.text(root, "正在读取用量...");

            final JSONObject[] usHolder = new JSONObject[1];
            LinearLayout uRow1 = row(h, root);
            h.button(uRow1, "时段明细", () -> {
                JSONObject us = usHolder[0];
                JSONObject hours = us != null ? us.optJSONObject("hours") : null;
                if (hours == null || hours.length() == 0) { h.notice("暂无时段用量记录"); return; }
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = hours.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (key.matches("[0-9]{1,10}")) keys.add(key);
                }
                Collections.sort(keys, (a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));
                StringBuilder sb = new StringBuilder("按小时段明细（最新在前）：\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:00", Locale.getDefault());
                for (String k : keys) {
                    JSONObject b = hours.optJSONObject(k);
                    if (b == null) continue;
                    long v = Long.parseLong(k);
                    long ms = v > 10000000000L ? v : v * 3600000L;
                    sb.append("\n• ").append(sdf.format(new Date(ms))).append("\n")
                      .append("  输入: ").append(b.optLong("i")).append(" | 输出: ").append(b.optLong("o"))
                      .append(" | 缓存: ").append(b.optLong("c")).append(" | 次数: ").append(b.optLong("t"));
                }
                dialog(h, "时段用量明细", sb.toString());
            });

            h.button(uRow1, "导入记录", () -> {
                new AlertDialog.Builder(h.a).setTitle("导入历史用量")
                    .setMessage("将从历史会话补充统计本地 Token 用量，是否继续？")
                    .setPositiveButton("确定", (d, w) -> h.post("/api/usage/import", new JSONObject(), r -> {
                        h.notice("历史用量导入完成");
                        h.nav(id);
                    })).setNegativeButton("取消", null).show();
            });

            h.button(uRow1, "开关统计", () -> {
                JSONObject us = usHolder[0];
                if (us == null) { h.notice("请等待读取统计状态"); return; }
                boolean off = us.optBoolean("off");
                new AlertDialog.Builder(h.a).setTitle("用量统计开关")
                    .setMessage("当前状态：" + (off ? "已暂停" : "正常记录") + "。\n确认" + (off ? "启用" : "暂停") + "用量统计？")
                    .setPositiveButton("确定", (d, w) -> h.post("/api/usage/set", NativeHome.json("off", !off), r -> {
                        h.notice("统计设置已变更");
                        h.nav(id);
                    })).setNegativeButton("取消", null).show();
            });

            LinearLayout uRow2 = row(h, root);
            h.button(uRow2, "事件账本", () -> h.get("/api/ledger?n=60", r -> {
                JSONArray items = r.optJSONArray("items");
                if (items == null || items.length() == 0) { h.notice("账本中暂无记录"); return; }
                StringBuilder sb = new StringBuilder("说明：运行事件与审计日志，非订阅扣费账单。\n\n");
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    sb.append("[").append(sdf.format(new Date(item.optLong("at")))).append("] ")
                      .append(item.optBoolean("ok", true) ? "✓ " : "✗ ")
                      .append(item.optString("kind")).append("\n")
                      .append(item.optString("msg")).append("\n");
                    sb.append("\n");
                }
                dialog(h, "事件账本 (Ledger)", sb.toString());
            }));

            h.button(uRow2, "刷新", () -> h.nav(id));

            h.get("/api/usage", us -> {
                usHolder[0] = us;
                JSONObject hours = us.optJSONObject("hours");
                long inTok = 0, outTok = 0, cacheTok = 0, turns = 0;
                int count = 0;
                if (hours != null) {
                    for (Iterator<String> it = hours.keys(); it.hasNext(); ) {
                        JSONObject b = hours.optJSONObject(it.next());
                        if (b != null) {
                            inTok += b.optLong("i");
                            outTok += b.optLong("o");
                            cacheTok += b.optLong("c");
                            turns += b.optLong("t");
                            count++;
                        }
                    }
                }
                tvUsage.setText("统计状态: " + (us.optBoolean("off") ? "已暂停" : "正常记录") + "\n"
                    + "输入 Tokens: " + inTok + "\n"
                    + "输出 Tokens: " + outTok + "\n"
                    + "缓存 Tokens: " + cacheTok + "\n"
                    + "累计轮次: " + turns + " | 统计时段: " + count + " 个");
            });

            return;
        }
        TextView tvStatus = h.text(root, "正在读取状态…");
        h.text(root, "\n【能力与扩展】");
        TextView tvCaps = h.text(root, "正在读取系统能力...");
        final JSONObject[] cpHolder = new JSONObject[1];

        LinearLayout cRow = row(h, root);
        h.button(cRow, "能力详情", () -> {
            JSONObject cp = cpHolder[0];
            if (cp == null) { h.notice("能力清单尚未加载完成"); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("模型: ").append(cp.optString("model", "默认")).append("\n");
            sb.append("权限模式: ").append(cp.optString("permissionMode", "默认")).append("\n");
            sb.append("输出风格: ").append(cp.optString("outputStyle", "default")).append("\n");
            sb.append("版本: ").append(cp.optString("version", "未知")).append("\n\n");
            appendList(sb, "可用工具", cp.optJSONArray("tools"));
            appendList(sb, "MCP 服务器", cp.optJSONArray("mcpServers"));
            appendList(sb, "技能组件", cp.optJSONArray("skills"));
            appendList(sb, "插件列表", cp.optJSONArray("plugins"));
            appendList(sb, "预设智能体", cp.optJSONArray("agents"));
            appendList(sb, "斜杠指令", cp.optJSONArray("slashCommands"));
            dialog(h, "系统能力详情", sb.toString());
        });

        h.button(cRow, "CLI 诊断", () -> {
            String[] allow = {"doctor", "mcp list", "agents", "auth status", "plugin list", "plugins", "auto-mode"};
            new AlertDialog.Builder(h.a).setTitle("CLI 运行诊断").setItems(allow, (d, which) -> {
                String cmd = allow[which];
                h.get("/api/cli?cmd=" + Uri.encode(cmd), res -> {
                    String out = res.optString("out");
                    String err = res.optString("err");
                    String full = (out.isEmpty() ? "" : out) + (err.isEmpty() ? "" : "\n[错误]\n" + err);
                    dialog(h, "诊断结果: " + cmd, full.isEmpty() ? "命令执行完毕，无输出。" : full);
                });
            }).setNegativeButton("取消", null).show();
        });



        h.get("/api/status", st -> {
            JSONObject auth = st.optJSONObject("auth");
            String authStr = auth == null ? ("key".equals(st.optString("keyMode")) ? "已配置密钥" : "待确认")
                : (auth.optBoolean("loggedIn") ? "已登录" : (auth.optBoolean("unknown") ? "状态未明" : "未登录"));
            tvStatus.setText("服务产品: " + st.optString("product", "Claude CLI") + " (v" + st.optString("version", "") + ")\n"
                + "引擎模型: " + st.optString("model", "未指定") + " (" + st.optString("providerLabel", st.optString("provider", "-")) + ")\n"
                + "认证状态: " + authStr + " | 桥接状态: " + (st.optBoolean("bridge") ? "在线" : "离线") + "\n"
                + "工作目录: " + st.optString("workspace", "-") + "\n"
                + "运行队列: 活跃 " + st.optInt("running") + " / 排队 " + st.optInt("queued") + " / 挂起 " + st.optInt("held")
                + (st.optBoolean("busy") ? " [忙碌]" : " [空闲]"));
        });

        h.get("/api/capabilities", cp -> {
            cpHolder[0] = cp;
            JSONArray tools = cp.optJSONArray("tools");
            JSONArray sc = cp.optJSONArray("slashCommands");
            JSONArray mcp = cp.optJSONArray("mcpServers");
            JSONArray sk = cp.optJSONArray("skills");
            tvCaps.setText("Claude Code: " + cp.optString("version", "尚无会话记录") + "\n"
                + "工具数量: " + (tools != null ? tools.length() : 0) + " 项 | "
                + "指令数量: " + (sc != null ? sc.length() : 0) + " 个\n"
                + "MCP 服务: " + (mcp != null ? mcp.length() : 0) + " 个 | "
                + "技能组件: " + (sk != null ? sk.length() : 0) + " 项");
        });

    }
}
