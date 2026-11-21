package cc.codroid;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

public class NativeHome {
    final MainActivity a;
    final LinearLayout root;
    final LinearLayout toolbar;
    final TextView titleView;
    final LinearLayout body;
    final Runnable chooseTheme;
    final NativeChat chat;
    final NativeFiles files = new NativeFiles();

    String screen = "";
    int generation = 0;
    String chatId = "";
    String projectId = "";

    private final Deque<String> history = new ArrayDeque<>();
    private boolean isNavigatingBack = false;
    private boolean disposed = false;
    private boolean paused;

    public NativeHome(MainActivity a, Runnable chooseTheme) {
        this.a = a;
        this.chooseTheme = chooseTheme;
        this.chat = new NativeChat(this);

        this.root = new LinearLayout(a);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        this.root.setFitsSystemWindows(true);

        this.toolbar = new LinearLayout(a);
        this.toolbar.setOrientation(LinearLayout.HORIZONTAL);
        this.toolbar.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = isTablet() ? dp(24) : dp(16);
        this.toolbar.setPadding(hPad, dp(4), hPad, dp(4));

        TypedValue abSizeVal = new TypedValue();
        int abHeight = dp(56);
        if (a.getTheme().resolveAttribute(android.R.attr.actionBarSize, abSizeVal, true)) {
            abHeight = TypedValue.complexToDimensionPixelSize(abSizeVal.data, a.getResources().getDisplayMetrics());
        }
        this.toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, abHeight));

        Button navBtn = new Button(a, null, android.R.attr.borderlessButtonStyle);
        navBtn.setText("菜单");
        navBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        navBtn.setOnClickListener(v -> showNavPopup(navBtn));
        this.toolbar.addView(navBtn);

        this.titleView = new TextView(a);
        this.titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        this.titleView.setSingleLine(true);
        this.titleView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleLp.setMargins(dp(8), 0, dp(8), 0);
        this.titleView.setLayoutParams(titleLp);
        this.titleView.setText(getScreenTitle("chat"));
        this.toolbar.addView(this.titleView);

        Button settingsBtn = new Button(a, null, android.R.attr.borderlessButtonStyle);
        settingsBtn.setText("设置");
        settingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        settingsBtn.setOnClickListener(v -> nav("settings"));
        this.toolbar.addView(settingsBtn);

        View divider = new View(a);
        int divH = Math.max(1, dp(1));
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, divH));
        TypedValue divColor = new TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.dividerHorizontal, divColor, true)) {
            divider.setBackgroundResource(divColor.resourceId);
        } else if (a.getTheme().resolveAttribute(android.R.attr.colorBackground, divColor, true)) {
            divider.setBackgroundColor(divColor.data);
        }

        this.body = new LinearLayout(a);
        this.body.setOrientation(LinearLayout.VERTICAL);
        this.body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        this.root.addView(this.toolbar);
        this.root.addView(divider);
        this.root.addView(this.body);

        a.setContentView(this.root);
        chatId = a.getSharedPreferences("native-ui", 0).getString("conversation", "");
        projectId = a.getSharedPreferences("native-ui", 0).getString("project", "");
        nav("chat");
    }

    public void nav(String id) {
        if (disposed || id == null || id.isEmpty()) return;

        if ("provider".equals(id)) {
            ProviderSettingsDialog.show(a, () -> { if ("chat".equals(screen)) chat.refreshSelection(); });
            return;
        }

        if (!isNavigatingBack && screen != null && !screen.isEmpty() && !screen.equals(id)) {
            history.push(screen);
        }

        files.leave();
        chat.leave();
        this.screen = id;
        this.generation++;
        renderScreen(id);
    }

    public void showChat(String sharedId) {
        chatId = sharedId == null ? "" : sharedId;
        remember();
        nav("chat");
    }

    private void renderScreen(String id) {
        if (disposed) return;
        this.titleView.setText(getScreenTitle(id));
        this.body.removeAllViews();

        if ("chat".equals(id) || "sessions".equals(id)) {
            chat.show(id);
        } else if ("files".equals(id) || "terminal".equals(id) || "git".equals(id) || "editor".equals(id)) {
            files.show(this, id);
        } else {
            NativePanels.show(this, id);
        }
    }

    void remember() { a.getSharedPreferences("native-ui", 0).edit().putString("conversation", chatId).putString("project", projectId).apply(); }

    void pause() {
        remember();
        if (paused) return;
        paused = true;
        if ("chat".equals(screen) || "terminal".equals(screen)) {
            generation++;
            chat.leave();
            files.leave();
        }
    }

    public void resume() {
        if (disposed || !paused) return;
        paused = false;
        if ("chat".equals(screen) || "terminal".equals(screen)) nav(screen);
    }

    public boolean back() {
        if (disposed) return false;
        while (!history.isEmpty()) {
            String prev = history.pop();
            if (prev != null && !prev.isEmpty() && !prev.equals(screen)) {
                isNavigatingBack = true;
                try {
                    nav(prev);
                } finally {
                    isNavigatingBack = false;
                }
                return true;
            }
        }
        return false;
    }

    public void dispose() {
        remember();
        chat.leave();
        files.leave();
        disposed = true;
        generation++;
        history.clear();
        body.removeAllViews();
        root.removeAllViews();
    }

    public void openSession(String sid) {
        if (sid == null || sid.trim().isEmpty() || disposed) return;
        final String s = sid.trim();
        if (s.startsWith("multi:")) {
            showChat(s.substring(6));
        } else {
            String origin = s.startsWith("claude:") ? s.substring(7) : s;
            post("/api/multi/create", json("claudeSessionId", origin), res -> showChat(res.optString("id")));
        }
    }

    public void addToDraft(String id, String text) {
        if (!disposed && "chat".equals(screen) && chatId.equals(id)) { chat.compose(text); return; }
        NativeChat.appendDraft(a, id, text);
    }

    public void share(String text) {
        if (text == null || disposed) return;
        if (!"chat".equals(screen)) {
            nav("chat");
        }
        chat.compose(text);
    }

    public void get(String path, Consumer<JSONObject> ok) {
        req("GET", path, null, ok);
    }

    public void post(String path, JSONObject data, Consumer<JSONObject> ok) {
        req("POST", path, data, ok);
    }

    public void req(String verb, String path, JSONObject data, Consumer<JSONObject> ok) {
        if (disposed) return;
        final int captureGen = generation;
        NativeRequests.call(a, verb, path, data,
                res -> {
                    if (disposed || captureGen != generation) {
                        return;
                    }
                    if (ok != null) {
                        ok.accept(res);
                    }
                },
                err -> {
                    if (disposed || captureGen != generation) {
                        return;
                    }
                    String msg = (err != null && !err.isEmpty()) ? err : "网络请求失败 (" + verb + " " + path + ")";
                    notice(msg);
                }
        );
    }

    public int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v,
                a.getResources().getDisplayMetrics()
        );
    }

    public void notice(String msg) {
        if (msg == null || msg.isEmpty() || disposed) return;
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    public void button(LinearLayout parent, String title, Runnable action) {
        if (parent == null || disposed) return;
        Button btn = new Button(a, null, android.R.attr.buttonStyle);
        btn.setText(title);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(4), 0, dp(4));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        parent.addView(btn);
    }

    public TextView text(LinearLayout parent, String value) {
        TextView tv = new TextView(a);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(2), 0, dp(2));
        tv.setLayoutParams(lp);
        if (parent != null) {
            parent.addView(tv);
        }
        return tv;
    }

    public LinearLayout scrolling() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        sv.setLayoutParams(svLp);

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        sv.addView(content);
        body.addView(sv);
        return content;
    }

    public void confirm(String message, Runnable action) {
        if (disposed) return;
        new AlertDialog.Builder(a)
                .setTitle("操作确认")
                .setMessage(message)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (action != null) action.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void openUrl(String url) {
        if (url == null || url.trim().isEmpty() || disposed) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            a.startActivity(intent);
        } catch (Exception e) {
            notice("无法打开外部链接: " + e.getMessage());
        }
    }

    public static String encode(String query) {
        return query == null ? "" : Uri.encode(query);
    }

    public static JSONObject json(Object... alternatingKeyValue) {
        JSONObject obj = new JSONObject();
        if (alternatingKeyValue == null) return obj;
        for (int i = 0; i < alternatingKeyValue.length - 1; i += 2) {
            Object k = alternatingKeyValue[i];
            Object v = alternatingKeyValue[i + 1];
            if (k != null) {
                try {
                    obj.put(String.valueOf(k), v == null ? JSONObject.NULL : v);
                } catch (JSONException ignored) {
                }
            }
        }
        return obj;
    }

    private void showNavPopup(View anchor) {
        if (disposed) return;
        PopupMenu popup = new PopupMenu(a, anchor);
        String[] screens = {"chat","sessions","projects","files","terminal","git","provider","settings","lineage","sched","bookmarks","ext","caps","usage","help","changelog"};
        for (int i = 0; i < screens.length; i++) popup.getMenu().add(0, i, i, getScreenTitle(screens[i]));
        popup.setOnMenuItemClickListener(item -> { nav(screens[item.getItemId()]); return true; });
        popup.show();
    }

    private String getScreenTitle(String id) {
        if (id == null) return "Codroid";
        if (id.startsWith("project_detail:")) return "项目详情";
        switch (id) {
            case "set-claude": return "Claude 行为";
            case "set-device": return "设备";
            case "set-run": return "后台运行";
            case "set-storage": return "存储";
            case "set-trouble": return "排障";
            case "set-update": return "检查更新";
            case "chat": return "对话";
            case "sessions": return "历史会话";
            case "projects": return "项目";
            case "files": return "文件管理";
            case "terminal": return "终端";
            case "provider": return "接口与模型";
            case "git": return "Git";
            case "editor": return "编辑文件";
            case "settings": return "系统设置";
            case "lineage": return "会话谱系";
            case "sched": return "定时任务";
            case "bookmarks": return "书签收藏";
            case "ext": return "扩展插件";
            case "caps": return "功能权限";
            case "usage": return "资源用量";
            case "help": return "帮助文档";
            case "changelog": return "更新日志";
            default: return id;
        }
    }

    private boolean isTablet() {
        int screenLayout = a.getResources().getConfiguration().screenLayout;
        int size = screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return size >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }
}
