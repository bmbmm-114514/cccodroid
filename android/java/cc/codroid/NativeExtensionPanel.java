package cc.codroid;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeExtensionPanel {
    private int reqTicket;

    public static void show(NativeHome h) { new NativeExtensionPanel().render(h); }

    private void render(NativeHome h) {
        final int gen = h.generation;
        h.body.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(h.a);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(h.dp(12), h.dp(8), h.dp(12), h.dp(8));
        h.body.addView(topBar);

        TextView title = h.text(topBar, "扩展类型: ");
        title.setTextSize(15);

        Spinner spinner = new Spinner(h.a);
        spinner.setAdapter(new ArrayAdapter<>(h.a,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"插件管理", "MCP 服务", "技能扩展", "可选组件", "扩展市场", "Hooks"}));
        topBar.addView(spinner);

        LinearLayout scroll = h.scrolling();

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (h.generation != gen) return;
                scroll.removeAllViews();
                int ticket = ++reqTicket;
                if (pos == 0) renderPlugins(h, scroll, gen, ticket);
                else if (pos == 1) renderMcp(h, scroll, gen, ticket);
                else if (pos == 2) renderSkills(h, scroll, gen, ticket);
                else if (pos == 3) renderComponents(h, scroll, gen, ticket);
                else {
                    LinearLayout page = new LinearLayout(h.a);
                    page.setOrientation(LinearLayout.VERTICAL);
                    scroll.addView(page);
                    if (pos == 4) NativeMarketplacePanel.show(h, page);
                    else NativeHooksPanel.show(h, page);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private LinearLayout card(NativeHome h, LinearLayout parent) {
        LinearLayout c = new LinearLayout(h.a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(h.dp(12), h.dp(8), h.dp(12), h.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, h.dp(6));
        c.setLayoutParams(lp);
        parent.addView(c);
        return c;
    }

    private LinearLayout row(NativeHome h, LinearLayout parent) {
        LinearLayout r = new LinearLayout(h.a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r);
        return r;
    }

    private void confirm(NativeHome h, String title, String msg, Runnable ok) {
        new AlertDialog.Builder(h.a)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定", (d, w) -> ok.run())
            .setNegativeButton("取消", null)
            .show();
    }

    // --- 插件 (Plugins) ---
    private void renderPlugins(NativeHome h, LinearLayout root, int gen, int ticket) {
        LinearLayout bar = row(h, root);
        bar.setPadding(h.dp(10), h.dp(4), h.dp(10), h.dp(4));
        EditText qIn = new EditText(h.a);
        qIn.setHint("搜索可用插件...");
        qIn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(qIn);

        LinearLayout list = new LinearLayout(h.a);
        list.setOrientation(LinearLayout.VERTICAL);

        h.button(bar, "搜索", () -> {
            String q = qIn.getText().toString().trim();
            loadPlugins(h, list, gen, ++reqTicket, q);
        });
        h.button(bar, "已安装", () -> {
            qIn.setText("");
            if (list.isAttachedToWindow()) loadPlugins(h, list, gen, ++reqTicket, null);
        });

        root.addView(list);
        loadPlugins(h, list, gen, ticket, null);
    }

    private void loadPlugins(NativeHome h, LinearLayout list, int gen, int ticket, String q) {
        boolean search = q != null;
        String path = search ? "/api/ext/plugins?available=1&q=" + Uri.encode(q) : "/api/ext/plugins";
        h.get(path, res -> {
            if (ticket != reqTicket || h.generation != gen) return;
            list.removeAllViews();
            if (!res.optBoolean("ok", true)) { h.text(list, res.optString("message", "读取失败")); return; }
            if (search) {
                JSONArray avail = res.optJSONArray("available");
                if (avail == null || avail.length() == 0) {
                    h.text(list, "未找到匹配插件");
                    return;
                }
                int n = Math.min(avail.length(), 40);
                for (int i = 0; i < n; i++) {
                    JSONObject p = avail.optJSONObject(i);
                    if (p == null) continue;
                    String id = p.optString("id");
                    String name = p.optString("name", id);
                    String desc = p.optString("description", "");
                    String mkt = p.optString("marketplace", "");
                    LinearLayout c = card(h, list);
                    h.text(c, name + (mkt.isEmpty() ? "" : " · 来源: " + mkt));
                    if (!desc.isEmpty()) h.text(c, desc);
                    LinearLayout acts = row(h, c);
                    h.button(acts, "安装", () -> confirm(h, "安装确认",
                        "确定从市场 [" + mkt + "] 安装插件 " + name + " 吗？",
                        () -> installPlugin(h, list, gen, id, null)));
                }
            } else {
                JSONArray inst = res.optJSONArray("installed");
                if (inst == null || inst.length() == 0) {
                    h.text(list, "暂无已安装插件");
                    return;
                }
                for (int i = 0; i < inst.length(); i++) {
                    JSONObject p = inst.optJSONObject(i);
                    if (p == null) continue;
                    String id = p.optString("id");
                    boolean on = p.optBoolean("enabled", true);
                    String ver = p.optString("version", "");
                    LinearLayout c = card(h, list);
                    h.text(c, id + (ver.isEmpty() ? "" : " (v" + ver + ")"));
                    h.text(c, "状态: " + (on ? "已启用" : "已停用"));
                    LinearLayout acts = row(h, c);
                    if (on) {
                        h.button(acts, "停用", () -> h.post("/api/ext/plugins/disable", NativeHome.json("id", id), r -> {
                            if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                            h.notice("已停用");
                            if (list.isAttachedToWindow()) loadPlugins(h, list, gen, ++reqTicket, null);
                        }));
                    } else {
                        h.button(acts, "启用", () -> h.post("/api/ext/plugins/enable", NativeHome.json("id", id), r -> {
                            if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                            h.notice("已启用");
                            if (list.isAttachedToWindow()) loadPlugins(h, list, gen, ++reqTicket, null);
                        }));
                    }
                    h.button(acts, "卸载", () -> confirm(h, "卸载插件", "确定卸载插件 " + id + " 吗？", () ->
                        h.post("/api/ext/plugins/uninstall", NativeHome.json("id", id), r -> {
                            if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                            h.notice("已卸载");
                            if (list.isAttachedToWindow()) loadPlugins(h, list, gen, ++reqTicket, null);
                        })));
                }
            }
        });
    }

    private void installPlugin(NativeHome h, LinearLayout list, int gen, String id, String accept) {
        JSONObject b = accept != null ? NativeHome.json("id", id, "accept", accept) : NativeHome.json("id", id);
        h.post("/api/ext/plugins/install", b, res -> {
            if (h.generation != gen) return;
            if (res.optBoolean("needConfirm")) {
                String cmd = res.optString("command", "");
                String sha = res.optString("sha256", "");
                confirm(h, "执行确认", "安装需要执行以下命令：\n\n" + cmd + "\n\n是否确认执行并安装？",
                    () -> installPlugin(h, list, gen, id, sha));
            } else if (res.optBoolean("ok")) {
                h.notice("插件安装成功");
                if (list.isAttachedToWindow()) loadPlugins(h, list, gen, ++reqTicket, null);
            } else {
                h.notice(res.optString("message", "安装失败"));
            }
        });
    }

    // --- MCP 服务 ---
    private void renderMcp(NativeHome h, LinearLayout root, int gen, int ticket) {
        LinearLayout bar = row(h, root);
        bar.setPadding(h.dp(10), h.dp(4), h.dp(10), h.dp(4));
        LinearLayout list = new LinearLayout(h.a);
        list.setOrientation(LinearLayout.VERTICAL);

        h.button(bar, "添加 MCP 服务", () -> NativeMcpEditorPanel.show(h, null, () -> { if (list.isAttachedToWindow()) loadMcp(h, list, gen, ++reqTicket); }));
        h.button(bar, "刷新", () -> loadMcp(h, list, gen, ++reqTicket));

        root.addView(list);
        loadMcp(h, list, gen, ticket);
    }

    private void loadMcp(NativeHome h, LinearLayout list, int gen, int ticket) {
        h.get("/api/ext/mcp", res -> {
            if (ticket != reqTicket || h.generation != gen) return;
            list.removeAllViews();
            if (!res.optBoolean("ok", true)) { h.text(list, res.optString("message", "读取失败")); return; }
            JSONArray arr = res.optJSONArray("items");
            if (arr == null || arr.length() == 0) {
                h.text(list, "暂无 MCP 服务");
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name");
                boolean ok = m.optBoolean("ok");
                boolean dis = m.optBoolean("disabled");
                String st = m.optString("status");

                LinearLayout c = card(h, list);
                h.text(c, name);
                h.text(c, "状态: " + (dis ? "已停用" : (ok ? "✔ 已连接" : "未连接 " + st)));


                LinearLayout acts = row(h, c);
                if (!dis && !m.optBoolean("user")) continue;
                h.button(acts, "编辑配置与凭据", () -> NativeMcpEditorPanel.show(h, name, () -> { if (list.isAttachedToWindow()) loadMcp(h, list, gen, ++reqTicket); }));
                if (dis) {
                    h.button(acts, "启用", () -> h.post("/api/ext/mcp/enable", NativeHome.json("name", name), r -> {
                        if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                        h.notice("已启用");
                        if (list.isAttachedToWindow()) loadMcp(h, list, gen, ++reqTicket);
                    }));
                } else {
                    h.button(acts, "停用", () -> h.post("/api/ext/mcp/disable", NativeHome.json("name", name), r -> {
                        if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                        h.notice("已停用");
                        if (list.isAttachedToWindow()) loadMcp(h, list, gen, ++reqTicket);
                    }));
                }
                h.button(acts, "删除", () -> confirm(h, "删除 MCP", "确定移除 MCP 服务 " + name + " 吗？", () ->
                    h.post(dis ? "/api/ext/mcp/forget" : "/api/ext/mcp/remove", NativeHome.json("name", name), r -> {
                        if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                        h.notice("已删除");
                        if (list.isAttachedToWindow()) loadMcp(h, list, gen, ++reqTicket);
                    })));
            }
        });
    }

    private void renderSkills(NativeHome h, LinearLayout root, int gen, int ticket) {
        LinearLayout bar = row(h, root);
        bar.setPadding(h.dp(10), h.dp(4), h.dp(10), h.dp(4));
        LinearLayout list = new LinearLayout(h.a);
        list.setOrientation(LinearLayout.VERTICAL);

        h.button(bar, "刷新技能", () -> loadSkills(h, list, gen, ++reqTicket));
        root.addView(list);
        loadSkills(h, list, gen, ticket);
    }

    private void loadSkills(NativeHome h, LinearLayout list, int gen, int ticket) {
        h.get("/api/ext/skills", res -> {
            if (ticket != reqTicket || h.generation != gen) return;
            list.removeAllViews();
            if (!res.optBoolean("ok", true)) { h.text(list, res.optString("message", "读取失败")); return; }
            JSONArray arr = res.optJSONArray("items");
            if (arr == null || arr.length() == 0) {
                h.text(list, "暂无已安装技能");
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("name", s.optString("id", "未命名技能"));
                String dir = s.optString("dir", s.optString("id", ""));
                String desc = s.optString("description", "");
                String mode = s.optString("mode", "on");

                LinearLayout c = card(h, list);
                h.text(c, name);
                if (!desc.isEmpty()) h.text(c, desc);
                h.text(c, "运行模式: " + mode);

                LinearLayout acts = row(h, c);
                String next = "off".equals(mode) ? "on" : "off";
                String label = "off".equals(mode) ? "启用" : "停用";
                h.button(acts, label, () -> h.post("/api/ext/skills/mode", NativeHome.json("dir", dir, "mode", next), r -> {
                    if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                    h.notice("模式已切换");
                    if (list.isAttachedToWindow()) loadSkills(h, list, gen, ++reqTicket);
                }));

                h.button(acts, "删除", () -> confirm(h, "删除技能", "确定删除技能 " + name + " 吗？", () ->
                    h.post("/api/ext/skills/delete", NativeHome.json("dir", dir), r -> {
                        if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                        h.notice("技能已删除");
                        if (list.isAttachedToWindow()) loadSkills(h, list, gen, ++reqTicket);
                    })));
            }
        });
    }

    // --- 可选组件 (Components) ---
    private void renderComponents(NativeHome h, LinearLayout root, int gen, int ticket) {
        LinearLayout bar = row(h, root);
        bar.setPadding(h.dp(10), h.dp(4), h.dp(10), h.dp(4));
        LinearLayout list = new LinearLayout(h.a);
        list.setOrientation(LinearLayout.VERTICAL);

        h.button(bar, "刷新组件", () -> loadComponents(h, list, gen, ++reqTicket));
        root.addView(list);
        loadComponents(h, list, gen, ticket);
    }

    private void loadComponents(NativeHome h, LinearLayout list, int gen, int ticket) {
        h.get("/api/components", res -> {
            if (ticket != reqTicket || h.generation != gen) return;
            list.removeAllViews();
            if (!res.optBoolean("ok", true)) { h.text(list, res.optString("message", "读取失败")); return; }
            JSONArray arr = res.optJSONArray("items");
            if (arr == null || arr.length() == 0) {
                h.text(list, res.optBoolean("supported", true) ? res.optString("indexError", "暂无可用组件") : "此环境不支持组件");
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("id"), state = c.optString("state");
                JSONObject names = c.optJSONObject("name"), descriptions = c.optJSONObject("desc");
                String name = names == null ? id : names.optString("zh", id);
                String desc = descriptions == null ? "" : descriptions.optString("zh", "");
                boolean installed = !c.isNull("installedVersion") || "ok".equals(state);
                if (c.optJSONObject("progress") != null || (!installed && !c.optBoolean("available"))) {
                    h.text(list, name + " · " + state);
                    continue;
                }
                renderCompCard(h, list, gen, id, name, desc, installed);
            }
        });
    }

    private void renderCompCard(NativeHome h, LinearLayout list, int gen, String id, String name, String desc, boolean installed) {
        LinearLayout c = card(h, list);
        h.text(c, name.isEmpty() ? id : name);
        if (!desc.isEmpty()) h.text(c, desc);
        h.text(c, "状态: " + (installed ? "已安装" : "未安装"));
        LinearLayout acts = row(h, c);
        if (installed) {
            h.button(acts, "卸载", () -> confirm(h, "卸载组件", "确定卸载可选组件 " + name + " 吗？", () ->
                h.post("/api/components/remove", NativeHome.json("id", id), r -> {
                    if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                    h.notice("已卸载");
                    if (list.isAttachedToWindow()) loadComponents(h, list, gen, ++reqTicket);
                })));
        } else {
            h.button(acts, "安装", () -> confirm(h, "安装组件", "确定安装可选组件 " + name + " 吗？", () ->
                h.post("/api/components/install", NativeHome.json("id", id), r -> {
                    if (!r.optBoolean("ok")) { h.notice(r.optString("message", "操作失败")); return; }
                    h.notice("已安装");
                    if (list.isAttachedToWindow()) loadComponents(h, list, gen, ++reqTicket);
                })));
        }
    }
}
