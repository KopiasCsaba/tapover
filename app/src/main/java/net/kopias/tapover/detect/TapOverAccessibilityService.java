package net.kopias.tapover.detect;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import net.kopias.tapover.media.LiveMediaLoader;
import net.kopias.tapover.overlay.OverlayConfig;
import net.kopias.tapover.overlay.OverlayController;
import net.kopias.tapover.overlay.ScreenGeometry;
import net.kopias.tapover.preset.Preset;
import net.kopias.tapover.preset.PresetStore;

/**
 * Detects the foreground app/activity via {@code TYPE_WINDOW_STATE_CHANGED} and shows/hides the
 * matching preset's overlay, running the preset's tap action. Event-driven — no polling, no timers.
 * Wiring only: events are classified by {@link WindowEventClassifier} (which documents the
 * visibility rules), thumbnails load off the main thread in {@link LiveMediaLoader}, tap actions run
 * in {@link ActionRunner}, and lock-screen state comes from {@link LockState}.
 *
 * <p>Lock screen: a preset with {@code showOnLockScreen} shows a padlock instead of its content
 * while locked (no media is queried or held); one without it is held back (faded out) while locked.
 */
public class TapOverAccessibilityService extends AccessibilityService {


    /** How many packages {@link #lastActivityFor} remembers. */
    private static final int RECENT_PACKAGES = 8;

    private OverlayController controller;
    private LiveMediaLoader media;
    private LockState lock;
    private ActionRunner actions;

    /** The active target preset (shown, or faded out under the shade); null = none. */
    private Preset currentPreset;

    /** The shade opened over the active target and hasn't announced its dismissal yet. */
    private boolean shadeOpen;

    /** Display rotation last handled, so the display listener reacts only to real rotations. */
    private int lastRotation = -1;

    /** Set while connected so the UI can ask the running service to reload presets. */
    private static TapOverAccessibilityService instance;
    /** True while the preset editor is in front (it draws its own preview instead). */
    private static volatile boolean editorActive;

    /**
     * Recent foreground activity class per package, read by the editor via {@link #lastActivityFor}.
     * Static: the editor runs in-process.
     */
    private static final RecentActivities RECENT = new RecentActivities(RECENT_PACKAGES);

    /** The most recent foreground activity class observed for {@code pkg}, or null. Read by the editor. */
    public static String lastActivityFor(String pkg) {
        return RECENT.get(pkg);
    }

    /** True if this accessibility service is enabled in system settings. */
    public static boolean isEnabled(Context ctx) {
        ComponentName expected = new ComponentName(ctx, TapOverAccessibilityService.class);
        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        // ENABLED_ACCESSIBILITY_SERVICES is a ':'-separated list of flattened ComponentNames;
        // split on ':' and check whether any entry resolves to this service.
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String entry : splitter) {
            ComponentName parsed = ComponentName.unflattenFromString(entry);
            if (parsed != null && parsed.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    /** Ask the running service (if any) to re-read presets from storage. */
    public static void requestReload() {
        if (instance != null) {
            instance.reloadPresets();
        }
    }

    /** While the preset editor is in front the service shows no overlay and ignores events. */
    public static void setEditorActive(boolean active) {
        editorActive = active;
        if (active && instance != null) {
            instance.removeOverlay();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        controller = new OverlayController(this);
        media = new LiveMediaLoader(this, this::onMediaLoaded);
        lock = new LockState(this, this::onLockStateChanged);
        actions = new ActionRunner(this, controller, media);
        instance = this;
        lastRotation = ScreenGeometry.read(this).rotation;
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        lock.register();
        reloadPresets();
        DebugLog.i("accessibility service connected; presets=" + TargetRegistry.size());
    }

    /** Load persisted presets into the registry; drop any stale overlay. */
    private void reloadPresets() {
        TargetRegistry.replaceAll(new PresetStore(this).load());
        if (controller != null) {
            removeOverlay();
        }
        DebugLog.i("reload presets=" + TargetRegistry.size());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null || editorActive) {
            return;
        }
        String pkg = p.toString();
        CharSequence c = event.getClassName();
        String cls = (c == null) ? null : c.toString();
        int cct = event.getContentChangeTypes();

        WindowEventClassifier.Kind kind = WindowEventClassifier.classify(pkg, cls, cct, getPackageName(),
                () -> WindowEventClassifier.currentImePackage(this));
        switch (kind) {
            case OWN_MAIN_SCREEN:
                clearOverlay();
                return;
            case SHADE_OPENED:
            case SHADE_CLOSED:
            case SYSTEM_UI_OTHER:
                // Concise log so the shade and (real-hardware-only) rotation cases stay one adb logcat away.
                DebugLog.i("system-ui pkg=" + pkg + " cct=" + cct
                        + (kind == WindowEventClassifier.Kind.SHADE_CLOSED ? " → restore (pane gone)"
                                : kind == WindowEventClassifier.Kind.SHADE_OPENED ? " → fade out (pane shown)"
                                : " → ignore"));
                onShade(kind);
                return;
            case TRANSIENT:
                DebugLog.i("transient pkg=" + pkg + " cls=" + cls + " → ignore");
                return;
            case FOREGROUND:
                onForeground(pkg, cls);
                return;
            default: // OWN_WINDOW, IME: not an app switch
                break;
        }
    }

    /** The shade opened/closed over the active target (rule 1): fade out / back in, keep the preset. */
    private void onShade(WindowEventClassifier.Kind kind) {
        if (controller == null || currentPreset == null) {
            return;
        }
        if (kind == WindowEventClassifier.Kind.SHADE_CLOSED) {
            shadeOpen = false;
            if (!lockBlocked(currentPreset)) {
                applyLockState(); // the shade may have covered a lock/unlock
                controller.restore();
            }
        } else if (kind == WindowEventClassifier.Kind.SHADE_OPENED) {
            shadeOpen = true;
            controller.fadeOut();
        }
    }

    /** A genuine foreground activity (rule 3): show its preset, or hide and forget. */
    private void onForeground(String pkg, String cls) {
        if (cls != null) {
            // Record the runtime class so the editor's activity picker can later offer it
            // (see RECENT). This is the value matching uses below.
            RECENT.put(pkg, cls);
        }
        DebugLog.i("fg pkg=" + pkg + " cls=" + cls);

        // Match on the runtime class name from the event, not a manifest activity/alias name:
        // an <activity-alias> resolves to its target class at runtime, so the class accessibility
        // reports is the only reliable key. Preset.matches() compares it (exact or suffix-tolerant).
        Preset preset = TargetRegistry.findMatch(pkg, cls);
        if (preset != null) {
            // Target is foreground → show it (or fade it back in if it was suppressed).
            if (Settings.canDrawOverlays(this)) {
                showOverlay(preset);
            } else {
                DebugLog.w("overlay permission not granted; cannot show");
            }
        } else {
            // A different real app is in front → hide and forget. No debounce, no launcher check:
            // the launcher is just another non-matching app and hides the overlay at once.
            clearOverlay();
        }
    }

    /**
     * Make the preset's overlay visible. If the same preset is already active, just fade it back in
     * (it may have been suppressed by the shade); otherwise build and show it fresh. IMAGE content
     * starts as the placeholder and the latest thumbnail is filled in when it has loaded.
     */
    private void showOverlay(Preset preset) {
        boolean alreadyActive = currentPreset != null
                && currentPreset.id.equals(preset.id)
                && controller.isShowing();
        if (alreadyActive) {
            // The display may have rotated while the overlay was faded out (e.g. locked in
            // landscape, unlocked in portrait) — re-resolve the position before fading back in.
            repositionOverlay();
            applyLockState();
            // The target re-emits events under an open shade (e.g. after a rotation); only the
            // shade's own dismissal may bring the overlay back then.
            if (!shadeOpen && !lockBlocked(preset)) {
                controller.restore();
            }
            return;
        }

        media.stop();
        currentPreset = preset;
        shadeOpen = false;
        OverlayConfig cfg = overlayConfigFor(preset);
        boolean locked = lock.isLocked();
        // Allowed on the lock screen while locked: show a padlock, never the content.
        cfg.locked = locked && preset.showOnLockScreen;
        if (cfg.locked) {
            DebugLog.i("locked → lock icon");
        }
        OverlayController.OnTapListener tap = () -> onOverlayTap(preset);
        if (lockBlocked(preset)) {
            // Not allowed on the lock screen while locked: create it faded out (non-touchable); the
            // unlock (or the lock screen's own dismissal) fades it back in.
            DebugLog.i("locked; preset not shown on lock screen → held back");
            controller.showImmediate(cfg, tap, true);
        } else {
            controller.show(cfg, tap);
        }
        syncMedia();
    }

    /** True while locked and the preset isn't allowed on the lock screen: keep it hidden. */
    private boolean lockBlocked(Preset preset) {
        return preset != null && !preset.showOnLockScreen && lock.isLocked();
    }

    /**
     * Bring the live overlay in line with the lock state: padlock instead of content for a
     * lock-screen preset while locked (content back on unlock), held back for one that isn't
     * allowed there. Media is loaded only while unlocked (see {@link #syncMedia}).
     */
    private void applyLockState() {
        Preset preset = currentPreset;
        if (preset == null || controller == null || !controller.isShowing()) {
            return;
        }
        boolean locked = lock.isLocked();
        boolean padlock = locked && preset.showOnLockScreen;
        if (padlock != controller.isLocked()) {
            controller.setLocked(padlock);
            DebugLog.i(padlock ? "locked → lock icon" : "unlocked → content");
        }
        if (lockBlocked(preset)) {
            controller.fadeOut();
        }
        syncMedia();
    }

    /**
     * Keep the live thumbnail loader in line with the overlay: running for an IMAGE overlay at its
     * current size, stopped otherwise — and always stopped while locked (no MediaStore access then).
     * A size change (rotation) restarts it so the thumbnail is decoded for the new size.
     */
    private void syncMedia() {
        Preset preset = currentPreset;
        boolean wanted = preset != null && preset.content == OverlayConfig.Content.IMAGE
                && controller.isShowing() && !lock.isLocked();
        if (!wanted) {
            media.stop();
            return;
        }
        int size = controller.currentSizePx();
        if (!media.isLoading(preset.actionFolder, size)) {
            media.start(preset.actionFolder, size);
        }
    }

    /** A thumbnail arrived (main thread; stale requests were already dropped by the loader). */
    private void onMediaLoaded(Bitmap thumb, boolean isVideo, boolean initial) {
        if (currentPreset == null || controller == null || !controller.isShowing()
                || lock.isLocked()) {
            return;
        }
        // First load just fills the placeholder; live refreshes pulse.
        controller.updateMedia(thumb, isVideo, !initial);
    }

    /**
     * Lock broadcast (screen off/on, unlock). Screen off on a locked/secure device switches to the
     * padlock (or holds the overlay back). On unlock a target that stayed in front (e.g. a camera
     * opened from the lock screen) shows its content again, or an overlay held back while locked.
     */
    private void onLockStateChanged(boolean screenOff) {
        if (controller == null) {
            return;
        }
        applyLockState();
        if (!screenOff && !lock.isLocked()) {
            DebugLog.i("unlocked");
            if (currentPreset != null && !shadeOpen) {
                controller.restore();
            }
        }
    }

    /** Resolve the preset's percentage position against the CURRENT display size. */
    private OverlayConfig overlayConfigFor(Preset preset) {
        ScreenGeometry g = ScreenGeometry.read(this);
        return preset.toOverlayConfig(g.width, g.height, g.rotation);
    }

    /**
     * Re-resolve the live overlay's pixel position for the current display size. The position is
     * stored as screen percentages, so pixels computed in one orientation are wrong in the other.
     */
    private void repositionOverlay() {
        Preset preset = currentPreset;
        if (preset == null || controller == null || !controller.isShowing()) {
            return;
        }
        OverlayConfig cfg = overlayConfigFor(preset);
        lastRotation = ScreenGeometry.read(this).rotation;
        controller.relayout(cfg.x, cfg.y, cfg.sizePx);
        // Size may differ between the portrait and landscape sets: the thumbnail was decoded for
        // the old size, so reload it at the new one (the old one is drawn scaled until then).
        syncMedia();
    }

    /**
     * Display rotated (or otherwise resized): move the overlay to match. This also covers the
     * race where a target's window event arrives before the display finishes rotating to the
     * target's orientation — the config change lands afterwards and corrects the position.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        repositionOverlay();
    }

    /**
     * A 180 degree flip changes neither orientation nor size, so no configuration change is
     * delivered; the display listener catches every rotation change.
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            int rot = ScreenGeometry.read(TapOverAccessibilityService.this).rotation;
            if (rot != lastRotation) {
                // Track every rotation, even with no overlay up: a later 180° flip back must
                // still be seen as a change.
                lastRotation = rot;
                repositionOverlay();
            }
        }
    };

    /** Forget the active target: stop media work, reset shade state. */
    private void forgetTarget() {
        if (media != null) {
            media.stop();
        }
        currentPreset = null;
        shadeOpen = false;
    }

    /** Fade the overlay out and remove it (a different app is foreground). Forgets the target. */
    private void clearOverlay() {
        forgetTarget();
        if (controller != null) {
            controller.hide();
        }
    }

    /** Remove the overlay immediately, no fade (config reload / teardown). */
    private void removeOverlay() {
        forgetTarget();
        if (controller != null) {
            controller.removeNow();
        }
    }

    /**
     * The overlay already faded itself out on the tap. On success the launched app comes to the
     * front and clears it; if the action failed the target stays in front, so bring it back.
     */
    private void onOverlayTap(Preset preset) {
        actions.run(preset, () -> currentPreset == preset, launched -> {
            if (!launched && currentPreset == preset && !shadeOpen && !lockBlocked(preset)) {
                controller.restore();
            }
        });
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (lock != null) {
            lock.unregister();
        }
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        if (controller != null) {
            removeOverlay();
        }
        if (media != null) {
            media.shutdown();
        }
        RECENT.clear();
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }
}
