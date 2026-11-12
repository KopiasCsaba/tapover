package net.kopias.tapover.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import net.kopias.tapover.R;
import net.kopias.tapover.overlay.OverlayConfig;
import net.kopias.tapover.preset.Preset;

/**
 * The editor form's fields and their binding to a {@link Preset}: {@link #populate} fills the
 * form, {@link #writeTo} reads it back. The bigger parts live in their own sections (position
 * sets, target activities, image folder, lock-screen toggle); this class wires them and the
 * simple fields.
 */
final class PresetForm {

    // Small fixed set of framework drawables for SYSTEM_ICON content.
    // LABELS and RES are index-aligned (index 0 = "None"/no drawable).
    private static final String[] ICON_LABELS = {
            "None", "Camera", "Gallery", "View", "App",
            "Image", "Slideshow", "Edit", "Share", "Set as" };
    private static final int[] ICON_RES = {
            0,
            android.R.drawable.ic_menu_camera,
            android.R.drawable.ic_menu_gallery,
            android.R.drawable.ic_menu_view,
            android.R.drawable.sym_def_app_icon,
            android.R.drawable.ic_menu_report_image,
            android.R.drawable.ic_menu_slideshow,
            android.R.drawable.ic_menu_edit,
            android.R.drawable.ic_menu_share,
            android.R.drawable.ic_menu_set_as,
    };

    private final Activity activity;
    private final EditorHost host;
    private final EditText name, targetPackage, color, actionPackage;
    private final Spinner action, content, icon;
    private final View iconLabel, labelColor, btnPickColor;
    private final StepperRow cornerRow;
    private final AppPicker appPicker;

    final PositionEditor position;
    private final TargetActivitiesList activities;
    final FolderField folder;
    private final LockScreenToggle showOnLock;

    PresetForm(Activity activity, SharedPreferences editorPrefs, EditorHost host) {
        this.activity = activity;
        this.host = host;
        name = activity.findViewById(R.id.f_name);
        targetPackage = activity.findViewById(R.id.f_target_package);
        color = activity.findViewById(R.id.f_color);
        actionPackage = activity.findViewById(R.id.f_action_package);
        action = activity.findViewById(R.id.f_action);
        content = activity.findViewById(R.id.f_content);
        icon = activity.findViewById(R.id.f_icon);
        iconLabel = activity.findViewById(R.id.label_icon);
        labelColor = activity.findViewById(R.id.label_color);
        btnPickColor = activity.findViewById(R.id.btn_pick_color);

        position = new PositionEditor(activity, host);
        cornerRow = new StepperRow(activity, activity.findViewById(R.id.f_corner_row),
                activity.getString(R.string.editor_corner), 0, Preset.MAX_SIZE_PX / 2f, 1, true,
                v -> { host.refreshPreview(); host.scheduleSave(); });
        activities = new TargetActivitiesList(activity, activity.findViewById(R.id.f_target_activities), host);
        folder = new FolderField(activity, editorPrefs, host);
        showOnLock = new LockScreenToggle(activity, activity.findViewById(R.id.f_show_on_lock), host);
        appPicker = new AppPicker(activity, host);

        action.setAdapter(enumAdapter(namesOf(Preset.Action.values())));
        content.setAdapter(enumAdapter(namesOf(OverlayConfig.Content.values())));
        icon.setAdapter(enumAdapter(ICON_LABELS));

        activity.findViewById(R.id.btn_pick_target).setOnClickListener(v -> pickApp(targetPackage));
        activity.findViewById(R.id.btn_pick_action_app).setOnClickListener(v -> pickApp(actionPackage));
        activity.findViewById(R.id.btn_pick_activity).setOnClickListener(
                v -> activities.showPicker(targetPackage()));
        // Picker-only fields: tapping the (non-editable) field opens the same picker as its button.
        targetPackage.setOnClickListener(v -> pickApp(targetPackage));
        actionPackage.setOnClickListener(v -> pickApp(actionPackage));
        // Color stays a typeable hex field, but the button (and tapping the field) open a picker.
        btnPickColor.setOnClickListener(v -> pickColor());
        color.setOnClickListener(v -> pickColor());
    }

    /** Wire the field change listeners; called once, after the first {@link #populate}. */
    void attachListeners() {
        position.attachListeners();
        color.addTextChangedListener(new SimpleWatcher(() -> { host.refreshPreview(); host.scheduleSave(); }));
        name.addTextChangedListener(new SimpleWatcher(host::scheduleSave));

        content.setOnItemSelectedListener(new SimpleSelected(() -> {
            updateFieldVisibility();
            host.refreshPreview();
            host.saveNow();
        }));
        icon.setOnItemSelectedListener(new SimpleSelected(() -> { host.refreshPreview(); host.saveNow(); }));
        // The action choice gates the image-folder field (open-latest-image uses it).
        action.setOnItemSelectedListener(new SimpleSelected(() -> {
            updateFieldVisibility();
            host.refreshPreview();
            host.saveNow();
        }));
    }

    void populate(Preset p) {
        name.setText(p.name);
        targetPackage.setText(p.targetPackage);
        activities.setAll(p.targetActivities);
        position.load(p);
        showOnLock.set(p.showOnLockScreen);
        cornerRow.setValue(p.cornerRadiusPx);
        color.setText(String.format("#%08X", p.colorArgb));
        actionPackage.setText(p.actionPackage);
        folder.set(p.actionFolder);
        action.setSelection(p.action.ordinal());
        content.setSelection(p.content.ordinal());
        icon.setSelection(iconIndexFor(p.iconResId));
    }

    /** Write the form onto {@code p} (everything but its id). */
    void writeTo(Preset p) {
        p.name = name.getText().toString().trim();
        p.targetPackage = targetPackage();
        p.targetActivities = activities.values();
        position.writeTo(p);
        p.showOnLockScreen = showOnLock.isChecked();
        writeAppearance(p);
        p.action = action();
        p.actionPackage = actionPackage.getText().toString().trim();
        p.actionFolder = folder.uri();
    }

    /** Write the look (corner, color, content, icon) onto {@code p}. */
    void writeAppearance(Preset p) {
        p.cornerRadiusPx = cornerRadiusPx();
        p.colorArgb = colorArgb();
        p.content = content();
        p.iconResId = iconResId();
    }

    String targetPackage() {
        return targetPackage.getText().toString().trim();
    }

    int cornerRadiusPx() {
        return Math.round(cornerRow.getValue());
    }

    int colorArgb() {
        return EditorUi.parseColor(color.getText().toString(), Color.RED);
    }

    OverlayConfig.Content content() {
        return OverlayConfig.Content.valueOf((String) content.getSelectedItem());
    }

    int iconResId() {
        return ICON_RES[icon.getSelectedItemPosition()];
    }

    private Preset.Action action() {
        return Preset.Action.valueOf((String) action.getSelectedItem());
    }

    /**
     * Show each content/action-specific field only when it is actually used:
     * color → SOLID_COLOR, icon → SYSTEM_ICON, folder → IMAGE content OR the
     * open-latest-image action (the folder feeds both the IMAGE preview and that action).
     */
    void updateFieldVisibility() {
        OverlayConfig.Content c = content();
        Preset.Action a = action();
        setRowVisible(c == OverlayConfig.Content.SOLID_COLOR, color, labelColor, btnPickColor);
        setRowVisible(c == OverlayConfig.Content.SYSTEM_ICON, icon, iconLabel);
        folder.setVisible(c == OverlayConfig.Content.IMAGE || a == Preset.Action.OPEN_LATEST_MEDIA_FILE);
    }

    /** Forwarded from the activity (the folder picker's result). */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        folder.onActivityResult(requestCode, resultCode, data);
    }

    private void pickApp(EditText target) {
        appPicker.show(pkg -> {
            if (target == targetPackage && !pkg.equals(targetPackage())) {
                activities.clear(); // they belonged to the previous target app
            }
            target.setText(pkg);
            host.refreshPreview();
            host.saveNow();
        });
    }

    private void pickColor() {
        host.showEditorDialog(ColorPickerDialog.builder(activity, colorArgb(), argb -> {
            color.setText(String.format("#%08X", argb));
            host.refreshPreview();
            host.saveNow();
        }));
    }

    private static void setRowVisible(boolean visible, View... views) {
        int vis = visible ? View.VISIBLE : View.GONE;
        for (View v : views) v.setVisibility(vis);
    }

    private ArrayAdapter<String> enumAdapter(String[] items) {
        return new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, items);
    }

    private static String[] namesOf(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i].name();
        return out;
    }

    private static int iconIndexFor(int resId) {
        for (int i = 0; i < ICON_RES.length; i++) if (ICON_RES[i] == resId) return i;
        return 0;
    }
}
