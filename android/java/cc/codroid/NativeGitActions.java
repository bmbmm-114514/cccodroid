package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.Arrays;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class NativeGitActions {
    private static String enc(String s) {
        return android.net.Uri.encode(s);
    }

    private static void ui(NativeHome h, int gen, Runnable r) {
        h.a.runOnUiThread(() -> { if (h.generation == gen && !h.a.isDestroyed()) r.run(); });
    }

    public static void add(NativeHome h, LinearLayout parent, String dir, Runnable refresh) {
        parent.addView(PixelUi.icon(h.a, "more", "更多 Git 操作", () -> {
            String[] items = {"提交日志", "拉取 (Pull)", "推送 (Push)", "抓取 (Fetch)", "设置身份"};
            PixelChoiceSheet.actions(h.a, "Git 操作", Arrays.asList(items), which -> {
                if (which == 0) showLog(h, dir);
                else if (which >= 1 && which <= 3) {
                    String op = which == 1 ? "pull" : (which == 2 ? "push" : "fetch");
                    String title = items[which];
                    new MaterialAlertDialogBuilder(h.a).setTitle("确认" + title)
                        .setMessage("确定要执行 " + title + " 吗？")
                        .setPositiveButton("确定", (d, w) -> write(h, op, NativeHome.json("dir", dir), res -> {
                            h.notice(title + " 成功");
                            if (which == 1 && refresh != null) refresh.run();
                        }))
                        .setNegativeButton("取消", null).show();
                } else if (which == 4) showIdentity(h, dir);
            });
        }));
    }

    private static void showLog(NativeHome h, String dir) {
        int gen = h.generation;
        h.get("/api/git/log?dir=" + enc(dir), res -> ui(h, gen, () -> {
            JSONArray commits = res.optJSONArray("commits");
            if (commits == null || commits.length() == 0) { h.notice("暂无提交记录"); return; }
            String[] list = new String[commits.length()];
            for (int i = 0; i < commits.length(); i++) {
                JSONObject c = commits.optJSONObject(i);
                list[i] = c.optString("short") + " " + c.optString("subject") + " (" + c.optString("author") + ")";
            }
            PixelChoiceSheet.actions(h.a, "提交日志", Arrays.asList(list),
                    w -> showCommit(h, dir, commits.optJSONObject(w).optString("hash")));
        }));
    }

    private static void showCommit(NativeHome h, String dir, String hash) {
        int gen = h.generation;
        h.get("/api/git/show?dir=" + enc(dir) + "&commit=" + enc(hash), res -> ui(h, gen, () -> {
            String msg = res.optString("message");
            JSONArray files = res.optJSONArray("files");
            int len = files != null ? files.length() : 0;
            String[] list = new String[len];
            for (int i = 0; i < len; i++) list[i] = files.optJSONObject(i).optString("path");
            PixelChoiceSheet.actions(h.a, msg.trim().isEmpty() ? hash : msg.trim(), Arrays.asList(list),
                    w -> showDiff(h, dir, hash, list[w]));
        }));
    }

    private static void showDiff(NativeHome h, String dir, String hash, String file) {
        int gen = h.generation;
        h.get("/api/git/diff?dir=" + enc(dir) + "&commit=" + enc(hash) + "&file=" + enc(file), res -> ui(h, gen, () -> {
            String diff = res.optString("diff", res.toString());
            ScrollView sv = new ScrollView(h.a);
            TextView tv = new TextView(h.a);
            tv.setTextIsSelectable(true);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setText(diff);
            tv.setPadding(h.dp(12), h.dp(8), h.dp(12), h.dp(8));
            sv.addView(tv);
            new MaterialAlertDialogBuilder(h.a).setTitle("文件对比: " + file)
                .setView(sv)
                .setNegativeButton("关闭", null).show();
        }));
    }

    private static void showIdentity(NativeHome h, String dir) {
        LinearLayout ll = new LinearLayout(h.a);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(h.dp(16), h.dp(8), h.dp(16), 0);
        EditText etName = new EditText(h.a); etName.setHint("用户名 (Name)");
        EditText etEmail = new EditText(h.a); etEmail.setHint("邮箱 (Email)");
        ll.addView(etName); ll.addView(etEmail);
        new MaterialAlertDialogBuilder(h.a).setTitle("设置 Git 身份")
            .setView(ll)
            .setPositiveButton("保存", (d, w) -> {
                String name = etName.getText().toString().trim();
                String email = etEmail.getText().toString().trim();
                write(h, "identity", NativeHome.json("dir", dir, "name", name, "email", email),
                    r -> h.notice("身份设置成功"));
            })
            .setNegativeButton("取消", null).show();
    }

    static void write(NativeHome h,String op,JSONObject body,Consumer<JSONObject> ok) {
        int generation=h.generation;
        new Thread(() -> {
            HttpURLConnection connection=null;
            try {
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                connection=Env.gateway("POST","/api/git/"+op,bytes);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                connection.setConnectTimeout(8000);connection.setReadTimeout(90000);
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
                int status=connection.getResponseCode();
                ByteArrayOutputStream result=new ByteArrayOutputStream();
                try(InputStream in=status>=200&&status<300 ? connection.getInputStream():connection.getErrorStream()){
                    byte[] buf=new byte[4096];
                    if(in!=null) for(int n;(n=in.read(buf))>=0 && result.size()<1048576;)result.write(buf,0,n);
                }
                JSONObject r=new JSONObject(result.toString("UTF-8"));
                h.a.runOnUiThread(() -> {
                    if(h.generation!=generation || h.a.isDestroyed())return;
                    if(status>=200&&status<300){ok.accept(r);return;}
                    if(status==409 && "untrusted".equals(r.optString("error")) && !r.optString("fp").isEmpty() && !body.has("trust")){
                        JSONArray risks=r.optJSONArray("risks");
                        StringBuilder text=new StringBuilder(r.optString("message"));
                        for(int i=0; risks!=null && i<risks.length();i++)text.append("\n").append(risks.optString(i));
                        new MaterialAlertDialogBuilder(h.a).setTitle("信任此仓库的配置？").setMessage(text)
                                .setNegativeButton("取消",null).setPositiveButton("信任并继续",(d,w) -> {
                                    try { body.put("trust",r.optString("fp"));write(h,op,body,ok); }
                                    catch(org.json.JSONException e){h.notice(e.getMessage());}
                                }).show();
                    }else h.notice(r.optString("message",r.optString("error","Git 操作失败")));
                });
            }catch(Exception e){h.a.runOnUiThread(() -> {if(h.generation==generation)h.notice(e.getMessage());});}
            finally{if(connection!=null)connection.disconnect();}
        },"native-git-"+op).start();
    }
}
