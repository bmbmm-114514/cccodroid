package cc.codroid;

import android.app.AlertDialog;
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
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

public class NativeHistoryActions {

    public static void show(NativeHome h, View anchor) {
        if (h == null || h.a == null) return;
        if (anchor != null) {
            PopupMenu menu = new PopupMenu(h.a, anchor);
            menu.getMenu().add(0, 1, 0, "搜索 Claude 历史");
            menu.getMenu().add(0, 2, 0, "导出所有 Claude 会话");
            menu.getMenu().add(0, 3, 0, "从文件导入 Claude 会话");
            menu.getMenu().add(0, 4, 0, "查看回收站与恢复");
            menu.setOnMenuItemClickListener(item -> {
                int itemId = item.getItemId();
                if (itemId == 1) {
                    searchHistory(h);
                    return true;
                } else if (itemId == 2) {
                    exportAllSessions(h);
                    return true;
                } else if (itemId == 3) {
                    h.a.pickNativeHistory();
                    return true;
                } else if (itemId == 4) {
                    viewTrashAndRestore(h);
                    return true;
                }
                return false;
            });
            menu.show();
        } else {
            String[] options = new String[]{"搜索 Claude 历史", "导出所有 Claude 会话", "从文件导入 Claude 会话", "查看回收站与恢复"};
            new AlertDialog.Builder(h.a)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        searchHistory(h);
                    } else if (which == 1) {
                        exportAllSessions(h);
                    } else if (which == 2) {
                        h.a.pickNativeHistory();
                    } else if (which == 3) {
                        viewTrashAndRestore(h);
                    }
                })
                .show();
        }
    }

    public static void legacy(NativeHome h, LinearLayout parent, JSONObject item) {
        if (h == null || parent == null || item == null) return;
        String id = item.optString("id");
        if (id == null || id.isEmpty()) return;
        String title = item.optString("title", id);
        if (title == null || title.isEmpty()) title = id;

        Button btn = new Button(h.a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, h.dp(6));
        btn.setLayoutParams(lp);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setPadding(h.dp(12), h.dp(10), h.dp(12), h.dp(10));
        btn.setText(title);

        btn.setOnClickListener(v -> h.openSession("claude:" + id));

        final String finalTitle = title;
        btn.setOnLongClickListener(v -> {
            String[] options = new String[]{"导出此会话", "移至回收站"};
            new AlertDialog.Builder(h.a)
                .setTitle(finalTitle)
                .setItems(options, (dialog, which) -> {
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
                })
                .show();
            return true;
        });

        parent.addView(btn);
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

        new AlertDialog.Builder(h.a)
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

        AlertDialog dialog = new AlertDialog.Builder(h.a)
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

            AlertDialog dialog = new AlertDialog.Builder(h.a)
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
                    new AlertDialog.Builder(h.a)
                        .setTitle(itemTitle)
                        .setItems(actions, (itemDlg, which) -> {
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
                        })
                        .show();
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
