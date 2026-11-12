package net.kopias.tapover.media;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Off-main-thread {@link LatestImageFinder} work for the accessibility service: loads the newest
 * media's thumbnail of a folder and keeps it live (a {@link ContentObserver} on images + videos)
 * while {@link #start started}; also runs one-shot lookups for the tap action. All MediaStore
 * queries and thumbnail decodes run on one background thread; results come back on the main thread.
 *
 * <p>Stale results are dropped: each {@link #start} creates a new request object that acts as the
 * generation token, and a result is delivered only if its request is still the current one — so a
 * preset change, overlay removal, size change or lock ({@link #stop}) since the request discards it.
 * Methods other than the listener callbacks are main-thread only.
 */
public final class LiveMediaLoader {

    /** Receives a loaded thumbnail on the main thread. */
    public interface Listener {
        /**
         * @param thumb   thumbnail, or null when the folder has no media / no permission (placeholder)
         * @param initial first load of a {@link #start} (no refresh pulse wanted), vs. a live refresh
         */
        void onMedia(Bitmap thumb, boolean isVideo, boolean initial);
    }

    /** What to load; identity is the generation token (see class doc). Immutable. */
    private static final class Request {
        final String folder;
        final int sizePx;

        Request(String folder, int sizePx) {
            this.folder = folder;
            this.sizePx = sizePx;
        }
    }

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("TapOver-media");
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Current request; null = stopped. Written on main, read on the background thread. */
    private volatile Request current;

    /** Registered only while started, to refresh the thumbnail live. Callbacks run on {@link #bg}. */
    private ContentObserver observer;

    // Background thread only: what was last loaded, so a refresh only reloads on a real change.
    private Request lastLoadedFor;
    private Uri lastShown;

    public LiveMediaLoader(Context context, Listener listener) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.listener = listener;
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    /** True if started for exactly this folder and size. */
    public boolean isLoading(String folder, int sizePx) {
        Request r = current;
        return r != null && r.sizePx == sizePx && Objects.equals(r.folder, folder);
    }

    /**
     * Load the newest media of {@code folder} at {@code sizePx} and keep watching for new captures.
     * Supersedes any previous request (its pending results are dropped).
     */
    public void start(String folder, int sizePx) {
        Request r = new Request(folder, sizePx);
        current = r;
        registerObserver();
        bg.post(() -> load(r, true));
    }

    /** Stop watching and drop every pending result. Safe to call when stopped. */
    public void stop() {
        current = null;
        unregisterObserver();
    }

    /** One-shot newest-media lookup off the main thread; {@code done} runs on the main thread. */
    public void findLatest(String folder, Consumer<LatestImageFinder.Media> done) {
        bg.post(() -> {
            LatestImageFinder.Media m = LatestImageFinder.findLatestMedia(context, folder);
            main.post(() -> done.accept(m));
        });
    }

    /** Stop and end the background thread (service teardown). The loader is unusable afterwards. */
    public void shutdown() {
        stop();
        thread.quitSafely();
    }

    /** Background thread. */
    private void load(Request r, boolean initial) {
        if (r != current) {
            return; // superseded before we even started
        }
        LatestImageFinder.Media m = LatestImageFinder.findLatestMedia(context, r.folder);
        Uri u = LatestImageFinder.uriOf(m);
        // MediaStore fires several events per capture; only redraw when the newest item actually
        // changes (avoids reloading the same thumbnail repeatedly).
        if (!initial && r == lastLoadedFor && Objects.equals(u, lastShown)) {
            return;
        }
        lastLoadedFor = r;
        lastShown = u;
        Bitmap thumb = (m == null) ? null : LatestImageFinder.loadThumbnail(context, m.uri, r.sizePx);
        boolean isVideo = LatestImageFinder.isVideo(m);
        main.post(() -> {
            if (r == current) {
                listener.onMedia(thumb, isVideo, initial);
            }
        });
    }

    /** Watch images + videos so the overlay refreshes when a new capture lands. */
    private void registerObserver() {
        if (observer != null) {
            return; // already watching
        }
        observer = new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange, Uri changedUri) {
                Request r = current;
                if (r != null) {
                    load(r, false);
                }
            }
        };
        context.getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        context.getContentResolver().registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
    }

    private void unregisterObserver() {
        if (observer != null) {
            context.getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
    }
}
