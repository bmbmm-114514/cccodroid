package cc.codroid;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

public class NativeHistoryActions {

    public static void show(NativeHome h) {
        if (h == null || h.a == null) return;
        String[] options = {"搜索 Claude 历史", "导出所有 Claude 会话", "从文件导入 Claude 会话", "查看回收站与恢复"};
        PixelChoiceSheet.actions(h.a, "历史会话", java.util.Arrays.asList(options), which -> {
            if (which == 0) searchHistory(h);
            else if (which == 1) exportAllSessions(h);
            else if (which == 2) h.a.pickNativeHistory();
            else viewTrashAndRestore(h);
        });
    }

    public static void legacy(NativeHome h, LinearLayout parent, JSONObject item) {
        if (h == null || parent == null || item == null) return;
        String id = item.optString("id");
        if (id == null || id.isEmpty()) return;
        String title = item.optString("title", id);
        if (title == null || title.isEmpty()) title = id;

        final String finalTitle = title;
        Runnable more = () -> {
            String[] options = new String[]{"导出此会话", "移至回收站"};
            PixelChoiceSheet.actions(h.a, finalTitle, java.util.Arrays.asList(options), which -> {
                    if (which == 0) {
                        JSONArray ids = new JSONArray();
                        ids.put(id);
                        h.post("/api/sessions/export", NativeHome.json("ids", ids), res -> {
                            if (res != null && res.optBoolean("ok")) {
                                h.notice("已导出: " + res.optString("file"));
                            } else {
                                String msg = res != null ? res.optString("message", "导出失败") : "导出失败";
                                h.notice("导出失败: " + msg);
                            }
                        });
                    } else if (which == 1) {
                        h.confirm("确定将该会话移入回收站？", () -> {
                            JSONArray ids = new JSONArray();
                            ids.put(id);
                            h.post("/api/sessions/trash", NativeHome.json("ids", ids), res -> {
                                if (res != null && res.optBoolean("ok")) {
                                    JSONArray busy = res.optJSONArray("busy");
                                    JSONArray failed = res.optJSONArray("failed");
                                    if (busy != null && busy.length() > 0) {
                                        h.notice("会话正在生成中，无法移入回收站");
                                    } else if (failed != null && failed.length() > 0) {
                                        h.notice("移入回收站失败或已在回收站");
                                    } else {
                                        h.notice("已移入回收站");
                                        h.nav("sessions");
                                    }
                                } else {
                                    h.notice("移入回收站失败");
                                }
                            });
                        });
                    }
                });
        };
        parent.addView(sessionRow(h,title,"Claude Code", () -> h.openSession("claude:"+id),more));
    }

    static View sessionRow(NativeHome h, String title, String meta, Runnable open, Runnable more) {
        LinearLayout row = new LinearLayout(h.a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(h.dp(72));
        row.setPaddingRelative(h.dp(16),h.dp(8),h.dp(4),h.dp(8));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(PixelUi.color(h.a,"surfaceContainerHigh")),
                PixelUi.shape(h.a, "surfaceContainerLow", 16), null));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1,-2);
        rowLp.bottomMargin = h.dp(4);
        row.setLayoutParams(rowLp);
        row.setOnClickListener(v -> open.run());
        row.setOnLongClickListener(v -> { more.run(); return true; });
        row.setFocusable(true);

        LinearLayout labels = new LinearLayout(h.a);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(h.a);
        name.setText(title);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(PixelUi.color(h.a,"onSurface"));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
        name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        labels.addView(name);
        if (!meta.isEmpty()) {
            TextView detail = new TextView(h.a);
            detail.setPadding(0,h.dp(2),0,0);
            detail.setText(meta);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
            detail.setTextColor(PixelUi.color(h.a,"onSurfaceVariant"));
            labels.addView(detail);
        }
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(PixelUi.icon(h.a,"more","更多操作，" + title,more),new LinearLayout.LayoutParams(h.dp(48),h.dp(48)));
        return row;
    }

    private static void searchHistory(NativeHome h) {
        EditText input = new EditText(h.a);
        input.setHint("输入搜索关键字");
        input.setSingleLine(true);
        FrameLayout container = new FrameLayout(h.a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = h.dp(20);
        lp.rightMargin = h.dp(20);
        lp.topMargin = h.dp(10);
        lp.bottomMargin = h.dp(10);
        input.setLayoutParams(lp);
        container.addView(input);

        new MaterialAlertDialogBuilder(h.a)
            .setTitle("搜索 Claude 历史")
            .setView(container)
            .setPositiveButton("搜索", (dialog, which) -> {
                String q = input.getText().toString().trim();
                if (q.isEmpty()) return;
                try {
                    String url = "/api/sessions/search?q=" + URLEncoder.encode(q, "UTF-8");
                    h.get(url, res -> showSearchResults(h, q, res));
                } catch (Exception e) {
                    h.notice("搜索失败: " + e.getMessage());
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showSearchResults(NativeHome h, String q, JSONObject res) {
        if (res == null) {
            h.notice("搜索无响应");
            return;
        }
        JSONArray results = res.optJSONArray("results");
        if (results == null || results.length() == 0) {
            h.notice("未找到匹配会话");
            return;
        }
        int total = res.optInt("total", results.length());
        long ms = res.optLong("ms", 0);

        ScrollView scrollView = new ScrollView(h.a);
        LinearLayout listLayout = new LinearLayout(h.a);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(16));
        scrollView.addView(listLayout);

        AlertDialog dialog = new MaterialAlertDialogBuilder(h.a)
            .setTitle("搜索结果: " + q + " (" + total + " 条, " + ms + "ms)")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .create();

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            String title = item.optString("title", id);
            String snippet = item.optString("snippet");
            String cwd = item.optString("cwd");
            int count = item.optInt("count", 0);
            int sizeKb = item.optInt("sizeKb", 0);

            LinearLayout card = new LinearLayout(h.a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(h.dp(10), h.dp(8), h.dp(10), h.dp(8));
            card.setClickable(true);
            card.setFocusable(true);

            TypedValue outValue = new TypedValue();
            if (h.a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
                card.setBackgroundResource(outValue.resourceId);
            }

            TextView titleView = new TextView(h.a);
            String titleText = title + (count > 0 ? " (" + count + " 处匹配)" : "");
            titleView.setText(titleText);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            titleView.setTypeface(null, Typeface.BOLD);
            card.addView(titleView);

            if (snippet != null && !snippet.isEmpty()) {
                TextView snippetView = new TextView(h.a);
                snippetView.setText(snippet);
                snippetView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                snippetView.setMaxLines(3);
                snippetView.setEllipsize(TextUtils.TruncateAt.END);
                snippetView.setPadding(0, h.dp(4), 0, 0);
                card.addView(snippetView);
            }

            StringBuilder meta = new StringBuilder();
            if (cwd != null && !cwd.isEmpty()) {
                meta.append(cwd);
            }
            if (sizeKb > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(sizeKb).append(" KB");
            }
            if (meta.length() > 0) {
                TextView metaView = new TextView(h.a);
                metaView.setText(meta.toString());
                metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                metaView.setPadding(0, h.dp(2), 0, 0);
                card.addView(metaView);
            }

            card.setOnClickListener(v -> {
                dialog.dismiss();
                h.openSession("claude:" + id);
            });

            listLayout.addView(card);

            View divider = new View(h.a);
            divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0x1F888888);
            listLayout.addView(divider);
        }

        dialog.show();
    }

    private static void exportAllSessions(NativeHome h) {
        h.confirm("确定导出所有 Claude 会话？", () -> {
            h.notice("正在导出全部会话...");
            h.post("/api/sessions/export", NativeHome.json("all", true), res -> {
                if (res != null && res.optBoolean("ok")) {
                    int count = res.optInt("count", 0);
                    String file = res.optString("file", "");
                    h.notice("导出成功: 共 " + count + " 个会话\n" + file);
                } else {
                    String msg = res != null ? res.optString("message", "导出失败") : "导出失败";
                    h.notice("导出失败: " + msg);
                }
            });
        });
    }

    private static void viewTrashAndRestore(NativeHome h) {
        h.get("/api/trash", res -> {
            if (res == null) {
                h.notice("获取回收站失败");
                return;
            }
            JSONArray items = res.optJSONArray("items");
            int keepDays = res.optInt("keepDays", 30);
            if (items == null || items.length() == 0) {
                h.notice("回收站为空 (自动保留 " + keepDays + " 天)");
                return;
            }

            ScrollView scrollView = new ScrollView(h.a);
            LinearLayout listLayout = new LinearLayout(h.a);
            listLayout.setOrientation(LinearLayout.VERTICAL);
            listLayout.setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(16));
            scrollView.addView(listLayout);

            AlertDialog dialog = new MaterialAlertDialogBuilder(h.a)
                .setTitle("回收站 (" + items.length() + " 项，保留 " + keepDays + " 天)")
                .setView(scrollView)
                .setPositiveButton("全部恢复", (d, w) -> {
                    h.confirm("确定恢复回收站中的全部 " + items.length() + " 个会话？", () -> {
                        JSONArray allIds = new JSONArray();
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.optJSONObject(i);
                            if (it != null) allIds.put(it.optString("id"));
                        }
                        h.post("/api/trash/restore", NativeHome.json("ids", allIds), r -> {
                            if (r != null && r.optBoolean("ok")) {
                                JSONArray done = r.optJSONArray("done");
                                int doneCount = done != null ? done.length() : items.length();
                                h.notice("已恢复 " + doneCount + " 个会话");
                                h.nav("sessions");
                            } else {
                                h.notice("恢复失败");
                            }
                        });
                    });
                })
                .setNegativeButton("清空回收站", (d, w) -> {
                    h.confirm("确定彻底删除回收站中的全部会话？此操作不可撤销！", () -> {
                        h.post("/api/trash/purge", NativeHome.json("all", true), r -> {
                            if (r != null && r.optBoolean("ok")) {
                                int purged = r.optInt("purged", 0);
                                h.notice("已彻底清空 " + purged + " 个会话");
                                h.nav("sessions");
                            } else {
                                String msg = r != null ? r.optString("message", "清空失败") : "清空失败";
                                h.notice("清空失败: " + msg);
                            }
                        });
                    });
                })
                .setNeutralButton("关闭", null)
                .create();

            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String id = it.optString("id");
                String title = it.optString("title", id);
                if (title == null || title.isEmpty()) title = id;

                LinearLayout card = new LinearLayout(h.a);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(h.dp(10), h.dp(8), h.dp(10), h.dp(8));
                card.setClickable(true);
                card.setFocusable(true);

                TypedValue outValue = new TypedValue();
                if (h.a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
                    card.setBackgroundResource(outValue.resourceId);
                }

                TextView titleView = new TextView(h.a);
                titleView.setText(title);
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                titleView.setTypeface(null, Typeface.BOLD);
                card.addView(titleView);

                TextView idView = new TextView(h.a);
                idView.setText(id);
                idView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                idView.setPadding(0, h.dp(2), 0, 0);
                card.addView(idView);

                final String itemTitle = title;
                card.setOnClickListener(v -> {
                    String[] actions = new String[]{"恢复此会话", "彻底删除"};
                    PixelChoiceSheet.actions(h.a, itemTitle, java.util.Arrays.asList(actions), which -> {
                            if (which == 0) {
                                JSONArray ids = new JSONArray();
                                ids.put(id);
                                h.post("/api/trash/restore", NativeHome.json("ids", ids), r -> {
                                    if (r != null && r.optBoolean("ok")) {
                                        h.notice("已恢复会话");
                                        dialog.dismiss();
                                        h.nav("sessions");
                                    } else {
                                        h.notice("恢复失败");
                                    }
                                });
                            } else if (which == 1) {
                                h.confirm("确定彻底删除会话 \"" + itemTitle + "\"？此操作不可撤销！", () -> {
                                    JSONArray ids = new JSONArray();
                                    ids.put(id);
                                    h.post("/api/trash/purge", NativeHome.json("ids", ids), r -> {
                                        if (r != null && r.optBoolean("ok")) {
                                            h.notice("已彻底删除");
                                            dialog.dismiss();
                                            h.nav("sessions");
                                        } else {
                                            String msg = r != null ? r.optString("message", "删除失败") : "删除失败";
                                            h.notice("删除失败: " + msg);
                                        }
                                    });
                                });
                            }
                        });
                });

                listLayout.addView(card);

                View divider = new View(h.a);
                divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(0x1F888888);
                listLayout.addView(divider);
            }

            dialog.show();
        });
    }
}
