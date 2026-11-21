package cc.codroid;

import android.util.ArrayMap;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;

final class NativeEnergyPanel {
    private static final String[][] PROVIDERS = {
        {"claude", "Claude Code"}, {"codex", "Codex"}, {"agy", "Antigravity"}
    };
    private static final String[][] PERIODS = {
        {"hour", "小时", "近 24 小时"}, {"day", "日", "近 14 天"}, {"week", "周", "近 12 周"}
    };

    static void addQuota(NativeHome h, LinearLayout page) {
        LinearLayout quota = NativeStatusViews.section(page, "订阅剩余额度");
        NativeStatusViews.detail(quota, "正在读取额度…");
        h.req("GET", "/api/multi/state", null, res -> renderQuota(quota, res.optJSONObject("quota")),
                error -> message(quota, "无法读取额度 · " + error));
    }

    static void add(NativeHome h, LinearLayout page) { new NativeEnergyPanel(h, page); }

    private final NativeHome h;
    private final LinearLayout battery, samples, bill;
    private final ArrayMap<String, JSONArray> periods = new ArrayMap<>();
    private final TextView historyStatus;
    private int displayedPeriod = -1;
    private String displayedBattery, displayedBill;
    private int energyRequest, billRequest;
    private final PixelSegmentedTabs picker;

    private NativeEnergyPanel(NativeHome h, LinearLayout page) {
        this.h = h;
        battery = NativeStatusViews.section(page, "设备电池");
        LinearLayout history = NativeStatusViews.section(page, "耗电记录");
        picker = new PixelSegmentedTabs(h.a, true, this::loadEnergy);
        picker.labels(Arrays.asList(PERIODS[0][1], PERIODS[1][1], PERIODS[2][1]));
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
        bounds.setMargins(h.dp(12), h.dp(8), h.dp(12), 0);
        history.addView(picker.view, bounds);
        historyStatus = NativeStatusViews.detail(history, "");
        historyStatus.setVisibility(View.GONE);
        historyStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        samples = new LinearLayout(h.a);
        samples.setOrientation(LinearLayout.VERTICAL);
        history.addView(samples, -1, -2);

        LinearLayout system = NativeStatusViews.section(page, "系统耗电账单");
        PixelUi.row(system, "usage", "查询系统耗电账单", "自上次充满电起", this::loadBill);
        bill = new LinearLayout(h.a);
        bill.setOrientation(LinearLayout.VERTICAL);
        system.addView(bill, -1, -2);

        loadEnergy(1);
    }

    private static void message(LinearLayout panel, String text) {
        panel.removeAllViews();
        NativeStatusViews.detail(panel, text);
    }

    private static void renderQuota(LinearLayout card, JSONObject quota) {
        card.removeAllViews();
        long now = System.currentTimeMillis();
        for (String[] provider : PROVIDERS) {
            JSONArray windows = quota == null ? null : quota.optJSONArray(provider[0]);
            boolean listed = false;
            if (windows != null) for (int i=0; i<windows.length(); i++) {
                JSONObject window = windows.optJSONObject(i);
                if (window == null) continue;
                if (!listed) {
                    TextView title = NativeStatusViews.detail(card, provider[1]);
                    title.setTextColor(PixelUi.color(card.getContext(), "primary"));
                }
                listed = true;
                long reset = window.optLong("resetsAt", 0);
                boolean expired = reset > 0 && reset <= now;
                String name = window.optString("name");
                NativeStatusViews.quota(card, name.isEmpty() ? "当前周期" : name,
                        expired ? Double.NaN : window.optDouble("remaining", Double.NaN), expired ? 0 : reset);
            }
            if (!listed) NativeStatusViews.metric(card, provider[1], "未知");
        }
    }

    private void loadEnergy(int index) {
        String[] period = PERIODS[index];
        picker.select(index, true);
        for (int i = 0; i < PERIODS.length; i++) picker.view.getChildAt(i).setContentDescription(PERIODS[i][2]);
        final int ticket = ++energyRequest;
        String key = period[0];
        JSONArray cached = periods.get(key);
        historyStatus.setOnClickListener(null);
        historyStatus.setClickable(false);
        historyStatus.setVisibility(View.GONE);
        if (cached != null && displayedPeriod != index) renderSamples(index, cached);
        if (displayedPeriod < 0) {
            historyStatus.setText("正在读取" + period[2] + "的记录…");
            historyStatus.setVisibility(View.VISIBLE);
        }
        if (battery.getChildCount() == 0) message(battery, "正在读取电池状态…");
        h.req("GET", "/api/energy?view=" + key, null, data -> {
            if (ticket != energyRequest) return;
            if (!data.optBoolean("ok")) {
                historyError(index, data.optString("message", "无法读取耗电记录"));
                return;
            }
            historyStatus.setVisibility(View.GONE);
            JSONArray bars = data.optJSONArray("bars");
            if (bars == null) bars = new JSONArray();
            JSONArray previous = periods.put(key, bars);
            if (displayedPeriod != index || previous == null || !bars.toString().equals(previous.toString())) {
                renderSamples(index, bars);
            }
            JSONObject state = data.optJSONObject("battery");
            String batteryKey = String.valueOf(state);
            if (!batteryKey.equals(displayedBattery)) {
                displayedBattery = batteryKey;
                renderBattery(state);
            }
            if (billRequest == 0 && data.optJSONObject("sys") != null) renderBill(data.optJSONObject("sys"));
        }, error -> {
            if (ticket == energyRequest) historyError(index, error);
        });
    }

    private void historyError(int index, String error) {
        historyStatus.setOnClickListener(v -> loadEnergy(index));
        historyStatus.setText(PERIODS[index][2] + " · " + error + " · 点按重试");
        historyStatus.setVisibility(View.VISIBLE);
        if (displayedBattery == null) message(battery, "电池状态未知");
    }

    private static String number(double value, String unit) {
        if (!Double.isFinite(value)) return "未知";
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(1);
        return format.format(value) + unit;
    }

    private void renderBattery(JSONObject state) {
        if (state == null) { message(battery, "暂无电池采样"); return; }
        battery.removeAllViews();
        int level = state.optInt("level", -1);
        NativeStatusViews.metric(battery, "电池电量", level < 0 || level > 100 ? "未知" : level + "%");
        if (!state.isNull("charging")) NativeStatusViews.metric(battery, "充电状态", state.optBoolean("charging") ? "充电中" : "放电中");
        NativeStatusViews.metric(battery, "实时电流", number(state.optDouble("currentUa", Double.NaN) / 1000.0, " mA"));
        NativeStatusViews.metric(battery, "电池温度", number(state.optDouble("temp", Double.NaN), " ℃"));
        if (!state.isNull("screen")) NativeStatusViews.metric(battery, "屏幕状态", state.optBoolean("screen") ? "亮屏" : "息屏");
    }

    private void renderSamples(int index, JSONArray bars) {
        String[] period = PERIODS[index];
        if (displayedPeriod >= 0 && displayedPeriod != index) {
            PixelSegmentedTabs.slideContent(samples, index - displayedPeriod);
        }
        displayedPeriod = index;
        samples.removeAllViews();
        NativeStatusViews.detail(samples, period[2]);
        if (bars == null || bars.length() == 0) { NativeStatusViews.detail(samples, "暂无采样"); return; }
        for (int i = bars.length() - 1; i >= Math.max(0, bars.length() - 6); i--) {
            JSONObject bar = bars.optJSONObject(i);
            if (bar == null) continue;
            String name = "hour".equals(period[0]) && bar.optLong("t", 0) > 0
                    ? DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(bar.optLong("t")))
                    : bar.optString("t", "未知时段");
            if (bar.isNull("c") && bar.isNull("s") && bar.isNull("f") && bar.isNull("d")) {
                TextView value = NativeStatusViews.metric(samples, name, "暂无采样");
                ((View) value.getParent()).setMinimumHeight(h.dp(56));
                continue;
            }
            PixelUi.row(samples, "schedule", name, "", () -> showSample(name, bar));
        }
    }

    private void showSample(String name, JSONObject bar) {
        LinearLayout content = new LinearLayout(h.a);
        content.setOrientation(LinearLayout.VERTICAL);
        NativeStatusViews.metric(content, "应用 CPU", number(bar.optDouble("c", Double.NaN), " 秒"));
        NativeStatusViews.metric(content, "前台", number(bar.optDouble("f", Double.NaN), " 秒"));
        NativeStatusViews.metric(content, "亮屏", number(bar.optDouble("s", Double.NaN), " 秒"));
        NativeStatusViews.metric(content, "整机掉电", number(bar.optDouble("d", Double.NaN), "%"));
        new MaterialAlertDialogBuilder(h.a).setTitle(name).setView(content).setPositiveButton("关闭", null).show();
    }

    private void loadBill() {
        final int ticket = ++billRequest;
        displayedBill = null;
        message(bill, "正在向系统查询…");
        h.req("POST", "/api/energy/system", NativeHome.json(), result -> {
            if (ticket != billRequest) return;
            JSONObject report = result.optJSONObject("sys");
            if (result.optBoolean("ok") && report != null) renderBill(report);
            else message(bill, result.optString("message", "无法读取系统耗电统计"));
        }, error -> {
            if (ticket == billRequest) message(bill, "查询失败 · " + error);
        });
    }

    private void renderBill(JSONObject report) {
        String signature = report.toString();
        if (signature.equals(displayedBill)) return;
        displayedBill = signature;
        bill.removeAllViews();
        JSONObject me = report.optJSONObject("me");
        if (me == null) NativeStatusViews.detail(bill, "本应用尚无耗电记录");
        else {
            NativeStatusViews.metric(bill, "本应用耗电", number(me.optDouble("mah", Double.NaN), " mAh"));
            if (!me.isNull("rank") && !report.isNull("apps"))
                NativeStatusViews.metric(bill, "应用排名", me.optInt("rank") + " / " + report.optInt("apps"));
            NativeStatusViews.metric(bill, "其中屏幕耗电", number(me.optDouble("screen", Double.NaN), " mAh"));
        }
        NativeStatusViews.metric(bill, "整机耗电", number(report.optDouble("used", Double.NaN), " mAh"));
        long since = report.optLong("sinceMs", -1);
        if (since >= 0) NativeStatusViews.metric(bill, "统计时长", android.text.format.DateUtils.formatElapsedTime(since / 1000));
        JSONArray top = report.optJSONArray("top");
        if (top == null || top.length() == 0) return;
        NativeStatusViews.detail(bill, "耗电前列");
        for (int i=0; i<Math.min(3, top.length()); i++) {
            JSONObject app = top.optJSONObject(i);
            if (app != null) NativeStatusViews.metric(bill, app.optString("name","应用"), number(app.optDouble("mah", Double.NaN), " mAh"));
        }
    }
}
