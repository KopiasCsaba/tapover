package net.kopias.tapover.ui;

import android.content.Context;
import android.graphics.Color;
import android.widget.Toast;

/** Small shared helpers for the editor's classes. */
final class EditorUi {

    private EditorUi() {}

    /** A long toast of {@code resId}, formatted with {@code args} when given. */
    static void toast(Context ctx, int resId, Object... args) {
        String text = args.length == 0 ? ctx.getString(resId) : ctx.getString(resId, args);
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
    }

    static int parseColor(String s, int def) {
        try { return Color.parseColor(s.trim()); } catch (Exception e) { return def; }
    }

    static int themeColor(Context ctx, int attr) {
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(new int[] {attr});
        try {
            return ta.getColor(0, 0xFF808080);
        } finally {
            ta.recycle();
        }
    }
}
