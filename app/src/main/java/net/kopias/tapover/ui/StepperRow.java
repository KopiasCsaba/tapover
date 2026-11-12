package net.kopias.tapover.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import net.kopias.tapover.R;

/**
 * One numeric editor row built in code: a label, a typeable field (the precise source of truth),
 * {@code −}/{@code +} buttons that step the value (press-and-hold repeats) and a slider below.
 * The three controls stay in sync. Typed values outside [min, max] or unparsable text are left in
 * the field as typed; the slider clamps and {@link #getValue()} returns the clamped parsed value
 * (or the last valid one when the text doesn't parse).
 */
public class StepperRow {

    /** Called when the user changes the value via the field, the buttons or the slider. */
    public interface Listener {
        void onValueChanged(float v);
    }

    private static final long REPEAT_START_MS = 400;
    private static final long REPEAT_EVERY_MS = 60;

    private final float min, max, step;
    private final boolean integer;
    private final Listener listener;
    private final EditText field;
    private final SeekBar slider;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float value;
    /** True while we change the field/slider from code, so their callbacks don't echo back. */
    private boolean updating;

    public StepperRow(Context ctx, LinearLayout container, String label, float min, float max,
                      float step, boolean integer, Listener l) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.integer = integer;
        this.listener = l;
        this.value = min;
        float dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(label);
        top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button minus = stepButton(ctx, "−", ctx.getString(R.string.stepper_decrease, label), -1, dp);
        top.addView(minus, new LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp)));

        field = new EditText(ctx);
        field.setInputType(integer ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setGravity(Gravity.CENTER);
        field.setSelectAllOnFocus(true);
        field.setContentDescription(label);
        top.addView(field, new LinearLayout.LayoutParams((int) (96 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));

        Button plus = stepButton(ctx, "+", ctx.getString(R.string.stepper_increase, label), +1, dp);
        top.addView(plus, new LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp)));

        container.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        slider = new SeekBar(ctx);
        slider.setMax(Math.round((max - min) / step));
        slider.setContentDescription(label);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (40 * dp));
        slp.bottomMargin = (int) (8 * dp);
        container.addView(slider, slp);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (updating) return;
                try {
                    value = clamp(Float.parseFloat(s.toString().trim()));
                } catch (NumberFormatException e) {
                    // keep the last valid value; the text stays as typed
                }
                syncSlider();
                notifyListener();
            }
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || updating) return;
                value = clamp(round(min + progress * step));
                syncField();
                notifyListener();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        syncField();
        syncSlider();
    }

    public float getValue() {
        return value;
    }

    /** Show {@code v} in the field (as given) and the slider (clamped); does not fire the listener. */
    public void setValue(float v) {
        value = clamp(v);
        updating = true;
        field.setText(format(v));
        updating = false;
        syncSlider();
    }

    // --- internals ---

    /** A square −/+ button: tap steps once; holding repeats after a short delay until release. */
    private Button stepButton(Context ctx, String glyph, String description, int dir, float dp) {
        final Button b = new Button(ctx);
        b.setText(glyph);
        b.setTextSize(20);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight((int) (48 * dp));
        b.setContentDescription(description);
        final boolean[] repeated = {false};
        final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!b.isPressed()) return; // finger slid off the button
                repeated[0] = true;
                stepBy(dir);
                handler.postDelayed(this, REPEAT_EVERY_MS);
            }
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    repeated[0] = false;
                    handler.postDelayed(repeat, REPEAT_START_MS);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(repeat);
                    break;
                default:
                    break;
            }
            return false; // let the button handle pressed state and the click
        });
        // A plain tap (or an accessibility click) steps once; a release after repeating doesn't.
        b.setOnClickListener(v -> {
            if (repeated[0]) { repeated[0] = false; return; }
            stepBy(dir);
        });
        return b;
    }

    /** Move to the next grid value (min + k·step) in direction {@code dir}. */
    private void stepBy(int dir) {
        float idx = (value - min) / step;
        double k = dir > 0 ? Math.floor(idx + 1e-3) + 1 : Math.ceil(idx - 1e-3) - 1;
        float next = clamp(round((float) (min + k * step)));
        if (next == value) return;
        value = next;
        syncField();
        syncSlider();
        notifyListener();
    }

    private void syncField() {
        updating = true;
        field.setText(format(value));
        if (field.hasFocus()) field.setSelection(field.getText().length());
        updating = false;
    }

    private void syncSlider() {
        updating = true;
        slider.setProgress(Math.round((value - min) / step));
        updating = false;
    }

    private void notifyListener() {
        if (listener != null) listener.onValueChanged(value);
    }

    private float clamp(float v) {
        return Math.max(min, Math.min(max, v));
    }

    /** Trim float noise from step arithmetic (steps are at most 4 decimals). */
    private static float round(float v) {
        return Math.round(v * 10000f) / 10000f;
    }

    private String format(float v) {
        return integer ? String.valueOf(Math.round(v)) : String.valueOf(v);
    }
}
