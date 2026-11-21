package cc.codroid;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

final class NativeChatActions {
    static void show(NativeHome h, View anchor) {
        if (h == null || anchor == null || !anchor.isAttachedToWindow()) return;
        BottomSheetDialog sheet = new BottomSheetDialog(h.a);
        ScrollView scroll = new ScrollView(sheet.getContext());
        LinearLayout actions = new LinearLayout(scroll.getContext());
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(h.dp(16), h.dp(16), h.dp(16), h.dp(20));
        scroll.addView(actions);

        item(actions, sheet, "attach", "添加附件", h.a::pickNativeAttachment);
        item(actions, sheet, "camera", "拍照", h.a::takeNativePhoto);
        item(actions, sheet, "device", "截屏", () -> {
            String owner = h.chatId;
            NativeRequests.call(h.a, "POST", "/api/screenshot", NativeHome.json("delay",0,"shot",Priv.armShot(0)),
                    result -> h.addToDraft(owner, "附件：" + result.optString("path")), h::notice);
        });
        item(actions, sheet, "link", "应用内浏览器", () -> BrowserActivity.open(h.a, ""));
        item(actions, sheet, "files", "项目文件", () -> h.nav("files"));
        item(actions, sheet, "lineage", "Git", () -> h.nav("git"));
        item(actions, sheet, "terminal", "终端", () -> h.nav("terminal"));
        item(actions, sheet, "usage", "用量统计", () -> h.nav("usage"));
        item(actions, sheet, "bookmark", "书签", () -> h.nav("bookmarks"));

        sheet.setContentView(scroll);
        sheet.show();
    }

    private static void item(LinearLayout parent, BottomSheetDialog sheet,
                             String icon, String title, Runnable action) {
        PixelUi.row(parent, icon, title, "", () -> {
            sheet.dismiss();
            action.run();
        });
    }
}
