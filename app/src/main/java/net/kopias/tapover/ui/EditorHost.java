package net.kopias.tapover.ui;

import android.app.AlertDialog;

/** What the editor's form sections need from the editor: preview refresh, auto-save, dialogs. */
interface EditorHost {
    /** Redraw the in-form preview and update the on-screen one. */
    void refreshPreview();

    /** Save after the user stopped typing / stepping for a moment. */
    void scheduleSave();

    /** Save right away (drops any pending debounced save, which this one supersedes). */
    void saveNow();

    /** Show an editor dialog with the on-screen preview hidden until it is dismissed. */
    void showEditorDialog(AlertDialog.Builder b);
}
