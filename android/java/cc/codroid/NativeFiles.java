package cc.codroid;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class NativeFiles {

    private static final int MODE_FILES = 0;
    private static final int MODE_EDITOR = 1;
    private static final int MODE_GIT = 2;
    private static final int MODE_TERM = 3;

    private int activeMode = MODE_FILES;
    private int lastGeneration = -1;

    // --- 文件浏览器状态 ---
    private String projectRoot = "";
    private String currentDir = "";
    private final List<FileItem> fileList = new ArrayList<>();
    private boolean filesLoading = false;
    private boolean showHidden;
    private int filePage, gitPage;
    private static final int PAGE_SIZE = 60;
    private String search = "";
    private boolean truncated;
    private final java.util.LinkedHashSet<String> selectedFiles = new java.util.LinkedHashSet<>();
    private boolean selecting;
    private String selectionDir = "";

    // --- 编辑器状态 ---
    private String editingPath = null;
    private double openMtime = 0;
    private String editingText = "";
    private Bitmap editingBmp = null;
    private boolean isImage = false;
    private boolean isModified = false;
    private boolean editorLoading = false;

    // --- Git 状态 ---
    private String gitBranch = "";
    private final List<GitFile> gitFiles = new ArrayList<>();
    private boolean gitLoading = false;

    // --- 终端状态 ---
    private final List<TermSession> termSessions = new ArrayList<>();
    private String activeTermId = null;
    private final NativeTerminalBuffer termOutput = new NativeTerminalBuffer(80, 24);
    private long outputTotal;
    private boolean termExited;
    private boolean termAvailable = true;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean drawingOutput;

    private TerminalInputView termTextView = null;
    private ScrollView termScrollView = null;
    private volatile HttpURLConnection activeTermConn = null;
    private volatile Thread activeTermThread = null;
    private boolean termLoading = false;
    private boolean creatingTerm;
    private boolean autoStartTerm = true;

    private String projectId;
    private String pageError = "";
    private boolean notRepo;
    private final java.util.LinkedHashSet<String> gitSelection = new java.util.LinkedHashSet<>();
    private int fileRequest;
    private int editRequest;
    private boolean saving;
    private int termCols = 80, termRows = 24;
    private Runnable pendingResize;
    private boolean resizing;
    private volatile int streamGeneration;
    private static final ExecutorService termWrites = Executors.newSingleThreadExecutor();
    private final ArrayList<String> roots = new ArrayList<>();

    void leave() {
        if (pendingResize != null) handler.removeCallbacks(pendingResize);
        pendingResize = null;
        disconnectTerm();
        termTextView = null;
        termScrollView = null;
        lastGeneration = -1;
    }

    void show(NativeHome h, String id) {
        lastGeneration = h.generation;
        activeMode = "editor".equals(id) ? MODE_EDITOR : "git".equals(id) ? MODE_GIT
                : "terminal".equals(id) ? MODE_TERM : MODE_FILES;
        pageError = "";
        if (!h.projectId.equals(projectId)) {
            String target = h.projectId;
            if (!target.isEmpty()) {
                read(h, "/api/projects", resp -> {
                    JSONArray list = resp.optJSONArray("projects");
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject p = list.optJSONObject(i);
                        if (p != null && target.equals(p.optString("id")) && !p.optString("path").isEmpty()) {
                            setProject(target, p.optString("path"));
                            loadPage(h);
                            return;
                        }
                    }
                    pageError = "找不到当前项目的目录";
                    render(h);
                });
                return;
            }
            setProject(target, "");
        }
        loadPage(h);
    }

    private void setProject(String id, String dir) {
        projectId = id;
        currentDir = projectRoot = dir;
        gitSelection.clear();
        fileList.clear();
        roots.clear();
    }

    private void loadPage(NativeHome h) {
        if (activeMode == MODE_FILES) loadFiles(h);
        else if (activeMode == MODE_GIT) loadGit(h);
        else if (activeMode == MODE_TERM) loadTerm(h);
        else if (editingPath != null && editorLoading) readFileContent(h, editingPath);
        else render(h);
    }

    private void read(NativeHome h, String path, Consumer<JSONObject> callback) {
        int generation = h.generation;
        NativeRequests.call(h.a, "GET", path, null, response -> {
            if (h.generation == generation && lastGeneration == generation) callback.accept(response);
        }, error -> {
            if (h.generation != generation || lastGeneration != generation) return;
            filesLoading = gitLoading = termLoading = false;
            pageError = error;
            render(h);
        });
    }

    private void render(NativeHome h) {
        h.body.removeAllViews();

        // 顶部模式导航栏
        LinearLayout navBar = new LinearLayout(h.a);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, -2);
        navParams.setMargins(h.dp(16), h.dp(4), h.dp(16), h.dp(6));
        navBar.setLayoutParams(navParams);
        navBar.setBackground(PixelUi.shape(h.a, "surfaceContainerHigh", 28));
        int navPad = h.dp(4);
        navBar.setPadding(navPad, navPad, navPad, navPad);

        makeTabButton(h, navBar, "文件", activeMode == MODE_FILES, () -> h.nav("files"));
        makeTabButton(h, navBar, isModified ? "编辑器 *" : "编辑器", activeMode == MODE_EDITOR, () -> h.nav("editor"));
        makeTabButton(h, navBar, "Git", activeMode == MODE_GIT, () -> h.nav("git"));
        makeTabButton(h, navBar, "终端", activeMode == MODE_TERM, () -> h.nav("terminal"));
        if (activeMode == MODE_TERM && pageError.isEmpty()) {
            NativeTerminalLayout panel = new NativeTerminalLayout(h.a);
            h.body.addView(panel, new LinearLayout.LayoutParams(-1, -1));
            panel.addView(navBar);
            renderTermView(h, panel, navBar);
            return;
        }
        h.body.addView(navBar);

        if (!pageError.isEmpty()) {
            h.text(h.body, pageError);
            h.button(h.body, "重试", () -> show(h, h.screen));
            return;
        }

        // 主体区域
        switch (activeMode) {
            case MODE_FILES:
                renderFilesView(h);
                break;
            case MODE_EDITOR:
                renderEditorView(h);
                break;
            case MODE_GIT:
                renderGitView(h);
                break;
        }
    }

    // =========================================================================
    // 1. 文件面板 (浏览 / 新建 / 重命名 / 删除 / 发现项目根)
    // =========================================================================

    private void renderFilesView(NativeHome h) {
        int m16 = h.dp(16);
        int pad12 = h.dp(12);

        LinearLayout headerGroup = PixelUi.group(h.body);
        LinearLayout.LayoutParams hgLp = (LinearLayout.LayoutParams) headerGroup.getLayoutParams();
        hgLp.setMargins(m16, m16, m16, h.dp(8));
        headerGroup.setLayoutParams(hgLp);
        headerGroup.setPadding(pad12, pad12, pad12, pad12);
        headerGroup.setOrientation(LinearLayout.HORIZONTAL);
        headerGroup.setGravity(Gravity.CENTER_VERTICAL);

        String displayPath = currentDir.isEmpty() ? (projectRoot.isEmpty() ? "/" : projectRoot) : currentDir;
        String dirBaseName = "/".equals(displayPath) ? "/" : basename(displayPath);

        boolean canGoUp = !currentDir.isEmpty() && !currentDir.equals("/");
        ImageButton upBtn = PixelUi.icon(h.a, "back", "上一级", () -> {
            int lastSlash = currentDir.lastIndexOf('/');
            if (lastSlash > 0) {
                currentDir = currentDir.substring(0, lastSlash);
            } else {
                currentDir = "/";
            }
            loadFiles(h);
        });
        upBtn.setEnabled(canGoUp);
        upBtn.setAlpha(canGoUp ? 1.0f : 0.38f);
        headerGroup.addView(upBtn);

        LinearLayout pathBox = new LinearLayout(h.a);
        pathBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        pbLp.setMarginStart(h.dp(8));
        pbLp.setMarginEnd(h.dp(8));
        pathBox.setLayoutParams(pbLp);

        TextView titleTv = new TextView(h.a);
        titleTv.setText(dirBaseName);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(PixelUi.color(h.a, "onSurface"));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        pathBox.addView(titleTv);

        TextView subPathTv = new TextView(h.a);
        subPathTv.setText(displayPath);
        subPathTv.setVisibility(dirBaseName.equals(displayPath) ? View.GONE : View.VISIBLE);
        subPathTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subPathTv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
        subPathTv.setSingleLine(true);
        subPathTv.setEllipsize(TextUtils.TruncateAt.START);
        pathBox.addView(subPathTv);

        headerGroup.addView(pathBox);

        ImageButton overflowBtn = PixelUi.icon(h.a, "more", "更多操作", null);
        overflowBtn.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(h.a, v);
            menu.getMenu().add(0, 1, 0, "新建文件");
            menu.getMenu().add(0, 2, 0, "新建目录");
            menu.getMenu().add(0, 3, 0, "刷新");
            menu.getMenu().add(0, 4, 0, "位置");
            menu.getMenu().add(0, 5, 0, "筛选");
            menu.getMenu().add(0, 6, 0, selecting ? "退出多选" : "多选");
            if (selecting) {
                menu.getMenu().add(0, 7, 0, "压缩选中");
            }
            menu.setOnMenuItemClickListener(mi -> {
                switch (mi.getItemId()) {
                    case 1:
                        promptCreate(h, false);
                        return true;
                    case 2:
                        promptCreate(h, true);
                        return true;
                    case 3:
                        loadFiles(h);
                        return true;
                    case 4:
                        String[] paths = roots.toArray(new String[0]);
                        new MaterialAlertDialogBuilder(h.a).setTitle("选择位置").setItems(paths, (dialog, index) -> {
                            currentDir = paths[index];
                            loadFiles(h);
                        }).setNegativeButton("取消", null).show();
                        return true;
                    case 5:
                        LinearLayout box = new LinearLayout(h.a);
                        box.setOrientation(LinearLayout.VERTICAL);
                        box.setPadding(m16, h.dp(8), m16, 0);
                        EditText filter = new EditText(h.a);
                        filter.setSingleLine(true);
                        filter.setHint("文件名");
                        filter.setText(search);
                        CheckBox hidden = new CheckBox(h.a);
                        hidden.setText("显示隐藏文件");
                        hidden.setChecked(showHidden);
                        box.addView(filter);
                        box.addView(hidden);
                        new MaterialAlertDialogBuilder(h.a).setTitle("筛选文件").setView(box)
                                .setPositiveButton("应用", (d, w) -> {
                                    search = filter.getText().toString();
                                    showHidden = hidden.isChecked();
                                    loadFiles(h);
                                }).setNegativeButton("取消", null).show();
                        return true;
                    case 6:
                        selecting = !selecting;
                        selectedFiles.clear();
                        render(h);
                        return true;
                    case 7:
                        if (selectedFiles.isEmpty()) {
                            h.notice("先选择文件或目录");
                        } else {
                            NativeFileArchive.create(h, new ArrayList<>(selectedFiles), currentDir, () -> loadFiles(h));
                        }
                        return true;
                    default:
                        return false;
                }
            });
            menu.show();
        });
        headerGroup.addView(overflowBtn);

        if (filesLoading) {
            renderLoading(h, h.body, "正在读取目录列表...");
            return;
        }

        ScrollView sv = new ScrollView(h.a);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout listLayout = new LinearLayout(h.a);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(m16, 0, m16, m16);

        if (fileList.isEmpty()) {
            LinearLayout emptyGroup = PixelUi.group(listLayout);
            emptyGroup.setPadding(pad12, h.dp(24), pad12, h.dp(24));

            TextView emptyTv = new TextView(h.a);
            emptyTv.setText(search.isEmpty() ? "当前目录为空" : "没有匹配的文件");
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            emptyTv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
            emptyGroup.addView(emptyTv);
        } else {
            filePage = Math.min(filePage, (fileList.size() - 1) / PAGE_SIZE);
            LinearLayout group = PixelUi.group(listLayout);
            for (int i = filePage * PAGE_SIZE; i < Math.min(fileList.size(), (filePage + 1) * PAGE_SIZE); i++) {
                FileItem item = fileList.get(i);

                LinearLayout itemGroup = new LinearLayout(h.a);
                group.addView(itemGroup);
                itemGroup.setBackground(PixelUi.clickable(h.a, null, 12));
                itemGroup.setMinimumHeight(h.dp(64));
                itemGroup.setOrientation(LinearLayout.HORIZONTAL);
                itemGroup.setGravity(Gravity.CENTER_VERTICAL);
                itemGroup.setPadding(h.dp(8), h.dp(8), h.dp(8), h.dp(8));

                if (selecting) {
                    CheckBox selected = new CheckBox(h.a);
                    selected.setContentDescription("选择 " + item.name);
                    selected.setChecked(selectedFiles.contains(item.path));
                    itemGroup.setTag(selected);
                    selected.setOnCheckedChangeListener((c, on) -> {
                        if (on) selectedFiles.add(item.path); else selectedFiles.remove(item.path);
                    });
                    itemGroup.addView(selected);
                }

                ImageButton typeIcon = PixelUi.icon(h.a, item.isDir ? "projects" : "files", item.isDir ? "目录" : "文件", null);
                typeIcon.setClickable(false);
                typeIcon.setFocusable(false);
                typeIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                itemGroup.addView(typeIcon);

                LinearLayout contentBox = new LinearLayout(h.a);
                contentBox.setOrientation(LinearLayout.VERTICAL);
                contentBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                contentBox.setPadding(h.dp(8), 0, h.dp(8), 0);

                TextView nameTv = new TextView(h.a);
                nameTv.setText(item.name);
                nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                nameTv.setTextColor(PixelUi.color(h.a, "onSurface"));
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.END);
                contentBox.addView(nameTv);

                TextView secondaryTv = new TextView(h.a);
                secondaryTv.setText(item.isDir ? "文件夹" : (formatSize(item.size) + "  " + formatTime(item.mtime)));
                secondaryTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                secondaryTv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
                secondaryTv.setSingleLine(true);
                secondaryTv.setEllipsize(TextUtils.TruncateAt.END);
                contentBox.addView(secondaryTv);

                itemGroup.addView(contentBox);

                itemGroup.setOnClickListener(v -> {
                    if (selecting) {
                        ((CheckBox) itemGroup.getTag()).toggle();
                    } else if (item.isDir) {
                        currentDir = item.path;
                        loadFiles(h);
                    } else {
                        if (item.kind.equals("archive")) {
                            NativeFileArchive.open(h, item.path, currentDir, () -> loadFiles(h));
                        } else if (item.kind.matches("pdf|audio|video|office|docx|sheet")) {
                            NativeFilePreview.open(h, item.path, item.kind);
                        } else {
                            openFile(h, item.path);
                        }
                    }
                });

                ImageButton itemOverflow = PixelUi.icon(h.a, "more", "更多操作，" + item.name, null);
                itemOverflow.setOnClickListener(v -> showItemMenu(h, v, item));
                itemGroup.addView(itemOverflow);
            }
        }

        pager(h, listLayout, fileList.size(), filePage, page -> {
            filePage = page;
            render(h);
        });

        if (truncated) {
            h.text(listLayout, "目录条目较多，请筛选文件名以查看其余结果。");
        }

        sv.addView(listLayout);
        h.body.addView(sv);
    }

    private void showItemMenu(NativeHome h, View anchor, FileItem item) {
        PopupMenu menu = new PopupMenu(h.a, anchor);
        menu.getMenu().add(0, 1, 0, "重命名");
        menu.getMenu().add(0, 2, 0, "删除");
        menu.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                promptRename(h, item);
                return true;
            } else if (mi.getItemId() == 2) {
                promptDelete(h, item);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void loadFiles(NativeHome h) {
        filesLoading = true;
        render(h);
        if (!selectionDir.equals(currentDir)) { filePage = 0; selecting = false; selectedFiles.clear(); selectionDir = currentDir; }
        String q = "?path=" + Uri.encode(currentDir) + "&q=" + Uri.encode(search) + "&hidden=" + (showHidden ? "1" : "0");
        final int request = ++fileRequest;
        read(h, "/api/files" + q, resp -> {
            if (request != fileRequest) return;
            filesLoading = false;
            fileList.clear();
            if (resp != null) {
                if (resp.has("error")) {
                    pageError = resp.optString("message", resp.optString("error"));
                    render(h);
                    return;
                }
                currentDir = resp.optString("path", currentDir);
                truncated = resp.optBoolean("truncated");
                if (projectRoot.isEmpty()) projectRoot = currentDir;
                roots.clear();
                JSONArray locations = resp.optJSONArray("roots");
                for (int i = 0; locations != null && i < locations.length(); i++)
                    roots.add(locations.optString(i));
                if (!roots.contains(projectRoot)) roots.add(0, projectRoot);
                JSONArray arr = resp.optJSONArray("entries");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.optJSONObject(i);
                        if (obj == null) continue;
                        String name = obj.optString("name", "");
                        String p = obj.optString("path", currentDir + "/" + name);
                        boolean dir = obj.optBoolean("dir", obj.optBoolean("isDirectory", "dir".equals(obj.optString("kind"))));
                        long sz = obj.optLong("size", 0);
                        long mt = obj.optLong("mtime", 0);
                        String kd = obj.optString("kind", "");
                        fileList.add(new FileItem(name, p, dir, sz, mt, kd));
                    }
                }
                Collections.sort(fileList, (a, b) -> {
                    if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                });
            }
            render(h);
        });
    }

    private void promptCreate(NativeHome h, boolean isDir) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(h.a);
        builder.setTitle(isDir ? "新建目录" : "新建文件");
        final EditText input = new EditText(h.a);
        input.setHint(isDir ? "目录名称" : "文件名称");
        input.setSingleLine(true);
        builder.setView(input);
        builder.setPositiveButton("创建", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) {
                h.notice("请输入单个文件或目录名称"); return;
            }
            String base = currentDir.endsWith("/") ? currentDir : currentDir + "/";
            String target = base + name;
            String ep = isDir ? "/api/file/mkdir?path=" : "/api/file/new?path=";
            h.post(ep + Uri.encode(target), NativeHome.json(), res -> {
                h.notice(isDir ? "目录创建成功" : "文件创建成功");
                loadFiles(h);
            });
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void promptRename(NativeHome h, FileItem item) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(h.a);
        builder.setTitle("重命名");
        final EditText input = new EditText(h.a);
        input.setText(item.name);
        input.setSelection(item.name.length());
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty() || newName.equals(item.name)) return;
            h.post("/api/file/rename?from=" + Uri.encode(item.path) + "&name=" + Uri.encode(newName),
                    NativeHome.json(), res -> {
                        h.notice("重命名成功");
                        loadFiles(h);
                    });
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void promptDelete(NativeHome h, FileItem item) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(h.a);
        builder.setTitle("确认删除");
        builder.setMessage("确定永久删除 " + (item.isDir ? "目录" : "文件") + "「" + item.name + "」吗？此操作无法撤销。");
        builder.setPositiveButton("删除", (dialog, which) -> {
            h.post("/api/file/delete?path=" + Uri.encode(item.path), NativeHome.json(), res -> {
                h.notice("已删除");
                loadFiles(h);
            });
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // =========================================================================
    // 2. 原生文件编辑器与预览 (流式读取 / 保存 / 冲突解决 / 图片预览)
    // =========================================================================

    private void openFile(NativeHome h, String path) {
        if (saving) { h.notice("正在保存"); return; }
        if (path.equals(editingPath) && !editorLoading) { h.nav("editor"); return; }
        Runnable open = () -> {
            editingPath = path;
            editingText = "";
            editingBmp = null;
            openMtime = 0;
            isModified = false;
            isImage = false;
            editorLoading = true;
            h.nav("editor");
        };
        if (!isModified) open.run();
        else new MaterialAlertDialogBuilder(h.a).setTitle("放弃当前文件的修改？")
                .setMessage(editingPath).setNegativeButton("取消", null)
                .setPositiveButton("放弃并打开", (dialog, index) -> open.run()).show();
    }

    private void renderEditorView(NativeHome h) {
        if (editingPath == null) {
            LinearLayout emptyBox = new LinearLayout(h.a);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            int p = h.dp(16);
            emptyBox.setPadding(p, p, p, p);
            emptyBox.setGravity(Gravity.CENTER);

            TextView tv = new TextView(h.a);
            tv.setText("未打开任何文件，请从「文件」列表中轻触文件进行查看或编辑。");
            tv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
            tv.setGravity(Gravity.CENTER);
            emptyBox.addView(tv);

            Button goFiles = makeBtn(h, "前往文件列表", () -> {
                h.nav("files");
            });
            emptyBox.addView(goFiles);
            h.body.addView(emptyBox);
            return;
        }

        // 顶部操作栏
        LinearLayout bar = new LinearLayout(h.a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = h.dp(6);
        bar.setPadding(pad, pad, pad, pad);

        TextView titleTv = new TextView(h.a);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        titleTv.setText(basename(editingPath) + (isModified ? " *" : ""));
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(PixelUi.color(h.a, "onSurface"));
        bar.addView(titleTv);

        if (!isImage && !editorLoading && editingPath.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|html|htm)")) {
            bar.addView(makeSmallBtn(h, "预览", () -> NativeDocumentPreview.show(h, editingPath, editingText)));
        }
        if (!isImage) {
            Button saveBtn = makeSmallBtn(h, "保存", () -> {
                saveFileContent(h);
            });
            saveBtn.setEnabled(!saving && !editorLoading && openMtime > 0);
            bar.addView(saveBtn);
        }

        Button closeBtn = makeSmallBtn(h, "关闭", () -> {
            if (isModified) {
                new MaterialAlertDialogBuilder(h.a)
                        .setTitle("放弃修改？")
                        .setMessage("当前文件有未保存的改动，确定直接关闭吗？")
                        .setPositiveButton("放弃并关闭", (d, w) -> {
                            editingPath = null;
                            isModified = false;
                            h.nav("files");
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                editingPath = null;
                h.nav("files");
            }
        });
        closeBtn.setEnabled(!saving);
        bar.addView(closeBtn);
        h.body.addView(bar);
        addDivider(h, h.body);

        if (editorLoading) {
            renderLoading(h, h.body, "正在加载文件内容...");
            return;
        }

        if (isImage) {
            ScrollView imgScroll = new ScrollView(h.a);
            imgScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
            LinearLayout imgBox = new LinearLayout(h.a);
            imgBox.setOrientation(LinearLayout.VERTICAL);
            imgBox.setGravity(Gravity.CENTER);
            imgBox.setPadding(pad, pad, pad, pad);

            if (editingBmp != null) {
                ImageView iv = new ImageView(h.a);
                iv.setAdjustViewBounds(true);
                iv.setImageBitmap(editingBmp);
                imgBox.addView(iv);

                TextView spec = new TextView(h.a);
                spec.setText("分辨率: " + editingBmp.getWidth() + " × " + editingBmp.getHeight());
                spec.setPadding(0, h.dp(8), 0, 0);
                spec.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
                imgBox.addView(spec);
            } else {
                TextView errTv = new TextView(h.a);
                errTv.setText("图片无法在本地解码预览");
                errTv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
                imgBox.addView(errTv);
            }
            imgScroll.addView(imgBox);
            h.body.addView(imgScroll);
        } else {
            // 原生文本编辑区：加权滚动布局
            ScrollView editScroll = new ScrollView(h.a);
            editScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

            final EditText editor = new EditText(h.a);
            editor.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            editor.setEnabled(!saving);
            editor.setText(editingText);
            editor.setTypeface(Typeface.MONOSPACE);
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            editor.setGravity(Gravity.TOP | Gravity.START);
            editor.setTextColor(PixelUi.color(h.a, "onSurface"));
            editor.setBackground(null);
            int edPad = h.dp(8);
            editor.setPadding(edPad, edPad, edPad, edPad);
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

            final TextView charCount = new TextView(h.a);
            editor.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    editingText = s.toString();
                    charCount.setText("字符数 " + editingText.length());
                    if (!isModified) {
                        isModified = true;
                        titleTv.setText(basename(editingPath) + " *");
                    }
                }
            });

            editScroll.addView(editor);
            h.body.addView(editScroll);

            LinearLayout bottomInfo = new LinearLayout(h.a);
            bottomInfo.setOrientation(LinearLayout.HORIZONTAL);
            bottomInfo.setPadding(edPad, h.dp(4), edPad, h.dp(4));
            charCount.setText("字符数 " + editingText.length());
            charCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            charCount.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
            bottomInfo.addView(charCount);
            h.body.addView(bottomInfo);
        }
    }

    private void readFileContent(NativeHome h, String path) {
        final int id = ++editRequest, generation = h.generation;
        render(h);
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = Env.gateway("/api/file?path=" + Uri.encode(path));
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                if (connection.getResponseCode() != 200) throw new IOException("读取失败 (HTTP " + connection.getResponseCode() + ")");
                double mtime = Double.parseDouble(connection.getHeaderField("x-mtime"));
                boolean image = isImageFile(path, connection.getContentType());
                byte[] bytes;
                try (InputStream in = connection.getInputStream()) { bytes = bounded(in, image ? 16 << 20 : 2 << 20); }
                Bitmap decoded = null;
                String content = "";
                if (image) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = 1;
                    while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > 2048) opts.inSampleSize *= 2;
                    decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                    if (decoded == null) throw new IOException("无法解码图片");
                } else {
                    for (byte b : bytes) if (b == 0) throw new IOException("此文件为二进制");
                    content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                }
                final Bitmap bmp = decoded;
                final String result = content;
                h.a.runOnUiThread(() -> {
                    if (id != editRequest || !path.equals(editingPath)) return;
                    editingText = result;
                    editingBmp = bmp;
                    openMtime = mtime;
                    isImage = image;
                    editorLoading = false;
                    if (generation == lastGeneration && activeMode == MODE_EDITOR) render(h);
                });
            } catch (Exception e) {
                h.a.runOnUiThread(() -> {
                    if (id != editRequest || !path.equals(editingPath)) return;
                    editorLoading = false;
                    editingPath = null;
                    if (generation != lastGeneration || activeMode != MODE_EDITOR) return;
                    render(h);
                    new MaterialAlertDialogBuilder(h.a).setTitle("无法在编辑器中打开").setMessage(e.getMessage())
                            .setPositiveButton("用其他应用打开", (d,w) -> NativeFilePreview.open(h,path,"other"))
                            .setNegativeButton("取消", null).show();
                });
            } finally { if (connection != null) connection.disconnect(); }
        }, "native-file-read").start();
    }

    private static byte[] bounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) >= 0;) {
            if (n > limit - bytes.size()) throw new IOException("文件超过预览大小上限");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    private void saveFileContent(NativeHome h) {
        if (saving || editorLoading || openMtime <= 0 || editingPath == null) return;
        String path = editingPath, content = editingText;
        double mtime = openMtime;
        saving = true;
        render(h);
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                connection = Env.gateway("POST", "/api/file/save?path=" + Uri.encode(path) + "&mtime=" + mtime, data);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(data.length);
                connection.setReadTimeout(20000);
                connection.setConnectTimeout(10000);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
                int status = connection.getResponseCode();
                if (status == 409) throw new IOException("磁盘文件已变化，未覆盖。请复制编辑内容后重新打开文件。");
                if (status != 200) throw new IOException("保存失败 (HTTP " + status + ")");
                double stamp;
                try (InputStream in = connection.getInputStream()) {
                    stamp = new JSONObject(new String(bounded(in, 16384), StandardCharsets.UTF_8)).getDouble("mtime");
                }
                h.a.runOnUiThread(() -> {
                    saving = false;
                    if (!path.equals(editingPath)) return;
                    openMtime = stamp;
                    isModified = !content.equals(editingText);
                    if (lastGeneration == h.generation && activeMode == MODE_EDITOR) { render(h); h.notice("文件已保存"); }
                });
            } catch (Exception e) {
                h.a.runOnUiThread(() -> {
                    saving = false;
                    if (lastGeneration == h.generation && activeMode == MODE_EDITOR) { render(h); h.notice(e.getMessage()); }
                });
            } finally { if (connection != null) connection.disconnect(); }
        }, "native-file-save").start();
    }

    // =========================================================================
    // 3. Git 面板 (状态 / 差异 / 撤销 / 分支切换 / 提交)
    // =========================================================================

    private void renderGitView(NativeHome h) {
        LinearLayout header = new LinearLayout(h.a);
        header.setOrientation(LinearLayout.VERTICAL);
        int pad = h.dp(8);
        header.setPadding(pad, pad, pad, pad);

        TextView branchTv = new TextView(h.a);
        branchTv.setText("Git 当前分支: " + (gitBranch.isEmpty() ? "未知" : gitBranch));
        branchTv.setTypeface(Typeface.DEFAULT_BOLD);
        branchTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        branchTv.setTextColor(PixelUi.color(h.a, "onSurface"));
        header.addView(branchTv);

        LinearLayout actRow = new LinearLayout(h.a);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setPadding(0, h.dp(4), 0, 0);

        if (!notRepo) {
            actRow.addView(makeBtn(h, "切换分支", () -> promptSwitchBranch(h)));
            NativeGitActions.add(h, actRow, currentDir, () -> loadGit(h));
        }
        actRow.addView(PixelUi.icon(h.a, "refresh", "刷新 Git 状态", () -> loadGit(h)));
        header.addView(actRow);

        h.body.addView(header);
        addDivider(h, h.body);

        if (gitLoading) {
            renderLoading(h, h.body, "正在读取 Git 仓库状态...");
            return;
        }

        if (notRepo) {
            h.text(h.body, "当前目录没有 Git 仓库。");
            h.button(h.body, "初始化 Git 仓库", () -> h.post("/api/git/init", NativeHome.json("dir", currentDir), r -> loadGit(h)));
            return;
        }
        // 提交区（有改动时展示）
        if (!gitFiles.isEmpty()) {
            LinearLayout commitBox = new LinearLayout(h.a);
            commitBox.setOrientation(LinearLayout.HORIZONTAL);
            commitBox.setGravity(Gravity.CENTER_VERTICAL);
            commitBox.setPadding(pad, h.dp(4), pad, h.dp(4));

            final EditText msgInput = new EditText(h.a);
            msgInput.setHint("输入本次提交说明...");
            msgInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            msgInput.setSingleLine(true);
            commitBox.addView(msgInput);

            Button commitBtn = makeSmallBtn(h, "提交选中", () -> {
                if (gitSelection.isEmpty()) { h.notice("请先勾选要提交的文件"); return; }
                String msg = msgInput.getText().toString().trim();
                if (msg.isEmpty()) {
                    h.notice("请填写提交说明");
                    return;
                }
                new MaterialAlertDialogBuilder(h.a)
                        .setTitle("确认提交")
                        .setMessage("提交选中的 " + gitSelection.size() + " 个文件？\n" + msg)
                        .setPositiveButton("确定提交", (d, w) -> {
                            NativeGitActions.write(h, "commit", NativeHome.json("message", msg, "files", new JSONArray(gitSelection), "dir", currentDir), resp -> {
                                gitSelection.clear();
                                h.notice("提交成功: " + resp.optString("hash", ""));
                                loadGit(h);
                            });
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            commitBox.addView(commitBtn);
            h.body.addView(commitBox);
            addDivider(h, h.body);
        }

        ScrollView sv = new ScrollView(h.a);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        LinearLayout filesLayout = new LinearLayout(h.a);
        filesLayout.setOrientation(LinearLayout.VERTICAL);
        filesLayout.setPadding(pad, pad, pad, pad);

        if (gitFiles.isEmpty()) {
            TextView cleanTv = new TextView(h.a);
            cleanTv.setText("工作区非常干净，没有未提交的改动。");
            cleanTv.setGravity(Gravity.CENTER);
            cleanTv.setPadding(pad, pad, pad, pad);
            cleanTv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
            filesLayout.addView(cleanTv);
        } else {
            gitPage = Math.min(gitPage, (gitFiles.size() - 1) / PAGE_SIZE);
            for (int i = gitPage * PAGE_SIZE; i < Math.min(gitFiles.size(), (gitPage + 1) * PAGE_SIZE); i++) {
                GitFile gf = gitFiles.get(i);
                LinearLayout row = new LinearLayout(h.a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(pad, pad, pad, pad);

                CheckBox selected = new CheckBox(h.a);
                selected.setContentDescription("提交 " + gf.path);
                selected.setChecked(gitSelection.contains(gf.path));
                selected.setOnCheckedChangeListener((box, checked) -> {
                    if (checked) gitSelection.add(gf.path);
                    else gitSelection.remove(gf.path);
                });
                row.addView(selected);
                TextView kindTv = new TextView(h.a);
                kindTv.setText(formatGitKind(gf.kind) + " ");
                kindTv.setTypeface(Typeface.DEFAULT_BOLD);
                kindTv.setTextColor(PixelUi.color(h.a, "primary"));
                row.addView(kindTv);

                TextView pathTv = new TextView(h.a);
                pathTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                pathTv.setText(gf.path);
                pathTv.setTextColor(PixelUi.color(h.a, "onSurface"));
                row.addView(pathTv);

                Button diffBtn = makeSmallBtn(h, "差异", () -> showDiffDialog(h, gf.path));
                Button discardBtn = makeSmallBtn(h, "撤销", () -> promptDiscard(h, gf.path));
                row.addView(diffBtn);
                row.addView(discardBtn);

                filesLayout.addView(row);
                addDivider(h, filesLayout);
            }
        }
        pager(h, filesLayout, gitFiles.size(), gitPage, page -> { gitPage = page; render(h); });
        sv.addView(filesLayout);
        h.body.addView(sv);
    }

    private void loadGit(NativeHome h) {
        gitLoading = true;
        render(h);
        read(h, "/api/git/status?dir=" + Uri.encode(currentDir), resp -> {
            notRepo = resp.optBoolean("notRepo");
            if (resp.has("error") && !notRepo) pageError = resp.optString("error");
            gitLoading = false;
            gitFiles.clear();
            if (resp != null) {
                gitBranch = resp.optString("branch", "");
                JSONArray arr = resp.optJSONArray("files");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.optJSONObject(i);
                        if (obj == null) continue;
                        String p = obj.optString("path", obj.optString("file", ""));
                        String k = obj.optString("kind", "");
                        gitFiles.add(new GitFile(p, k));
                    }
                }
            }
            java.util.HashSet<String> changed = new java.util.HashSet<>();
            for (GitFile gf : gitFiles) changed.add(gf.path);
            gitSelection.retainAll(changed);
            render(h);
        });
    }

    private void promptSwitchBranch(NativeHome h) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(h.a);
        builder.setTitle("切换分支");
        LinearLayout box = new LinearLayout(h.a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = h.dp(16);
        box.setPadding(pad, pad, pad, pad);

        final EditText bInput = new EditText(h.a);
        bInput.setHint("分支名称");
        box.addView(bInput);

        final CheckBox cb = new CheckBox(h.a);
        cb.setText("若分支不存在则新建 (-c)");
        box.addView(cb);

        builder.setView(box);
        builder.setPositiveButton("切换", (dialog, which) -> {
            String target = bInput.getText().toString().trim();
            if (target.isEmpty()) return;
            NativeGitActions.write(h, "switch", NativeHome.json("dir", currentDir, "branch", target, "create", cb.isChecked()), res -> {
                h.notice("已切换到 " + target);
                loadGit(h);
            });
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showDiffDialog(NativeHome h, String path) {
        h.get("/api/git/diff?dir=" + Uri.encode(currentDir) + "&file=" + Uri.encode(path), resp -> {
            String diff = resp != null ? resp.optString("diff", "") : "";
            if (diff.isEmpty()) diff = "（无差异内容）";

            AlertDialog.Builder b = new MaterialAlertDialogBuilder(h.a);
            b.setTitle("差异: " + basename(path));
            ScrollView sv = new ScrollView(h.a);
            HorizontalScrollView hsv = new HorizontalScrollView(h.a);
            TextView tv = new TextView(h.a);
            tv.setText(diff);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            int p = h.dp(12);
            tv.setPadding(p, p, p, p);
            tv.setTextColor(PixelUi.color(h.a, "onSurface"));
            hsv.addView(tv);
            sv.addView(hsv);
            b.setView(sv);
            b.setPositiveButton("关闭", null);
            b.show();
        });
    }

    private void promptDiscard(NativeHome h, String path) {
        new MaterialAlertDialogBuilder(h.a)
                .setTitle("确认撤销改动")
                .setMessage("确定撤销对文件「" + basename(path) + "」的所有修改吗？未提交的改动将永久丢失。")
                .setPositiveButton("撤销改动", (d, w) -> {
                    NativeGitActions.write(h, "discard", NativeHome.json("dir", currentDir, "file", path), res -> {
                        h.notice("已撤销修改");
                        loadGit(h);
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatGitKind(String kind) {
        if ("added".equals(kind)) return "[新增]";
        if ("renamed".equals(kind)) return "[改名]";
        if ("modify".equals(kind) || "modified".equals(kind)) return "[改动]";
        if ("conflict".equals(kind)) return "[冲突]";
        if ("new".equals(kind)) return "[未跟踪]";
        if ("delete".equals(kind) || "deleted".equals(kind)) return "[删除]";
        if ("staged".equals(kind)) return "[暂存]";
        return "[" + kind + "]";
    }

    // =========================================================================
    // 4. 原生终端面板 (PTY 会话 / SSE 回放与流 / 虚拟控制键 / 输入)
    // =========================================================================

    private void renderTermView(NativeHome h, NativeTerminalLayout panel, View navBar) {
        LinearLayout topBar = new LinearLayout(h.a);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = h.dp(4);
        topBar.setPadding(pad, pad, pad, pad);

        HorizontalScrollView sessionScroll = new HorizontalScrollView(h.a);
        sessionScroll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        LinearLayout sessionBox = new LinearLayout(h.a);
        sessionBox.setOrientation(LinearLayout.HORIZONTAL);

        for (TermSession ts : termSessions) {
            String title = (ts.title.isEmpty() ? ts.id : ts.title) + (ts.alive ? "" : " (已退出)");
            Button sBtn = makeSmallBtn(h, title, () -> {
                if (!ts.id.equals(activeTermId)) {
                    connectTerm(h, ts.id);
                }
            });
            if (ts.id.equals(activeTermId)) {
                sBtn.setTypeface(Typeface.DEFAULT_BOLD);
            }
            sessionBox.addView(sBtn);
        }
        sessionScroll.addView(sessionBox);
        topBar.addView(sessionScroll);

        ImageButton newTermBtn = PixelUi.icon(h.a, "add", "新建终端", () -> createTerm(h));
        newTermBtn.setEnabled(termAvailable && !termLoading && !creatingTerm);
        topBar.addView(newTermBtn);

        Runnable closeTerm = () -> {
            if (activeTermId == null) return;
            new MaterialAlertDialogBuilder(h.a)
                    .setTitle("关闭终端")
                    .setMessage("确定结束当前终端会话进程吗？")
                    .setPositiveButton("关闭", (d, w) -> {
                        h.post("/api/term/" + Uri.encode(activeTermId) + "/kill", NativeHome.json(), res -> {
                            autoStartTerm = false;
                            loadTerm(h);
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        };

        Runnable clearTerm = () -> {
            termOutput.reset();
            if (termTextView != null) termTextView.setText("");
        };
        ImageButton more = PixelUi.icon(h.a, "more", "终端操作", null);
        more.setOnClickListener(anchor -> {
            PopupMenu menu = new PopupMenu(h.a, anchor);
            menu.getMenu().add(0, 1, 0, "清屏");
            menu.getMenu().add(0, 2, 0, "结束终端").setEnabled(activeTermId != null);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) clearTerm.run(); else closeTerm.run();
                return true;
            });
            menu.show();
        });
        topBar.addView(more);

        panel.addView(topBar);
        View divider = addDivider(h, panel);

        if (termLoading) {
            renderLoading(h, panel, "正在同步终端会话...");
            return;
        }

        if (!termAvailable && termSessions.isEmpty()) {
            h.text(panel, "当前运行环境没有可用的终端组件。");
            return;
        }
        if (activeTermId == null) {
            h.button(panel, "新建终端", () -> createTerm(h));
            return;
        }
        final String sessionId = activeTermId;
        // 终端输出滚动区域 (加权占满)
        termScrollView = new ScrollView(h.a);
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        outputParams.setMargins(h.dp(12), h.dp(6), h.dp(12), 0);
        termScrollView.setLayoutParams(outputParams);
        termScrollView.setBackground(PixelUi.shape(h.a, "surfaceContainerLow", 16));
        termScrollView.setClipToOutline(true);

        TerminalKeyInput input = new TerminalKeyInput(data -> sendTermKey(h, sessionId, data));
        termTextView = TerminalInputView.attach(termScrollView, input);
        termTextView.setTypeface(Typeface.MONOSPACE);
        termTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        termTextView.setTextColor(PixelUi.color(h.a, "onSurface"));
        int outPad = h.dp(12);
        termTextView.setPadding(outPad, outPad, outPad, outPad);
        termTextView.setText(termOutput.styledText());

        termScrollView.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (activeTermId == null || termScrollView != v || r <= l || b <= t) return;
            int cols = Math.max(20, Math.min(500, (int)((r-l-2*outPad) / termTextView.getPaint().measureText("M"))));
            int rows = Math.max(5, Math.min(300, (b-t-2*outPad) / Math.max(1, termTextView.getLineHeight())));
            if (cols == termCols && rows == termRows) return;
            termCols = cols; termRows = rows;
            scheduleTermResize(h);
        });
        panel.addView(termScrollView);

        View keys = TerminalControls.add(panel, input);
        panel.bind(navBar, topBar, divider, termScrollView, termTextView, keys);
    }

    private void createTerm(NativeHome h) {
        if (creatingTerm) return;
        creatingTerm = true;
        if (activeTermId == null) {
            termLoading = true;
            render(h);
        }
        NativeRequests.call(h.a, "POST", "/api/term/new", NativeHome.json("cols", termCols,
                "rows", termRows, "cwd", currentDir), response -> {
            creatingTerm = false;
            if (lastGeneration != h.generation || activeMode != MODE_TERM) return;
            termLoading = false;
            JSONObject term = response.optJSONObject("term");
            if (term == null || term.optString("id").isEmpty()) {
                pageError = "未能新建终端";
                render(h);
                return;
            }
            String id = term.optString("id");
            termSessions.add(new TermSession(id, term.optString("title", id), term.optBoolean("alive", true)));
            autoStartTerm = true;
            connectTerm(h, id);
        }, error -> {
            creatingTerm = false;
            if (lastGeneration != h.generation || activeMode != MODE_TERM) return;
            termLoading = false;
            if (activeTermId == null) {
                pageError = error;
                render(h);
            } else h.notice(error);
        });
    }

    private void loadTerm(NativeHome h) {
        termLoading = true;
        render(h);
        read(h, "/api/term/list", resp -> {
            termAvailable = resp.optBoolean("available", false);
            termLoading = false;
            termSessions.clear();
            if (resp != null) {
                JSONArray arr = resp.optJSONArray("terms");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.optJSONObject(i);
                        if (obj == null) continue;
                        String tid = obj.optString("id", "");
                        String title = obj.optString("title", tid);
                        boolean alive = obj.optBoolean("alive", true);
                        termSessions.add(new TermSession(tid, title, alive));
                    }
                }
            }
            TermSession active = null, live = null;
            for (TermSession session : termSessions) {
                if (session.alive) live = session;
                if (session.id.equals(activeTermId)) active = session;
            }
            if (creatingTerm) {
                termLoading = true;
                render(h);
            } else if (live != null) {
                connectTerm(h, active != null && active.alive ? active.id : live.id);
            } else if (termAvailable && autoStartTerm) {
                activeTermId = null;
                disconnectTerm();
                createTerm(h);
            } else if (active != null) {
                connectTerm(h, active.id);
            } else {
                activeTermId = null;
                disconnectTerm();
                render(h);
            }
        });
    }

    private void connectTerm(NativeHome h, String termId) {
        disconnectTerm();
        if (!termId.equals(activeTermId)) {
            termOutput.reset();
            termExited = false;
            outputTotal = 0;
        }
        activeTermId = termId;
        render(h);
        scheduleTermResize(h);
        streamTerm(h, termId);
    }

    private void streamTerm(NativeHome h, String termId) {
        final int ticket = streamGeneration;
        activeTermThread = new Thread(() -> {
            HttpURLConnection connection = null;
            boolean finished = false;
            try {
                connection = Env.gateway("/api/term/" + Uri.encode(termId) + "/stream");
                synchronized (this) {
                    if (ticket != streamGeneration) return;
                    activeTermConn = connection;
                }
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(65000);
                if (connection.getResponseCode() != 200) throw new IOException("终端连接失败 (HTTP " + connection.getResponseCode() + ")");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line, event = "";
                    while (ticket == streamGeneration && (line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) event = line.substring(6).trim();
                        else if (line.startsWith("data:")) {
                            Object value = new org.json.JSONTokener(line.substring(5).trim()).nextValue();
                            final String type = event;
                            h.a.runOnUiThread(() -> {
                                if (ticket != streamGeneration) return;
                                if ("replay".equals(type) && value instanceof JSONObject) {
                                    JSONObject replay = (JSONObject)value;
                                    String data = replay.optString("data");
                                    long total = replay.optLong("total", data.length());
                                    long start = total - data.length();
                                    if (outputTotal > total || outputTotal < start) {
                                        termOutput.reset(); termExited = false; outputTotal = start;
                                    }
                                    appendTermOutput(data.substring((int)(outputTotal - start)));
                                    outputTotal = total;
                                } else if ("o".equals(type) && value instanceof String) {
                                    outputTotal += ((String)value).length();
                                    appendTermOutput((String)value);
                                } else if ("exit".equals(type) && value instanceof JSONObject && !termExited) {
                                    termExited = true;
                                    appendTermOutput("\r\n[终端已退出，代码 " + ((JSONObject)value).optInt("code") + "]\r\n");
                                }
                            });
                            if ("exit".equals(event)) { finished = true; break; }
                        }
                    }
                }
            } catch (Exception e) {
                h.a.runOnUiThread(() -> { if (ticket == streamGeneration) h.notice(e.getMessage()); });
            } finally {
                if (connection != null) connection.disconnect();
                synchronized (this) { if (ticket == streamGeneration) activeTermConn = null; }
                if (!finished) handler.postDelayed(() -> {
                    if (ticket == streamGeneration && lastGeneration == h.generation) streamTerm(h, termId);
                }, 2500);
            }
        }, "native-terminal-stream");
        activeTermThread.start();
    }

    // During IME motion many different row counts can arrive before the PTY acknowledges one.
    private void scheduleTermResize(NativeHome h) {
        if (pendingResize != null) handler.removeCallbacks(pendingResize);
        pendingResize = () -> {
            pendingResize = null;
            if (activeTermId == null || termExited || termScrollView == null || lastGeneration != h.generation) return;
            String id = activeTermId;
            termOutput.resize(termCols, termRows);
            resizing = true;
            NativeRequests.call(h.a, "POST", "/api/term/" + Uri.encode(id) + "/resize",
                    NativeHome.json("cols", termCols, "rows", termRows), ok -> finishTermResize(), error -> {
                        if (id.equals(activeTermId) && lastGeneration == h.generation) h.notice(error);
                        finishTermResize();
                    });
        };
        if (!resizing) handler.postDelayed(pendingResize, 80);
    }

    private void finishTermResize() {
        resizing = false;
        if (pendingResize != null) handler.postDelayed(pendingResize, 80);
    }

    private boolean sendTermKey(NativeHome h, String tid, String data) {
        if (lastGeneration != h.generation || activeMode != MODE_TERM || !tid.equals(activeTermId) || termExited) {
            if (termExited) h.notice("终端已退出");
            return false;
        }
        termWrites.execute(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                connection = Env.gateway("POST", "/api/term/" + Uri.encode(tid) + "/in", bytes);
                connection.setDoOutput(true);
                connection.setReadTimeout(6000);
                connection.setConnectTimeout(6000);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = connection.getOutputStream()) { os.write(bytes); }
                int status = connection.getResponseCode();
                if (status != 200) throw new IOException(status == 409 ? "终端已退出" : "终端写入失败 (HTTP " + status + ")");
            } catch (Exception e) {
                h.a.runOnUiThread(() -> {
                    if (lastGeneration == h.generation && tid.equals(activeTermId)) h.notice(e.getMessage());
                });
            } finally { if (connection != null) connection.disconnect(); }
        });
        return true;
    }

    private void appendTermOutput(String raw) {
        if (raw.isEmpty()) return;
        termOutput.feed(raw);
        if (drawingOutput) return;
        drawingOutput = true;
        handler.postDelayed(() -> {
            drawingOutput = false;
            if (termTextView == null || termScrollView == null) return;
            termTextView.display(termOutput.styledText());
        }, 32);
    }

    private synchronized void disconnectTerm() {
        streamGeneration++;
        HttpURLConnection old = activeTermConn;
        activeTermConn = null;
        if (old != null) new Thread(old::disconnect, "native-terminal-close").start();
        if (activeTermThread != null) activeTermThread.interrupt();
        activeTermThread = null;
    }

    // =========================================================================
    // 5. 样式与原生控件工具方法 (遵循主题、无硬编码色值)
    // =========================================================================

    private static void pager(NativeHome h, LinearLayout parent, int total, int current, Consumer<Integer> move) {
        if (total <= PAGE_SIZE) return;
        LinearLayout row = new LinearLayout(h.a);
        Button before = makeSmallBtn(h, "上一页", () -> move.accept(current - 1));
        before.setEnabled(current > 0);
        row.addView(before);
        TextView count = new TextView(h.a);
        count.setText((current + 1) + " / " + ((total + PAGE_SIZE - 1) / PAGE_SIZE));
        row.addView(count);
        Button after = makeSmallBtn(h, "下一页", () -> move.accept(current + 1));
        after.setEnabled((current + 1) * PAGE_SIZE < total);
        row.addView(after);
        parent.addView(row);
    }

    private static Button createPixelBtn(NativeHome h, String title, String bgRole, String fgRole,
                                         int radiusDp, int padHDp, int padVDp, float sp,
                                         int minWidthDp, Runnable act) {
        Button b = new Button(h.a);
        b.setText(title);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinimumWidth(h.dp(minWidthDp));
        b.setMinWidth(h.dp(minWidthDp));
        b.setMinimumHeight(h.dp(48));
        b.setMinHeight(h.dp(48));
        b.setPadding(h.dp(padHDp), h.dp(padVDp), h.dp(padHDp), h.dp(padVDp));

        int fg = PixelUi.color(h.a, fgRole);
        int fgDisabled = Color.argb(97, Color.red(fg), Color.green(fg), Color.blue(fg));
        ColorStateList textColors = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{fgDisabled, fg}
        );
        b.setTextColor(textColors);

        b.setBackground(PixelUi.clickable(h.a, bgRole, radiusDp));

        if (act != null) {
            b.setOnClickListener(v -> act.run());
        }
        return b;
    }

    private static View addDivider(NativeHome h, LinearLayout parent) {
        View line = new View(h.a);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, h.dp(1))));
        line.setBackgroundColor(PixelUi.color(h.a, "outlineVariant"));
        parent.addView(line);
        return line;
    }

    private static Button makeTabButton(NativeHome h, LinearLayout parent, String title, boolean active, Runnable act) {
        String bgRole = active ? "secondaryContainer" : null;
        String fgRole = active ? "onSecondaryContainer" : "onSurfaceVariant";
        Button b = createPixelBtn(h, title, bgRole, fgRole, 24, 4, 4, 14, 0, act);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        b.setSelected(active);
        b.setMaxLines(1);
        b.setEllipsize(TextUtils.TruncateAt.END);
        if (active) {
            b.setTypeface(Typeface.DEFAULT_BOLD);
        }
        parent.addView(b);
        return b;
    }

    private static Button makeBtn(NativeHome h, String title, Runnable act) {
        return createPixelBtn(h, title, "secondaryContainer", "onSecondaryContainer", 24, 14, 6, 14, 48, act);
    }

    private static Button makeSmallBtn(NativeHome h, String title, Runnable act) {
        return createPixelBtn(h, title, "surfaceContainerHigh", "onSurfaceVariant", 18, 8, 4, 13, 48, act);
    }

    private static void renderLoading(NativeHome h, LinearLayout parent, String tip) {
        LinearLayout box = new LinearLayout(h.a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = h.dp(24);
        box.setPadding(p, p, p, p);

        ProgressBar pb = new ProgressBar(h.a, null, android.R.attr.progressBarStyle);
        box.addView(pb);

        TextView tv = new TextView(h.a);
        tv.setText(tip);
        tv.setPadding(0, h.dp(8), 0, 0);
        tv.setTextColor(PixelUi.color(h.a, "onSurfaceVariant"));
        box.addView(tv);

        parent.addView(box);
    }

    private static String basename(String path) {
        if (path == null || path.isEmpty()) return "";
        int idx = path.lastIndexOf('/');
        return (idx >= 0 && idx < path.length() - 1) ? path.substring(idx + 1) : path;
    }

    private static boolean isImageFile(String path, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/") && !contentType.contains("svg")) return true;
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg")
                || p.endsWith(".webp") || p.endsWith(".gif") || p.endsWith(".bmp");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String formatTime(long mtime) {
        if (mtime <= 0) return "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(mtime));
    }

    // =========================================================================
    // 6. 数据实体定义
    // =========================================================================

    private static class FileItem {
        final String name;
        final String path;
        final boolean isDir;
        final long size;
        final long mtime;
        final String kind;

        FileItem(String name, String path, boolean isDir, long size, long mtime, String kind) {
            this.name = name;
            this.path = path;
            this.isDir = isDir;
            this.size = size;
            this.mtime = mtime;
            this.kind = kind;
        }
    }

    private static class GitFile {
        final String path;
        final String kind;

        GitFile(String path, String kind) {
            this.path = path;
            this.kind = kind;
        }
    }

    private static class TermSession {
        final String id;
        final String title;
        final boolean alive;

        TermSession(String id, String title, boolean alive) {
            this.id = id;
            this.title = title;
            this.alive = alive;
        }
    }
}
