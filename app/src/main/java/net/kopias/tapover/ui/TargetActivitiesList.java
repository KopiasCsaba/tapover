package net.kopias.tapover.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kopias.tapover.R;
import net.kopias.tapover.detect.TapOverAccessibilityService;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The preset's target activities as edited (empty = any), shown as tap-to-remove rows, plus the
 * picker that lists the target package's activities so the user can narrow the preset to them.
 */
final class TargetActivitiesList {
    private final Context ctx;
    private final EditorHost host;
    private final LinearLayout list;
    private final List<String> activities = new ArrayList<>();

    TargetActivitiesList(Context ctx, LinearLayout list, EditorHost host) {
        this.ctx = ctx;
        this.list = list;
        this.host = host;
    }

    /** Replace the list (e.g. from a loaded preset) and redraw it. */
    void setAll(List<String> values) {
        activities.clear();
        activities.addAll(values);
        render();
    }

    /** Empty the list (it belonged to the previous target app) and redraw it. */
    void clear() {
        activities.clear();
        render();
    }

    /** A copy of the edited list. */
    List<String> values() {
        return new ArrayList<>(activities);
    }

    /** List the target package's activities so the user can narrow the preset to one. */
    void showPicker(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            EditorUi.toast(ctx, R.string.editor_error_pick_app_first);
            return;
        }
        // A set: several activity-aliases can point at the same runtime class (the duplicates).
        TreeSet<String> classes = new TreeSet<>();
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (info.activities != null) {
                for (ActivityInfo ai : info.activities) {
                    // For activity-aliases the runtime class is the targetActivity (what the
                    // AccessibilityService reports), so offer that instead of the alias name.
                    classes.add(ai.targetActivity != null ? ai.targetActivity : ai.name);
                }
            }
        } catch (Exception e) {
            EditorUi.toast(ctx, R.string.editor_error_read_activities, pkg);
            return;
        }

        // Parallel label (shown) / value (stored) lists. "Any activity" clears the field; the
        // currently-detected activity is offered first since it is guaranteed to match.
        final List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        labels.add(ctx.getString(R.string.editor_activity_any));
        values.add("");
        String detected = TapOverAccessibilityService.lastActivityFor(pkg);
        if (!TextUtils.isEmpty(detected) && !activities.contains(detected)) {
            labels.add(ctx.getString(R.string.editor_activity_detected, detected));
            values.add(detected);
        }
        for (String cls : classes) {
            if (!cls.equals(detected) && !activities.contains(cls)) {
                labels.add(cls);
                values.add(cls);
            }
        }
        if (values.size() == 1 && classes.isEmpty()) { // only "Any" — nothing to pick
            EditorUi.toast(ctx, R.string.editor_error_read_activities, pkg);
            return;
        }
        host.showEditorDialog(new AlertDialog.Builder(ctx)
                .setTitle(R.string.editor_pick_activity_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String v = values.get(which);
                    if (v.isEmpty()) {
                        activities.clear(); // "Any activity"
                    } else {
                        activities.add(v);
                    }
                    changed();
                }));
    }

    private void changed() {
        render();
        host.refreshPreview();
        host.saveNow();
    }

    /**
     * Rebuild the target-activity list: one outlined group, a row per activity (short class name
     * over its package, with a remove button), or an explanatory "Any activity" row when empty.
     */
    private void render() {
        list.removeAllViews();
        float dp = ctx.getResources().getDisplayMetrics().density;
        int secondary = EditorUi.themeColor(ctx, android.R.attr.textColorSecondary);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(12 * dp);
        box.setStroke(Math.max(1, (int) dp), (secondary & 0x00FFFFFF) | 0x40000000);
        list.setBackground(box);
        if (activities.isEmpty()) {
            list.addView(row(ctx.getString(R.string.editor_activity_any),
                    ctx.getString(R.string.editor_activity_any_hint), null));
            return;
        }
        for (int i = 0; i < activities.size(); i++) {
            if (i > 0) {
                View divider = new View(ctx);
                divider.setBackgroundColor((secondary & 0x00FFFFFF) | 0x26000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp));
                lp.setMarginStart((int) (16 * dp));
                list.addView(divider, lp);
            }
            String a = activities.get(i);
            int dot = a.lastIndexOf('.');
            String simple = dot >= 0 ? a.substring(dot + 1) : a;
            String prefix = dot > 0 ? a.substring(0, dot) : null;
            list.addView(row(simple, prefix, a));
        }
    }

    /** One list row: title + optional subtitle; {@code removeValue} != null adds a remove button. */
    private View row(String title, String subtitle, String removeValue) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (56 * dp));
        row.setPaddingRelative((int) (16 * dp), (int) (8 * dp),
                (int) ((removeValue != null ? 4 : 16) * dp), (int) (8 * dp));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(EditorUi.themeColor(ctx, android.R.attr.textColorPrimary));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t);
        if (subtitle != null) {
            TextView sub = new TextView(ctx);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setTextColor(EditorUi.themeColor(ctx, android.R.attr.textColorSecondary));
            if (removeValue != null) { // a package path: one line, keep both ends readable
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            }
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (removeValue != null) {
            ImageButton remove = new ImageButton(ctx);
            remove.setImageResource(R.drawable.ic_close);
            TypedValue bg = new TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, bg, true);
            remove.setBackgroundResource(bg.resourceId);
            remove.setContentDescription(ctx.getString(R.string.editor_activity_remove_desc, removeValue));
            remove.setOnClickListener(v -> {
                activities.remove(removeValue);
                changed();
            });
            int size = (int) (48 * dp);
            row.addView(remove, new LinearLayout.LayoutParams(size, size));
        }
        return row;
    }
}
