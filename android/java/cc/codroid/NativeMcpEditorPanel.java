package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class NativeMcpEditorPanel {

    static class ExistCred {
        final String name;
        final boolean isEnv;
        final CheckBox cb;
        ExistCred(String n, boolean e, CheckBox c) { name = n; isEnv = e; cb = c; }
    }

    static class NewCred {
        final EditText k, v;
        final View row;
        NewCred(EditText k, EditText v, View r) { this.k = k; this.v = v; this.row = r; }
    }

    public static void show(NativeHome h, String name, Runnable reload) {
        final boolean isEdit = name != null && !name.trim().isEmpty();
        final String targetName = isEdit ? name.trim() : "";
        if (isEdit) {
            h.get("/api/ext/mcp/get?name=" + Uri.encode(targetName), res -> {
                if (res == null || !res.optBoolean("ok")) {
                    h.notice(res != null ? res.optString("message", "配置加载失败") : "网络错误");
                    return;
                }
                buildDialog(h, targetName, res, reload);
            });
        } else {
            buildDialog(h, "", null, reload);
        }
    }

    private static void buildDialog(NativeHome h, String origName, JSONObject meta, Runnable reload) {
        final Context ctx = h.a;
        final boolean isEdit = !origName.isEmpty();
        final String origTrans = meta != null ? meta.optString("transport", "stdio") : "stdio";
        final String origTarget = meta != null ? meta.optString("target", "") : "";
        final String[] transports = {"stdio", "http", "sse"};

        ScrollView sv = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = h.dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView t1 = new TextView(ctx); t1.setText("服务器名称"); root.addView(t1);
        EditText nameEt = new EditText(ctx);
        nameEt.setSingleLine(true);
        nameEt.setSaveEnabled(false);
        nameEt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        nameEt.setHint("字母、数字、点、下划线、横线");
        if (isEdit) nameEt.setText(origName);
        root.addView(nameEt);

        TextView t2 = new TextView(ctx); t2.setText("传输协议"); root.addView(t2);
        Spinner transSp = new PixelChoiceField(ctx, "传输协议");
        transSp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, transports));
        int initPos = 0;
        if ("http".equals(origTrans)) initPos = 1;
        else if ("sse".equals(origTrans)) initPos = 2;
        transSp.setSelection(initPos);
        root.addView(transSp);

        TextView t3 = new TextView(ctx); t3.setText("命令或地址 (敏感)"); root.addView(t3);
        EditText targetEt = new EditText(ctx);
        targetEt.setSingleLine(true);
        targetEt.setSaveEnabled(false);
        targetEt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        targetEt.setHint(isEdit ? "留空保留当前命令或地址" : "命令 (stdio) 或 URL (http/sse)");
        root.addView(targetEt);

        List<ExistCred> existCreds = new ArrayList<>();
        if (isEdit && meta != null) {
            JSONArray envs = meta.optJSONArray("envNames");
            JSONArray hdrs = meta.optJSONArray("headerNames");
            if ((envs != null && envs.length() > 0) || (hdrs != null && hdrs.length() > 0)) {
                TextView existTitle = new TextView(ctx);
                existTitle.setText("已保存凭据 (勾选以删除):");
                root.addView(existTitle);
                addExistItems(ctx, root, envs, true, existCreds);
                addExistItems(ctx, root, hdrs, false, existCreds);
            }
        }

        TextView newCredTitle = new TextView(ctx);
        root.addView(newCredTitle);
        LinearLayout newCredLayout = new LinearLayout(ctx);
        newCredLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(newCredLayout);

        List<NewCred> newCreds = new ArrayList<>();
        Button addRowBtn = new Button(ctx);
        addRowBtn.setText("+ 添加凭据");
        addRowBtn.setOnClickListener(v -> addNewCredRow(ctx, newCredLayout, newCreds));
        root.addView(addRowBtn);

        Runnable updateCredHint = () -> {
            boolean stdio = "stdio".equals(transSp.getSelectedItem());
            newCredTitle.setText(stdio ? "添加环境变量 (NAME=值):" : "添加请求头 (Header: 值):");
        };
        transSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updateCredHint.run(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        updateCredHint.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(isEdit ? "编辑 MCP 服务器" : "添加 MCP 服务器")
            .setView(sv)
            .setPositiveButton(isEdit ? "保存" : "添加", null)
            .setNegativeButton("取消", null)
            .create();
        dialog.setCanceledOnTouchOutside(false);

        Runnable clearSecrets = () -> {
            targetEt.setText("");
            for (NewCred nc : newCreds) { nc.k.setText(""); nc.v.setText(""); }
        };
        dialog.setOnDismissListener(d -> clearSecrets.run());

        dialog.show();

        final int finalInitPos = initPos;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            boolean dirty = !nameEt.getText().toString().equals(isEdit ? origName : "")
                || !targetEt.getText().toString().isEmpty()
                || transSp.getSelectedItemPosition() != finalInitPos;
            if (!dirty) {
                for (ExistCred ec : existCreds) if (ec.cb.isChecked()) { dirty = true; break; }
            }
            if (!dirty) {
                for (NewCred nc : newCreds) {
                    if (!nc.k.getText().toString().isEmpty() || !nc.v.getText().toString().isEmpty()) { dirty = true; break; }
                }
            }
            if (dirty) {
                new MaterialAlertDialogBuilder(ctx).setMessage("放弃未保存的修改？")
                    .setPositiveButton("放弃", (d, w) -> { clearSecrets.run(); dialog.dismiss(); })
                    .setNegativeButton("继续编辑", null).show();
            } else {
                clearSecrets.run();
                dialog.dismiss();
            }
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nameVal = nameEt.getText().toString().trim();
            if (nameVal.startsWith("-") || !nameVal.matches("^[A-Za-z0-9._-]{1,64}$")) {
                h.notice("名字只能用字母、数字、点、下划线、横线");
                return;
            }
            String curTrans = (String) transSp.getSelectedItem();
            boolean isStdio = "stdio".equals(curTrans);
            String inputTarget = targetEt.getText().toString().trim();
            boolean transChanged = isEdit && !curTrans.equals(origTrans);
            if (transChanged && inputTarget.isEmpty()) {
                h.notice("切换传输协议必须提供新的命令或地址");
                return;
            }
            if (!isEdit && inputTarget.isEmpty()) {
                h.notice("缺少命令或地址");
                return;
            }
            String finalTarget = inputTarget.isEmpty() ? origTarget : inputTarget;
            if (finalTarget.isEmpty()) {
                h.notice("缺少命令或地址");
                return;
            }
            if (!isStdio && !finalTarget.toLowerCase().matches("^https?://\\S+$")) {
                h.notice("地址要以 http:// 或 https:// 开头");
                return;
            }

            JSONArray envArr = new JSONArray();
            JSONArray hdrArr = new JSONArray();
            for (NewCred nc : newCreds) {
                String k = nc.k.getText().toString().trim();
                String val = nc.v.getText().toString().trim();
                if (k.isEmpty() && val.isEmpty()) continue;
                if (k.isEmpty() || val.isEmpty()) {
                    h.notice("凭据名称和值均不能为空");
                    return;
                }
                if (isStdio) {
                    if (!k.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                        h.notice("环境变量名只能包含字母、数字和下划线，且不能以数字开头");
                        return;
                    }
                    envArr.put(k + "=" + val);
                } else {
                    if (!k.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") || val.indexOf('\r') >= 0 || val.indexOf('\n') >= 0) {
                        h.notice("请检查请求头名称，值不能换行");
                        return;
                    }
                    hdrArr.put(k + ": " + val);
                }
            }

            JSONArray dropEnv = new JSONArray();
            JSONArray dropHdr = new JSONArray();
            for (ExistCred ec : existCreds) {
                if (ec.cb.isChecked()) {
                    if (ec.isEnv) dropEnv.put(ec.name);
                    else dropHdr.put(ec.name);
                }
            }

            if (isEdit && origName.equals(nameVal) && !transChanged && inputTarget.isEmpty()
                    && envArr.length() == 0 && hdrArr.length() == 0 && dropEnv.length() == 0 && dropHdr.length() == 0) {
                dialog.dismiss();
                return;
            }
            Runnable submit = () -> {
                try {
                    JSONObject postData = new JSONObject();
                    postData.put("name", isEdit ? origName : nameVal);
                    if (isEdit) postData.put("newName", nameVal);
                    postData.put("transport", curTrans);
                    postData.put("target", finalTarget);
                    postData.put("env", envArr);
                    postData.put("headers", hdrArr);
                    if (isEdit) {
                        postData.put("expectedRevision", meta.optString("revision"));
                        postData.put("dropEnv", dropEnv);
                        postData.put("dropHeaders", dropHdr);
                    }
                    String path = isEdit ? "/api/ext/mcp/edit" : "/api/ext/mcp/add";
                    Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    save.setEnabled(false);
                    h.req("POST", path, postData, res -> {
                        save.setEnabled(true);
                        if (res != null && res.optBoolean("ok")) {
                            clearSecrets.run();
                            dialog.dismiss();
                            h.get("/api/ext/mcp/get?name=" + Uri.encode(nameVal), verifyRes -> {
                                boolean stored = verifyRes.optBoolean("ok") && curTrans.equals(verifyRes.optString("transport"))
                                        && expectedNames(isStdio ? envArr : hdrArr, isStdio ? '=' : ':', existCreds, isStdio)
                                            .equals(names(verifyRes.optJSONArray(isStdio ? "envNames" : "headerNames")));
                                h.notice(stored ? "已保存配置" : "网关已处理，重新读取的配置与提交不一致");
                                if (reload != null) reload.run();
                            });
                        } else {
                            h.notice(res != null && "stale".equals(res.optString("code")) ? "配置已修改，请重新打开后编辑" : "保存失败，请检查名称、地址和凭据格式");
                        }
                    }, message -> {
                        save.setEnabled(true);
                        h.notice("未能保存配置，请检查网关连接后重试");
                    });
                } catch (Exception e) {
                    h.notice("数据构建失败");
                }
            };

            boolean transDiscard = isEdit && (!"stdio".equals(origTrans) != !isStdio);
            if (transDiscard) {
                new MaterialAlertDialogBuilder(ctx).setTitle("确认切换协议")
                    .setMessage("切换传输协议将丢弃后端存储的不兼容密钥凭据，是否继续？")
                    .setPositiveButton("继续", (d, w) -> submit.run())
                    .setNegativeButton("取消", null).show();
            } else {
                submit.run();
            }
        });
    }

    private static java.util.Set<String> names(JSONArray a) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static java.util.Set<String> expectedNames(JSONArray added, char delimiter, List<ExistCred> existing, boolean env) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (ExistCred ec : existing) if (ec.isEnv == env && !ec.cb.isChecked()) keys.add(ec.name);
        for (int i = 0; i < added.length(); i++) {
            String entry = added.optString(i);
            keys.add(entry.substring(0, entry.indexOf(delimiter)).trim());
        }
        return keys;
    }

    private static void addExistItems(Context ctx, LinearLayout root, JSONArray arr, boolean isEnv, List<ExistCred> list) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String k = arr.optString(i);
            if (k == null || k.isEmpty()) continue;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(ctx);
            tv.setText((isEnv ? "[Env] " : "[Header] ") + k);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            CheckBox cb = PixelChoiceField.checkbox(ctx);
            cb.setText("删除");
            row.addView(tv);
            row.addView(cb);
            root.addView(row);
            list.add(new ExistCred(k, isEnv, cb));
        }
    }

    private static void addNewCredRow(Context ctx, LinearLayout container, List<NewCred> list) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText kEt = new EditText(ctx);
        kEt.setHint("名称");
        kEt.setSingleLine(true);
        kEt.setSaveEnabled(false);
        kEt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        kEt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        EditText vEt = new EditText(ctx);
        vEt.setHint("值 (敏感/掩码)");
        vEt.setSingleLine(true);
        vEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        vEt.setSaveEnabled(false);
        vEt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        vEt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        Button del = new Button(ctx);
        del.setText("✕");
        del.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        row.addView(kEt);
        row.addView(vEt);
        row.addView(del);
        container.addView(row);
        NewCred nc = new NewCred(kEt, vEt, row);
        list.add(nc);
        del.setOnClickListener(v -> {
            nc.k.setText("");
            nc.v.setText("");
            container.removeView(row);
            list.remove(nc);
        });
    }
}
