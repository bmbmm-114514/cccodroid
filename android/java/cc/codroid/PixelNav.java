package cc.codroid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;

public final class PixelNav {
    private static final int ID_CHAT = 1;
    private static final int ID_PROJECTS = 2;
    private static final int ID_FILES = 3;
    private static final int ID_SETTINGS = 4;

    private static final String[][] PRIMARY = {
        {"chat", "对话", "chat"},
        {"projects", "项目", "projects"},
        {"files", "文件", "files"},
        {"settings", "设置", "settings"}
    };

    private PixelNav() {}

    public static NavigationBarView make(NativeHome h, boolean wide) {
        Context ctx = h.a;
        NavigationBarView bar = wide ? new NavigationRailView(ctx) : new BottomNavigationView(ctx);
        if (bar instanceof BottomNavigationView) ((BottomNavigationView)bar).setItemHorizontalTranslationEnabled(false);
        bar.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);

        Menu menu = bar.getMenu();
        for (int i = 0; i < PRIMARY.length; i++) {
            MenuItem item = menu.add(Menu.NONE, i + 1, i, PRIMARY[i][1]);
            item.setIcon(PixelUi.drawable(ctx, PRIMARY[i][2]));
        }

        ViewGroup menuView = bar.getMenuViewGroup();
        // Keep Material's measured icon slots and ripple targets. Paint one highlight below them.
        bar.setItemActiveIndicatorEnabled(true);
        bar.setItemActiveIndicatorColor(ColorStateList.valueOf(Color.TRANSPARENT));
        MenuHighlight highlight = new MenuHighlight(bar);
        menuView.setBackground(highlight);
        menuView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { highlight.clear(); }
        });

        bar.setOnItemSelectedListener(item -> {
            int idx = item.getItemId() - 1;
            if (idx >= 0 && idx < PRIMARY.length) {
                h.selectTab(PRIMARY[idx][0]);
                return true;
            }
            return false;
        });

        bar.setOnItemReselectedListener(item -> {
            int index = item.getItemId() - 1;
            if (index >= 0 && index < PRIMARY.length) h.selectTab(PRIMARY[index][0]);
        });
        sync(bar, h.screen != null ? h.screen : "chat");
        return bar;
    }

    static int tabIndex(String id) {
        int target;
        if (id.startsWith("set-") || id.startsWith("ext-")) id = "settings";
        if (id.startsWith("project_detail:")) id = "projects";
        switch (id) {
            case "projects":
            case "bookmarks":
                target = ID_PROJECTS;
                break;
            case "git":
            case "terminal":
            case "editor":
            case "files":
                target = ID_FILES;
                break;
            case "settings":
            case "sched":
            case "ext":
            case "caps":
            case "usage":
            case "help":
            case "changelog":
                target = ID_SETTINGS;
                break;
            case "chat":
            case "sessions":
            case "lineage":
            default:
                target = ID_CHAT;
                break;
        }
        return target;
    }

    public static void sync(NavigationBarView bar, String id) {
        if (bar == null || id == null) return;
        MenuItem item = bar.getMenu().findItem(tabIndex(id));
        if (item != null) {
            item.setChecked(true);
            bar.getMenuViewGroup().invalidate();
        }
    }

    private static final class MenuHighlight extends Drawable {
        private final NavigationBarView bar;
        private final ViewGroup menu;
        private final Rect slotBounds = new Rect();
        private final PixelSelectionIndicator indicator;
        private int lastWidth, lastHeight;

        MenuHighlight(NavigationBarView bar) {
            this.bar = bar;
            menu = bar.getMenuViewGroup();
            indicator = new PixelSelectionIndicator(menu, PixelUi.color(bar.getContext(), "primaryContainer"),
                    PixelUi.dp(bar.getContext(), 24));
        }

        @Override public void draw(Canvas canvas) {
            View item = menu.findViewById(bar.getSelectedItemId());
            if (item == null) return;
            View slot = item.findViewById(com.google.android.material.R.id.navigation_bar_item_active_indicator_view);
            if (slot == null || slot.getWidth() == 0 || slot.getHeight() == 0) return;
            slot.getDrawingRect(slotBounds);
            menu.offsetDescendantRectToMyCoords(slot, slotBounds);
            boolean stableLayout = lastWidth == menu.getWidth() && lastHeight == menu.getHeight();
            lastWidth = menu.getWidth();
            lastHeight = menu.getHeight();
            indicator.moveTo(slotBounds.left, slotBounds.top, slotBounds.right, slotBounds.bottom, stableLayout);
            indicator.draw(canvas);
        }

        void clear() { indicator.moveTo(0, 0, 0, 0, false); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static void menu(NativeHome h) {
        BottomSheetDialog sheet = new BottomSheetDialog(h.a);
        ScrollView scroll = new ScrollView(h.a);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(h.a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(h.dp(16), h.dp(12), h.dp(16), h.dp(24));
        scroll.addView(root);

        addGroup(h, root, sheet, "会话与开发工具", new String[][]{
            {"sessions", "历史会话", "history"},
            {"terminal", "终端", "terminal"},
            {"git", "Git", "git"},
            {"bookmarks", "书签", "bookmark"},
            {"lineage", "会话谱系", "lineage"}
        });

        addGroup(h, root, sheet, "系统与自动化", new String[][]{
            {"sched", "定时任务", "schedule"},
            {"ext", "扩展", "extension"},
            {"caps", "设备能力", "device"}
        });

        addGroup(h, root, sheet, "支持与记录", new String[][]{
            {"usage", "用量", "usage"},
            {"help", "使用指南", "help"},
            {"changelog", "更新记录", "update"}
        });

        sheet.setContentView(scroll);
        sheet.show();
    }

    private static void addGroup(NativeHome h, LinearLayout parent, BottomSheetDialog sheet,
                                 String title, String[][] items) {
        TextView header = new TextView(h.a);
        header.setText(title);
        header.setTextColor(PixelUi.color(h.a, "primary"));
        header.setTextSize(12f);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(h.dp(8), h.dp(12), h.dp(8), h.dp(4));
        parent.addView(header);

        for (String[] it : items) {
            PixelUi.row(parent, it[2], it[1], "", () -> {
                sheet.dismiss();
                h.nav(it[0]);
            });
        }
    }
}
