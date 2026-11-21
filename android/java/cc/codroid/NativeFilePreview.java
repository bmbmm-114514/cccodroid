package cc.codroid;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.UUID;

public class NativeFilePreview {
    private static final long MAX_SIZE = 64 * 1024 * 1024L; // 64 MiB

    public static void open(NativeHome h, String path, String kind) {
        if (path == null) return;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk") || lower.endsWith(".html") || lower.endsWith(".htm")) {
            h.notice("不支持在此二进制查看器中打开此格式文件");
            return;
        }

        final int gen = h.generation;
        new Thread(() -> {
            File baseDir = new File(h.a.getFilesDir(), "cache/share/preview");
            prune(baseDir);
            File subFolder = new File(baseDir, UUID.randomUUID().toString());
            if (!subFolder.exists() && !subFolder.mkdirs()) {
                postNotice(h, gen, "无法创建预览缓存目录");
                return;
            }

            File destFile = new File(subFolder, new File(path).getName());
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;

            try {
                conn = Env.gateway("/api/file?path=" + Uri.encode(path));
                conn.setConnectTimeout(8000); conn.setReadTimeout(30000);
                conn.connect();
                if (conn.getResponseCode() != 200) {
                    postNotice(h, gen, "下载失败，状态码: " + conn.getResponseCode());
                    return;
                }

                long len = conn.getContentLengthLong();
                if (len > MAX_SIZE) {
                    postNotice(h, gen, "文件超过64MiB限制，无法预览");
                    return;
                }

                in = conn.getInputStream();
                out = new FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_SIZE) {
                        destFile.delete();
                        postNotice(h, gen, "文件超过64MiB限制，预览取消");
                        return;
                    }
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (Exception e) {
                destFile.delete();
                postNotice(h, gen, "下载出错: " + e.getMessage());
                return;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }

            h.a.runOnUiThread(() -> {
                if (h.generation != gen || h.a.isDestroyed()) { remove(destFile); return; }
                if ("pdf".equalsIgnoreCase(kind) || lower.endsWith(".pdf")) {
                    openPdf(h, destFile);
                } else {
                    openExternal(h, destFile);
                }
            });
        }).start();
    }

    private static void remove(File copy) {
        copy.delete();
        copy.getParentFile().delete();
    }

    private static void prune(File cache) {
        File[] dirs = cache.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            if (System.currentTimeMillis() - dir.lastModified() < 86400000L) continue;
            File[] contents = dir.listFiles();
            if (contents != null) for (File copy : contents) copy.delete();
            dir.delete();
        }
    }

    private static void openExternal(NativeHome h, File file) {
        try {
            Uri uri = Share.uriFor(h.a, file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (uri == null) throw new java.io.IOException("无法授权读取文件");
            String filename = file.getName(); int dot = filename.lastIndexOf('.');
            String ext = dot < 0 ? "" : filename.substring(dot+1);
            String mime = !ext.isEmpty() ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT)) : null;
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "选择打开方式");
            h.a.startActivity(chooser);
        } catch (Exception e) {
            h.notice("打开外部查看器失败: " + e.getMessage());
        }
    }

    private static void openPdf(NativeHome h, File file) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            remove(file);
            h.notice("PDF加载失败: " + e.getMessage());
            return;
        }

        final PdfRenderer finalRenderer = renderer;
        final int totalPages = renderer.getPageCount();
        final int[] index = new int[]{0};

        LinearLayout root = new LinearLayout(h.a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        LinearLayout controls = new LinearLayout(h.a);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        Button prevBtn = new Button(h.a);
        prevBtn.setText("上一页");
        TextView pageText = new TextView(h.a);
        pageText.setPadding(30, 0, 30, 0);
        Button nextBtn = new Button(h.a);
        nextBtn.setText("下一页");

        controls.addView(prevBtn);
        controls.addView(pageText);
        controls.addView(nextBtn);

        ImageView previewImg = new ImageView(h.a);
        previewImg.setAdjustViewBounds(true);
        root.addView(controls);
        root.addView(previewImg);

        Runnable render = () -> {
            if (totalPages <= 0) { h.notice("空 PDF"); return; }
            try (PdfRenderer.Page page = finalRenderer.openPage(index[0])) {
                int pw = page.getWidth(), ph = page.getHeight();
                int bw = pw, bh = ph;
                if (Math.max(pw, ph) > 1200) {
                    if (pw >= ph) {
                        bw = 1200;
                        bh = Math.max(1, (int)((long)ph * 1200 / pw));
                    } else {
                        bh = 1200;
                        bw = Math.max(1, (int)((long)pw * 1200 / ph));
                    }
                }
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(android.graphics.Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                previewImg.setImageBitmap(bmp);
                pageText.setText(String.format(Locale.CHINA, "第 %d / %d 页", index[0] + 1, totalPages));
                prevBtn.setEnabled(index[0] > 0);
                nextBtn.setEnabled(index[0] < totalPages - 1);
            } catch (RuntimeException e) { h.notice(e.getMessage()); }
        };

        prevBtn.setOnClickListener(v -> { if (index[0] > 0) { index[0]--; render.run(); } });
        nextBtn.setOnClickListener(v -> { if (index[0] < totalPages - 1) { index[0]++; render.run(); } });
        render.run();

        new MaterialAlertDialogBuilder(h.a)
            .setTitle(file.getName())
            .setView(root)
            .setPositiveButton("关闭", null)
            .setOnDismissListener(d -> {
                try { finalRenderer.close(); } catch (Exception ignored) {}
                remove(file);
            })
            .show();
    }

    private static void postNotice(NativeHome h, int gen, String msg) {
        h.a.runOnUiThread(() -> {
            if (h.generation == gen) {
                h.notice(msg);
            }
        });
    }
}
