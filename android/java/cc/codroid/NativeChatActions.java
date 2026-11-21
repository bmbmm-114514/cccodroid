package cc.codroid;

import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

public class NativeChatActions {
    public static void show(NativeHome h, View anchor) {
        if (h == null || anchor == null || h.a == null) {
            return;
        }
        PopupMenu popup = new PopupMenu(h.a, anchor);
        Menu menu = popup.getMenu();

        item(menu, "添加附件", () -> h.a.pickNativeAttachment());
        item(menu, "拍照", () -> h.a.takeNativePhoto());
        item(menu, "截屏", () -> {
            String owner = h.chatId;
            NativeRequests.call(h.a, "POST", "/api/screenshot", NativeHome.json("delay",0,"shot",Priv.armShot(0)),
                result -> h.addToDraft(owner, "附件：" + result.optString("path")), h::notice);
        });
        item(menu, "应用内浏览器", () -> BrowserActivity.open(h.a, ""));
        item(menu, "项目文件", () -> h.nav("files"));
        item(menu, "Git", () -> h.nav("git"));
        item(menu, "终端", () -> h.nav("terminal"));
        item(menu, "接口与模型", () -> h.nav("provider"));
        item(menu, "用量统计", () -> h.nav("usage"));
        item(menu, "历史会话", () -> h.nav("sessions"));
        item(menu, "书签", () -> h.nav("bookmarks"));
        item(menu, "新建会话", () -> h.showChat(""));

        popup.show();
    }

    private static void item(Menu menu, String title, Runnable action) {
        menu.add(title).setOnMenuItemClickListener(item -> {
            action.run();
            return true;
        });
    }
}
