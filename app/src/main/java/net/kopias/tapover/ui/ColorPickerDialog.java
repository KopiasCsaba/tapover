package net.kopias.tapover.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import net.kopias.tapover.R;

import java.util.function.IntConsumer;

/**
 * Built-in ARGB color picker — framework Views only (no third-party dependency). Four
 * Alpha/Red/Green/Blue SeekBars over a live swatch, seeded from the field's current color;
 * OK hands the color back so the caller writes "#AARRGGBB" into the (still typeable) color field
 * and the existing preview/save paths consume it unchanged.
 */
final class ColorPickerDialog {

    private ColorPickerDialog() {}

    static AlertDialog.Builder builder(Context ctx, int start, IntConsumer onPicked) {
        final int pad = (int) (12 * ctx.getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        final View swatch = new View(ctx);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pad * 4);
        swatchLp.bottomMargin = pad;
        swatch.setLayoutParams(swatchLp);
        swatch.setBackgroundColor(start);
        root.addView(swatch);

        final SeekBar a = addChannel(ctx, root, "A", Color.alpha(start));
        final SeekBar r = addChannel(ctx, root, "R", Color.red(start));
        final SeekBar g = addChannel(ctx, root, "G", Color.green(start));
        final SeekBar b = addChannel(ctx, root, "B", Color.blue(start));

        SeekBar.OnSeekBarChangeListener live = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                swatch.setBackgroundColor(Color.argb(
                        a.getProgress(), r.getProgress(), g.getProgress(), b.getProgress()));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        };
        a.setOnSeekBarChangeListener(live);
        r.setOnSeekBarChangeListener(live);
        g.setOnSeekBarChangeListener(live);
        b.setOnSeekBarChangeListener(live);

        return new AlertDialog.Builder(ctx)
                .setTitle(R.string.editor_pick_color)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, which) -> onPicked.accept(Color.argb(
                        a.getProgress(), r.getProgress(), g.getProgress(), b.getProgress())))
                .setNegativeButton(android.R.string.cancel, null);
    }

    /** Add a labelled 0–255 SeekBar row to the color-picker dialog and return the SeekBar. */
    private static SeekBar addChannel(Context ctx, LinearLayout parent, String label, int value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setMinWidth((int) (24 * ctx.getResources().getDisplayMetrics().density));
        row.addView(t);
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(255);
        sb.setProgress(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(sb, lp);
        parent.addView(row);
        return sb;
    }
}
