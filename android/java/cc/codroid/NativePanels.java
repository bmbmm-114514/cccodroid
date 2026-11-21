package cc.codroid;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public final class NativePanels {

    private NativePanels() {}

    public static void show(NativeHome h, String id) {
        switch (id) {
            case "help":
            case "changelog":
                NativeReferencePanel.show(h, id);
                return;
            case "ext":
                NativeExtensionPanel.show(h);
                return;
            case "sched":
                NativeSchedulePanel.show(h);
                return;
            case "usage":
            case "caps":
                NativeRuntimePanel.show(h, id);
                return;
        }
        if (id.startsWith("ext-")) { NativeExtensionPanel.show(h); return; }
        if ("set-workspace".equals(id)) { showWorkspaceSettings(h, h.scrolling()); return; }
        if (id.startsWith("set-")) { NativeSettingsPanel.show(h, id); return; }
        LinearLayout content = h.scrolling();
        if (id.startsWith("project_detail:")) showProjectDetail(h, content, id.substring("project_detail:".length()));
        else if ("projects".equals(id)) showProjects(h, content);
        else if ("lineage".equals(id)) showLineage(h, content);
        else if ("bookmarks".equals(id)) showBookmarks(h, content);
        else if ("settings".equals(id)) showSettings(h, content);
    }

    private static void showProjects(NativeHome h, LinearLayout c) {
        h.headerAction("refresh", "刷新项目列表", () -> h.nav("projects"));
        PixelUi.row(PixelUi.group(c), "add", "添加项目", "创建文件夹、打开已有目录或克隆 Git 仓库", () ->
                new AlertDialog.Builder(h.a).setTitle("添加项目")
                        .setItems(new String[]{"新建空目录", "添加已有目录", "克隆 Git 项目"}, (dialog, index) ->
                                createProjectDialog(h, new String[]{"new", "dir", "git"}[index]))
                        .setNegativeButton("取消", null).show());

        TextView loading = h.text(c, "正在读取项目列表...");
        loading.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));

        h.get("/api/projects", res -> {
            c.removeView(loading);
            JSONArray arr = res.optJSONArray("projects");
            if (arr == null) {
                arr = res.optJSONArray("items");
            }
            if (arr == null || arr.length() == 0) {
                TextView empty = h.text(c, "还没有项目");
                empty.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                empty.setPadding(h.dp(16), h.dp(24), h.dp(16), h.dp(24));
                return;
            }

            LinearLayout group = PixelUi.group(c);
            paged(h, group, arr, p -> {
                String pid = p.optString("id");
                String path = p.optString("path", "").trim();
                String name = p.optString("name", "").trim();
                if (name.isEmpty() || "未命名项目".equals(name)) {
                    if (!path.isEmpty()) {
                        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                        name = (idx >= 0 && idx < path.length() - 1) ? path.substring(idx + 1) : path;
                    }
                }
                if (name.isEmpty()) {
                    name = "未命名项目";
                }

                boolean isActive = pid.equals(h.projectId);
                String supporting;
                if (isActive) {
                    supporting = path.isEmpty() ? "当前项目" : ("当前项目 · " + path);
                } else {
                    supporting = path;
                }

                LinearLayout row = PixelUi.row(group, "projects", name, supporting, null);
                row.setBackground(PixelUi.clickable(h.a, null, 12));
                row.setFocusable(true);
                row.setOnClickListener(v -> h.nav("project_detail:" + pid));

                final String finalName = name;

                ImageButton moreBtn = PixelUi.icon(h.a, "more", name + "的项目操作", null);
                moreBtn.setOnClickListener(v -> showProjectItemMenu(h, v, pid, finalName, isActive));

                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                btnLp.gravity = Gravity.CENTER_VERTICAL;
                row.addView(moreBtn, btnLp);
            });
        });
    }

    private static void showProjectItemMenu(NativeHome h, View anchor, String pid, String name, boolean isActive) {
        PopupMenu popup = new PopupMenu(h.a, anchor);
        popup.setGravity(Gravity.END);

        popup.getMenu().add(Menu.NONE, 1, 2, isActive ? "取消选择当前项目" : "设为当前项目");
        popup.getMenu().add(Menu.NONE, 2, 3, "在此项目新建对话");
        popup.getMenu().add(Menu.NONE, 3, 4, "删除项目");

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                h.projectId = isActive ? "" : pid;
                h.notice(isActive ? "已取消选择项目" : "已选择项目 " + name);
                h.nav("projects");
                return true;
            } else if (id == 2) {
                h.projectId = pid;
                h.chatId = "";
                h.nav("chat");
                return true;
            } else if (id == 3) {
                confirmDeleteProject(h, pid, name);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private static void confirmDeleteProject(NativeHome h, String pid, String name) {
        AlertDialog.Builder b = new AlertDialog.Builder(h.a);
        b.setTitle("删除项目「" + name + "」");
        b.setMessage("请选择删除模式：仅移除项目配置记录，或彻底删除磁盘对应目录及其中所有文件。");
        b.setPositiveButton("彻底删除磁盘目录", (d, w) -> {
            confirmDialog(h.a, "高风险操作确认", "此操作不可恢复！将彻底递归删除目录及其会话文件。确定执行吗？", () -> {
                h.req("DELETE", "/api/projects/" + enc(pid) + "?withDir=1", null, res -> {
                    h.notice("项目及磁盘目录已彻底删除");
                    if (pid.equals(h.projectId)) h.projectId = "";
                    h.nav("projects");
                });
            });
        });
        b.setNeutralButton("仅移除配置", (d, w) -> {
            h.req("DELETE", "/api/projects/" + enc(pid), null, res -> {
                h.notice("项目已从列表中移除");
                if (pid.equals(h.projectId)) h.projectId = "";
                h.nav("projects");
            });
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private static void createProjectDialog(NativeHome h, String mode) {
        boolean isGit = "git".equals(mode);
        boolean existing = "dir".equals(mode);
        LinearLayout l = new LinearLayout(h.a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(h.dp(20), h.dp(10), h.dp(20), h.dp(10));

        TextView tip = new TextView(h.a);
        tip.setText(isGit ? "输入项目名称与远程 Git 仓库地址 (https:// 或 git@)：" : existing ? "选择已有项目的名称和目录" : "输入项目名称与可选的初始指令");
        setSecondaryStyle(tip, 13f);
        l.addView(tip);

        EditText etName = new EditText(h.a);
        etName.setHint("项目名称 (必填)");
        l.addView(etName);

        EditText etExtra = new EditText(h.a);
        if (isGit) {
            etExtra.setHint("Git 地址 (https://... / git@...)");
            etExtra.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        } else if (existing) {
            etExtra.setSingleLine(true);
            etExtra.setHint("项目目录的绝对路径");
        } else {
            etExtra.setHint("项目初始指令 (写入 CLAUDE.md，可选)");
            etExtra.setMinLines(2);
        }
        l.addView(etExtra);

        new AlertDialog.Builder(h.a)
                .setTitle(isGit ? "克隆远程 Git 项目" : existing ? "添加已有目录" : "新建本地项目目录")
                .setView(l)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String extra = etExtra.getText().toString().trim();
                    if (name.isEmpty() || ((isGit || existing) && extra.isEmpty())) {
                        h.notice("请填好项目名称和路径或仓库地址");
                        return;
                    }
                    JSONObject b = NativeHome.json(
                            "name", name,
                            "mode", mode,
                            isGit ? "url" : existing ? "path" : "instr", extra
                    );
                    h.post("/api/projects", b, res -> {
                        h.notice("项目「" + name + "」创建成功");
                        h.nav("projects");
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showProjectDetail(NativeHome h, LinearLayout c, String pid) {
        TextView loading = h.text(c, "正在读取项目详情与指令配置...");

        h.get("/api/projects/" + enc(pid), res -> {
            c.removeView(loading);
            JSONObject proj = res.optJSONObject("project");
            if (proj == null) {
                h.text(c, "未找到该项目详情。");
                return;
            }

            String name = proj.optString("name", "未命名项目");
            String path = proj.optString("path", "");
            String model = proj.optString("model", "");
            String provider = proj.optString("provider", "");
            String perm = proj.optString("perm", "");
            String effort = proj.optString("effort", "");

            LinearLayout propCard = makeCard(h, c);
            TextView pTitle = new TextView(h.a);
            pTitle.setText("属性设置: " + name);
            setTitleStyle(pTitle, 16f);
            propCard.addView(pTitle);

            TextView pathTv = new TextView(h.a);
            pathTv.setText("物理目录: " + path);
            setSecondaryStyle(pathTv, 12f);
            propCard.addView(pathTv);

            EditText etName = makeLabeledInput(h, propCard, "项目名称", name);
            EditText etModel = makeLabeledInput(h, propCard, "绑定模型 (留空继承全局)", model);
            EditText etProvider = makeLabeledInput(h, propCard, "绑定提供商 (留空继承全局)", provider);
            EditText etPerm = makeLabeledInput(h, propCard, "执行权限级别", perm);
            EditText etEffort = makeLabeledInput(h, propCard, "思考预算 Effort", effort);

            LinearLayout savePropRow = makeActionRow(h);
            propCard.addView(savePropRow);
            h.button(savePropRow, "保存项目属性变更", () -> {
                JSONObject b = NativeHome.json(
                        "name", etName.getText().toString().trim(),
                        "model", etModel.getText().toString().trim(),
                        "provider", etProvider.getText().toString().trim(),
                        "perm", etPerm.getText().toString().trim(),
                        "effort", etEffort.getText().toString().trim()
                );
                h.req("PATCH", "/api/projects/" + enc(pid), b, okRes -> {
                    h.notice("项目属性更新成功");
                    h.nav("project_detail:" + pid);
                });
            });

            LinearLayout instrCard = makeCard(h, c);
            TextView instrTitle = new TextView(h.a);
            instrTitle.setText("项目专属开发指令 (CLAUDE.md)");
            setTitleStyle(instrTitle, 16f);
            instrCard.addView(instrTitle);

            TextView instrTip = new TextView(h.a);
            instrTip.setText("该指令将作为本工作区会话的上下文规范，直接安全映射至项目根目录 CLAUDE.md：");
            setSecondaryStyle(instrTip, 12f);
            instrCard.addView(instrTip);

            EditText etInstr = new EditText(h.a);
            etInstr.setHint("输入项目指令规范...");
            etInstr.setMinLines(5);
            etInstr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            instrCard.addView(etInstr);

            etInstr.setEnabled(false);
            h.get("/api/projects/" + enc(pid) + "/instr", instrRes -> {
                etInstr.setText(instrRes.optString("text", ""));
                etInstr.setEnabled(true);

                LinearLayout instrBtnRow = makeActionRow(h);
                instrCard.addView(instrBtnRow);
                h.button(instrBtnRow, "保存 CLAUDE.md 指令", () -> {
                    String newText = etInstr.getText().toString();
                    h.req("PUT", "/api/projects/" + enc(pid) + "/instr", NativeHome.json("text", newText), r -> {
                        h.notice("项目指令已保存");
                    });
                });

                h.button(instrBtnRow, "清空指令", () -> {
                    confirmDialog(h.a, "清空指令确认", "确定删除该项目的 CLAUDE.md 规范吗？", () -> {
                        h.req("PUT", "/api/projects/" + enc(pid) + "/instr", NativeHome.json("text", ""), r -> {
                            etInstr.setText("");
                            h.notice("指令已清空");
                        });
                    });
                });

            });

            JSONArray sessions = res.optJSONArray("sessions");
            if (sessions != null && sessions.length() > 0) {
                LinearLayout sessCard = makeCard(h, c);
                TextView sTitle = new TextView(h.a);
                sTitle.setText("关联会话历史 (" + sessions.length() + ")");
                setTitleStyle(sTitle, 15f);
                sessCard.addView(sTitle);

                paged(h, sessCard, sessions, s -> {
                    String sid = s.optString("id");
                    String title = s.optString("title", "会话 " + sid);
                    long mtime = s.optLong("mtime", 0);

                    TextView tvS = new TextView(h.a);
                    tvS.setText("• " + title + (mtime > 0 ? (" (" + formatDate(mtime) + ")") : ""));
                    tvS.setPadding(0, h.dp(4), 0, h.dp(4));
                    setSelectable(tvS);
                    tvS.setOnClickListener(v -> h.openSession(sid));
                    sessCard.addView(tvS);
                });
            }
        });
    }

    private static void showLineage(NativeHome h, LinearLayout c) {
        LinearLayout bar = makeActionRow(h);
        c.addView(bar);
        h.button(bar, "重新构建谱系分析", () -> h.nav("lineage"));

        TextView loading = h.text(c, "正在分析会话演化与分支谱系关系...");

        h.get("/api/lineage", res -> {
            c.removeView(loading);

            if (res.optBoolean("building", false)) {
                int done = res.optInt("done", 0);
                int total = res.optInt("total", 0);
                TextView tv = h.text(c, "后台正在解析会话树，进度: " + done + " / " + total);
                setSecondaryStyle(tv, 14f);
                Button refreshBtn = new Button(h.a);
                refreshBtn.setText("查询进度");
                refreshBtn.setOnClickListener(v -> h.nav("lineage"));
                c.addView(refreshBtn);
                return;
            }

            JSONArray nodes = res.optJSONArray("nodes");
            if (nodes == null) {
                nodes = res.optJSONArray("items");
            }

            if (nodes == null || nodes.length() == 0) {
                TextView empty = h.text(c, "当前没有检索到分支派生关系。在对话中通过分叉探讨或派生任务后，此处将呈现会话的分支关系。");
                setSecondaryStyle(empty, 14f);
                return;
            }

            TextView summary = new TextView(h.a);
            summary.setText("共发现 " + nodes.length() + " 个会话");
            setSecondaryStyle(summary, 13f);
            c.addView(summary);

            java.util.Map<String,String> parents = new java.util.HashMap<>();
            java.util.Map<String,Integer> counts = new java.util.HashMap<>();
            JSONArray edges = res.optJSONArray("edges");
            NativeLineagePanel.add(h, c, nodes, edges);
            for (int e = 0; edges != null && e < edges.length(); e++) {
                JSONObject edge = edges.optJSONObject(e);
                if (edge == null) continue;
                String cid = edge.optString("child"), pid = edge.optString("parent");
                if (pid.isEmpty() || cid.isEmpty()) continue;
                String kind = edge.optString("kind");
                if ("merge".equals(kind) || "read".equals(kind)) continue;
                parents.put(cid, pid);
                counts.put(pid, (counts.containsKey(pid) ? counts.get(pid) : 0) + 1);
            }
            paged(h, c, nodes, node -> {
                if (node.optBoolean("trashed")) return;
                String sid = node.optString("id", node.optString("sid", ""));
                String title = node.optString("title", "未命名分支");
                String parent = parents.get(sid);
                if (parent == null) parent = "";
                long at = node.optLong("lastTs", 0);
                int childrenCount = counts.containsKey(sid) ? counts.get(sid) : 0;

                LinearLayout card = makeCard(h, c);
                TextView tvTitle = new TextView(h.a);
                tvTitle.setText(title);
                setTitleStyle(tvTitle, 16f);
                card.addView(tvTitle);

                StringBuilder info = new StringBuilder();
                info.append("会话ID: ").append(sid).append("\n");
                if (!parent.isEmpty()) {
                    info.append("上级会话: ").append(parent).append("\n");
                } else {
                    info.append("根会话\n");
                }
                if (childrenCount > 0) {
                    info.append("子分支: ").append(childrenCount).append("\n");
                }
                if (at > 0) {
                    info.append("最近活动: ").append(formatDate(at));
                }

                TextView tvInfo = new TextView(h.a);
                tvInfo.setText(info.toString().trim());
                setSecondaryStyle(tvInfo, 12f);
                card.addView(tvInfo);

                LinearLayout btnRow = makeActionRow(h);
                card.addView(btnRow);
                h.button(btnRow, "打开对话", () -> h.openSession(sid));
            });
        });
    }

    private static void showBookmarks(NativeHome h, LinearLayout c) {
        LinearLayout bar = makeActionRow(h);
        c.addView(bar);
        h.button(bar, "刷新书签", () -> h.nav("bookmarks"));

        TextView loading = h.text(c, "正在检索消息与段落书签...");

        h.get("/api/bookmarks", res -> {
            c.removeView(loading);
            JSONArray items = res.optJSONArray("items");
            if (items == null || items.length() == 0) {
                TextView empty = h.text(c, "暂无已保存的书签。在会话中标记重要回答或段落即可归集至此。");
                setSecondaryStyle(empty, 14f);
                return;
            }

            paged(h, c, items, bm -> {
                String bid = bm.optString("id");
                String sid = bm.optString("sid");
                String role = bm.optString("role", "assistant");
                String excerpt = bm.optString("excerpt", "无摘录摘要");
                String title = bm.optString("title", "会话 " + sid);
                long at = bm.optLong("at", 0);
                int para = bm.isNull("para") ? -1 : bm.optInt("para", -1);

                LinearLayout card = makeCard(h, c);

                TextView tvTitle = new TextView(h.a);
                tvTitle.setText(title + " [" + ("user".equals(role) ? "用户提问" : "AI回答") + "]");
                setTitleStyle(tvTitle, 15f);
                card.addView(tvTitle);

                TextView tvExcerpt = new TextView(h.a);
                tvExcerpt.setText(excerpt);
                tvExcerpt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                tvExcerpt.setPadding(0, h.dp(4), 0, h.dp(4));
                card.addView(tvExcerpt);

                TextView tvMeta = new TextView(h.a);
                tvMeta.setText((para >= 0 ? "第 " + (para + 1) + " 段 · " : "") + (at > 0 ? formatDate(at) : ""));
                setSecondaryStyle(tvMeta, 12f);
                card.addView(tvMeta);

                LinearLayout btnRow = makeActionRow(h);
                card.addView(btnRow);

                h.button(btnRow, "打开对话", () -> { h.chat.bookmarkTurn = bm.optString("uuid"); h.openSession(sid); });

                h.button(btnRow, "移除此书签", () -> {
                    confirmDialog(h.a, "移除书签", "确定将该内容从书签库中移除吗？", () -> {
                        h.post("/api/bookmarks/del", NativeHome.json("id", bid), delRes -> {
                            h.notice("书签已移除");
                            h.nav("bookmarks");
                        });
                    });
                });
            });
        });
    }

    private static void addSectionHeader(NativeHome h, LinearLayout parent, String title) {
        TextView tv = new TextView(h.a);
        tv.setText(title);
        if (android.os.Build.VERSION.SDK_INT >= 28) tv.setAccessibilityHeading(true);
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(PixelUi.color(h.a, "primary"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(h.dp(12), h.dp(14), h.dp(12), h.dp(6));
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private static void showSettings(NativeHome h, LinearLayout c) {

        h.setHeading("", "");
        TextView heading = PixelUi.label(h.a, "设置", 34, "onSurface");
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setPadding(h.dp(8), h.dp(12), h.dp(8), h.dp(12));
        c.addView(heading);

        addSectionHeader(h, c, "常用与界面");
        LinearLayout g1 = PixelUi.group(c);
        PixelUi.row(g1, "model", "接口与模型", "Claude、ChatGPT、Google 与第三方接口", () -> h.nav("provider"));
        PixelUi.row(g1, "tune", "界面与主题", "原生安卓与经典主题", h.chooseTheme);
        PixelUi.row(g1, "projects", "工作区与运行", "目录、并发任务与执行模式", () -> h.nav("set-workspace"));

        addSectionHeader(h, c, "系统与服务");
        LinearLayout g2 = PixelUi.group(c);
        PixelUi.row(g2, "edit", "Claude 行为", "提示词与交互参数", () -> h.nav("set-claude"));
        PixelUi.row(g2, "history", "后台与并行", "后台保活与内存管理", () -> h.nav("set-run"));
        PixelUi.row(g2, "storage", "存储", "缓存与自动清理", () -> h.nav("set-storage"));
        PixelUi.row(g2, "device", "设备与权限", "通知、语音与设备权限", () -> h.nav("set-device"));

        addSectionHeader(h, c, "维护与支持");
        LinearLayout g3 = PixelUi.group(c);
        PixelUi.row(g3, "power", "用量与耗电", "令牌用量与电量统计", () -> h.nav("usage"));
        PixelUi.row(g3, "check", "更新", "检查版本更新与升级说明", () -> h.nav("set-update"));
        PixelUi.row(g3, "more", "排障", "运行日志与诊断", () -> h.nav("set-trouble"));
    }

    private static void showWorkspaceSettings(NativeHome h, LinearLayout c) {

        addSectionHeader(h, c, "工作空间与调度");
        LinearLayout gSettings = PixelUi.group(c);
        TextView loadingSettings = new TextView(h.a);
        loadingSettings.setText("正在读取设置…");
        loadingSettings.setTextSize(14f);
        loadingSettings.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
        loadingSettings.setPadding(h.dp(16), h.dp(16), h.dp(16), h.dp(16));
        gSettings.addView(loadingSettings);

        addSectionHeader(h, c, "底层运行模式");
        LinearLayout gExec = PixelUi.group(c);
        TextView loadingExec = new TextView(h.a);
        loadingExec.setText("正在读取执行模式…");
        loadingExec.setTextSize(14f);
        loadingExec.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
        loadingExec.setPadding(h.dp(16), h.dp(16), h.dp(16), h.dp(16));
        gExec.addView(loadingExec);

        h.get("/api/settings", res -> {
            gSettings.removeAllViews();

            String ws = res.optString("workspace", "");
            int maxP = res.optInt("maxParallel", 1);
            int maxPCap = res.optInt("maxParallelCap", 8);
            String outputStyle = res.optString("outputStyle", "");

            PixelUi.row(gSettings, "projects", "工作空间配置", ws.isEmpty() ? "未配置工作空间" : ws, () -> {
                new android.app.AlertDialog.Builder(h.a)
                        .setTitle("工作空间配置")
                        .setMessage(ws.isEmpty() ? "当前未指定工作空间路径" : ws)
                        .setPositiveButton("确定", null)
                        .show();
            });

            String pSupporting = "最大并发任务数: " + maxP + " (上限 " + maxPCap + ")";
            PixelUi.row(gSettings, "tune", "并发调度限制", pSupporting, () -> {
                EditText etP = new EditText(h.a);
                etP.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                etP.setText(String.valueOf(maxP));
                etP.setSelection(etP.getText().length());
                etP.setTextColor(PixelUi.color(h.a, "onSurface"));
                etP.setBackground(PixelUi.shape(h.a, "surfaceContainerHigh", 8));
                etP.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(12));

                LinearLayout container = new LinearLayout(h.a);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(h.dp(24), h.dp(16), h.dp(24), h.dp(8));
                container.addView(etP);

                new android.app.AlertDialog.Builder(h.a)
                        .setTitle("最大并发任务数 (上限 " + maxPCap + ")")
                        .setView(container)
                        .setPositiveButton("保存并发上限", (dialog, which) -> {
                            try {
                                int val = Integer.parseInt(etP.getText().toString().trim());
                                if (val < 1 || val > maxPCap) {
                                    h.notice("请输入 1 到 " + maxPCap + " 之间的数字");
                                    return;
                                }
                                h.post("/api/settings", NativeHome.json("maxParallel", val), r -> {
                                    h.notice("最大并发数已更新");
                                    h.nav("set-workspace");
                                });
                            } catch (Exception e) {
                                h.notice("请输入有效数字");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });

            String curStyleName = outputStyle.isEmpty() ? "默认输出风格" : outputStyle;
            PixelUi.row(gSettings, "edit", "输出格式风格", curStyleName, () -> {
                JSONArray styles = res.optJSONArray("outputStyles");
                java.util.List<String> ids = new java.util.ArrayList<>();
                java.util.List<String> names = new java.util.ArrayList<>();
                ids.add("");
                names.add("使用默认输出风格");
                int checked = outputStyle.isEmpty() ? 0 : -1;

                if (styles != null) {
                    for (int i = 0; i < styles.length(); i++) {
                        JSONObject s = styles.optJSONObject(i);
                        if (s == null) continue;
                        String sid = s.optString("id");
                        ids.add(sid);
                        names.add(sid);
                        if (sid.equals(outputStyle)) {
                            checked = ids.size() - 1;
                        }
                    }
                }

                CharSequence[] items = names.toArray(new CharSequence[0]);
                new android.app.AlertDialog.Builder(h.a)
                        .setTitle("输出格式风格")
                        .setSingleChoiceItems(items, checked, (dialog, which) -> {
                            dialog.dismiss();
                            String sid = ids.get(which);
                            String sname = names.get(which);
                            h.post("/api/settings", NativeHome.json("outputStyle", sid), r -> {
                                h.notice(sid.isEmpty() ? "已恢复默认输出风格" : "输出风格已切换为: " + sname);
                                h.nav("set-workspace");
                            });
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        });

        h.get("/api/exec-mode", emRes -> {
            gExec.removeAllViews();

            String active = emRes.optString("active", "direct");
            int targetSdk = emRes.optInt("targetSdk", 0);
            boolean must = emRes.optBoolean("must", false);

            String emSupporting = "当前模式: " + active + " (targetSdk: " + targetSdk + (must ? " 强制加载器模式" : "") + ")";
            PixelUi.row(gExec, "tune", "底层运行模式 (Exec Mode)", emSupporting, () -> {
                java.util.List<String> modes = new java.util.ArrayList<>();
                String[] allModes = {"auto", "direct", "loader"};
                for (String m : allModes) {
                    if (must && "direct".equals(m)) continue;
                    modes.add(m);
                }
                int activeIndex = modes.indexOf(active);
                CharSequence[] items = modes.toArray(new CharSequence[0]);

                new android.app.AlertDialog.Builder(h.a)
                        .setTitle("切换底层运行模式")
                        .setSingleChoiceItems(items, activeIndex, (dialog, which) -> {
                            dialog.dismiss();
                            String selectedMode = modes.get(which);
                            new android.app.AlertDialog.Builder(h.a)
                                    .setTitle("切换执行模式")
                                    .setMessage("切换底层执行模式并立即重启网关吗？")
                                    .setPositiveButton("确定", (d2, w2) -> {
                                        h.post("/api/exec-mode", NativeHome.json("mode", selectedMode, "restart", true), r -> {
                                            h.notice("已下发切换模式指令并重启网关");
                                            h.nav("set-workspace");
                                        });
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        });
    }

    private static void paged(NativeHome h, LinearLayout parent, JSONArray list, Consumer<JSONObject> render) {
        new Runnable() {
            int next;
            @Override public void run() {
                int limit = Math.min(next + 25, list.length());
                while (next < limit) {
                    JSONObject item = list.optJSONObject(next++);
                    if (item != null) render.accept(item);
                }
                if (next == list.length()) return;
                Button more = new Button(h.a);
                more.setText("加载更多（" + next + "/" + list.length() + "）");
                more.setOnClickListener(v -> { parent.removeView(more); run(); });
                parent.addView(more);
            }
        }.run();
    }

    private static LinearLayout makeCard(NativeHome h, LinearLayout parent) {
        LinearLayout card = PixelUi.group(parent);
        card.setPadding(h.dp(16), h.dp(14), h.dp(16), h.dp(14));
        return card;
    }

    private static LinearLayout makeActionRow(NativeHome h) {
        LinearLayout row = new LinearLayout(h.a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, h.dp(4), 0, h.dp(4));
        row.setLayoutParams(lp);
        return row;
    }

    private static EditText makeLabeledInput(NativeHome h, LinearLayout parent, String label, String val) {
        TextView tv = new TextView(h.a);
        tv.setText(label);
        setSecondaryStyle(tv, 12f);
        tv.setPadding(0, h.dp(4), 0, 0);
        parent.addView(tv);

        EditText et = new EditText(h.a);
        et.setText(val != null ? val : "");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        parent.addView(et);
        return et;
    }

    private static void setTitleStyle(TextView tv, float sp) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(PixelUi.color(tv.getContext(), "onSurface"));
    }

    private static void setSecondaryStyle(TextView tv, float sp) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(PixelUi.color(tv.getContext(), "onSurfaceVariant"));
    }

    private static void setSelectable(View v) {
        TypedValue tv = new TypedValue();
        if (v.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    private static String formatDate(long ts) {
        if (ts <= 0) return "--";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return sdf.format(new Date(ts));
        } catch (Exception e) {
            return String.valueOf(ts);
        }
    }

    private static String enc(String s) {
        return s == null ? "" : Uri.encode(s);
    }

    private static void confirmDialog(Context ctx, String title, String msg, Runnable onConfirm) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", (d, w) -> {
                    if (onConfirm != null) onConfirm.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }


}
