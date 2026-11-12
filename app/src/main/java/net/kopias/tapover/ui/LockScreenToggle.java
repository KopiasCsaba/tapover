package net.kopias.tapover.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;

import net.kopias.tapover.R;

/**
 * The "Show on lock screen" checkbox. Turning it on by hand asks first: the overlay then also
 * appears while the phone is locked (as a lock icon) and its tap opens the chosen app there. Only
 * "Enable" keeps it on; Cancel or dismissing the dialog leaves it off. Turning it off, or setting
 * it from a loaded preset ({@link #set}), needs no confirmation.
 */
final class LockScreenToggle {
    private final CheckBox box;
    /** True while the box is changed from code, so its listener doesn't react. */
    private boolean setting;

    LockScreenToggle(Context ctx, CheckBox box, EditorHost host) {
        this.box = box;
        box.setOnCheckedChangeListener((b, checked) -> {
            if (setting) {
                return;
            }
            if (!checked) {
                host.saveNow();
                return;
            }
            set(false); // stays off unless confirmed
            host.showEditorDialog(new AlertDialog.Builder(ctx)
                    .setTitle(R.string.editor_lock_confirm_title)
                    .setMessage(R.string.editor_lock_confirm_message)
                    .setPositiveButton(R.string.editor_lock_confirm_enable, (d, w) -> {
                        set(true);
                        host.saveNow();
                    })
                    .setNegativeButton(android.R.string.cancel, null));
        });
    }

    /** Set from code (a loaded preset): no dialog, no save. */
    void set(boolean on) {
        setting = true;
        try {
            box.setChecked(on);
        } finally {
            setting = false;
        }
    }

    boolean isChecked() {
        return box.isChecked();
    }
}
