package cc.codroid;

import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class NativeLocalUsage {
    private NativeLocalUsage() {}

    static void add(NativeHome h, LinearLayout page) {
        final JSONObject[] usHolder = new JSONObject[1];

        LinearLayout card = NativeStatusViews.section(page, "Claude Code 本地用量");
        TextView tvStatus = NativeStatusViews.detail(card, "正在读取用量…");

        TextView tvInput = NativeStatusViews.metric(card, "输入 Tokens", "—");
        TextView tvOutput = NativeStatusViews.metric(card, "输出 Tokens", "—");
        TextView tvCache = NativeStatusViews.metric(card, "缓存 Tokens", "—");
        TextView tvTurns = NativeStatusViews.metric(card, "累计轮次", "—");
        TextView tvBins = NativeStatusViews.metric(card, "统计时段", "—");

        PixelUi.row(card, "history", "时段明细", "", () -> {
            JSONObject us = usHolder[0];
            JSONObject hours = us != null ? us.optJSONObject("hours") : null;
            if (hours == null || hours.length() == 0) {
                h.notice("暂无时段用量记录");
                return;
            }
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = hours.keys(); it.hasNext(); ) {
                String key = it.next();
                if (key.matches("[0-9]{1,10}")) {
                    keys.add(key);
                }
            }
            if (keys.isEmpty()) {
                h.notice("暂无有效时段用量记录");
                return;
            }
            Collections.sort(keys, (a, b) -> Long.compare(Long.parseLong(b), Long.parseLong(a)));

            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:00", Locale.getDefault());
            for (String k : keys) {
                JSONObject b = hours.optJSONObject(k);
                if (b == null) continue;
                long v = Long.parseLong(k);
                long ms = v * 3600000L;
                sb.append("\n• ").append(sdf.format(new Date(ms))).append("\n")
                  .append("  输入: ").append(b.optLong("i")).append(" | 输出: ").append(b.optLong("o"))
                  .append(" | 缓存: ").append(b.optLong("c")).append(" | 次数: ").append(b.optLong("t"));
            }
            NativeRuntimePanel.dialog(h, "时段用量明细", sb.toString());
        });

        PixelUi.row(card, "update", "导入记录", "从历史会话补充统计本地 Token 用量", () -> {
            new MaterialAlertDialogBuilder(h.a)
                .setTitle("导入历史用量")
                .setMessage("将从历史会话补充统计本地 Token 用量，是否继续？")
                .setPositiveButton("确定", (d, w) -> h.post("/api/usage/import", new JSONObject(), r -> {
                    h.notice("历史用量导入完成");
                    h.nav("usage");
                }))
                .setNegativeButton("取消", null)
                .show();
        });

        PixelUi.row(card, "tune", "用量统计", "启用或暂停记录", () -> {
            JSONObject us = usHolder[0];
            if (us == null) {
                h.notice("请等待读取统计状态");
                return;
            }
            boolean off = us.optBoolean("off");
            new MaterialAlertDialogBuilder(h.a)
                .setTitle("用量统计开关")
                .setMessage("当前状态：" + (off ? "已暂停" : "正常记录") + "。\n确认" + (off ? "启用" : "暂停") + "用量统计？")
                .setPositiveButton("确定", (d, w) -> h.post("/api/usage/set", NativeHome.json("off", !off), r -> {
                    h.notice("统计设置已变更");
                    h.nav("usage");
                }))
                .setNegativeButton("取消", null)
                .show();
        });

        PixelUi.row(card, "book", "事件账本", "运行事件与审计日志", () -> h.get("/api/ledger?n=60", r -> {
            JSONArray items = r != null ? r.optJSONArray("items") : null;
            if (items == null || items.length() == 0) {
                h.notice("账本中暂无记录");
                return;
            }
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                sb.append("[").append(sdf.format(new Date(item.optLong("at")))).append("] ")
                  .append(item.optBoolean("ok", true) ? "✓ " : "✗ ")
                  .append(item.optString("kind")).append("\n")
                  .append(item.optString("msg")).append("\n\n");
            }
            NativeRuntimePanel.dialog(h, "事件账本 (Ledger)", sb.toString());
        }));

        h.req("GET", "/api/usage", null, us -> {
            if (us == null) return;
            usHolder[0] = us;
            boolean off = us.optBoolean("off");
            tvStatus.setText(off ? "已暂停统计" : "正在记录");

            JSONObject hours = us.optJSONObject("hours");
            long inTok = 0;
            long outTok = 0;
            long cacheTok = 0;
            long turns = 0;
            int count = 0;

            if (hours != null) {
                for (Iterator<String> it = hours.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (!key.matches("[0-9]{1,10}")) continue;
                    JSONObject b = hours.optJSONObject(key);
                    if (b != null) {
                        inTok += b.optLong("i");
                        outTok += b.optLong("o");
                        cacheTok += b.optLong("c");
                        turns += b.optLong("t");
                        count++;
                    }
                }
            }

            NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
            tvInput.setText(nf.format(inTok));
            tvOutput.setText(nf.format(outTok));
            tvCache.setText(nf.format(cacheTok));
            tvTurns.setText(nf.format(turns));
            tvBins.setText(nf.format(count));
        }, error -> tvStatus.setText("无法读取用量 · " + error));
    }
}
