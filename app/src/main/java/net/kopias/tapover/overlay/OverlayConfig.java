package net.kopias.tapover.overlay;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Appearance of a single overlay: shape, size, screen position, and content.
 *
 * <p>This is appearance-only and in-memory. The persisted preset model produces these configs
 * (target matching, tap action, etc.).
 */
public class OverlayConfig {

    /** What is drawn inside the (clipped) shape. */
    public enum Content { SOLID_COLOR, SYSTEM_ICON, IMAGE }

    /** Square bounding box edge length, in pixels. */
    public int sizePx = 200;

    /** Offset from the top-left of the screen, in pixels. */
    public int x = 0;
    public int y = 0;

    /**
     * Corner radius in pixels. {@code 0} = square; {@code >= sizePx / 2f} = full circle.
     * Values in between produce a rounded square.
     */
    public float cornerRadiusPx = 0f;

    public Content content = Content.SOLID_COLOR;

    /** Used when {@link Content#SOLID_COLOR}. */
    public int colorArgb = Color.RED;

    /** Framework drawable (e.g. {@code android.R.drawable.*}) used when {@link Content#SYSTEM_ICON}. */
    public int iconResId = 0;

    /** Used when {@link Content#IMAGE}; null otherwise. */
    public Bitmap imageBitmap = null;

    /** When {@link Content#IMAGE}: the latest media is a video → draw a play badge. */
    public boolean isVideo = false;

    /**
     * Lock-screen privacy mode: draw a padlock instead of the content (any {@link Content}), so a
     * locked device never reveals the configured color/icon/thumbnail. See {@link OverlayView#setLocked}.
     */
    public boolean locked = false;
}
