package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AlertDialog;
import android.os.Handler;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class NativeFileArchive {
    public static void open(NativeHome h, String path, String destDir, Runnable refresh) {
        browse(h, path, "", null, destDir, refresh);
    }

    private static void browse(NativeHome h, String path, String inner, String pwd, String destDir, Runnable ref) {
        JSONObject req = pwd == null ? NativeHome.json("path", path, "dir", inner)
                : NativeHome.json("path", path, "dir", inner, "password", pwd);
        h.post("/api/arc/list", req, res -> {
            if (res.optBoolean("needPassword")) {
                EditText et = new EditText(h.a);
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                new MaterialAlertDialogBuilder(h.a).setTitle("输入解压密码").setView(et)
                        .setPositiveButton("确定", (d, w) -> browse(h, path, inner, et.getText().toString(), destDir, ref))
                        .setNegativeButton("取消", null).show();
                return;
            }
            JSONArray arr = res.optJSONArray("entries");
            int len = arr != null ? arr.length() : 0;
            List<String> names = new ArrayList<>();
            names.add("全部解压");
            if (!inner.isEmpty()) names.add(".. (返回上一级)");
            for (int i = 0; i < len; i++) {
                JSONObject o = arr.optJSONObject(i);
                names.add(o.optString("name") + (o.optBoolean("dir") ? "/" : ""));
            }
            PixelChoiceSheet.actions(h.a, inner.isEmpty() ? "浏览压缩包" : inner, names, which -> {
                if (which == 0) {
                    promptExtract(h, path, destDir, null, pwd, ref);
                    return;
                }
                int idx = which - 1;
                if (!inner.isEmpty()) {
                    if (idx == 0) {
                        int slash = inner.lastIndexOf('/', inner.length() - 2);
                        browse(h, path, slash >= 0 ? inner.substring(0, slash + 1) : "", pwd, destDir, ref);
                        return;
                    }
                    idx--;
                }
                JSONObject item = arr.optJSONObject(idx);
                String name = item.optString("name");
                if (item.optBoolean("dir")) {
                    browse(h, path, inner + name + "/", pwd, destDir, ref);
                } else {
                    PixelChoiceSheet.actions(h.a, name, Arrays.asList("解压此项", "全部解压"), act ->
                            promptExtract(h, path, destDir, act == 0 ? Collections.singletonList(inner + name) : null, pwd, ref));
                }
            });
        });
    }

    private static void promptExtract(NativeHome h, String path, String dest, List<String> entries, String pwd, Runnable ref) {
        EditText et = new EditText(h.a);
        et.setText(new java.io.File(dest, new java.io.File(path).getName() + ".files").getPath());
        new MaterialAlertDialogBuilder(h.a).setTitle("解压目标路径").setView(et)
                .setPositiveButton("解压", (d, w) -> {
                    try {
                        JSONObject req = NativeHome.json("path", path, "dest", et.getText().toString());
                        if (entries != null) req.put("entries", new JSONArray(entries));
                        if (pwd != null) req.put("password", pwd);
                        h.post("/api/arc/extract", req, res -> track(h, res.optString("id"), ref));
                    } catch (Exception e) { h.notice(e.getMessage()); }
                }).setNegativeButton("取消", null).show();
    }

    public static void create(NativeHome h, List<String> absoluteSelectedPaths, String dir, Runnable refresh) {
        EditText et = new EditText(h.a);
        et.setHint("输入文件名(无需后缀)");
        new MaterialAlertDialogBuilder(h.a).setTitle("压缩为 ZIP").setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) { h.notice("请输入单个压缩包名称"); return; }
                    if (!name.endsWith(".zip")) name += ".zip";
                    String dest = dir + (dir.endsWith("/") ? "" : "/") + name;
                    JSONObject req = NativeHome.json("items", new JSONArray(absoluteSelectedPaths), "dest", dest, "format", "zip", "level", 5);
                    h.post("/api/arc/create", req, res -> track(h, res.optString("id"), refresh));
                }).setNegativeButton("取消", null).show();
    }

    private static void track(NativeHome h, String id, Runnable done) {
        if (id == null || id.isEmpty()) { h.notice("未返回任务编号"); return; }
        int gen = h.generation;
        Handler handler = new Handler(h.a.getMainLooper());
        AlertDialog[] dlg = new AlertDialog[1];
        dlg[0] = new MaterialAlertDialogBuilder(h.a).setTitle("处理中").setMessage("进度: 0%")
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> h.post("/api/arc/cancel", NativeHome.json("id", id), r -> {}))
                .show();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (h.generation != gen || !dlg[0].isShowing()) { dlg[0].dismiss(); return; }
                NativeRequests.call(h.a, "GET", "/api/arc/job?id=" + android.net.Uri.encode(id), null, res -> {
                    if (h.generation != gen || !dlg[0].isShowing()) { dlg[0].dismiss(); return; }
                    String st = res.optString("state");
                    if ("done".equals(st)) {
                        dlg[0].dismiss();
                        if (done != null) done.run();
                    } else if ("cancelled".equals(st)) {
                        dlg[0].dismiss(); h.notice("任务已停止");
                    } else if ("error".equals(st)) {
                        dlg[0].dismiss();
                        h.notice("错误: " + res.optString("error", "失败"));
                    } else {
                        dlg[0].setMessage("进度: " + res.optInt("pct") + "%");
                        handler.postDelayed(this, 500);
                    }
                }, error -> { dlg[0].dismiss(); if (h.generation == gen) h.notice(error); });
            }
        });
    }
}
