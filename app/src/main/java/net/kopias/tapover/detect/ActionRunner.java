package net.kopias.tapover.detect;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import net.kopias.tapover.R;
import net.kopias.tapover.media.LatestImageFinder;
import net.kopias.tapover.media.LiveMediaLoader;
import net.kopias.tapover.overlay.OverlayController;
import net.kopias.tapover.preset.Preset;

import java.util.function.BooleanSupplier;

/** Runs a preset's tap action; failures are surfaced with the overlay's message bubble. Main thread. */
final class ActionRunner {


    /** Result of {@link #run}, on the main thread. */
    interface Done {
        /** @param launched false if nothing was launched (the target stays in front) */
        void onDone(boolean launched);
    }

    private final Context context;
    private final OverlayController controller;
    private final LiveMediaLoader media;

    ActionRunner(Context context, OverlayController controller, LiveMediaLoader media) {
        this.context = context;
        this.controller = controller;
        this.media = media;
    }

    /**
     * Run the preset's tap action. The media lookup of OPEN_LATEST_MEDIA_FILE runs off the main
     * thread; when it returns, the launch happens only if {@code stillWanted} (the user may have
     * left the target meanwhile). {@code done} is not called for a dropped launch.
     */
    void run(Preset preset, BooleanSupplier stillWanted, Done done) {
        switch (preset.action) {
            case LAUNCH_APP:
                done.onDone(launchApp(preset.actionPackage));
                return;
            case OPEN_LATEST_MEDIA_FILE:
                media.findLatest(preset.actionFolder, m -> {
                    if (stillWanted.getAsBoolean()) {
                        done.onDone(openMedia(preset, m));
                    }
                });
                return;
            default:
                done.onDone(false);
        }
    }

    private boolean launchApp(String pkg) {
        Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            DebugLog.w("no launch intent for " + pkg);
            controller.flashMessage(context.getString(R.string.action_error_launch, pkg));
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
        DebugLog.i("launch " + pkg);
        return true;
    }

    private boolean openMedia(Preset preset, LatestImageFinder.Media media) {
        if (media == null) {
            DebugLog.w("no latest media in " + preset.actionFolder);
            controller.flashMessage(context.getString(R.string.action_error_no_media));
            return false;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(media.uri, media.mime); // image/* or video/*
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // If an app is specified, open the media directly with it; otherwise let the system pick
        // the default viewer.
        if (preset.actionPackage != null && !preset.actionPackage.isEmpty()) {
            view.setPackage(preset.actionPackage);
        }
        try {
            context.startActivity(view);
            DebugLog.i("open latest media " + media.uri + " (" + media.mime + ")"
                    + (view.getPackage() != null ? " via " + view.getPackage() : ""));
            return true;
        } catch (ActivityNotFoundException e) {
            // Chosen app can't handle it / isn't installed → fall back to default.
            DebugLog.w("actionPackage can't open media; retrying without package", e);
            view.setPackage(null);
            try {
                context.startActivity(view);
                return true;
            } catch (ActivityNotFoundException e2) {
                DebugLog.w("no viewer for media " + media.uri, e2);
                controller.flashMessage(context.getString(R.string.action_error_no_viewer));
                return false;
            }
        }
    }
}
