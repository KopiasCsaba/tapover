package net.kopias.tapover.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import net.kopias.tapover.R;

/**
 * Draws an {@link OverlayConfig}: a rounded-rect (or circle/square) shape clipped to its
 * bounds, filled with a solid color, a centered system icon, or a center-cropped bitmap — or, in
 * lock-screen privacy mode ({@link OverlayConfig#locked}), a padlock instead of any of those.
 */
public class OverlayView extends View {

    private final OverlayConfig config;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF bounds = new RectF();
    private final Rect bitmapSrc = new Rect();
    private final Path playTriangle = new Path();
    /** SYSTEM_ICON drawable, resolved once; null when none or the id doesn't resolve. */
    private Drawable icon;
    /** Padlock glyph for {@link OverlayConfig#locked}, resolved on first use. */
    private Drawable lockIcon;

    public OverlayView(Context context, OverlayConfig config) {
        super(context);
        this.config = config;
        updateLockedDescription();
        if (config.content == OverlayConfig.Content.SYSTEM_ICON && config.iconResId != 0) {
            try {
                icon = context.getResources().getDrawable(config.iconResId, context.getTheme());
            } catch (RuntimeException e) {
                // A stale/foreign id (e.g. from restored data) must not crash the service's draw.
                Log.w("TapOver", "icon resource not found: " + config.iconResId, e);
            }
        }
    }

    /**
     * Replace the IMAGE content (bitmap + video flag) and redraw. When {@code animate} is true
     * play a brief update pulse; it is suppressed while the overlay is faded out (shade open) so a
     * live refresh never yanks the hidden dot back into view.
     */
    public void setMedia(Bitmap b, boolean isVideo, boolean animate) {
        if (config.locked) {
            return; // never hold or draw media while locked (privacy); unlock reloads it
        }
        config.imageBitmap = b;
        config.isVideo = isVideo;
        invalidate();
        if (animate) {
            playUpdatePulse();
        }
    }

    /**
     * Switch lock-screen privacy mode in place and redraw. Entering it drops any loaded bitmap, so
     * nothing of the content stays in memory behind the padlock; leaving it shows the configured
     * content again (IMAGE: the placeholder until the caller delivers fresh media).
     */
    public void setLocked(boolean locked) {
        if (config.locked == locked) {
            return;
        }
        config.locked = locked;
        if (locked) {
            config.imageBitmap = null;
            config.isVideo = false;
        }
        updateLockedDescription();
        invalidate();
    }

    public boolean isLocked() {
        return config.locked;
    }

    private void updateLockedDescription() {
        setContentDescription(config.locked
                ? getContext().getString(R.string.overlay_locked_description) : null);
    }

    /** Subtle non-looping pulse to signal that the media just refreshed. */
    private void playUpdatePulse() {
        animate().cancel();
        setScaleX(0.85f);
        setScaleY(0.85f);
        setAlpha(0.5f);
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        float maxRadius = Math.min(w, h) / 2f;
        float radius = Math.max(0f, Math.min(config.cornerRadiusPx, maxRadius));
        clipPath.reset();
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.clipPath(clipPath);

        if (config.locked) {
            drawLock(canvas);
            canvas.restoreToCount(save);
            return;
        }
        switch (config.content) {
            case SOLID_COLOR:
                paint.setColor(config.colorArgb);
                canvas.drawRect(bounds, paint);
                break;
            case SYSTEM_ICON:
                drawIcon(canvas);
                break;
            case IMAGE:
                drawBitmap(canvas);
                break;
        }

        canvas.restoreToCount(save);
    }

    /** Dark fill along the overlay's own shape (clipped) with a centered white padlock. */
    private void drawLock(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6202020);
        canvas.drawRect(bounds, paint);
        if (lockIcon == null) {
            lockIcon = getResources().getDrawable(R.drawable.ic_lock_overlay, getContext().getTheme());
        }
        drawCentered(canvas, lockIcon, 0.55f);
    }

    /** Draw {@code d} centered, its side {@code fraction} of the view's shorter side. */
    private void drawCentered(Canvas canvas, Drawable d, float fraction) {
        int w = getWidth();
        int h = getHeight();
        int side = (int) (Math.min(w, h) * fraction);
        int left = (w - side) / 2;
        int top = (h - side) / 2;
        d.setBounds(left, top, left + side, top + side);
        d.draw(canvas);
    }

    private void drawIcon(Canvas canvas) {
        Drawable d = icon;
        if (d == null) {
            drawPlaceholder(canvas); // no icon: keep the (tappable) overlay visible
            return;
        }
        drawCentered(canvas, d, 0.6f);
    }

    private void drawBitmap(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (config.imageBitmap != null) {
            int bw = config.imageBitmap.getWidth();
            int bh = config.imageBitmap.getHeight();
            // Center-crop: the largest centered source rect with the view's aspect ratio.
            float scale = Math.max((float) w / bw, (float) h / bh);
            int srcW = (int) (w / scale);
            int srcH = (int) (h / scale);
            int srcLeft = (bw - srcW) / 2;
            int srcTop = (bh - srcH) / 2;
            bitmapSrc.set(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH);
            canvas.drawBitmap(config.imageBitmap, bitmapSrc, bounds, paint);
        } else {
            drawPlaceholder(canvas); // no media (none yet / no permission): keep it visible
        }
        // Video affordance: play badge over the thumbnail, or as the icon when no thumb loaded.
        if (config.isVideo) {
            drawPlayBadge(canvas, w, h);
        }
    }

    /**
     * Shown when IMAGE/SYSTEM_ICON content has nothing to draw, so the overlay never becomes an
     * invisible tap target: translucent grey fill + outline along the clip shape (half of each
     * stroke is clipped).
     */
    private void drawPlaceholder(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99808080);
        canvas.drawRect(bounds, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 * d); // 2dp visible: black outer band
        paint.setColor(0xFF000000);
        canvas.drawPath(clipPath, paint);
        paint.setStrokeWidth(2 * d); // 1dp visible: white edge
        paint.setColor(0xFFFFFFFF);
        canvas.drawPath(clipPath, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A translucent dark disc with a white play triangle, centered. */
    private void drawPlayBadge(Canvas canvas, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.22f;

        paint.setColor(0x99000000); // translucent black disc
        canvas.drawCircle(cx, cy, r, paint);

        float t = r * 0.7f; // triangle size
        playTriangle.rewind();
        playTriangle.moveTo(cx - t * 0.4f, cy - t * 0.55f);
        playTriangle.lineTo(cx - t * 0.4f, cy + t * 0.55f);
        playTriangle.lineTo(cx + t * 0.6f, cy);
        playTriangle.close();
        paint.setColor(0xFFFFFFFF); // white play triangle
        canvas.drawPath(playTriangle, paint);
    }
}
