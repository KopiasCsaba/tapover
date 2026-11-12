package net.kopias.tapover.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import net.kopias.tapover.media.LatestImageFinder;
import net.kopias.tapover.overlay.OverlayConfig;
import net.kopias.tapover.preset.Preset;

/**
 * Latest media of the picked folder and one thumbnail shared by both previews (drawn
 * center-cropped at any size). Re-read only when the folder changes or after {@link #invalidate}
 * (the editor was in the background).
 */
final class MediaThumbCache {
    private final Context ctx;
    private String folder;
    private LatestImageFinder.Media media;
    private Bitmap thumb;

    MediaThumbCache(Context ctx) {
        this.ctx = ctx;
    }

    /** For IMAGE content, fill in the folder's latest thumbnail; none found → shape-only preview. */
    void fill(OverlayConfig cfg, String folderUri) {
        if (cfg.content != OverlayConfig.Content.IMAGE) {
            return;
        }
        if (!folderUri.equals(folder)) {
            folder = folderUri;
            media = LatestImageFinder.findLatestMedia(ctx, folderUri);
            thumb = (media == null) ? null
                    : LatestImageFinder.loadThumbnail(ctx, media.uri, Preset.MAX_SIZE_PX);
        }
        if (media != null) {
            cfg.isVideo = media.isVideo;
            cfg.imageBitmap = thumb;
        }
    }

    /** The latest media found (null = none). */
    Uri uri() {
        return media == null ? null : media.uri;
    }

    /** Re-read on the next fill: a photo taken meanwhile must show on return. */
    void invalidate() {
        folder = null;
    }
}
