package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
        new MaterialAlertDialogBuilder(h.a).setTitle(title).setView(sv).setPositiveButton("关闭", null).show();
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
        LinearLayout page = h.scrolling();
        if ("usage".equals(id)) {
            NativeEnergyPanel.addQuota(h, page);
            NativeLocalUsage.add(h, page);
            NativeEnergyPanel.add(h, page);
            h.headerAction("refresh", "刷新用量", () -> h.nav(id));
            return;
        }
        LinearLayout root = PixelUi.group(page);
        int pad = h.dp(16);
        root.setPadding(pad, pad, pad, h.dp(32));
        TextView tvStatus = h.text(root, "正在读取状态…");
        h.text(root, "能力与扩展");
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
            PixelChoiceSheet.actions(h.a, "CLI 运行诊断", java.util.Arrays.asList(allow), which -> {
                String cmd = allow[which];
                h.get("/api/cli?cmd=" + Uri.encode(cmd), res -> {
                    String out = res.optString("out");
                    String err = res.optString("err");
                    String full = (out.isEmpty() ? "" : out) + (err.isEmpty() ? "" : "\n[错误]\n" + err);
                    dialog(h, "诊断结果: " + cmd, full.isEmpty() ? "命令执行完毕，无输出。" : full);
                });
            });
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
