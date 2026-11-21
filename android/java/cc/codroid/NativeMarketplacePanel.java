package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public class NativeMarketplacePanel {
    public static void show(NativeHome h, LinearLayout root) {
        root.removeAllViews();

        LinearLayout marketSec = PixelUi.group(root);
        marketSec.setPadding(h.dp(12), h.dp(12), h.dp(12), h.dp(12));

        h.text(marketSec, "插件市场源");
        h.button(marketSec, "＋ 添加市场源", () -> {
            EditText et = new EditText(h.a);
            et.setHint("Git 仓库 URL 或本地路径");
            new MaterialAlertDialogBuilder(h.a)
                .setTitle("添加市场源")
                .setView(et)
                .setPositiveButton("添加", (d, w) -> {
                    String src = et.getText().toString().trim();
                    if (src.isEmpty()) return;
                    h.post("/api/ext/marketplaces/add", NativeHome.json("source", src), res -> {
                        if (!root.isAttachedToWindow()) return;
                        if (!res.optBoolean("ok", true)) {
                            h.notice(res.optString("message", "添加失败"));
                            return;
                        }
                        h.notice("已添加市场源");
                        show(h, root);
                    });
                })
                .setNegativeButton("取消", null)
                .show();
        });

        LinearLayout marketList = new LinearLayout(h.a);
        marketList.setOrientation(LinearLayout.VERTICAL);
        marketSec.addView(marketList);

        h.get("/api/ext/marketplaces", res -> {
            if (!root.isAttachedToWindow() || res == null) return;
            JSONArray items = res.optJSONArray("items");
            if (items == null || items.length() == 0) {
                h.text(marketList, "暂无已配置的市场源");
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject m = items.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "");
                String src = m.optString("source", "");
                LinearLayout row = new LinearLayout(h.a);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, h.dp(4), 0, h.dp(4));
                h.text(row, name + (src.isEmpty() ? "" : "\n" + src));
                h.button(row, "删除", () -> confirm(h, "删除市场源", "确定删除市场源「" + name + "」？", () -> {
                    h.post("/api/ext/marketplaces/remove", NativeHome.json("name", name), r -> {
                        if (!root.isAttachedToWindow()) return;
                        if (!r.optBoolean("ok", true)) {
                            h.notice(r.optString("message", "删除失败"));
                            return;
                        }
                        h.notice("已删除市场源");
                        show(h, root);
                    });
                }));
                marketList.addView(row);
            }
        });

        LinearLayout skillSec = PixelUi.group(root);
        skillSec.setPadding(h.dp(12), h.dp(12), h.dp(12), h.dp(12));

        h.text(skillSec, "技能市场目录");
        h.get("/api/ext/catalog/skills", res -> {
            if (!root.isAttachedToWindow() || res == null) return;
            if (!res.optBoolean("ok", true)) {
                h.text(skillSec, "加载失败: " + res.optString("message", "网络异常"));
                return;
            }
            String repo = res.optString("repo", "官方仓库");
            h.text(skillSec, "来源仓库: " + repo);
            JSONArray items = res.optJSONArray("items");
            if (items == null || items.length() == 0) {
                h.text(skillSec, "目录中无可用技能");
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject s = items.optJSONObject(i);
                if (s == null) continue;
                String dir = s.optString("dir", "");
                String name = s.optString("name", dir);
                String desc = s.optString("description", "");
                boolean installed = s.optBoolean("installed", false);
                boolean official = s.optBoolean("official", false);

                LinearLayout card = new LinearLayout(h.a);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(0, h.dp(6), 0, h.dp(6));

                String title = name + (official ? " [官方]" : "") + (installed ? " (已安装)" : "");
                h.text(card, title);
                if (!desc.isEmpty()) h.text(card, desc);
                if (!installed) {
                    h.button(card, "安装", () -> confirm(h, "安装技能", "确定从「" + repo + "」安装技能「" + name + "」？", () -> {
                        h.post("/api/ext/catalog/skills/install", NativeHome.json("dir", dir), r -> {
                            if (!root.isAttachedToWindow()) return;
                            if (!r.optBoolean("ok", true)) {
                                h.notice(r.optString("message", "安装失败"));
                                return;
                            }
                            h.notice("安装成功: " + name);
                            show(h, root);
                        });
                    }));
                }
                skillSec.addView(card);
            }
        });
    }

    private static void confirm(NativeHome h, String title, String msg, Runnable ok) {
        new MaterialAlertDialogBuilder(h.a)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定", (d, w) -> ok.run())
            .setNegativeButton("取消", null)
            .show();
    }
}
