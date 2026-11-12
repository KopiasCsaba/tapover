package net.kopias.tapover.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import net.kopias.tapover.detect.DebugLog;

import java.util.Collections;

/**
 * Manages a single WindowManager overlay window (plus a transient {@link MessageBubble}). The accessibility
 * service owns one and calls {@link #show} / {@link #hide} on foreground changes; the editor owns
 * another for its draggable {@link #showPreview}. Windows use whole-screen coordinates (insets and
 * cutouts ignored), matching {@link ScreenGeometry}. Main thread only.
 */
public class OverlayController {

    private static final String TAG = "TapOver";

    /** Tap callback for the overlay. */
    public interface OnTapListener {
        void onTap();
    }

    /** Drag callbacks for the editor's preview; positions are the window's clamped params.x/y. */
    public interface DragListener {
        void onDragged(int x, int y);
        void onDragEnd(int x, int y);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final MessageBubble message;

    private OverlayView currentView;
    /** Layout params of the live overlay, kept so touchability can be toggled while suppressed. */
    private WindowManager.LayoutParams overlayParams;
    /** True while the overlay is faded out (e.g. the notification shade is open) but still present. */
    private boolean suppressed;
    /**
     * Monotonic op counter. {@link #hide} captures it and only removes the view if no later
     * operation (show/restore/fadeOut/hide) bumped it meanwhile — guards the fade-out-then-remove
     * against a re-show that arrives mid-animation.
     */
    private long opSeq;

    /** Fade durations for the overlay circle. */
    private static final long FADE_IN_MS = 180;
    private static final long FADE_OUT_MS = 160;
    /** Quicker fade right after a tap, so the dot gets out of the way of the launched app. */
    private static final long TAP_FADE_OUT_MS = 100;

    public OverlayController(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.message = new MessageBubble(context, windowManager, windowType());
    }

    /** Accessibility overlays (from the service) can also draw above the lock screen. */
    private int windowType() {
        return context instanceof AccessibilityService
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    public boolean isShowing() {
        return currentView != null;
    }

    /**
     * Show (or replace) the overlay for the given config, fading it in. Calling repeatedly does
     * not stack windows — any existing overlay is removed first. Use {@link #restore} to fade a
     * still-present (suppressed) overlay back in without rebuilding it.
     */
    public void show(OverlayConfig config, OnTapListener listener) {
        showInternal(config, listener, true, false);
    }

    /**
     * Rebuild the overlay already at its target state, with no fade: fully visible, or (when
     * {@code suppressed}) added faded out and non-touchable, as {@link #fadeOut} would leave it.
     * Used when an existing overlay must be recreated (e.g. resized after rotation).
     */
    public void showImmediate(OverlayConfig config, OnTapListener listener, boolean suppressed) {
        showInternal(config, listener, false, suppressed);
    }

    private void showInternal(OverlayConfig config, OnTapListener listener, boolean fadeIn,
                              boolean startSuppressed) {
        removeNow(); // replace any existing overlay

        OverlayView view = new OverlayView(context, config);
        view.setClickable(true);
        view.setOnClickListener(v -> {
            DebugLog.i("overlay tapped");
            // Fade out (window kept, non-touchable) so a double tap can't fire the action twice;
            // the listener may restore() it if the action fails and the target stays in front.
            fadeOut(TAP_FADE_OUT_MS);
            if (listener != null) {
                listener.onTap();
            }
        });

        view.setAlpha(fadeIn || startSuppressed ? 0f : 1f);
        if (addOverlay(view, config, startSuppressed)) {
            if (startSuppressed) {
                suppressed = true;
            } else if (fadeIn) {
                view.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            }
        }
    }

    /**
     * Show (or replace) an editor preview: same window type, flags and positioning as {@link #show},
     * but no fade and no tap action. Touching the dot drags the window; the window position is
     * clamped to the display ({@code [0, w-size] x [0, h-size]}) and reported to {@code l}.
     */
    public void showPreview(OverlayConfig config, DragListener l) {
        removeNow();
        OverlayView view = new OverlayView(context, config);
        // A drag starting at a screen edge would otherwise be taken by the system Back gesture.
        view.addOnLayoutChangeListener((v, left, top, right, bottom, ol, ot, or, ob) ->
                v.setSystemGestureExclusionRects(
                        Collections.singletonList(new Rect(0, 0, right - left, bottom - top))));
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (overlayParams == null || currentView != v) {
                    return false;
                }
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = overlayParams.x;
                        startY = overlayParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        // Clamp to the whole screen the window is laid out in (not the app's
                        // DisplayMetrics, which exclude system bars before API 35).
                        ScreenGeometry g = ScreenGeometry.read(context);
                        int size = overlayParams.width;
                        int x = clamp(startX + Math.round(e.getRawX() - downRawX),
                                Math.max(0, g.width - size));
                        int y = clamp(startY + Math.round(e.getRawY() - downRawY),
                                Math.max(0, g.height - size));
                        relayout(x, y, size);
                        if (l != null) {
                            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                                l.onDragged(x, y);
                            } else {
                                l.onDragEnd(x, y);
                            }
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
        addOverlay(view, config, false);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /** Add {@code view} as the overlay window at the config's position/size; false on failure. */
    private boolean addOverlay(OverlayView view, OverlayConfig config, boolean notTouchable) {
        overlayParams = new WindowManager.LayoutParams(
                config.sizePx,
                config.sizePx,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (notTouchable ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        // Whole-screen coordinates in every rotation: ignore status bar / nav bar / cutout insets.
        overlayParams.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) {
            overlayParams.setFitInsetsTypes(0);
        }
        overlayParams.x = config.x;
        overlayParams.y = config.y;

        try {
            windowManager.addView(view, overlayParams);
            currentView = view;
            suppressed = false;
            opSeq++;
            return true;
        } catch (RuntimeException e) {
            // BadTokenException, IllegalState (e.g. permission revoked) — log, stay clean.
            Log.e(TAG, "Failed to add overlay view", e);
            currentView = null;
            overlayParams = null;
            return false;
        }
    }

    /** Fade an existing overlay back in (e.g. after the notification shade is dismissed). */
    public void restore() {
        if (currentView == null || !suppressed) {
            return;
        }
        opSeq++;
        suppressed = false;
        setTouchable(true);
        currentView.animate().cancel();
        currentView.animate().alpha(1f).setDuration(FADE_IN_MS).start();
    }

    /**
     * Fade the overlay out but KEEP the window, so it can fade back in. Used when a transient
     * surface (the notification shade) covers the target without it leaving the foreground.
     * The faded-out overlay is made non-touchable so taps pass through to whatever is on top.
     */
    public void fadeOut() {
        fadeOut(FADE_OUT_MS);
    }

    private void fadeOut(long durationMs) {
        if (currentView == null || suppressed) {
            return;
        }
        opSeq++;
        suppressed = true;
        setTouchable(false);
        currentView.animate().cancel();
        currentView.animate().alpha(0f).setDuration(durationMs).start();
    }

    /**
     * Re-lay out the live overlay window: new top-left pixel coordinates and square size (e.g.
     * after the display rotated). No-op when nothing is shown or nothing changed.
     */
    public void relayout(int x, int y, int sizePx) {
        if (currentView == null || overlayParams == null) {
            return;
        }
        if (overlayParams.x == x && overlayParams.y == y
                && overlayParams.width == sizePx && overlayParams.height == sizePx) {
            return;
        }
        overlayParams.x = x;
        overlayParams.y = y;
        overlayParams.width = sizePx;
        overlayParams.height = sizePx;
        try {
            windowManager.updateViewLayout(currentView, overlayParams);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to relayout overlay", e);
        }
    }

    /** True while the overlay is faded out but still present. */
    public boolean isSuppressed() {
        return suppressed;
    }

    /** Side length in px of the live overlay window, or -1 when nothing is shown. */
    public int currentSizePx() {
        return (currentView == null || overlayParams == null) ? -1 : overlayParams.width;
    }

    /** Toggle touch interception in place (the window stays added). */
    private void setTouchable(boolean touchable) {
        if (currentView == null || overlayParams == null) {
            return;
        }
        if (touchable) {
            overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            windowManager.updateViewLayout(currentView, overlayParams);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to update overlay flags", e);
        }
    }

    /** Update the IMAGE content (bitmap + video flag) in place, with the refresh pulse. No-op if not showing. */
    public void updateMedia(Bitmap b, boolean isVideo) {
        updateMedia(b, isVideo, true);
    }

    /**
     * Update the IMAGE content in place. {@code pulse} plays the refresh pulse — only when actually
     * visible, never while faded out under the shade. Ignored while {@link #isLocked() locked}.
     */
    public void updateMedia(Bitmap b, boolean isVideo, boolean pulse) {
        if (currentView != null) {
            currentView.setMedia(b, isVideo, pulse && !suppressed);
        }
    }

    /**
     * Switch the live overlay into / out of lock-screen privacy mode in place (no rebuild, no fade):
     * a padlock replaces the content while locked, and any loaded bitmap is dropped. No-op if not
     * showing.
     */
    public void setLocked(boolean locked) {
        if (currentView != null) {
            currentView.setLocked(locked);
        }
    }

    /** True while the live overlay draws the padlock instead of its content. */
    public boolean isLocked() {
        return currentView != null && currentView.isLocked();
    }

    /**
     * Briefly show a feedback bubble in our own overlay window (see {@link MessageBubble}). Safe to
     * call from the service's main thread.
     */
    public void flashMessage(CharSequence text) {
        message.flash(text);
    }

    /** Fade the overlay out and remove it. Safe to call when nothing is shown. */
    public void hide() {
        message.remove();
        if (currentView == null) {
            return;
        }
        final OverlayView v = currentView;
        final long seq = ++opSeq;
        setTouchable(false); // the fading dot must not run its action any more
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
            // Only remove if no later show()/restore() re-claimed the overlay mid-animation.
            if (seq == opSeq && currentView == v) {
                detach(v);
            }
        }).start();
    }

    /** Remove the overlay immediately, no fade (config reload / service teardown). */
    public void removeNow() {
        message.remove();
        if (currentView == null) {
            return;
        }
        opSeq++;
        currentView.animate().cancel();
        detach(currentView);
    }

    /** Remove {@code v}'s window and forget the overlay state. */
    private void detach(View v) {
        try {
            windowManager.removeView(v);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to remove overlay view", e);
        }
        currentView = null;
        overlayParams = null;
        suppressed = false;
    }
}
