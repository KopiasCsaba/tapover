package net.kopias.tapover.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Transient feedback bubble in its own overlay window, used to surface a tap-action failure (e.g.
 * "no photos in DCIM"). The overlay is drawn with SYSTEM_ALERT_WINDOW / accessibility overlay, which
 * (unlike a background Toast on Android 13+) is not suppressed when the target app, not TapOver, is
 * in the foreground.
 */
final class MessageBubble {

    private static final String TAG = "TapOver";

    /** How long the feedback bubble stays up. */
    private static final long MESSAGE_MS = 2500;

    private final Context context;
    private final WindowManager windowManager;
    private final int windowType;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View messageView;
    private Runnable messageRemover;

    MessageBubble(Context context, WindowManager windowManager, int windowType) {
        this.context = context;
        this.windowManager = windowManager;
        this.windowType = windowType;
    }

    /** Show {@code text} for {@link #MESSAGE_MS}; safe to call from any thread (posts to main). */
    void flash(CharSequence text) {
        ui.post(() -> show(text));
    }

    private void show(CharSequence text) {
        remove();
        float density = context.getResources().getDisplayMetrics().density;
        int padH = (int) (20 * density);
        int padV = (int) (12 * density);

        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(padH, padV, padH, padV);
        tv.setMaxWidth((int) (300 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202020);
        bg.setCornerRadius(24 * density);
        tv.setBackground(bg);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = (int) (120 * density);

        try {
            windowManager.addView(tv, params);
            messageView = tv;
            messageRemover = this::remove;
            ui.postDelayed(messageRemover, MESSAGE_MS);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to add message view", e);
            messageView = null;
        }
    }

    /** Remove the bubble now, if shown. Main thread. */
    void remove() {
        if (messageRemover != null) {
            ui.removeCallbacks(messageRemover);
            messageRemover = null;
        }
        if (messageView == null) {
            return;
        }
        try {
            windowManager.removeView(messageView);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to remove message view", e);
        } finally {
            messageView = null;
        }
    }
}
