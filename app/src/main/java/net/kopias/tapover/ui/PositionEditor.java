package net.kopias.tapover.ui;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import net.kopias.tapover.R;
import net.kopias.tapover.overlay.ScreenGeometry;
import net.kopias.tapover.preset.Preset;

/**
 * Size/position editing: one set for both orientations, or separate portrait and landscape sets.
 * The Size / X / Y stepper rows edit the selected set; the Portrait/Landscape selector shows only
 * when the sets are split and follows the device orientation.
 */
final class PositionEditor {
    private final Activity activity;
    private final EditorHost host;
    private final PosSet portraitSet = new PosSet();
    private final PosSet landSet = new PosSet();
    private final CheckBox sameBoth;
    private final RadioGroup orient;
    private final StepperRow sizeRow, xRow, yRow;

    PositionEditor(Activity activity, EditorHost host) {
        this.activity = activity;
        this.host = host;
        sameBoth = activity.findViewById(R.id.f_same_both);
        orient = activity.findViewById(R.id.f_orient);
        LinearLayout rows = activity.findViewById(R.id.f_position_rows);
        StepperRow.Listener onRow = v -> {
            commitRowsToSelected();
            host.refreshPreview();
            host.scheduleSave();
        };
        sizeRow = new StepperRow(activity, rows, activity.getString(R.string.editor_size),
                Preset.MIN_SIZE_PX, Preset.MAX_SIZE_PX, 1, true, onRow);
        xRow = new StepperRow(activity, rows, activity.getString(R.string.editor_x), 0, 100, 0.25f, false, onRow);
        yRow = new StepperRow(activity, rows, activity.getString(R.string.editor_y), 0, 100, 0.25f, false, onRow);
        // Fallbacks used if a field is never given a parsable value (same as the old defaults).
        sizeRow.setValue(220);
        xRow.setValue(5f);
        yRow.setValue(12f);
    }

    /** Wire the split checkbox and the orientation selector. */
    void attachListeners() {
        // Set after load() so loading a preset doesn't run the seeding rule.
        sameBoth.setOnCheckedChangeListener((b, checked) -> {
            if (!checked && !landSet.isSet) { // first split: start landscape from portrait
                landSet.from(portraitSet.size, portraitSet.x, portraitSet.y);
                landSet.isSet = true;
            }
            updateOrientationUi();
            host.refreshPreview();
            host.saveNow();
        });
        orient.setOnCheckedChangeListener((g, checkedId) -> {
            loadRowsFromSelected();
            host.refreshPreview();
        });
    }

    void load(Preset p) {
        portraitSet.from(p.sizePx, p.xPercent, p.yPercent);
        landSet.from(p.landSizePx, p.landXPercent, p.landYPercent);
        landSet.isSet = p.hasLandscapeSet();
        // A split flag without a stored landscape set behaves as one set, so show it ticked.
        sameBoth.setChecked(p.sameInBothOrientations || !p.hasLandscapeSet());
        updateOrientationUi();
    }

    /** Store both sets and the split flag onto {@code p}. */
    void writeTo(Preset p) {
        commitRowsToSelected();
        p.sizePx = Math.round(portraitSet.size);
        p.xPercent = portraitSet.x;
        p.yPercent = portraitSet.y;
        p.sameInBothOrientations = sameBoth.isChecked();
        if (landSet.isSet) {
            p.landSizePx = Math.round(landSet.size);
            p.landXPercent = landSet.x;
            p.landYPercent = landSet.y;
        } else {
            p.landSizePx = 0; // no landscape set in the editor (e.g. a template was loaded)
        }
    }

    /**
     * Store the rows (the selected set) onto {@code p} for the on-screen preview. Ticked:
     * natural-frame values. Split: display coords of the current orientation, so give both sets
     * the row values.
     */
    void writeRowsTo(Preset p) {
        p.sameInBothOrientations = sameBoth.isChecked();
        p.sizePx = rowSizePx();
        p.xPercent = xRow.getValue();
        p.yPercent = yRow.getValue();
        p.landSizePx = p.sizePx;
        p.landXPercent = p.xPercent;
        p.landYPercent = p.yPercent;
    }

    /** The Size row's value in px (at least 1). */
    int rowSizePx() {
        return Math.max(1, Math.round(sizeRow.getValue()));
    }

    /**
     * 0 when the on-screen preview can show the edited set, else the hint to rotate: with split
     * sets the edited one must be the one the current display orientation would use.
     */
    int orientationHint() {
        boolean deviceLand = isDeviceLandscape();
        boolean editingLand = selectedSet() == landSet;
        if (!sameBoth.isChecked() && editingLand != deviceLand) {
            return editingLand ? R.string.editor_rotate_landscape : R.string.editor_rotate_portrait;
        }
        return 0;
    }

    /**
     * The preview was dragged to window position (x, y): store it as percent of the display (0.25
     * steps) in the rows and the selected set. Writes rows without their listener (no feedback loop).
     */
    void applyDrag(int x, int y) {
        ScreenGeometry dm = ScreenGeometry.read(activity);
        if (sameBoth.isChecked()) {
            float[] pct = Preset.displayToNaturalPercent(x, y, rowSizePx(),
                    dm.width, dm.height, dm.rotation);
            xRow.setValue(quarter(pct[0]));
            yRow.setValue(quarter(pct[1]));
        } else {
            xRow.setValue(quarter(x * 100f / dm.width));
            yRow.setValue(quarter(y * 100f / dm.height));
        }
        commitRowsToSelected();
    }

    /** The display rotated: keep edits, snap the selector to the device. */
    void onDisplayRotated() {
        commitRowsToSelected();
        snapSelectorToDevice();
        loadRowsFromSelected();
    }

    private static float quarter(float v) {
        return Math.round(v * 4f) / 4f;
    }

    private boolean isDeviceLandscape() {
        return ScreenGeometry.read(activity).isLandscape();
    }

    /** The set the stepper rows currently edit: landscape only when split and Landscape is chosen. */
    private PosSet selectedSet() {
        boolean land = !sameBoth.isChecked()
                && orient.getCheckedRadioButtonId() == R.id.f_orient_landscape;
        return land ? landSet : portraitSet;
    }

    /** Show the selected set in the rows (no listener callbacks). */
    private void loadRowsFromSelected() {
        PosSet s = selectedSet();
        sizeRow.setValue(s.size);
        xRow.setValue(s.x);
        yRow.setValue(s.y);
    }

    /** Store the rows' current values into the selected set. */
    private void commitRowsToSelected() {
        selectedSet().from(sizeRow.getValue(), xRow.getValue(), yRow.getValue());
    }

    /** Show the Portrait/Landscape selector only when the sets are split; it follows the device. */
    private void updateOrientationUi() {
        orient.setVisibility(sameBoth.isChecked() ? View.GONE : View.VISIBLE);
        snapSelectorToDevice(); // fires loadRowsFromSelected() through the group listener if it changes
        loadRowsFromSelected();
    }

    private void snapSelectorToDevice() {
        orient.check(isDeviceLandscape() ? R.id.f_orient_landscape : R.id.f_orient_portrait);
    }
}
