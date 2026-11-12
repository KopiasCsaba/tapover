package net.kopias.tapover.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import net.kopias.tapover.R;
import net.kopias.tapover.media.LatestImageFinder;

/**
 * The image folder field. The folder is chosen with the system folder picker; the latest photo is
 * read via MediaStore (needs the media-read permission).
 */
final class FolderField {
    private static final int REQ_PICK_FOLDER = 2001;
    private static final String KEY_FOLDER_HINT_SHOWN = "folder_hint_shown";

    private final Activity activity;
    private final EditorHost host;
    private final SharedPreferences prefs;
    private final EditText field;
    private final View label, button;

    /** The picked image folder, stored as the folder picker's tree URI string ("" = none). The folder
     *  field shows a friendly name derived from this; this is the value persisted onto the preset and
     *  parsed into a MediaStore volume + relative path by {@link LatestImageFinder}. */
    private String folderUri = "";

    FolderField(Activity activity, SharedPreferences prefs, EditorHost host) {
        this.activity = activity;
        this.prefs = prefs;
        this.host = host;
        field = activity.findViewById(R.id.f_action_folder);
        label = activity.findViewById(R.id.label_action_folder);
        button = activity.findViewById(R.id.btn_pick_folder);
        button.setOnClickListener(v -> showPicker());
        // Picker-only field: tapping the (non-editable) field opens the same picker as its button.
        field.setOnClickListener(v -> showPicker());
    }

    String uri() {
        return folderUri;
    }

    /** Set the picked folder from the folder picker's tree URI string ("" = none) and update its display. */
    void set(String uriString) {
        folderUri = (uriString == null) ? "" : uriString;
        field.setText(displayName(folderUri));
    }

    void setVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        field.setVisibility(vis);
        label.setVisibility(vis);
        button.setVisibility(vis);
    }

    /** A short human-readable name for a tree URI (e.g. "DCIM/Camera"), or the "none" placeholder. */
    private String displayName(String uriString) {
        if (TextUtils.isEmpty(uriString)) {
            return activity.getString(R.string.editor_folder_none);
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString));
            int colon = docId.indexOf(':');
            String path = (colon >= 0) ? docId.substring(colon + 1) : docId;
            return TextUtils.isEmpty(path) ? docId : path;
        } catch (Exception e) {
            return uriString;
        }
    }

    /**
     * Launch the system folder picker; the result comes back through {@link #onActivityResult}. The
     * first time ever, warn first that a big folder (DCIM) takes a while to load in the picker.
     */
    private void showPicker() {
        if (!prefs.getBoolean(KEY_FOLDER_HINT_SHOWN, false)) {
            host.showEditorDialog(new AlertDialog.Builder(activity)
                    .setTitle(R.string.editor_folder_hint_title)
                    .setMessage(R.string.editor_folder_hint)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        prefs.edit().putBoolean(KEY_FOLDER_HINT_SHOWN, true).apply();
                        launchPicker();
                    })
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }
        launchPicker();
    }

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            activity.startActivityForResult(i, REQ_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            EditorUi.toast(activity, R.string.editor_error_no_picker);
        }
    }

    /** Forwarded from the activity; applies a picked folder. */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_FOLDER || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        // We only need the folder's path (parsed into a MediaStore volume + relative path); the
        // newest photo is read via MediaStore, so no persistable URI grant is taken.
        set(data.getData().toString());
        host.refreshPreview();
        host.saveNow();
    }
}
