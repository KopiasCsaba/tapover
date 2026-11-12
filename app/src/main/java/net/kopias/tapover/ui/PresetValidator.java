package net.kopias.tapover.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import net.kopias.tapover.R;
import net.kopias.tapover.detect.TapOverAccessibilityService;
import net.kopias.tapover.media.LatestImageFinder;
import net.kopias.tapover.media.MediaPermissions;
import net.kopias.tapover.overlay.OverlayConfig;
import net.kopias.tapover.preset.Preset;

import java.util.ArrayList;
import java.util.List;

/** The editor's checks on a preset built from the form. */
final class PresetValidator {

    private PresetValidator() {}

    /**
     * The blocking checks (a version that can't possibly work is never stored); null when valid.
     * {@code editingId} is the edited preset's id (skipped by the duplicate guard), null for a new one.
     */
    static String validate(Context ctx, Preset p, List<Preset> presets, String editingId) {
        String pkg = p.targetPackage;
        if (TextUtils.isEmpty(pkg)) { return ctx.getString(R.string.editor_error_target); }
        Preset.Action act = p.action;
        String actPkg = p.actionPackage;
        if (act == Preset.Action.LAUNCH_APP && TextUtils.isEmpty(actPkg)) {
            return ctx.getString(R.string.editor_error_action_package);
        }
        // 1. The target app must be installed (else the overlay could never trigger).
        if (!isInstalled(ctx, pkg)) {
            return ctx.getString(R.string.editor_error_target_not_installed, pkg);
        }
        // 2. A launch-app action must point at an installed app.
        if (act == Preset.Action.LAUNCH_APP && !isInstalled(ctx, actPkg)) {
            return ctx.getString(R.string.editor_error_action_not_installed, actPkg);
        }
        // 3. When the folder is used (IMAGE thumbnail OR open-latest-image), a folder must be picked.
        //    (The media-read permission itself is an app-level grant -> a non-blocking warning on leaving.)
        if (usesFolder(p) && TextUtils.isEmpty(p.actionFolder)) {
            return ctx.getString(R.string.editor_error_folder_missing);
        }
        if (usesFolder(p) && !LatestImageFinder.isSupportedFolder(p.actionFolder)) {
            return ctx.getString(R.string.editor_error_folder_unsupported);
        }
        // 4. Duplicate guard: one target must not get two overlays (the edited preset is skipped by id).
        for (Preset other : presets) {
            if (editingId != null && other.id.equals(editingId)) { continue; }
            if (p.targetsSameAs(other)) {
                return ctx.getString(R.string.editor_error_duplicate, pkg);
            }
        }
        return null;
    }

    /** Short names of the app-level permissions this saved preset needs but doesn't have yet. */
    static List<String> missingPermissions(Context ctx, Preset p) {
        List<String> missing = new ArrayList<>();
        if (!Settings.canDrawOverlays(ctx)) {
            missing.add(ctx.getString(R.string.perm_overlay_short));
        }
        if (!TapOverAccessibilityService.isEnabled(ctx)) {
            missing.add(ctx.getString(R.string.perm_accessibility_short));
        }
        if (usesFolder(p) && !MediaPermissions.granted(ctx)) {
            missing.add(ctx.getString(R.string.perm_media_short));
        }
        return missing;
    }

    /** The image folder feeds both the IMAGE thumbnail and the open-latest-image action. */
    private static boolean usesFolder(Preset p) {
        return p.content == OverlayConfig.Content.IMAGE
                || p.action == Preset.Action.OPEN_LATEST_MEDIA_FILE;
    }

    /** True if {@code pkg} resolves to an installed package this app can see. */
    private static boolean isInstalled(Context ctx, String pkg) {
        if (TextUtils.isEmpty(pkg)) { return false; }
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
