package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.function.Consumer;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public class NativeSettingsPanel {
    public static void show(NativeHome h, String id) {
        LinearLayout page = h.scrolling();
        if ("set-device".equals(id)) {
            showDevice(h, page);
            return;
        }
        LinearLayout v = PixelUi.group(page);
        v.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(20));
        if ("set-claude".equals(id)) showClaude(h, v);
        else if ("set-run".equals(id)) showRun(h, v);
        else if ("set-storage".equals(id)) showStorage(h, v);
        else if ("set-update".equals(id)) showUpdate(h, v);
        else if ("set-trouble".equals(id)) showTrouble(h, v);
    }

    private static void confirm(NativeHome h, String title, String msg, Runnable onOk) {
        new MaterialAlertDialogBuilder(h.a).setTitle(title).setMessage(msg)
            .setPositiveButton("确定", (d, w) -> onOk.run()).setNegativeButton("取消", null).show();
    }

    private static Switch addSwitch(LinearLayout p, String label, boolean checked) {
        Switch s = new Switch(p.getContext());
        s.setText(label);
        s.setChecked(checked);
        s.setTextSize(16);
        s.setTextColor(PixelUi.color(p.getContext(), "onSurface"));
        s.setMinHeight(Math.round(60 * p.getResources().getDisplayMetrics().density));
        p.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private static EditText addInput(LinearLayout p, String hint, boolean multi) {
        EditText e = new EditText(p.getContext());
        e.setHint(hint);
        float density = p.getResources().getDisplayMetrics().density;
        int inset = Math.round(12 * density);
        e.setBackground(PixelUi.shape(p.getContext(), "surfaceContainerHigh", 12));
        e.setMinHeight(Math.round(48 * density));
        e.setPadding(inset, inset, inset, inset);
        e.setTextColor(PixelUi.color(p.getContext(), "onSurface"));
        e.setHintTextColor(PixelUi.color(p.getContext(), "onSurfaceVariant"));
        if (multi) { e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); e.setMinLines(3); e.setMaxLines(6); }
        p.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static String sz(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        if (b < 1073741824) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.1f GB", b / 1073741824.0);
    }

    private static JSONArray toArr(String s) {
        JSONArray a = new JSONArray();
        for (String l : s.split("\n")) { l = l.trim(); if (!l.isEmpty()) a.put(l); }
        return a;
    }

    private static String fromArr(JSONArray a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) sb.append(i > 0 ? "\n" : "").append(a.optString(i));
        return sb.toString();
    }

    private static void showClaude(NativeHome h, LinearLayout v) {
        Switch swIdle = addSwitch(v, "空闲时自动压缩上下文", false);
        h.text(v, "自动压缩窗口（留空使用默认值）");
        EditText etWin = addInput(v, "例如: 20000", false); etWin.setInputType(InputType.TYPE_CLASS_NUMBER);
        h.text(v, "允许的工具");
        EditText etAllowed = addInput(v, "每行一个工具名称", true);
        h.text(v, "禁用的工具");
        EditText etDisallowed = addInput(v, "每行一个工具名称", true);
        h.get("/api/settings", r -> {
            if (r == null) return;
            swIdle.setChecked(r.optBoolean("idleCompaction", false));
            if (r.has("autoCompactWindow") && !r.isNull("autoCompactWindow")) etWin.setText(String.valueOf(r.optInt("autoCompactWindow")));
            etAllowed.setText(fromArr(r.optJSONArray("allowedTools")));
            etDisallowed.setText(fromArr(r.optJSONArray("disallowedTools")));
            h.button(v, "保存配置", () -> confirm(h, "确认保存", "确定更新 Claude 行为配置？", () -> {
                try {
                    JSONObject b = new JSONObject();
                    b.put("idleCompaction", swIdle.isChecked());
                    String w = etWin.getText().toString().trim();
                    b.put("autoCompactWindow", w.isEmpty() ? JSONObject.NULL : Integer.parseInt(w));
                    b.put("allowedTools", toArr(etAllowed.getText().toString()));
                    b.put("disallowedTools", toArr(etDisallowed.getText().toString()));
                    h.post("/api/settings", b, res -> h.notice("配置已保存"));
                } catch (Exception e) { h.notice("参数错误"); }
            }));
        });
    }

    private static void showRun(NativeHome h, LinearLayout v) {
        Switch swIsland = addSwitch(v, "工作岛后台保活", false);
        swIsland.setOnClickListener(v1 -> h.post("/api/settings", NativeHome.json("workIsland", swIsland.isChecked()), res -> h.notice("工作岛设置已更新")));
        Switch swMem = addSwitch(v, "内存管家", false);
        h.text(v, "内存保护白名单包名 (换行或逗号分隔):");
        EditText etWl = addInput(v, "例如: com.tencent.mm", true);
        swMem.setOnClickListener(v1 -> {
            if (swMem.isChecked()) {
                swMem.setChecked(false);
                confirm(h, "开启内存管家？", "清理时会停止白名单外的后台应用，可能收不到其推送。确定开启？", () -> {
                    swMem.setChecked(true);
                    h.post("/api/settings", NativeHome.json("memGuard", NativeHome.json("enabled", true)), res -> h.notice("内存管家已开启"));
                });
            } else {
                h.post("/api/settings", NativeHome.json("memGuard", NativeHome.json("enabled", false)), res -> h.notice("内存管家已关闭"));
            }
        });
        h.button(v, "保存白名单", () -> {
            JSONArray arr = new JSONArray();
            for (String p : etWl.getText().toString().split("[\\s,，]+")) {
                p = p.trim();
                if (p.matches("^[A-Za-z][\\w]*(\\.[\\w]+)+$")) arr.put(p);
                else if (!p.isEmpty()) { h.notice("非法包名: " + p); return; }
            }
            h.post("/api/settings", NativeHome.json("memGuard", NativeHome.json("whitelist", arr)), res -> h.notice("白名单已保存 (" + arr.length() + " 项)"));
        });
        TextView tvMem = h.text(v, "内存状态: 点击检测");
        h.button(v, "检查内存状态", () -> {
            tvMem.setText("正在统计内存...");
            h.get("/api/mem/status", r -> {
                if (r == null) return;
                JSONObject m = r.optJSONObject("mem");
                JSONArray apps = r.optJSONArray("apps");
                int n = 0; long sum = 0;
                if (apps != null) for (int i = 0; i < apps.length(); i++) {
                    JSONObject a = apps.optJSONObject(i);
                    if (a != null && !a.optBoolean("protect")) { n++; sum += a.optLong("pss"); }
                }
                tvMem.setText(String.format(Locale.CHINA, "可用: %s / 总计: %s\n可停止后台: %d 个 (%s)",
                    sz(m != null ? m.optLong("available") : 0), sz(m != null ? m.optLong("total") : 0), n, sz(sum)));
            });
        });
        h.button(v, "清理后台应用", () -> confirm(h, "确认清理", "确定清理白名单外的后台应用？", () ->
            h.post("/api/mem/clear", new JSONObject(), r -> h.notice(r.optBoolean("ok") ? "内存清理完成" : r.optString("message", "清理失败")))));
        h.button(v, "系统应用详情设置", () -> h.a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + h.a.getPackageName()))));
        h.get("/api/settings", r -> {
            if (r == null) return;
            swIsland.setChecked(r.optBoolean("workIsland", false));
            JSONObject mg = r.optJSONObject("memGuard");
            if (mg != null) {
                swMem.setChecked(mg.optBoolean("enabled", false));
                etWl.setText(fromArr(mg.optJSONArray("whitelist")));
            }
        });
    }

    private static void pollStorage(NativeHome h, TextView tvInfo, Switch swAuto, Spinner spEvery) {
        int gen = h.generation;
        h.get("/api/storage", r -> {
            if (r == null || h.generation != gen) return;
            if (r.optBoolean("scanning", false)) {
                tvInfo.setText("正在扫描存储空间…");
                h.body.postDelayed(() -> { if (gen == h.generation && tvInfo.isAttachedToWindow()) pollStorage(h, tvInfo, swAuto, spEvery); }, 1500);
                return;
            }
            JSONObject s = r.optJSONObject("scan");
            if (s == null) tvInfo.setText("尚未执行过存储扫描");
            else {
                JSONObject dev = s.optJSONObject("device"), c = s.optJSONObject("cache"), tp = s.optJSONObject("tmp"), jk = s.optJSONObject("junk"), ss = s.optJSONObject("sessions");
                tvInfo.setText(String.format(Locale.CHINA, "应用占用: %s | 设备可用: %s\n缓存: %s\n临时文件: %s (可清: %s)\nClaude杂物: %s (可清: %s)\n会话: %d 个 (%s)",
                    sz(s.optLong("app", 0)), sz(dev != null ? dev.optLong("free", 0) : 0),
                    sz(c != null ? c.optLong("bytes") : 0),
                    sz(tp != null ? tp.optLong("bytes") : 0), sz(tp != null ? tp.optLong("auto") : 0),
                    sz(jk != null ? jk.optLong("bytes") : 0), sz(jk != null ? jk.optLong("auto") : 0),
                    ss != null ? ss.optInt("count") : 0, sz(ss != null ? ss.optLong("bytes") : 0)));
            }
            JSONObject a = r.optJSONObject("auto");
            if (a != null) {
                swAuto.setChecked(a.optBoolean("enabled", true));
                String ev = a.optString("every", "week");
                spEvery.setSelection("day".equals(ev) ? 0 : "month".equals(ev) ? 2 : 1);
            }
        });
    }

    private static void showStorage(NativeHome h, LinearLayout v) {
        h.text(v, "存储管理与清理");
        TextView tvInfo = h.text(v, "正在读取存储状态...");
        Switch swAuto = addSwitch(v, "自动清理", true);
        h.text(v, "自动清理频次:");
        Spinner spEvery = new Spinner(h.a);
        String[] evKeys = {"day", "week", "month"};
        spEvery.setAdapter(new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item, new String[]{"每天", "每周", "每月"}));
        v.addView(spEvery, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        swAuto.setOnClickListener(v1 -> h.post("/api/settings", NativeHome.json("autoClean", NativeHome.json("enabled", swAuto.isChecked())), res -> h.notice("自动清理已更新")));
        h.button(v, "保存清理频次", () -> h.post("/api/settings", NativeHome.json("autoClean",
                NativeHome.json("every", evKeys[spEvery.getSelectedItemPosition()])), res -> h.notice("清理频次已更新")));
        h.button(v, "扫描存储空间", () -> {
            tvInfo.setText("正在扫描…");
            h.post("/api/storage/scan", new JSONObject(), res -> pollStorage(h, tvInfo, swAuto, spEvery));
        });
        h.button(v, "清理缓存", () -> confirm(h, "清理缓存", "确定清理缓存文件？", () ->
            h.post("/api/storage/clean", NativeHome.json("cat", "cache"), res -> { h.notice("缓存清理完成"); pollStorage(h, tvInfo, swAuto, spEvery); })));
        h.button(v, "清理7天前临时文件", () -> confirm(h, "清理临时文件", "确定清理7天前未修改的临时文件？", () ->
            h.post("/api/storage/clean", NativeHome.json("cat", "tmp"), res -> { h.notice("临时文件清理完成"); pollStorage(h, tvInfo, swAuto, spEvery); })));
        h.button(v, "清理30天前Claude杂物", () -> confirm(h, "清理杂物", "确定清理30天前杂物及日志？", () ->
            h.post("/api/storage/clean", NativeHome.json("cat", "junk"), res -> { h.notice("杂物清理完成"); pollStorage(h, tvInfo, swAuto, spEvery); })));
        pollStorage(h, tvInfo, swAuto, spEvery);
    }

    private static void showDevice(NativeHome h, LinearLayout page) {
        LinearLayout notifyCard = NativeStatusViews.section(page, "通知");
        TextView system = NativeStatusViews.metric(notifyCard, "系统通知", "读取中…");
        MaterialSwitch done = NativeStatusViews.toggle(notifyCard, "任务完成通知");
        MaterialSwitch ask = NativeStatusViews.toggle(notifyCard, "等待确认通知");
        done.setEnabled(false);
        ask.setEnabled(false);
        Consumer<JSONObject> bind = state -> {
            if (state == null || !state.optBoolean("ok") || state.isNull("done") || state.isNull("ask")) {
                system.setText("未知");
                return;
            }
            system.setText(state.isNull("system") ? "未知" : state.optBoolean("system") ? "已允许" : "已关闭");
            done.setChecked(state.optBoolean("done"));
            ask.setChecked(state.optBoolean("ask"));
            done.setEnabled(true);
            ask.setEnabled(true);
        };
        boolean[] saving = {false};
        int[] refreshId = {0};
        Runnable refresh = () -> {
            if (saving[0]) return;
            int id = ++refreshId[0];
            done.setEnabled(false);
            ask.setEnabled(false);
            h.req("GET", "/api/app/notify_status", null, state -> {
                if (id == refreshId[0]) bind.accept(state);
            }, error -> {
                if (id != refreshId[0]) return;
                system.setText("无法读取");
                h.notice(error);
            });
        };
        setNotifyAction(h, done, ask, "done", bind, saving);
        setNotifyAction(h, ask, done, "ask", bind, saving);
        refresh.run();
        PixelUi.row(notifyCard, "settings", "系统通知设置", "", () -> {
            try {
                h.a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, h.a.getPackageName()));
            } catch (Exception e) {
                h.a.startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        LinearLayout shizukuCard = NativeStatusViews.section(page, "Shizuku");
        NativePairingPanel pairing = NativePairingPanel.add(h, shizukuCard);

        PixelUi.row(shizukuCard, "device", "打开或下载 Shizuku", "", () -> {
            Intent it = h.a.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (it != null) {
                h.a.startActivity(it);
            } else {
                h.a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
            }
        });

        PixelUi.row(shizukuCard, "key", "请求 Shizuku 授权", "", () ->
                h.post("/api/priv/request", new JSONObject(), r ->
                        h.notice(r != null ? r.optString("message", "已发送请求") : "已发送请求")));

        LinearLayout permCard = NativeStatusViews.section(page, "系统权限");
        PixelUi.row(permCard, "lock", "应用系统权限", "", () ->
                h.a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + h.a.getPackageName()))));
        Runnable refreshDevice = () -> {
            refresh.run();
            pairing.poll();
        };
        h.headerAction("refresh", "刷新设备状态", refreshDevice);
        // Re-read permissions on return from Android settings or the Shizuku approval window.
        ViewTreeObserver.OnWindowFocusChangeListener focus = focused -> {
            if (focused) refreshDevice.run();
        };
        if (page.isAttachedToWindow()) page.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
            }
        });
    }

    private static void setNotifyAction(NativeHome h, MaterialSwitch toggle, MaterialSwitch sibling,
                                        String key, Consumer<JSONObject> bind, boolean[] saving) {
        toggle.setOnClickListener(v -> {
            saving[0] = true;
            boolean requested = toggle.isChecked();
            toggle.setEnabled(false);
            sibling.setEnabled(false);
            Consumer<String> failed = error -> {
                saving[0] = false;
                toggle.setChecked(!requested);
                toggle.setEnabled(true);
                sibling.setEnabled(true);
                h.notice(error);
            };
            h.req("POST", "/api/app/notify_set", NativeHome.json(key, requested), state -> {
                saving[0] = false;
                if (state.optBoolean("ok") && state.has("done") && state.has("ask")) bind.accept(state);
                else failed.accept(state.optString("message", "保存失败"));
            }, failed);
        });
    }

    private static void showUpdate(NativeHome h, LinearLayout v) {
        h.text(v, "软件版本与工具包");
        String appVer = "未知";
        try { appVer = h.a.getPackageManager().getPackageInfo(h.a.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        h.text(v, "当前客户端版本: " + appVer);
        TextView tvCli = h.text(v, "Claude CLI 版本: 检测中…");
        TextView tvStatus = h.text(v, "工具包状态: 点击下方检测");
        h.button(v, "查看开源仓库 (GitHub)", () -> h.a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bmbmm-114514/cccodroid"))));
        Runnable refresh = () -> h.get("/api/app/upd_status", r -> {
            JSONArray items = r.optJSONArray("items");
            if (items == null) { tvCli.setText(""); tvStatus.setText(r.optString("message", "工具包状态未知")); return; }
            StringBuilder status = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String version = item.optString("current");
                if ("claude".equals(item.optString("id"))) tvCli.setText("Claude Code " + version);
                status.append(item.optString("name")).append(" ").append(version).append(" · ").append(item.optString("state")).append("\n");
            }
            JSONObject verify = r.optJSONObject("verify");
            if (verify != null && !verify.optString("message").isEmpty()) status.append(verify.optString("message"));
            tvStatus.setText(status.toString().trim());
        });
        h.button(v, "刷新工具包状态", refresh);
        h.button(v, "校验工具包完整性", () -> h.post("/api/app/upd_verify", NativeHome.json("full", true), r -> {
            h.notice(r.optString("message", r.optBoolean("ok", true) ? "已开始校验" : "校验失败")); refresh.run();
        }));
        refresh.run();
    }

    private static void showTrouble(NativeHome h, LinearLayout v) {
        h.text(v, "故障排查与诊断");
        TextView tvOut = h.text(v, "就绪。点击下方按钮进行只读诊断或查看账本。");
        h.button(v, "运行系统诊断 (Doctor)", () -> {
            tvOut.setText("正在执行诊断…");
            h.get("/api/cli?cmd=doctor", r -> {
                if (r == null) return;
                String out = r.optString("out", ""), err = r.optString("err", "");
                String result = (out + "\n" + err).trim();
                String state = r.optBoolean("timeout") ? "诊断超时" : r.isNull("code") ? "未返回退出状态" : "退出码 " + r.optInt("code");
                tvOut.setText(result.isEmpty() ? state : state + "\n" + result);
            });
        });
        h.button(v, "查看修复账本 (Ledger)", () -> {
            tvOut.setText("正在读取账本…");
            h.get("/api/ledger?n=60", r -> {
                if (r == null) return;
                JSONArray list = r.optJSONArray("items");
                if (list == null) list = r.optJSONArray("ledger");
                if (list == null && r.has("list")) list = r.optJSONArray("list");
                if (list != null && list.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject it = list.optJSONObject(i);
                        if (it != null) sb.append(it.optBoolean("ok", true) ? "✓ " : "✗ ")
                            .append("[").append(it.optString("kind", "log")).append("] ")
                            .append(it.optString("msg", it.optString("message", ""))).append("\n");
                    }
                    tvOut.setText(sb.toString().trim());
                } else tvOut.setText(r.optString("message", "暂无账本记录"));
            });
        });
        h.button(v, "重置显示", () -> tvOut.setText("就绪。点击下方按钮进行只读诊断或查看账本。"));
    }
}
