package net.kopias.tapover.ui;

import android.view.View;
import android.widget.AdapterView;

/** OnItemSelectedListener that runs a Runnable on selection. */
final class SimpleSelected implements AdapterView.OnItemSelectedListener {
    private final Runnable onChange;

    SimpleSelected(Runnable onChange) { this.onChange = onChange; }

    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onChange.run(); }
    @Override public void onNothingSelected(AdapterView<?> p) { }
}
