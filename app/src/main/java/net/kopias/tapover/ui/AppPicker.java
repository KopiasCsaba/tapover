package net.kopias.tapover.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

import net.kopias.tapover.R;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Picks a launcher app; hands the chosen package name back. */
final class AppPicker {
    /** Apps likely to show photos (gallery/viewer) are bolded so they're easy to spot. */
    private static final Pattern PHOTO_APP =
            Pattern.compile("photo|pics|gallery|images|album|viewer", Pattern.CASE_INSENSITIVE);

    private final Context ctx;
    private final EditorHost host;
    /** Launcher apps, "label\npackage" sorted by label (photo apps bold); loaded on first use. */
    private List<CharSequence> labels;
    private List<String> packages;

    AppPicker(Context ctx, EditorHost host) {
        this.ctx = ctx;
        this.host = host;
    }

    void show(Consumer<String> onPicked) {
        if (labels == null) {
            // Loading every launcher label is the slow part; do it once per editor.
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                byLabel.put(ri.loadLabel(pm) + "\n" + pkg, pkg); // label shown, pkg stored
            }
            labels = new ArrayList<>(byLabel.size());
            for (String row : byLabel.keySet()) {
                // The row is "label\npackage", so one find() matches either part.
                if (PHOTO_APP.matcher(row).find()) {
                    SpannableString bold = new SpannableString(row);
                    bold.setSpan(new StyleSpan(Typeface.BOLD), 0, row.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    labels.add(bold);
                } else {
                    labels.add(row);
                }
            }
            packages = new ArrayList<>(byLabel.values());
        }
        final List<String> pkgs = packages;
        host.showEditorDialog(new AlertDialog.Builder(ctx)
                .setTitle(R.string.editor_pick_app)
                .setItems(labels.toArray(new CharSequence[0]),
                        (d, which) -> onPicked.accept(pkgs.get(which))));
    }
}
