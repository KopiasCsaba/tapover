package net.kopias.tapover.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import net.kopias.tapover.R;
import net.kopias.tapover.overlay.OverlayConfig;
import net.kopias.tapover.overlay.OverlayController;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The optional draggable on-screen preview: the real overlay window (same window code path as the
 * service's overlay), toggled by the editor-wide "Show on screen" preference. It is hidden while
 * an editor dialog is open, so the dialogs are shown through here.
 */
final class ScreenPreview {
    private static final String KEY_SHOW_ON_SCREEN = "show_on_screen";

    private final Activity activity;
    private final OverlayController controller;
    private final CheckBox showOnScreen;
    private final TextView hint;
    private final PositionEditor position;
    private final MediaThumbCache media;
    /** The edited values resolved exactly like the service does (incl. the cached latest media). */
    private final Supplier<OverlayConfig> config;
    private final OverlayController.DragListener dragListener;

    /** Appearance of the shown preview window; a change rebuilds it, a pure move/resize relayouts. */
    private String key;
    private boolean resumed;
    /** Editor dialogs currently showing; the preview is hidden while any is open. */
    private final List<AlertDialog> openDialogs = new ArrayList<>();

    ScreenPreview(Activity activity, SharedPreferences prefs, PositionEditor position,
                  MediaThumbCache media, Supplier<OverlayConfig> config,
                  OverlayController.DragListener dragListener) {
        this.activity = activity;
        this.position = position;
        this.media = media;
        this.config = config;
        this.dragListener = dragListener;
        controller = new OverlayController(activity);
        hint = activity.findViewById(R.id.f_show_hint);
        showOnScreen = activity.findViewById(R.id.f_show_on_screen);
        showOnScreen.setChecked(prefs.getBoolean(KEY_SHOW_ON_SCREEN, true));
        showOnScreen.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(KEY_SHOW_ON_SCREEN, checked).apply();
            update();
        });
    }

    /** Only shown while the editor is in front. */
    void setResumed(boolean resumed) {
        this.resumed = resumed;
    }

    /**
     * Show, move or remove the on-screen preview to match the form: only while the editor is in
     * front, "Show on screen" is ticked, the overlay permission is granted and the edited set is the
     * one the current display orientation would use. Otherwise it is removed and a hint says why.
     */
    void update() {
        boolean canDraw = Settings.canDrawOverlays(activity);
        showOnScreen.setEnabled(canDraw);
        int hintRes = 0;
        boolean show = false;
        if (!canDraw) {
            hintRes = R.string.editor_show_need_perm;
        } else if (showOnScreen.isChecked()) {
            hintRes = position.orientationHint();
            show = hintRes == 0 && resumed && openDialogs.isEmpty();
        }
        if (hintRes != 0) {
            hint.setText(hintRes);
        }
        hint.setVisibility(hintRes != 0 ? View.VISIBLE : View.GONE);
        if (!show) {
            remove();
            return;
        }
        OverlayConfig cfg = config.get();
        String k = cfg.cornerRadiusPx + "|" + cfg.colorArgb + "|" + cfg.content + "|"
                + cfg.iconResId + "|" + (cfg.imageBitmap == null ? "" : media.uri());
        if (controller.isShowing() && k.equals(key)) {
            controller.relayout(cfg.x, cfg.y, cfg.sizePx);
            return;
        }
        controller.showPreview(cfg, dragListener);
        key = controller.isShowing() ? k : null;
    }

    /** Snap the window to the stored values so it sits exactly where the service will show it. */
    void relayout(OverlayConfig cfg) {
        controller.relayout(cfg.x, cfg.y, cfg.sizePx);
    }

    void remove() {
        controller.removeNow();
        key = null;
    }

    /** Show an editor dialog with the on-screen preview hidden until it is dismissed. */
    void showDialog(AlertDialog.Builder b) {
        remove();
        AlertDialog dialog = b.create();
        dialog.setOnDismissListener(d -> {
            openDialogs.remove(dialog);
            update();
        });
        openDialogs.add(dialog);
        dialog.show();
    }

    /** Dismiss open dialogs so a recreate (e.g. dark-mode switch) doesn't leak their windows. */
    void dismissDialogs() {
        for (AlertDialog d : new ArrayList<>(openDialogs)) {
            d.dismiss();
        }
    }
}
