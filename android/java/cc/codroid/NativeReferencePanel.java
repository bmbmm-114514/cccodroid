package cc.codroid;

import android.content.pm.PackageInfo;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class NativeReferencePanel {

    static class Item {
        final String title, sub, content;
        final int level;
        Item(String t, String s, String c, int l) {
            title = t; sub = s; content = c; level = l;
        }
        String label(boolean isHelp) {
            if (!isHelp) return title + (sub.isEmpty() ? "" : " (" + sub + ")");
            return (level <= 1 ? "§ " : (level == 2 ? "  • " : "    - ")) + title;
        }
    }

    private static String readAsset(NativeHome h, String path) {
        try (InputStream is = h.a.getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Item> parseHelp(NativeHome h) {
        List<Item> list = new ArrayList<>();
        list.add(new Item("在同一对话切换模型", "",
            "应用内置 Claude Code、Codex 和 Antigravity（agy）。打开设置中的「接口与模型」，选择 Claude、ChatGPT 或 Google 登录，再选该接口提供的型号和思考强度。\n\n"
            + "聊天时也可从输入框切换接口。下面的上游指南保留了经典界面的功能说明。", 1));
        String raw = readAsset(h, "app/web/help/zh.md");
        if (!raw.isEmpty()) {
            String curTitle = "概述";
            int curLevel = 1;
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                int lvl = line.startsWith("### ") ? 3 : (line.startsWith("## ") ? 2 : (line.startsWith("# ") ? 1 : 0));
                if (lvl > 0) {
                    if (sb.length() > 0) {
                        list.add(new Item(curTitle, "第 " + curLevel + " 级章节", sb.toString().trim(), curLevel));
                        sb.setLength(0);
                    }
                    curLevel = lvl;
                    curTitle = line.substring(lvl).trim();
                } else {
                    sb.append(line).append('\n');
                }
            }
            if (sb.length() > 0) list.add(new Item(curTitle, "第 " + curLevel + " 级章节", sb.toString().trim(), curLevel));
        }
        if (list.size() == 1) list.add(new Item("默认说明", "说明", "未找到本地帮助文档资源。", 1));
        return list;
    }

    private static List<Item> parseChangelog(NativeHome h) {
        List<Item> list = new ArrayList<>();
        String raw = readAsset(h, "app/web/changelog/zh.json").trim();
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw).optJSONArray("entries");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        list.add(new Item(o.optString("v", "版本"), o.optString("date", ""), o.optString("md", ""), 1));
                    }
                }
            } catch (Exception ignored) {}
        }
        if (list.isEmpty()) list.add(new Item("暂无记录", "", "本地未检测到更新日志文件。", 1));
        return list;
    }

    public static void show(NativeHome h, String id) {
        final boolean isHelp = "help".equals(id);
        LinearLayout root = h.scrolling();

        String ver = "未知";
        try {
            PackageInfo pi = h.a.getPackageManager().getPackageInfo(h.a.getPackageName(), 0);
            ver = pi.versionName != null ? pi.versionName : String.valueOf(pi.versionCode);
        } catch (Exception ignored) {}

        TextView verTv = h.text(root, "当前安装应用版本: " + ver);
        verTv.setTextSize(13);
        verTv.setPadding(0, 0, 0, h.dp(8));

        final List<Item> allItems = isHelp ? parseHelp(h) : parseChangelog(h);
        final List<Item> visible = new ArrayList<>(allItems);
        final int[] sel = new int[]{0};

        EditText search = new EditText(h.a);
        search.setHint(isHelp ? "搜索章节标题或内容..." : "搜索版本号或更新说明...");
        search.setSingleLine(true);
        root.addView(search);

        TextView countTv = h.text(root, "");
        countTv.setTextSize(13);
        countTv.setPadding(0, h.dp(4), 0, h.dp(4));

        Spinner spinner = new Spinner(h.a);
        root.addView(spinner);

        LinearLayout stepRow = new LinearLayout(h.a);
        stepRow.setOrientation(LinearLayout.VERTICAL);
        root.addView(stepRow);

        LinearLayout detail = PixelUi.group(root);
        detail.setPadding(h.dp(16), h.dp(16), h.dp(16), h.dp(16));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(h.a, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        spinner.setAdapter(adapter);
        final boolean[] updating = new boolean[]{false};

        Runnable render = () -> {
            detail.removeAllViews();
            if (visible.isEmpty()) {
                h.text(detail, "未匹配到任何内容，请更换关键词。");
                return;
            }
            Item it = visible.get(sel[0]);
            TextView tit = h.text(detail, it.title);
            tit.setTextSize(18);
            tit.setPadding(0, 0, 0, h.dp(4));
            if (!it.sub.isEmpty()) {
                TextView st = h.text(detail, (isHelp ? "层级: " : "日期: ") + it.sub);
                st.setTextSize(13);
                st.setPadding(0, 0, 0, h.dp(6));
            }
            TextView tv = new TextView(h.a);
            tv.setText(it.content);
            tv.setTextColor(PixelUi.color(h.a, "onSurface"));
            tv.setLinkTextColor(PixelUi.color(h.a, "primary"));
            tv.setTextSize(15);
            tv.setLineSpacing(h.dp(2), 1.15f);
            tv.setAutoLinkMask(Linkify.WEB_URLS);
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            detail.addView(tv);
        };

        Runnable refresh = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (Item it : allItems) {
                if (q.isEmpty() || it.title.toLowerCase(Locale.ROOT).contains(q)
                        || it.sub.toLowerCase(Locale.ROOT).contains(q)
                        || it.content.toLowerCase(Locale.ROOT).contains(q)) {
                    visible.add(it);
                }
            }
            List<String> labels = new ArrayList<>();
            for (Item it : visible) labels.add(it.label(isHelp));
            updating[0] = true;
            adapter.clear();
            adapter.addAll(labels);
            adapter.notifyDataSetChanged();
            if (sel[0] >= visible.size()) sel[0] = Math.max(0, visible.size() - 1);
            if (!visible.isEmpty()) {
                spinner.setSelection(sel[0]);
                countTv.setText(String.format(Locale.CHINESE, "共 %d 项 (当前第 %d 项)", visible.size(), sel[0] + 1));
            } else {
                countTv.setText("未匹配到相关内容");
            }
            render.run();
            updating[0] = false;
        };

        h.button(stepRow, "◀ 上一项", () -> {
            if (sel[0] > 0) {
                sel[0]--;
                spinner.setSelection(sel[0]);
            }
        });
        h.button(stepRow, "下一项 ▶", () -> {
            if (sel[0] < visible.size() - 1) {
                sel[0]++;
                spinner.setSelection(sel[0]);
            }
        });

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (updating[0] || pos < 0 || pos >= visible.size()) return;
                sel[0] = pos;
                countTv.setText(String.format(Locale.CHINESE, "共 %d 项 (当前第 %d 项)", visible.size(), pos + 1));
                render.run();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int b, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int b, int c, int a) {
                sel[0] = 0;
                refresh.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        refresh.run();
    }
}
