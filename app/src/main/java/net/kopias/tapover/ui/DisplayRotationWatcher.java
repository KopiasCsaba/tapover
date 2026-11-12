package net.kopias.tapover.ui;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import net.kopias.tapover.overlay.ScreenGeometry;

/**
 * Reports display rotations. Rotation doesn't recreate the editor (configChanges), so the activity
 * forwards onConfigurationChanged to {@link #rotated}; a 180 degree flip delivers no configuration
 * change, so a display listener calls it too.
 */
final class DisplayRotationWatcher {
    private final Context ctx;
    private final Runnable onRotated;
    /** Display rotation last handled, so the display listener reacts only to real rotations. */
    private int lastRotation = -1;

    private final DisplayManager.DisplayListener listener;

    DisplayRotationWatcher(Context ctx, Runnable onRotated) {
        this.ctx = ctx;
        this.onRotated = onRotated;
        listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY
                        && ScreenGeometry.read(ctx).rotation != lastRotation) {
                    rotated();
                }
            }
        };
    }

    void start() {
        lastRotation = ScreenGeometry.read(ctx).rotation;
        ctx.getSystemService(DisplayManager.class).registerDisplayListener(listener, null);
    }

    void stop() {
        ctx.getSystemService(DisplayManager.class).unregisterDisplayListener(listener);
    }

    void rotated() {
        lastRotation = ScreenGeometry.read(ctx).rotation;
        onRotated.run();
    }
}
