package cc.codroid;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NativeDocumentPreview {

    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "(\\[([^\\]]+)\\]\\(([^\\)]+)\\))|" +
            "(`([^`]+)`)|" +
            "(\\*\\*([^*]+)\\*\\*|__([^_]+)__)|" +
            "(\\*([^*]+)\\*|_([^_]+)_)"
    );

    private static final Pattern LIST_PATTERN = Pattern.compile("^(\\s*)([*+-]|\\d+\\.)\\s+(.*)$");

    private NativeDocumentPreview() {
    }

    public static void show(NativeHome h, String path, String source) {
        if (h == null || h.a == null) {
            return;
        }

        String lowerPath = path != null ? path.toLowerCase(java.util.Locale.ROOT) : "";
        boolean isHtml = lowerPath.endsWith(".html") || lowerPath.endsWith(".htm");

        if (isHtml) {
            showHtmlDialog(h, source);
        } else {
            showMarkdownDialog(h, source);
        }
    }

    private static void showHtmlDialog(NativeHome h, String source) {
        Context ctx = h.a;
        WebView wv = new WebView(ctx);
        wv.setContentDescription("HTML文档预览内容");

        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        ws.setBlockNetworkLoads(true);
        ws.setBlockNetworkImage(true);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setDatabaseEnabled(false);
        ws.setDomStorageEnabled(false);
        ws.setGeolocationEnabled(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager cm = CookieManager.getInstance();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(wv, false);
        }

        wv.setWebViewClient(new WebViewClient() {
            private WebResourceResponse emptyResponse() {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return emptyResponse();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return emptyResponse();
            }
        });

        String csp = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; sandbox;\">\n";
        String safeHtml = csp + (source != null ? source : "");
        wv.loadDataWithBaseURL(null, safeHtml, "text/html", "UTF-8", null);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("文档预览 (HTML)")
                .setView(wv)
                .setPositiveButton("关闭", null)
                .create();

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.clearHistory();
                wv.removeAllViews();
                wv.destroy();
            }
        });

        dialog.show();
    }

    private static void showMarkdownDialog(NativeHome h, String source) {
        Context ctx = h.a;
        int padH = h.dp(16);
        int padV = h.dp(12);

        ScrollView sv = new ScrollView(ctx);
        sv.setContentDescription("Markdown文档预览区域");

        TextView tv = new TextView(ctx);
        tv.setContentDescription("Markdown文档内容");
        tv.setTextIsSelectable(true);
        tv.setPadding(padH, padV, padH, padV);
        tv.setLineSpacing(h.dp(2), 1.15f);

        CharSequence formatted = renderMarkdown(source, h.dp(16));
        tv.setText(formatted);

        sv.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(ctx)
                .setTitle("文档预览 (Markdown)")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static CharSequence renderMarkdown(String source, int indentUnit) {
        if (source == null || source.isEmpty()) {
            return "";
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = source.split("\r?\n", -1);
        boolean inCodeBlock = false;
        int codeStart = 0;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();

            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeStart = sb.length();
                } else {
                    inCodeBlock = false;
                    int codeEnd = sb.length();
                    if (codeEnd > codeStart) {
                        sb.setSpan(new TypefaceSpan("monospace"), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new BackgroundColorSpan(0x18777777), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                continue;
            }

            if (inCodeBlock) {
                sb.append(rawLine).append('\n');
                continue;
            }

            int lineStart = sb.length();

            if (rawLine.startsWith("#")) {
                int level = 0;
                while (level < rawLine.length() && rawLine.charAt(level) == '#') {
                    level++;
                }
                if (level <= 6 && level < rawLine.length() && rawLine.charAt(level) == ' ') {
                    String headingText = rawLine.substring(level + 1).trim();
                    appendInlineFormatted(sb, headingText);
                    int lineEnd = sb.length();

                    float scale;
                    switch (level) {
                        case 1: scale = 1.50f; break;
                        case 2: scale = 1.35f; break;
                        case 3: scale = 1.20f; break;
                        case 4: scale = 1.10f; break;
                        default: scale = 1.05f; break;
                    }

                    sb.setSpan(new RelativeSizeSpan(scale), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.append('\n');
                    continue;
                }
            }

            if (rawLine.startsWith("> ") || rawLine.equals(">")) {
                String quoteContent = rawLine.length() > 2 ? rawLine.substring(2) : "";
                appendInlineFormatted(sb, quoteContent);
                int lineEnd = sb.length();
                sb.setSpan(new QuoteSpan(0xFF78909C), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append('\n');
                continue;
            }

            Matcher listMatcher = LIST_PATTERN.matcher(rawLine);
            if (listMatcher.matches()) {
                String indent = listMatcher.group(1);
                String bullet = listMatcher.group(2);
                String itemContent = listMatcher.group(3);

                int depth = (indent != null ? indent.length() / 2 : 0) + 1;
                String displayBullet = (bullet != null && bullet.endsWith(".")) ? bullet : "•";
                sb.append(displayBullet).append(' ');

                appendInlineFormatted(sb, itemContent);
                int lineEnd = sb.length();

                int margin = depth * Math.max(indentUnit, 20);
                sb.setSpan(new LeadingMarginSpan.Standard(margin / 2, margin), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append('\n');
                continue;
            }

            appendInlineFormatted(sb, rawLine);
            sb.append('\n');
        }

        if (inCodeBlock && sb.length() > codeStart) {
            sb.setSpan(new TypefaceSpan("monospace"), codeStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new BackgroundColorSpan(0x18777777), codeStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.delete(sb.length() - 1, sb.length());
        }

        return sb;
    }

    private static void appendInlineFormatted(SpannableStringBuilder sb, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Matcher m = INLINE_PATTERN.matcher(text);
        int lastPos = 0;

        while (m.find()) {
            if (m.start() > lastPos) {
                sb.append(text.substring(lastPos, m.start()));
            }

            int spanStart = sb.length();

            if (m.group(1) != null) {
                String label = m.group(2);
                String url = m.group(3);
                sb.append(label).append(" (").append(url).append(")");
                int spanEnd = sb.length();
                sb.setSpan(new ForegroundColorSpan(0xFF1976D2), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new UnderlineSpan(), spanStart, spanStart + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null) {
                String code = m.group(5);
                sb.append(code);
                int spanEnd = sb.length();
                sb.setSpan(new TypefaceSpan("monospace"), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(0x18777777), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(6) != null) {
                String bold = m.group(7) != null ? m.group(7) : m.group(8);
                sb.append(bold);
                int spanEnd = sb.length();
                sb.setSpan(new StyleSpan(Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(9) != null) {
                String italic = m.group(10) != null ? m.group(10) : m.group(11);
                sb.append(italic);
                int spanEnd = sb.length();
                sb.setSpan(new StyleSpan(Typeface.ITALIC), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            lastPos = m.end();
        }

        if (lastPos < text.length()) {
            sb.append(text.substring(lastPos));
        }
    }
}
