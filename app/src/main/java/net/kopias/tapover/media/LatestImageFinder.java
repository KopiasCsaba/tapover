package net.kopias.tapover.media;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.util.Locale;

import net.kopias.tapover.detect.DebugLog;

/**
 * Finds the most recently modified image or video in a folder via MediaStore (fast, indexed), and
 * loads a thumbnail for it. The folder is chosen with the system folder picker
 * ({@code ACTION_OPEN_DOCUMENT_TREE}); the picked tree URI is parsed here into a MediaStore volume +
 * relative path, so the lookup is one cheap indexed query rather than a directory walk. Requires
 * READ_MEDIA_IMAGES / READ_MEDIA_VIDEO (API 33+) or READ_EXTERNAL_STORAGE (API ≤ 32) to see media
 * the app did not create.
 *
 * <p>The match is non-recursive: only files directly in the picked folder are considered (the user
 * picks the exact folder, e.g. DCIM/Camera). A legacy plain folder-name string is still accepted as a
 * top-level path on primary storage for backward compatibility.
 */
public final class LatestImageFinder {

    private static final String TAG = "TapOver";

    /** The device-storage DocumentsProvider; only its tree document ids map to MediaStore paths. */
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    private LatestImageFinder() {
    }

    /** The newest media item in a folder: its content Uri, whether it's a video, and its mime. */
    public static final class Media {
        public final Uri uri;
        public final boolean isVideo;
        public final String mime;

        Media(Uri uri, boolean isVideo) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.mime = isVideo ? "video/*" : "image/*";
        }
    }

    /** Null-safe Uri of a possibly-null media result (null media → null). */
    public static Uri uriOf(Media m) {
        return m == null ? null : m.uri;
    }

    /** Null-safe video flag of a possibly-null media result (null media → false). */
    public static boolean isVideo(Media m) {
        return m != null && m.isVideo;
    }

    /**
     * True if {@code folder} can be resolved to a MediaStore path: a tree URI from the device-storage
     * provider, or a legacy plain folder name. Trees from other providers (cloud, app folders) can't.
     */
    public static boolean isSupportedFolder(String folder) {
        if (folder == null || folder.isEmpty()) {
            return false;
        }
        return !folder.startsWith("content:")
                || EXTERNAL_STORAGE_AUTHORITY.equals(Uri.parse(folder).getAuthority());
    }

    /**
     * Newest image OR video in the chosen folder (whichever was modified most recently).
     *
     * @param folder the SAF tree URI string from the folder picker (e.g. {@code .../tree/primary%3ADCIM%2FCamera}),
     *               or a legacy top-level folder name (e.g. {@code "DCIM"}).
     * @return the newest media, or {@code null} if none / no permission / error.
     */
    public static Media findLatestMedia(Context ctx, String folder) {
        if (!isSupportedFolder(folder)) {
            return null;
        }

        String volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        String selection;
        String[] args;
        if (folder.startsWith("content:")) {
            // SAF tree URI → MediaStore volume + relative path, matched exactly (non-recursive).
            String docId;
            try {
                docId = DocumentsContract.getTreeDocumentId(Uri.parse(folder));
            } catch (Exception e) {
                return null;
            }
            int colon = docId.indexOf(':');
            String vol = (colon >= 0) ? docId.substring(0, colon) : "primary";
            String rel = (colon >= 0) ? docId.substring(colon + 1) : docId;
            if (rel.isEmpty()) {
                return null; // storage root — nothing meaningful to scan
            }
            if (!rel.endsWith("/")) {
                rel = rel + "/";
            }
            volume = vol.equalsIgnoreCase("primary")
                    ? MediaStore.VOLUME_EXTERNAL_PRIMARY : vol.toLowerCase(Locale.US);
            selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?)";
            args = new String[]{ rel,
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) };
        } else {
            // Legacy plain folder name → top-level path on primary storage (recursive LIKE).
            selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? ESCAPE '\\' AND "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?)";
            args = new String[]{ escapeLike(folder) + "/%",
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) };
        }

        Uri files = MediaStore.Files.getContentUri(volume);
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE };
        try (Cursor c = queryNewest(ctx, files, projection, selection, args)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID));
                int type = c.getInt(c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE));
                boolean isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                Uri collection = isVideo
                        ? MediaStore.Video.Media.getContentUri(volume)
                        : MediaStore.Images.Media.getContentUri(volume);
                return new Media(ContentUris.withAppendedId(collection, id), isVideo);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No media read permission for findLatestMedia", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "findLatestMedia query failed", e);
        }
        return null;
    }

    /**
     * Query the newest matching row only — ORDER BY DATE_MODIFIED DESC, _ID DESC, LIMIT 1. The limit is
     * applied version-correctly: via {@link ContentResolver#QUERY_ARG_LIMIT} on API 30+, and by
     * appending {@code "LIMIT 1"} to the sort string on API 29 (the sort-string limit is honored
     * on Q but rejected with IllegalArgumentException on R+).
     */
    private static Cursor queryNewest(Context ctx, Uri uri, String[] projection,
                                      String selection, String[] args) {
        ContentResolver cr = ctx.getContentResolver();
        // DATE_MODIFIED has 1 s resolution; _ID breaks ties (burst shots) in insertion order.
        String sort = MediaStore.MediaColumns.DATE_MODIFIED + " DESC, "
                + MediaStore.MediaColumns._ID + " DESC";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bundle q = new Bundle();
            q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
            q.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort);
            q.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);
            return cr.query(uri, projection, q, null);
        }
        return cr.query(uri, projection, selection, args, sort + " LIMIT 1");
    }

    /** Load a thumbnail bitmap for the given image Uri, or {@code null} on failure. */
    public static Bitmap loadThumbnail(Context ctx, Uri uri, int sizePx) {
        int s = Math.max(1, sizePx);
        try {
            return ctx.getContentResolver().loadThumbnail(uri, new Size(s, s), null);
        } catch (Exception e) {
            DebugLog.w("loadThumbnail failed for " + uri, e);
            return null;
        }
    }

    /** Escapes LIKE wildcards so a folder name containing % or _ matches only itself. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
