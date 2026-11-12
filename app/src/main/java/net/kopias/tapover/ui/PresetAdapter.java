package net.kopias.tapover.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import net.kopias.tapover.R;
import net.kopias.tapover.preset.Preset;

import java.util.List;
import java.util.function.Consumer;

/** The main screen's preset rows: name (or target package), target package and a delete button. */
public final class PresetAdapter extends BaseAdapter {
    private final Context ctx;
    private final List<Preset> presets;
    private final Consumer<Preset> onDelete;

    /** {@code presets} is the live list shown (the caller updates it and notifies). */
    public PresetAdapter(Context ctx, List<Preset> presets, Consumer<Preset> onDelete) {
        this.ctx = ctx;
        this.presets = presets;
        this.onDelete = onDelete;
    }

    @Override public int getCount() { return presets.size(); }
    @Override public Object getItem(int position) { return presets.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // Recycle the row the ListView hands back (convertView); inflate only when none exists.
        // Rows are reused across positions, so re-bind every field on each call.
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(ctx).inflate(R.layout.row_preset, parent, false);
        }
        Preset p = presets.get(position);
        ((TextView) row.findViewById(R.id.row_name))
                .setText(TextUtils.isEmpty(p.name) ? p.targetPackage : p.name);
        ((TextView) row.findViewById(R.id.row_target)).setText(p.targetPackage);
        row.findViewById(R.id.row_delete).setOnClickListener(v -> onDelete.accept(p));
        return row;
    }
}
