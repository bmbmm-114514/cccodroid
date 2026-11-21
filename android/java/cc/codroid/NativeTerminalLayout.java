package cc.codroid;

import android.content.Context;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Makes room for the prompt and both key rows as the window or keyboard changes height. */
final class NativeTerminalLayout extends LinearLayout {
    private View tabs, sessions, divider, viewport, keys;
    private TextView output;
    private boolean headerHidden;

    NativeTerminalLayout(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    void clearBinding() {
        if (tabs != null) tabs.setVisibility(VISIBLE);
        if (sessions != null) sessions.setVisibility(VISIBLE);
        if (divider != null) divider.setVisibility(VISIBLE);
        tabs = sessions = divider = viewport = keys = null;
        output = null;
        headerHidden = false;
    }

    void bind(View tabs, View sessions, View divider, View viewport, TextView output, View keys) {
        this.tabs = tabs;
        this.sessions = sessions;
        this.divider = divider;
        this.viewport = viewport;
        this.output = output;
        this.keys = keys;
    }

    // Decide before the parent measures its weighted body, so the header never steals
    // a frame from the terminal and the decision also works when the header is gone.
    static void fitHeader(View header, ViewGroup body, int widthSpec, int heightSpec) {
        boolean visible = true;
        if (body.getChildCount() == 1 && body.getChildAt(0) instanceof NativeTerminalLayout) {
            NativeTerminalLayout term = (NativeTerminalLayout) body.getChildAt(0);
            if (term.keys != null && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                header.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                visible = MeasureSpec.getSize(heightSpec) >= header.getMeasuredHeight() + term.minimumHeight(widthSpec);
            }
            term.headerHidden = !visible;
        }
        header.setVisibility(visible ? VISIBLE : GONE);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (keys != null && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            int remaining = MeasureSpec.getSize(heightSpec) - minimumHeight(widthSpec);
            int sessionsHeight = naturalHeight(sessions, widthSpec) + naturalHeight(divider, widthSpec);
            boolean showSessions = !headerHidden && remaining >= sessionsHeight;
            sessions.setVisibility(showSessions ? VISIBLE : GONE);
            divider.setVisibility(showSessions ? VISIBLE : GONE);
            boolean showTabs = showSessions && remaining - sessionsHeight >= naturalHeight(tabs, widthSpec);
            tabs.setVisibility(showTabs ? VISIBLE : GONE);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private int minimumHeight(int widthSpec) {
        LayoutParams lp = (LayoutParams) viewport.getLayoutParams();
        Paint.FontMetricsInt font = output.getPaint().getFontMetricsInt();
        int line = Math.max(output.getLineHeight(), font.bottom - font.top);
        return naturalHeight(keys, widthSpec) + lp.topMargin + lp.bottomMargin + line
                + output.getCompoundPaddingTop() + output.getCompoundPaddingBottom();
    }

    private int naturalHeight(View child, int widthSpec) {
        measureChildWithMargins(child, widthSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        return child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
    }
}
