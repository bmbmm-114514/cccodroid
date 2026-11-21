package cc.codroid;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static cc.codroid.PixelUi.dp;

/** Shared by the native update page and the classic theme's update sheet. */
final class NativeAgentUpdates implements View.OnAttachStateChangeListener {
    private static final String ENGINES = "/api/multi/engine-updates";
    private static final String CLAUDE = "/api/app/upd_status";

    static void show(MainActivity activity) {
        Context ui = new ContextThemeWrapper(activity, NativeAppearance.theme(activity));
        LinearLayout rows = new LinearLayout(ui);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(ui, 16), 0, dp(ui, 16), 0);
        attach(activity, rows);
        PixelChoiceSheet.confirm(ui, "Agent 更新", rows, "完成", () -> {});
    }

    static void attach(MainActivity activity, LinearLayout parent) {
        new NativeAgentUpdates(activity, parent);
    }

    private final LinearLayout root;
    private final MainActivity activity;
    private final Row[] rows;
    private final int[] revision = {0, 0};
    private final Runnable[] poll = {() -> read(0), () -> read(1)};
    private int mount;

    private NativeAgentUpdates(MainActivity activity, LinearLayout parent) {
        this.activity = activity;
        root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        rows = new Row[] {new Row("claude", "Claude Code", 0), new Row("codex", "Codex", 1), new Row("agy", "Antigravity", 1)};
        root.addOnAttachStateChangeListener(this);
        parent.addView(root, -1, -2);
    }

    @Override public void onViewAttachedToWindow(View view) { read(0); read(1); }
    @Override public void onViewDetachedFromWindow(View view) {
        mount++;
        for (Runnable task : poll) root.removeCallbacks(task);
        for (Row row : rows) row.pending = false;
    }

    private void read(int feed) {
        if (!root.isAttachedToWindow()) return;
        int page = mount, seq = ++revision[feed];
        root.removeCallbacks(poll[feed]);
        String key = feed == 0 ? "id" : "agent";
        Consumer<String> failed = message -> {
            if (page != mount || seq != revision[feed]) return;
            for (Row row : rows) if (row.feed == feed && !row.pending) row.fail(message);
        };
        NativeRequests.call(activity, "GET", feed == 0 ? CLAUDE : ENGINES, null, response -> {
            if (page != mount || seq != revision[feed]) return;
            JSONArray items = response.optJSONArray("items");
            if (items == null) { failed.accept(response.optString("message", "无法读取版本状态")); return; }
            boolean busy = false;
            for (Row row : rows) {
                if (row.feed != feed || row.pending) continue;
                JSONObject item = null;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject candidate = items.optJSONObject(i);
                    if (candidate != null && row.id.equals(candidate.optString(key))) { item = candidate; break; }
                }
                if (item == null) { row.fail("无法读取版本状态"); continue; }
                row.current = item.optString("current");
                row.latest = item.optString("latest");
                row.state = item.optString("state");
                row.message = item.optString("message");
                if (feed == 0 && response.optBoolean("checking") && !row.installing()) row.state = "checking";
                row.bind();
                busy |= row.busy();
            }
            if (busy) root.postDelayed(poll[feed], 1200);
        }, failed);
    }

    private void act(Row row, boolean install) {
        if (!root.isAttachedToWindow() || row.pending || row.busy() || (install && !row.available())) return;
        JSONObject data = row.feed == 0 ? NativeHome.json("item", "claude")
                : NativeHome.json("agent", row.id, "action", install ? "install" : "check");
        if (install) {
            try {
                data.put("version", row.latest);
                if (row.feed != 0) data.put("ticket", EngineUpdatePins.begin(row.id, row.latest));
            } catch (IllegalStateException e) { row.fail(e.getMessage()); return; }
            catch (Exception e) { row.fail("更新版本无效，请重新检查"); return; }
        }
        String route = row.feed == 0 ? (install ? "/api/app/upd_apply" : "/api/app/upd_check") : ENGINES;
        row.pending = true;
        row.state = install ? "installing" : "checking";
        row.message = "";
        row.bind();
        int page = mount;
        Consumer<String> failed = msg -> {
            if (page == mount) { row.pending = false; row.fail(msg); }
        };
        NativeRequests.call(activity, "POST", route, data, result -> {
            if (page != mount) return;
            if (!result.optBoolean("ok")) { failed.accept(result.optString("message", "请求失败")); return; }
            row.pending = false;
            read(row.feed);
        }, failed);
    }

    private final class Row {
        final String id;
        final int feed;
        final TextView version, detail;
        final LinearProgressIndicator progress;
        final MaterialButton check, update;
        String current = "", latest = "", state = "idle", message = "";
        boolean pending;

        Row(String id, String name, int feed) {
            this.id = id;
            this.feed = feed;
            Context ui = root.getContext();
            LinearLayout card = PixelUi.group(root);
            card.setPaddingRelative(dp(ui, 18), dp(ui, 16), dp(ui, 18), dp(ui, 10));
            card.addView(PixelUi.label(ui, name, 20, "onSurface"));
            version = PixelUi.label(ui, "", 14, "onSurfaceVariant");
            version.setPadding(0, dp(ui, 6), 0, dp(ui, 4));
            card.addView(version);
            detail = PixelUi.label(ui, "", 14, "onSurfaceVariant");
            detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            card.addView(detail);
            progress = new LinearProgressIndicator(ui);
            progress.setIndeterminate(true);
            card.addView(progress, -1, -2);
            check = PixelUi.button(ui, "检查更新", () -> act(this, false));
            update = PixelUi.button(ui, "更新", () -> act(this, true));
            for (MaterialButton button : new MaterialButton[]{check, update}) {
                button.setMinHeight(dp(ui, 48));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.gravity = Gravity.END;
                card.addView(button, params);
            }
            bind();
        }

        boolean installing() { return "installing".equals(state) || "downloading".equals(state); }
        boolean busy() { return installing() || "checking".equals(state); }
        boolean available() { return !latest.isEmpty() && ("available".equals(state) || "missing".equals(state)); }
        void fail(String error) { state = "error"; message = error; bind(); }
        void bind() {
            String v = current.isEmpty() ? ("missing".equals(state) ? "尚未安装" : "当前版本未知") : "当前版本 " + current;
            if (available()) v += "   →   " + latest;
            set(version, v);
            String status = message;
            if (busy()) status = installing() ? "正在更新…" : "正在检查…";
            else if ("current".equals(state) || "uptodate".equals(state)) status = message.isEmpty() ? "已是最新版本" : message;
            else if ("error".equals(state) && status.isEmpty()) status = "检查失败，请重试";
            set(detail, status);
            detail.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            progress.setVisibility(busy() ? View.VISIBLE : View.GONE);
            check.setEnabled(!pending && !busy());
            update.setEnabled(!pending && available());
            update.setVisibility(available() ? View.VISIBLE : View.GONE);
            set(update, "missing".equals(state) ? "安装 " + latest : "更新到 " + latest);
        }
        void set(TextView view, String value) {
            if (!value.contentEquals(view.getText())) view.setText(value);
        }
    }
}
