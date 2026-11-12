package net.kopias.tapover.detect;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import net.kopias.tapover.MainActivity;

import java.util.function.Supplier;

/**
 * Classifies each {@code TYPE_WINDOW_STATE_CHANGED} event into what it means for the overlay. Pure:
 * decides from package, class and content-change type only; {@link TapOverAccessibilityService}
 * acts on the result.
 *
 * <h2>Why the visibility logic looks the way it does</h2>
 *
 * The overlay must be visible exactly while the target app (e.g. the camera) is the foreground app,
 * and hidden otherwise. The hard part is that a window-state-change stream is <em>ambiguous</em>:
 * the status bar, the pulled-down notification shade, the rotation-suggestion button, the IME and
 * the app itself all emit {@code TYPE_WINDOW_STATE_CHANGED}, and — under our privacy posture
 * ({@code canRetrieveWindowContent="false"}) — we cannot query the window tree to ask "who is really
 * focused?" ({@code getWindows()} returns empty and no {@code TYPE_WINDOWS_CHANGED} arrive; both
 * verified on device). So the logic must classify each event from its package, class and
 * content-change type alone. It does that with three rules, each keyed on a <em>self-describing</em>
 * signal rather than timing or guesswork:
 *
 * <ol>
 *   <li><b>The notification shade (system UI).</b> The shade is an accessibility "pane", so it
 *       announces its own state: opening fires a {@code com.android.systemui} event carrying
 *       {@link AccessibilityEvent#CONTENT_CHANGE_TYPE_PANE_APPEARED} → fade out; dismissing fires one
 *       carrying {@link AccessibilityEvent#CONTENT_CHANGE_TYPE_PANE_DISAPPEARED} → fade back in. We
 *       act <em>only</em> on those two markers and ignore every other system-UI event — crucially the
 *       unlabelled {@code cct=0} "settle" that trails a dismiss, which would otherwise re-hide the
 *       overlay we just restored. The active preset is kept across the shade, so the fade-back-in does
 *       not depend on the target re-emitting an event (some devices never do).</li>
 *   <li><b>Transient non-activity windows (rotation button, popups, toasts).</b> A genuine foreground
 *       activity names its own Activity class in the event (e.g. {@code …CameraActivity}, Home =
 *       {@code …QuickstepLauncher}); a transient window reports a bare framework view class instead
 *       (the rotation button arrives as {@code com.android.launcher3 / android.widget.FrameLayout}).
 *       So a non-system event whose class is {@code android.widget.*} / {@code android.view.*} is
 *       treated as transient and ignored (see {@link #isTransientWindowClass}). This is safe by
 *       construction: an Activity can never be named in those framework view packages, so a real app
 *       switch is never mistaken for transient.</li>
 *   <li><b>A real different app.</b> Any other event is a genuine foreground activity: if it matches a
 *       preset, show; otherwise hide and forget immediately (no debounce, no launcher special-case —
 *       Home is just a non-matching activity).</li>
 * </ol>
 *
 * <p>The IME is ignored (rule between 1 and 2) so the keyboard does not count as an app switch.
 * {@code notificationTimeout} is set to 0 in {@code accessibility_service_config.xml} because a
 * non-zero debounce coalesces the shade's back-to-back open events and drops the {@code PANE_APPEARED}
 * marker rule 1 depends on. Nothing here reads window <em>content</em> — only package, class and
 * content-change type. The fully authoritative alternative ({@code getWindows()} focused-window
 * lookup) is deliberately not used: it requires {@code canRetrieveWindowContent="true"}, which the
 * privacy posture forbids; rules 1–2 trade that for two well-established AOSP behaviours, both
 * confirmed on real hardware (GrapheneOS Pixel).
 */
final class WindowEventClassifier {

    /** What a window-state change means for the overlay. */
    enum Kind {
        /** TapOver's own main screen came to the front: it replaces the target. */
        OWN_MAIN_SCREEN,
        /** Another TapOver window (overlay, message bubble, dialogs): not an app switch. */
        OWN_WINDOW,
        /** Rule 1: the shade opened (system-UI {@code PANE_APPEARED}) → fade out. */
        SHADE_OPENED,
        /** Rule 1: the shade was dismissed (system-UI {@code PANE_DISAPPEARED}) → fade back in. */
        SHADE_CLOSED,
        /** Rule 1: any other system-UI event (e.g. the {@code cct=0} settle) → ignore. */
        SYSTEM_UI_OTHER,
        /** The soft keyboard: not an app switch → ignore. */
        IME,
        /** Rule 2: a transient non-activity window → ignore. */
        TRANSIENT,
        /** Rule 3: a genuine foreground activity. */
        FOREGROUND
    }

    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private WindowEventClassifier() {
    }

    /**
     * @param cct    the event's content-change types
     * @param imePkg package of the current input method (looked up only when needed; may give null)
     */
    static Kind classify(String pkg, String cls, int cct, String ownPkg, Supplier<String> imePkg) {
        // Our own windows (overlay, message bubble, dialogs) also fire events as our package and
        // must not count as an app switch. Our main screen does: it replaces the target.
        if (pkg.equals(ownPkg)) {
            return MainActivity.class.getName().equals(cls) ? Kind.OWN_MAIN_SCREEN : Kind.OWN_WINDOW;
        }

        // The notification shade / status bar (system UI) takes focus over the target without the
        // target leaving the foreground, so it must not change the active preset. The shade is an
        // accessibility "pane": opening it fires a system-UI window-state change whose
        // contentChangeTypes carries PANE_APPEARED, and closing it fires one with PANE_DISAPPEARED —
        // the shade itself announcing its state, no timing or target re-emit needed. Act ONLY on
        // those two markers. System UI also emits ambiguous settle events (cct=0) — notably one
        // trailing the dismiss, and the rotation-suggestion button — which must be IGNORED, or that
        // trailing settle re-hides the overlay we just restored (the real-device bug).
        if (SYSTEM_UI_PACKAGE.equals(pkg)) {
            if ((cct & AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) != 0) {
                return Kind.SHADE_CLOSED;
            }
            if ((cct & AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) != 0) {
                return Kind.SHADE_OPENED;
            }
            return Kind.SYSTEM_UI_OTHER;
        }

        // Keep the overlay across the soft keyboard: the IME is not a real app switch, so leave
        // visibility untouched (preserves the transient-IME behaviour fixed earlier).
        if (pkg.equals(imePkg.get())) {
            return Kind.IME;
        }

        // A genuine foreground activity reports its own Activity class (e.g. ".CameraActivity").
        // Transient non-activity windows — the rotation-suggestion button (hosted by the launcher,
        // so it arrives as com.android.launcher3 / android.widget.FrameLayout), toasts, popups —
        // report a bare framework view class instead. Treat those as transient surfaces, never as an
        // app switch: leave the overlay and the active preset untouched so they can't hide a target
        // that is still in front. (The shade is handled above; this catches the rest.)
        if (isTransientWindowClass(cls)) {
            return Kind.TRANSIENT;
        }
        return Kind.FOREGROUND;
    }

    /**
     * True for a bare framework view class ({@code android.widget.*} / {@code android.view.*}). A
     * genuine foreground activity names its own Activity class in a window-state change; transient
     * non-activity windows (the rotation-suggestion button, popups, toasts) report a framework view
     * class instead. Used to ignore those so they don't hide the overlay over a still-active target.
     */
    static boolean isTransientWindowClass(String cls) {
        return cls != null
                && (cls.startsWith("android.widget.") || cls.startsWith("android.view."));
    }

    /** Package of the currently selected input method, or null if unknown. */
    static String currentImePackage(Context ctx) {
        String flat = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (flat == null) {
            return null;
        }
        ComponentName cn = ComponentName.unflattenFromString(flat);
        return cn == null ? null : cn.getPackageName();
    }
}
