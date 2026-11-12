package net.kopias.tapover.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import org.json.JSONException;
import net.kopias.tapover.R;
import net.kopias.tapover.detect.TapOverAccessibilityService;
import net.kopias.tapover.overlay.OverlayConfig;
import net.kopias.tapover.overlay.OverlayController;
import net.kopias.tapover.overlay.OverlayView;
import net.kopias.tapover.overlay.ScreenGeometry;
import net.kopias.tapover.preset.Preset;
import net.kopias.tapover.preset.PresetStore;
import net.kopias.tapover.preset.PresetTemplates;

import java.util.ArrayList;
import java.util.List;

/**
 * Add or edit a single preset. The form auto-saves (debounced for typing, immediately for picks) and
 * only ever stores a version that passes {@link PresetValidator#validate}. Besides the fields it
 * offers a "Load template" button, an in-form preview, an optional draggable on-screen preview (the
 * real overlay window, {@link ScreenPreview}), and separate portrait/landscape size+position sets
 * ({@link PositionEditor}). The fields and their binding to a preset live in {@link PresetForm};
 * this activity wires them to the previews, the auto-save and the lifecycle.
 */
public class EditorActivity extends Activity implements EditorHost {

    public static final String EXTRA_PRESET_ID = "preset_id";

    /** Largest side (px) of the in-form preview; the on-screen preview uses the real size. */
    private static final int PREVIEW_MAX = 300;
    private static final long SAVE_DEBOUNCE_MS = 400;
    /** Editor-wide preferences (e.g. "Show on screen", the one-time folder hint). */
    private static final String EDITOR_PREFS = "tapover_editor";

    private PresetStore store;
    private List<Preset> presets;
    /** The stored preset being edited; null for a new preset until its first save. */
    private Preset editing;
    /** Id for a new preset, fixed up front so every auto-save updates the same entry. */
    private String newId;

    private PresetForm form;
    private FrameLayout preview;
    private TextView status;
    private MediaThumbCache media;
    /** The on-screen preview window; null until created in onCreate. */
    private ScreenPreview screenPreview;
    private DisplayRotationWatcher rotationWatcher;

    // Auto-save state.
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedSave = this::autoSave;
    /** JSON of the last version written to the store by this editor (null = nothing saved yet). */
    private String lastSavedJson;
    /** The preset as last saved by this editor (null until a valid save happened). */
    private Preset lastSaved;
    /** JSON of the form as first shown; tells "user changed something" from "just opened". */
    private String baselineJson;
    /** Set once {@link #finish} ran its final save; later edits are not scheduled. */
    private boolean leaving;
    /** True while {@link #populate} fills the form, so half-filled states are never saved. */
    private boolean populating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        // Edge-to-edge (targetSdk 35): the app bar spans the status bar; the form scrolls below
        // it and clears the nav bar and keyboard.
        String id = getIntent().getStringExtra(EXTRA_PRESET_ID);
        Toolbar toolbar = WindowInsetsHelper.setupAppBar(this,
                getString(id != null ? R.string.editor_title_edit : R.string.editor_title_new));
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.cd_navigate_up);
        // Up behaves exactly like system Back (which ends in finish(): final save + warnings).
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
        WindowInsetsHelper.padScrollingContent(findViewById(R.id.editor_scroll));
        status = findViewById(R.id.f_status);
        preview = findViewById(R.id.preview_container);
        store = new PresetStore(this);
        presets = new ArrayList<>(store.load());
        media = new MediaThumbCache(this);

        SharedPreferences editorPrefs = getSharedPreferences(EDITOR_PREFS, MODE_PRIVATE);
        form = new PresetForm(this, editorPrefs, this);
        screenPreview = new ScreenPreview(this, editorPrefs, form.position, media, this::screenConfig,
                new OverlayController.DragListener() {
                    @Override public void onDragged(int x, int y) { applyDrag(x, y); }
                    @Override public void onDragEnd(int x, int y) { applyDrag(x, y); saveNow(); }
                });
        rotationWatcher = new DisplayRotationWatcher(this, this::onDisplayRotated);

        if (id != null) {
            for (Preset p : presets) {
                if (p.id.equals(id)) { editing = p; break; }
            }
        }
        if (editing == null) { newId = new Preset().id; } // stable id for the preset's first save
        populate(editing != null ? editing : defaults());
        form.attachListeners();

        findViewById(R.id.btn_load_template).setOnClickListener(v -> showTemplatePicker());

        form.updateFieldVisibility();
        refreshPreview();
        baselineJson = jsonOf(buildPreset());
        if (editing != null) { lastSavedJson = baselineJson; }
        showStatus(validate());
    }

    private Preset defaults() {
        Preset p = new Preset();
        p.sizePx = 220;
        p.cornerRadiusPx = 110f;
        p.colorArgb = Color.RED;
        p.actionFolder = ""; // no folder until the user picks one
        return p;
    }

    private void populate(Preset p) {
        populating = true;
        try {
            form.populate(p);
        } finally {
            populating = false;
        }
    }

    /** Offer the predefined templates; the chosen one fills the form (still fully editable). */
    private void showTemplatePicker() {
        final List<PresetTemplates.Template> templates = PresetTemplates.all();
        String[] labels = new String[templates.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = templates.get(i).label;
        }
        showEditorDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.editor_load_template_title)
                .setItems(labels, (d, which) -> {
                    populate(templates.get(which).toPreset());
                    form.updateFieldVisibility();
                    refreshPreview();
                    saveNow();
                }));
    }

    // --- auto-save ---

    @Override
    public void scheduleSave() {
        if (leaving || populating) { return; }
        saveHandler.removeCallbacks(debouncedSave);
        saveHandler.postDelayed(debouncedSave, SAVE_DEBOUNCE_MS);
    }

    @Override
    public void saveNow() {
        if (populating) { return; }
        saveHandler.removeCallbacks(debouncedSave);
        autoSave();
    }

    /** Validate and persist the form; returns the blocking reason, or null if it was valid. */
    private String autoSave() {
        String err = validate();
        showStatus(err);
        if (err != null) { return err; }
        Preset p = buildPreset();
        String json = jsonOf(p);
        if (json.equals(lastSavedJson)) { return null; } // nothing changed since the last write
        // Merge into what is stored now, not the list read at onCreate: another editor instance
        // or a delete on the main screen may have changed it meanwhile.
        presets = new ArrayList<>(store.load());
        boolean replaced = false;
        for (int i = 0; i < presets.size() && !replaced; i++) {
            if (presets.get(i).id.equals(p.id)) { presets.set(i, p); replaced = true; }
        }
        if (!replaced) { presets.add(p); }
        editing = p;
        // A recreate (uiMode, locale, process restore) must edit this preset, not start a new one.
        getIntent().putExtra(EXTRA_PRESET_ID, p.id);
        store.save(presets);
        TapOverAccessibilityService.requestReload();
        lastSavedJson = json;
        lastSaved = p;
        return null;
    }

    private void showStatus(String err) {
        status.setText(err == null ? "" : err);
        status.setVisibility(err == null ? View.GONE : View.VISIBLE);
    }

    private String validate() {
        return PresetValidator.validate(this, buildPreset(), presets,
                editing != null ? editing.id : null);
    }

    /** The form as a new Preset object (a copy of the edited one, so the stored list is untouched). */
    private Preset buildPreset() {
        Preset p;
        try {
            p = (editing != null) ? Preset.fromJson(editing.toJson()) : new Preset();
        } catch (JSONException e) {
            p = new Preset();
        }
        if (editing == null && newId != null) { p.id = newId; }
        form.writeTo(p);
        return p;
    }

    private static String jsonOf(Preset p) {
        try { return p.toJson().toString(); } catch (JSONException e) { return ""; }
    }

    /** Flush a pending save; on the way out explain a rejected state and warn about permissions. */
    @Override
    public void finish() {
        if (!leaving) {
            leaving = true;
            saveHandler.removeCallbacks(debouncedSave);
            String err = autoSave();
            if (err != null && !jsonOf(buildPreset()).equals(baselineJson)) {
                EditorUi.toast(this, R.string.editor_not_saved, err);
            }
            if (lastSaved != null) {
                // Non-blocking warning: if an app-level permission the saved preset needs is
                // ungranted it can never show/act; name exactly what to grant and where.
                List<String> missing = PresetValidator.missingPermissions(this, lastSaved);
                if (!missing.isEmpty()) {
                    EditorUi.toast(this, R.string.editor_warn_missing_perms,
                            TextUtils.join(", ", missing));
                }
            }
        }
        super.finish();
    }

    // --- previews ---

    @Override
    public void refreshPreview() {
        OverlayConfig cfg = new OverlayConfig();
        cfg.sizePx = Math.min(PREVIEW_MAX, form.position.rowSizePx());
        cfg.cornerRadiusPx = form.cornerRadiusPx();
        cfg.colorArgb = form.colorArgb();
        cfg.content = form.content();
        cfg.iconResId = form.iconResId();
        media.fill(cfg, form.folder.uri());
        OverlayView v = new OverlayView(this, cfg);
        preview.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cfg.sizePx, cfg.sizePx);
        lp.gravity = Gravity.CENTER;
        preview.addView(v, lp);
        if (screenPreview != null) { // null during onCreate, before the preview exists
            screenPreview.update();
        }
    }

    @Override
    public void showEditorDialog(AlertDialog.Builder b) {
        screenPreview.showDialog(b);
    }

    /**
     * The edited values resolved exactly like the service does ({@link Preset#toOverlayConfig}),
     * with the (cached) latest media the preview's appearance key needs.
     */
    private OverlayConfig screenConfig() {
        Preset p = new Preset();
        form.position.writeRowsTo(p);
        form.writeAppearance(p);
        ScreenGeometry g = ScreenGeometry.read(this);
        OverlayConfig cfg = p.toOverlayConfig(g.width, g.height, g.rotation);
        media.fill(cfg, form.folder.uri());
        return cfg;
    }

    /**
     * The preview was dragged to window position (x, y): store it in the rows, then snap the
     * window to the stored value so it sits exactly where the service will show it.
     */
    private void applyDrag(int x, int y) {
        form.position.applyDrag(x, y);
        screenPreview.relayout(screenConfig());
    }

    /** Rotation doesn't recreate the editor (configChanges); keep edits, snap the selector. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rotationWatcher.rotated();
    }

    private void onDisplayRotated() {
        form.position.onDisplayRotated();
        refreshPreview();
    }

    // --- lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        rotationWatcher.start();
        TapOverAccessibilityService.setEditorActive(true);
        screenPreview.setResumed(true);
        refreshPreview(); // also re-checks the overlay permission (may have changed meanwhile)
    }

    @Override
    protected void onPause() {
        screenPreview.setResumed(false);
        if (!leaving) { saveNow(); } // a pending edit must not be lost if the editor goes away
        rotationWatcher.stop();
        screenPreview.remove(); // never leave the preview window up when the editor isn't in front
        media.invalidate(); // a photo taken meanwhile must show on return
        TapOverAccessibilityService.setEditorActive(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveHandler.removeCallbacks(debouncedSave);
        screenPreview.dismissDialogs();
        screenPreview.remove();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        form.onActivityResult(requestCode, resultCode, data);
    }
}
