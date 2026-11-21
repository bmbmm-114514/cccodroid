package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AlertDialog;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class NativeLineagePanel {
    public static void add(NativeHome h, LinearLayout root, JSONArray nodes, JSONArray edges) {
        LinearLayout bar = new LinearLayout(h.a);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        h.button(bar, "导出会话关系（HTML）", () -> exportLineage(h, nodes, edges));
        h.button(bar, "汇合会话", () -> startMerge(h, nodes));
        h.button(bar, "补记来源", () -> startExtra(h, nodes, edges));
        root.addView(bar);
    }

    private static String titleOf(JSONObject n) {
        return n == null ? "" : n.optString("title", n.optString("id", "无标题"));
    }

    private static List<JSONObject> filterCandidates(JSONArray nodes) {
        List<JSONObject> list = new ArrayList<>();
        if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null || n.optBoolean("trashed", false) || n.optBoolean("deleted", false)) continue;
            String type = n.optString("type");
            if (!type.isEmpty() && !type.equalsIgnoreCase("claude") && !type.equalsIgnoreCase("chat")) continue;
            if (!n.optString("id").isEmpty()) list.add(n);
        }
        return list;
    }

    private static void exportLineage(NativeHome h, JSONArray nodes, JSONArray edges) {
        StringBuilder sb = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>会话谱系</title></head><body>");
        java.util.Set<String> visible = new java.util.HashSet<>();
        sb.append("<h2>会话节点</h2><ul>");
        if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null || n.optBoolean("trashed")) continue;
            String d = n.optLong("lastTs") == 0 ? "" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(n.optLong("lastTs")));
            visible.add(n.optString("id"));
            sb.append("<li>").append(TextUtils.htmlEncode(titleOf(n))).append(" (").append(TextUtils.htmlEncode(n.optString("id")))
              .append(d.isEmpty() ? "" : " · " + d).append(")</li>");
        }
        sb.append("</ul><h2>关系连线</h2><ul>");
        if (edges != null) for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i);
            if (e != null && visible.contains(e.optString("child")) && visible.contains(e.optString("parent"))) sb.append("<li>").append(TextUtils.htmlEncode(e.optString("parent"))).append(" &rarr; ")
                            .append(TextUtils.htmlEncode(e.optString("child"))).append("</li>");
        }
        sb.append("</ul></body></html>");
        String name = "cccodroid-lineage-" + System.currentTimeMillis() + ".html";
        h.post("/api/lineage/export", NativeHome.json("name", name, "data", sb.toString()), r -> {
            String file = r.optString("file");
            new MaterialAlertDialogBuilder(h.a).setTitle("导出成功").setMessage("文件已保存至：\n" + file)
                .setPositiveButton("分享", (d, w) -> h.post("/api/app/share_file", NativeHome.json("path", file, "mime", "text/html"), s -> h.notice(s.optBoolean("ok", false) ? "已调起分享" : s.optString("message", "分享失败"))))
                .setNegativeButton("关闭", null).show();
        });
    }

    private static void startMerge(NativeHome h, JSONArray nodes) {
        List<JSONObject> cands = filterCandidates(nodes);
        if (cands.size() < 2) { h.notice("可用会话不足 2 个，无法汇合"); return; }
        LinearLayout layout = new LinearLayout(h.a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(8));
        List<CheckBox> pickCbs = new ArrayList<>();
        List<CheckBox> compactCbs = new ArrayList<>();
        for (JSONObject n : cands) {
            LinearLayout row = new LinearLayout(h.a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            CheckBox cb = new CheckBox(h.a);
            cb.setText(titleOf(n));
            row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
            pickCbs.add(cb);
            boolean canCompact = n.optJSONArray("chapters") != null && n.optJSONArray("chapters").length() > 1;
            CheckBox cmp = new CheckBox(h.a);
            cmp.setText("压缩");
            cmp.setEnabled(canCompact);
            row.addView(cmp);
            compactCbs.add(cmp);
            layout.addView(row);
        }
        ScrollView sv = new ScrollView(h.a);
        sv.addView(layout);
        new MaterialAlertDialogBuilder(h.a).setTitle("选择汇合会话 (2-8个)").setView(sv)
            .setPositiveButton("提取材料", (d, w) -> {
                List<JSONObject> picked = new ArrayList<>();
                List<String> hows = new ArrayList<>();
                for (int i = 0; i < cands.size(); i++) {
                    if (pickCbs.get(i).isChecked()) {
                        picked.add(cands.get(i));
                        hows.add(compactCbs.get(i).isChecked() ? "compact" : "excerpt");
                    }
                }
                if (picked.size() < 2 || picked.size() > 8) { h.notice("请选择 2 到 8 个会话"); return; }
                fetchMaterials(h, picked, hows, 0, new ArrayList<>());
            }).setNegativeButton("取消", null).show();
    }

    private static void fetchMaterials(NativeHome h, List<JSONObject> nodes, List<String> hows, int idx, List<JSONObject> results) {
        if (idx >= nodes.size()) { showMergePreview(h, nodes, results); return; }
        String id = nodes.get(idx).optString("id");
        h.get("/api/lineage/material?id=" + Uri.encode(id) + "&how=" + Uri.encode(hows.get(idx)), obj -> {
            if (obj == null || obj.has("error")) { h.notice("提取材料失败：" + id); return; }
            results.add(obj);
            fetchMaterials(h, nodes, hows, idx + 1, results);
        });
    }

    private static void showMergePreview(NativeHome h, List<JSONObject> nodes, List<JSONObject> mats) {
        StringBuilder full = new StringBuilder();
        List<String> tails = new ArrayList<>();
        for (int i = 0; i < mats.size(); i++) {
            JSONObject m = mats.get(i);
            String id = nodes.get(i).optString("id");
            if (i > 0) full.append("\n\n---\n\n");
            full.append(m.optString("text", ""));
            String f4 = id.substring(0, Math.min(4, id.length()));
            tails.add(f4 + "·" + m.optInt("chapter", 1));
        }
        String tail = "（汇合自 " + TextUtils.join("、", tails) + "）";
        full.append("\n\n").append(tail);
        EditText et = new EditText(h.a);
        et.setText(full.toString());
        et.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(12));
        ScrollView sv = new ScrollView(h.a);
        sv.addView(et);
        new MaterialAlertDialogBuilder(h.a).setTitle("预览并编辑汇合草稿").setView(sv)
            .setPositiveButton("创建新对话草稿", (d, w) -> {
                String text = et.getText().toString().trim();
                if (text.isEmpty()) { h.notice("草稿内容不能为空"); return; }
                if (!text.endsWith(tail)) text += "\n\n" + tail;
                if (text.length() > 100000) { h.notice("草稿超过 10 万字，请精简内容"); return; }
                h.chatId = "";
                h.nav("chat");
                h.share(text);
            }).setNegativeButton("取消", null).show();
    }

    private static void startExtra(NativeHome h, JSONArray nodes, JSONArray edges) {
        List<JSONObject> cands = filterCandidates(nodes);
        if (cands.isEmpty()) { h.notice("暂无可用会话"); return; }
        String[] titles = new String[cands.size()];
        for (int i = 0; i < cands.size(); i++) titles[i] = titleOf(cands.get(i));
        new MaterialAlertDialogBuilder(h.a).setTitle("选择目标会话 (子级)")
            .setItems(titles, (d, which) -> showExtraConfig(h, cands.get(which), cands, edges))
            .setNegativeButton("取消", null).show();
    }

    private static void showExtraConfig(NativeHome h, JSONObject child, List<JSONObject> all, JSONArray edges) {
        String childId = child.optString("id");
        final AlertDialog[] picker = {null};
        LinearLayout layout = new LinearLayout(h.a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(8));
        List<String> existingParents = new ArrayList<>();
        if (edges != null) {
            for (int i = 0; i < edges.length(); i++) {
                JSONObject e = edges.optJSONObject(i);
                if (e != null && "merge".equals(e.optString("kind")) && childId.equals(e.optString("child"))) existingParents.add(e.optString("parent"));
            }
        }
        if (!existingParents.isEmpty()) {
            TextView exLabel = new TextView(h.a);
            exLabel.setText("已有来源 (点击解除)：");
            layout.addView(exLabel);
            for (String pid : existingParents) {
                Button btn = new Button(h.a);
                btn.setText("解除来源: " + pid);
                btn.setOnClickListener(v -> new MaterialAlertDialogBuilder(h.a).setTitle("确认解除")
                    .setMessage("确认解除与来源 " + pid + " 的关联？")
                    .setPositiveButton("解除", (d, w) -> h.post("/api/lineage/extra",
                        NativeHome.json("child", childId, "drop", pid), r -> { h.notice("已解除关联"); if (picker[0] != null) picker[0].dismiss(); h.nav("lineage"); }))
                    .setNegativeButton("取消", null).show());
                layout.addView(btn);
            }
        }
        TextView addLabel = new TextView(h.a);
        addLabel.setText("勾选新增父级来源：");
        layout.addView(addLabel);
        List<CheckBox> cbs = new ArrayList<>();
        List<String> parentIds = new ArrayList<>();
        for (JSONObject n : all) {
            String id = n.optString("id");
            if (id.equals(childId) || existingParents.contains(id)) continue;
            CheckBox cb = new CheckBox(h.a);
            cb.setText(titleOf(n));
            layout.addView(cb);
            cbs.add(cb);
            parentIds.add(id);
        }
        ScrollView sv = new ScrollView(h.a);
        sv.addView(layout);
        picker[0] = new MaterialAlertDialogBuilder(h.a).setTitle("补记来源：" + titleOf(child)).setView(sv)
            .setPositiveButton("保存补记", (d, w) -> {
                JSONArray addArr = new JSONArray();
                for (int i = 0; i < cbs.size(); i++) {
                    if (cbs.get(i).isChecked()) addArr.put(parentIds.get(i));
                }
                if (addArr.length() == 0) { h.notice("请至少勾选一个父级来源"); return; }
                if (addArr.length() > 12) { h.notice("一次最多选择 12 个来源"); return; }
                JSONObject req = NativeHome.json("child", childId, "add", addArr);
                h.post("/api/lineage/extra", req, r -> { h.notice("补记来源成功"); h.nav("lineage"); });
            }).setNegativeButton("取消", null).show();
    }
}
