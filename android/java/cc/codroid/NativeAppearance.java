package cc.codroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.TypedValue;
import android.widget.Toast;
import org.json.JSONObject;

/**
 * Native Android Monet and classic appearance manager.
 * Supports dynamic wallpaper color extraction on Android 12+ (API 31+)
 * with graceful Material fallbacks for Android 8.0+ (API 26+).
 */
public final class NativeAppearance {

    public static final String PREFS_NAME = "native-ui";
    public static final String KEY_FRONTEND = "frontend";
    private static final String PENDING_SKIN = "pending_classic_skin";
    private static final String[] SKINS = {"native", "qiusuo", "zhuyue", "goucheng", "shise"};
    public static final String FRONTEND_ANDROID = "android";
    public static final String FRONTEND_CLASSIC = "classic";

    private static final String STYLE_NAME = "AgentNative";
    private static final String[] CLASSIC_THEMES = new String[] {
        "原版暖色",
        "求索",
        "逐月",
        "构成",
        "拾色"
    };

    private NativeAppearance() {}

    /**
     * Determines whether the native Android UI frontend is active.
     * Defaults to true if unconfigured or set to "android".
     */
    public static boolean nativeMode(Activity a) {
        if (a == null) return true;
        SharedPreferences prefs = a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String frontend = prefs.getString(KEY_FRONTEND, FRONTEND_ANDROID);
        return !FRONTEND_CLASSIC.equalsIgnoreCase(frontend);
    }

    /**
     * Shows a native themed dialog to switch between Native Monet and Classic web themes.
     * Preserves user fonts and classic theme settings when toggling modes.
     * Recreates the activity only when a selection actually changes.
     */
    public static void choose(MainActivity a) { choose(a, null); }

    static void choose(MainActivity a, JSONObject webState) {
        if (a == null || a.isFinishing()) return;

        SharedPreferences prefs = a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean currentIsNative = nativeMode(a);
        String currentClassic = a.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("skin", SKINS[0]);

        final String[] items = new String[1 + CLASSIC_THEMES.length];
        items[0] = Build.VERSION.SDK_INT >= 31 ? "原生安卓 · 系统莫奈配色" : "原生安卓 · 跟随系统深浅色";
        for (int i = 0; i < CLASSIC_THEMES.length; i++) {
            items[i + 1] = "经典界面 · " + CLASSIC_THEMES[i];
        }

        int checkedIndex = 0;
        if (!currentIsNative) {
            checkedIndex = 1;
            for (int i = 0; i < CLASSIC_THEMES.length; i++) {
                if (SKINS[i].equals(currentClassic)) {
                    checkedIndex = i + 1;
                    break;
                }
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle("选择外观风格");
        builder.setSingleChoiceItems(items, checkedIndex, (dialog, which) -> {
            dialog.dismiss();

            boolean targetIsNative = (which == 0);
            String targetClassicTheme = targetIsNative ? currentClassic : SKINS[which - 1];

            boolean changed;
            if (targetIsNative) {
                changed = !currentIsNative;
            } else {
                changed = currentIsNative || !targetClassicTheme.equals(currentClassic);
            }

            if (changed) {
                if (currentIsNative) a.switchToClassic(targetClassicTheme);
                else if (targetIsNative) switchToNative(a, webState);
                else finishChange(a, prefs.edit().putString(PENDING_SKIN, targetClassicTheme));

            }
        });
        builder.setNegativeButton("取消", null);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private static final String WEB_PACKET = "pending_web_conversation";
    private static SharedPreferences prefs(Context a) {
        return a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void finishChange(MainActivity a, SharedPreferences.Editor edit) {
        if (edit.commit()) a.recreate();
        else Toast.makeText(a, "无法保存当前会话，请释放存储空间后重试", Toast.LENGTH_LONG).show();
    }

    static boolean validId(String id) {
        return id.isEmpty() || id.matches("(?:multi:)?[a-zA-Z0-9_-]{1,100}");
    }

    static boolean validSnapshot(JSONObject state) {
        return state != null && state.opt("sid") instanceof String && validId(state.optString("sid"))
                && state.opt("draft") instanceof String && state.opt("project") instanceof String
                && (state.optString("project").isEmpty() || state.optString("project").matches("[a-zA-Z0-9_-]{1,100}"));
    }

    static String mergeDraft(String current, String incoming, String previous) {
        if (current.equals(incoming) || current.isEmpty() || current.equals(previous)) return incoming;
        if (incoming.isEmpty() || incoming.equals(previous) || current.endsWith("\n\n"+incoming)) return current;
        if (incoming.endsWith("\n\n"+current)) return incoming;
        return current + "\n\n" + incoming;
    }

    static void toClassic(MainActivity a, NativeHome home, String skin) {
        String sid = home.chatId.isEmpty() ? "" : "multi:" + home.chatId;
        String draft = NativeChat.readThemeDraft(a, home.chatId);
        SharedPreferences prefs = prefs(a);
        JSONObject packet = NativeHome.json("sid", sid, "project", home.projectId, "draft", draft,
                "id", java.util.UUID.randomUUID().toString());
        if (sid.equals(prefs.getString("web_source_sid", null))) {
            try { packet.put("previous", prefs.getString("web_source_draft", "")); } catch (org.json.JSONException ignored) {}
        }
        finishChange(a, prefs.edit().putString(WEB_PACKET, packet.toString())
                .putString("native_source_sid", sid).putString("native_source_draft", draft)
                .putString(PENDING_SKIN, skin).putString(KEY_FRONTEND, FRONTEND_CLASSIC));
    }

    private static void switchToNative(MainActivity a, JSONObject state) {
        if (!validSnapshot(state)) {
            Toast.makeText(a, "会话还在加载，请稍后再切换", Toast.LENGTH_SHORT).show();
            return;
        }
        String sid = state.optString("sid");
        if (sid.isEmpty() || sid.startsWith("multi:")) {
            finishNative(a, state, sid.isEmpty() ? "" : sid.substring(6));
        } else {
            AlertDialog waiting = new AlertDialog.Builder(a).setMessage("正在打开原 Claude 会话…")
                    .setNegativeButton("取消", null).show();
            NativeRequests.call(a, "POST", "/api/multi/create", NativeHome.json("claudeSessionId", sid), result -> {
                if (!waiting.isShowing()) return;
                waiting.dismiss();
                String id = result.optString("id");
                if (!id.isEmpty() && validId(id) && !id.contains(":")) finishNative(a, state, id);
                else Toast.makeText(a, "未能打开原会话", Toast.LENGTH_LONG).show();
            }, error -> {
                if (!waiting.isShowing()) return;
                waiting.dismiss();
                Toast.makeText(a, error, Toast.LENGTH_LONG).show();
            });
        }
    }

    private static void finishNative(MainActivity a, JSONObject state, String sharedId) {
        SharedPreferences prefs = prefs(a);
        String sid = sharedId.isEmpty() ? "" : "multi:" + sharedId;
        String previous = sid.equals(prefs.getString("native_source_sid", null)) ? prefs.getString("native_source_draft", null) : null;
        String incoming = state.optString("draft");
        String merged = mergeDraft(NativeChat.readThemeDraft(a, sharedId), incoming, previous);
        if (!NativeChat.writeThemeDraft(a, sharedId, merged)) {
            Toast.makeText(a, "无法保存草稿，已保留当前界面", Toast.LENGTH_LONG).show();
            return;
        }
        finishChange(a, prefs.edit().remove(WEB_PACKET).putString("web_source_sid", sid).putString("web_source_draft", incoming)
                .putString("conversation", sharedId).putString("project", state.optString("project")).putString(KEY_FRONTEND, FRONTEND_ANDROID));
    }

    static String pendingClassicConversation(MainActivity a) {
        return prefs(a).getString(WEB_PACKET, "");
    }

    static void acknowledgeClassic(MainActivity a, String packet) {
        SharedPreferences prefs = prefs(a);
        if (packet.equals(prefs.getString(WEB_PACKET, ""))) prefs.edit().remove(WEB_PACKET).apply();
    }

    static String takeClassicSkin(Activity a) {
        SharedPreferences prefs = a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String skin = prefs.getString(PENDING_SKIN, "");
        if (!skin.isEmpty()) prefs.edit().remove(PENDING_SKIN).apply();
        return skin;
    }

    /**
     * Resolves the native Android style resource ID.
     * Attempts to find style "AgentNative"; falls back to system Material NoActionBar styles.
     */
    public static int theme(Activity a) {
        if (a == null) {
            return android.R.style.Theme_Material_Light_NoActionBar;
        }

        try {
            int resId = a.getResources().getIdentifier(STYLE_NAME, "style", a.getPackageName());
            if (resId != 0) {
                return resId;
            }
        } catch (Exception ignored) {}

        boolean isNight = false;
        try {
            int uiMode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            isNight = (uiMode == Configuration.UI_MODE_NIGHT_YES);
        } catch (Exception ignored) {}

        return isNight ? android.R.style.Theme_Material_NoActionBar
                       : android.R.style.Theme_Material_Light_NoActionBar;
    }

    /**
     * Generates a key summarizing system day/night state and real system palette colors.
     * Detects dynamic Monet wallpaper palette changes on Android 12+ without fixed palettes.
     */
    public static String paletteKey(Activity a) {
        if (a == null) return "";

        int uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED;
        try {
            uiMode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("night:").append(uiMode == Configuration.UI_MODE_NIGHT_YES ? "1" : "0");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                int c1 = a.getColor(android.R.color.system_accent1_600);
                int c2 = a.getColor(android.R.color.system_accent1_200);
                int n1 = a.getColor(android.R.color.system_neutral1_900);
                int n2 = a.getColor(android.R.color.system_neutral1_50);
                sb.append(":monet:")
                  .append(Integer.toHexString(c1)).append(":")
                  .append(Integer.toHexString(c2)).append(":")
                  .append(Integer.toHexString(n1)).append(":")
                  .append(Integer.toHexString(n2));
                return sb.toString();
            } catch (Throwable ignored) {}
        }

        try {
            TypedValue tv = new TypedValue();
            if (a.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                sb.append(":accent:").append(Integer.toHexString(tv.data));
            }
        } catch (Exception ignored) {}

        return sb.toString();
    }

    /**
     * Resolves an attribute color from the activity theme with fallback.
     */
    public static int resolveColor(Context context, int attrResId, int fallback) {
        if (context == null) return fallback;
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                try {
                    return context.getColor(tv.resourceId);
                } catch (Exception ignored) {}
            }
        }
        return fallback;
    }
}
