package net.kopias.tapover.ui;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Toolbar;

import net.kopias.tapover.R;

/**
 * Edge-to-edge inset handling for a screen made of a top app bar above a scrolling container.
 *
 * <p>{@code targetSdk 35} makes Android 15 lay activities out edge-to-edge, so the window draws
 * under the status and navigation bars. The idiomatic split is:
 * <ul>
 *   <li>the app bar's background extends under the status bar, its content padded below it
 *       ({@link #padAppBar});</li>
 *   <li>the scroll container sits below the app bar, so scrolled content can never reach the
 *       status bar, and clears the navigation bar / keyboard with padding that scrolls
 *       ({@code clipToPadding=false}), so the last items can still scroll above the nav bar
 *       ({@link #padScrollingContent}).</li>
 * </ul>
 *
 * <p>Listeners re-apply on every inset change (rotation, IME show/hide) on top of the view's
 * designed padding, and return the insets unconsumed so siblings receive them too.
 *
 * <p>Framework-only (no androidx {@code WindowInsetsCompat}) to honour the zero-third-party-deps
 * constraint.
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {}

    /**
     * Sets up the screen's {@code @layout/app_bar}: titles its toolbar and pads the bar by the
     * insets. Returns the toolbar for further setup (navigation icon, menu).
     */
    public static Toolbar setupAppBar(Activity a, CharSequence title) {
        padAppBar(a.findViewById(R.id.app_bar));
        Toolbar toolbar = a.findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        return toolbar;
    }

    /** Pads the app bar by the top and side system-bar insets; its background stays under the status bar. */
    public static void padAppBar(View bar) {
        onInsets(bar, (v, base, bars, ime) ->
                v.setPadding(base.left + bars.left, base.top + bars.top,
                        base.right + bars.right, base.bottom));
    }

    /**
     * Pads a scroll container (below the app bar) by the side and bottom system-bar insets, with
     * {@code clipToPadding=false} so content scrolls through the padding up to the nav bar.
     * The keyboard is handled as a bottom margin, which shrinks the container: edge-to-edge
     * windows aren't resized for {@code adjustResize}, but the size change still lets the
     * ScrollView/ListView keep the focused field visible.
     */
    public static void padScrollingContent(View scroller) {
        if (scroller instanceof ViewGroup) ((ViewGroup) scroller).setClipToPadding(false);
        onInsets(scroller, (v, base, bars, ime) -> {
            v.setPadding(base.left + bars.left, base.top,
                    base.right + bars.right, base.bottom + Math.max(0, bars.bottom - ime));
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams
                    && ((ViewGroup.MarginLayoutParams) lp).bottomMargin != ime) {
                ((ViewGroup.MarginLayoutParams) lp).bottomMargin = ime;
                v.setLayoutParams(lp);
            }
        });
    }

    private interface InsetsApplier {
        /** {@code base}: the view's designed padding; {@code ime}: keyboard height (0 if hidden). */
        void apply(View v, Insets base, Insets bars, int ime);
    }

    private static void onInsets(View v, InsetsApplier applier) {
        if (v == null) return;
        final Insets base = Insets.of(v.getPaddingLeft(), v.getPaddingTop(),
                v.getPaddingRight(), v.getPaddingBottom());
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            final Insets bars;
            final int ime;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // getInsets(Type) is API 30+.
                bars = insets.getInsets(WindowInsets.Type.systemBars());
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                // API 29 isn't edge-to-edge (the decor fits system windows and resizes for the
                // IME itself); the deprecated getters are only a fallback.
                bars = Insets.of(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                ime = 0;
            }
            applier.apply(view, base, bars, ime);
            return insets;
        });
        // In onCreate the view isn't attached yet, so the listener fires naturally on attach.
        if (v.isAttachedToWindow()) v.requestApplyInsets();
    }
}
