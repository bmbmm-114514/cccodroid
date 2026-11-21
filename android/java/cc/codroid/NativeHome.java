package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.text.TextUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationBarView;
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
    private final TextView subtitleView;
    private final LinearLayout heading, tools, shell, main;
    private final ImageButton leading;
    private NavigationBarView navigation;
    private boolean rail;
    private final NativeImeLayout ime;
    private MaterialButton modelChip;
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
    private final PixelPageMotion motion;
    private Runnable detachBack;
    private int backStartGeneration = -1;
    private boolean committingGesture, selectingTab;

    public NativeHome(MainActivity a, Runnable chooseTheme) {
        this.a = a;
        this.chooseTheme = chooseTheme;
        this.chat = new NativeChat(this);

        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        ime = new NativeImeLayout(root, this::syncBack);
        root.setBackgroundColor(PixelUi.color(a,"surface"));
        shell = new LinearLayout(a);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(shell, new LinearLayout.LayoutParams(-1,0,1));
        MaterialToolbar top = new MaterialToolbar(a);
        body = new LinearLayout(a);
        main = new LinearLayout(a) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                NativeTerminalLayout.fitHeader(top, body, widthSpec, heightSpec);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        main.setOrientation(LinearLayout.VERTICAL);
        FrameLayout viewport = new FrameLayout(a);
        shell.addView(viewport, new LinearLayout.LayoutParams(0, -1, 1));
        viewport.addView(main, new FrameLayout.LayoutParams(-1, -1));
        motion = new PixelPageMotion(viewport, main);

        top.setContentInsetsRelative(0,0);
        top.setBackgroundColor(PixelUi.color(a,"surface"));
        top.setMinimumHeight(dp(64));
        toolbar = new LinearLayout(a);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(dp(64));
        toolbar.setPaddingRelative(dp(4),dp(4),dp(12),dp(4));
        top.addView(toolbar, new androidx.appcompat.widget.Toolbar.LayoutParams(-1,-2));
        main.addView(top,new LinearLayout.LayoutParams(-1,-2));

        leading = PixelUi.icon(a,"menu","导航",this::leadingAction);
        toolbar.addView(leading);
        heading = new LinearLayout(a);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(dp(48));
        heading.setPaddingRelative(dp(8),0,dp(8),0);
        titleView = PixelUi.label(a,"",21,"onSurface");
        subtitleView = PixelUi.label(a,"",12,"onSurfaceVariant");
        for (TextView line : new TextView[]{titleView,subtitleView}) {
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(line);
        }
        toolbar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        tools = new LinearLayout(a);
        tools.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.addView(tools,new LinearLayout.LayoutParams(-2,-2));

        body.setOrientation(LinearLayout.VERTICAL);
        main.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        configureNavigation(a.getResources().getConfiguration().screenWidthDp >= 600);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            boolean wide = r-l >= dp(600);
            if (!disposed && wide != rail) root.post(() -> { if (!disposed) configureNavigation(root.getWidth() >= dp(600)); });
        });
        ime.attach(a.getWindow());
        a.setContentView(this.root);
        chatId = a.getSharedPreferences("native-ui", 0).getString("conversation", "");
        projectId = a.getSharedPreferences("native-ui", 0).getString("project", "");
        nav("chat");
    }

    private void configureNavigation(boolean wide) {
        if (navigation != null) {
            if (wide == rail) return;
            ((ViewGroup)navigation.getParent()).removeView(navigation);
        }
        rail = wide;
        navigation = PixelNav.make(this,wide);
        if (wide) shell.addView(navigation,0,new LinearLayout.LayoutParams(-2,-1));
        else root.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        ime.setBottomBar(wide ? null : navigation);
    }

    private boolean primary(String id) {
        return "chat".equals(id) || "projects".equals(id) || "files".equals(id) || "settings".equals(id);
    }
    private void leadingAction() {
        if (!primary(screen) && back()) return;
        PixelNav.menu(this);
    }
    void setHeading(String title,String subtitle) {
        titleView.setText(title);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    }
    void onHeadingClick(Runnable action) {
        heading.setOnClickListener(action == null ? null : v -> action.run());
        heading.setClickable(action != null);
        heading.setFocusable(action != null);
        heading.setBackground(action == null ? null : PixelUi.clickable(a,null,16));
    }
    void headerAction(String icon,String label,Runnable action) {
        modelChip = null;
        tools.removeAllViews();
        if (action != null) tools.addView(PixelUi.icon(a,icon,label,action));
    }
    void headerModel(String label,Runnable action) {
        if (modelChip == null) {
            tools.removeAllViews();
            modelChip = PixelUi.button(a,label,action);
            modelChip.setTextSize(14);
            modelChip.setMaxLines(1);
            modelChip.setEllipsize(TextUtils.TruncateAt.END);
            modelChip.setMaxWidth(dp(Math.min(200, a.getResources().getConfiguration().screenWidthDp * 47 / 100)));
            modelChip.setMinHeight(dp(48));
            modelChip.setIcon(PixelUi.drawable(a,"expand"));
            modelChip.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
            modelChip.setIconSize(dp(18));
            modelChip.setIconPadding(dp(3));
            modelChip.setPaddingRelative(dp(14),0,dp(10),0);
            tools.addView(modelChip,new LinearLayout.LayoutParams(-2,-2));
        }
        modelChip.setText(label);
        modelChip.setContentDescription("切换模型，当前 " + label);
    }
    void quickModel() {
        ProviderSettingsDialog.quick(a, () -> { if ("chat".equals(screen)) chat.refreshSelection(); });
    }

    void selectTab(String id) {
        if (disposed || id.equals(screen)) return;
        history.clear();
        if (!"chat".equals(id)) history.push("chat");
        selectingTab = true;
        try {
            returnTo(id);
        } finally {
            selectingTab = false;
        }
    }

    private void returnTo(String id) {
        isNavigatingBack = true;
        try {
            nav(id);
        } finally {
            isNavigatingBack = false;
        }
    }

    public void nav(String id) {
        if (disposed || id == null || id.isEmpty()) return;

        if ("provider".equals(id)) {
            ProviderSettingsDialog.show(a, () -> { if ("chat".equals(screen)) chat.refreshSelection(); });
            return;
        }

        String previous = screen;
        String previousTitle = getScreenTitle(previous);
        boolean changing = !previous.equals(id);
        boolean workspaceSwitch = !committingGesture && NativeFiles.isWorkspace(previous) && NativeFiles.isWorkspace(id);
        boolean tabSwitch = !workspaceSwitch && (selectingTab
                || (!committingGesture && PixelNav.tabIndex(previous) != PixelNav.tabIndex(id)));
        PixelPageMotion.TabStart tab = changing ? motion.capture(previous, id, tabSwitch) : null;
        if (!committingGesture && changing) motion.reset();
        if (!isNavigatingBack && screen != null && !screen.isEmpty() && !screen.equals(id)) {
            history.push(screen);
        }

        files.leave();
        chat.leave();
        this.screen = id;
        this.generation++;
        renderScreen(id);
        if (changing && !workspaceSwitch) {
            int direction = a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
            if (tabSwitch && PixelNav.tabIndex(id) < PixelNav.tabIndex(previous)) direction = -direction;
            motion.navigate(previous, previousTitle, isNavigatingBack && !selectingTab, committingGesture, direction, tab);
        }
        syncBack();
    }

    boolean committingBackGesture() { return committingGesture; }

    boolean canGoBack() { return !disposed && !history.isEmpty(); }

    private void syncBack() {
        boolean handlesBack = canGoBack() && !ime.isVisible();
        if (handlesBack && detachBack == null) {
            detachBack = PixelBack.attach(a, new PixelBack.Handler() {
                @Override public void start(boolean right) { startBack(right); }
                @Override public void progress(float fraction) { progressBack(fraction); }
                @Override public void cancel() { cancelBack(); }
                @Override public void commit() { commitBack(); }
            });
        } else if (!handlesBack && detachBack != null) {
            detachBack.run();
            detachBack = null;
        }
    }

    void startBack(boolean fromRight) {
        if (!canGoBack()) return;
        backStartGeneration = generation;
        String destination = history.peek();
        motion.start(destination, getScreenTitle(destination), fromRight);
    }

    void progressBack(float fraction) {
        if (backStartGeneration == generation) motion.seek(fraction);
    }

    void cancelBack() {
        backStartGeneration = -1;
        motion.cancel();
    }

    void commitBack() {
        int started = backStartGeneration;
        backStartGeneration = -1;
        if (paused || disposed || (started != -1 && started != generation)) return;
        committingGesture = started == generation;
        try {
            back();
        } finally {
            committingGesture = false;
        }
    }

    public void showChat(String sharedId) {
        chatId = sharedId == null ? "" : sharedId;
        remember();
        nav("chat");
    }

    private void renderScreen(String id) {
        if (disposed) return;
        setHeading(getScreenTitle(id), "");
        onHeadingClick(null);
        headerAction(null,null,null);
        boolean topLevel = primary(id);
        leading.setImageDrawable(PixelUi.drawable(a, topLevel ? "menu" : "back"));
        leading.setContentDescription(topLevel ? "导航" : "返回");
        leading.setTooltipText(topLevel ? "导航" : "返回");
        PixelNav.sync(navigation,id);
        if (!NativeFiles.isWorkspace(id) || !files.ownsBody(body)) body.removeAllViews();

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
        motion.reset();
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
        if (ime.hide()) return true;
        while (!history.isEmpty()) {
            String prev = history.pop();
            if (prev != null && !prev.isEmpty() && !prev.equals(screen)) {
                returnTo(prev);
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
        syncBack();
        motion.dispose();
        ime.detach();
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
        req(verb, path, data, ok, this::notice);
    }

    public void req(String verb, String path, JSONObject data, Consumer<JSONObject> ok, Consumer<String> failed) {
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
                    failed.accept(msg);
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
        Button btn = PixelUi.button(a,title, () -> { if (action != null) action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0, dp(2), 0, dp(2));
        btn.setLayoutParams(lp);
        parent.addView(btn);
    }

    public TextView text(LinearLayout parent, String value) {
        TextView tv = PixelUi.label(a,value,14,"onSurface");
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
        new MaterialAlertDialogBuilder(a)
                .setTitle("操作确认")
                .setMessage(message)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (action != null) action.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void openUrl(String url) {
        if (url == null || disposed) return;
        Uri uri = Uri.parse(url.trim());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || MainActivity.isGatewayUrl(uri.toString())) {
            notice("无法打开这个链接");
            return;
        }
        BrowserActivity.open(a, uri.toString());
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

    private String getScreenTitle(String id) {
        if (id == null) return "Codroid";
        if (id.startsWith("project_detail:")) return "项目详情";
        switch (id) {
            case "set-claude": return "Claude 行为";
            case "set-device": return "设备";
            case "set-workspace": return "工作区与运行";
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
            case "settings": return "设置";
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

}
