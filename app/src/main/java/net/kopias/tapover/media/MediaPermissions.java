package net.kopias.tapover.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The media-read permissions TapOver needs to see the latest photo AND video, version-split by
 * SDK level (READ_MEDIA_IMAGES/VIDEO on API 33+, READ_EXTERNAL_STORAGE below). Single source of
 * truth shared by the preset manager and the editor.
 */
public final class MediaPermissions {

    private MediaPermissions() {
    }

    /** The media permissions required on this SDK level. */
    public static String[] required() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? new String[]{ Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO }
                : new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    /** True if every required media permission is currently granted. */
    public static boolean granted(Context ctx) {
        for (String perm : required()) {
            if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
