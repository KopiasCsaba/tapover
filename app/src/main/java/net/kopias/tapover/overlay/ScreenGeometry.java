package net.kopias.tapover.overlay;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * A consistent snapshot of the default display: whole-screen size in the current display
 * orientation together with its rotation ({@code Surface.ROTATION_*}). Both come from the same
 * {@link Display} object and are re-read until the rotation is stable, so the size and the
 * rotation never describe different orientations.
 */
public final class ScreenGeometry {
    public final int width;
    public final int height;
    public final int rotation;

    private ScreenGeometry(int width, int height, int rotation) {
        this.width = width;
        this.height = height;
        this.rotation = rotation;
    }

    public static ScreenGeometry read(Context context) {
        Display d = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        int rot = d.getRotation();
        for (int i = 0; i < 5; i++) {
            d.getRealSize(p);
            int again = d.getRotation();
            if (again == rot) {
                break;
            }
            rot = again;
        }
        return new ScreenGeometry(p.x, p.y, rot);
    }

    public boolean isLandscape() {
        return width > height;
    }
}
