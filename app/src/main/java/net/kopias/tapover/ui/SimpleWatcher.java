package net.kopias.tapover.ui;

import android.text.Editable;
import android.text.TextWatcher;

/**
 * TextWatcher that runs a Runnable after each edit. Only afterTextChanged is wired (it drives
 * the live preview refresh); beforeTextChanged/onTextChanged are required interface stubs.
 */
final class SimpleWatcher implements TextWatcher {
    private final Runnable onChange;

    SimpleWatcher(Runnable onChange) { this.onChange = onChange; }

    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
    @Override public void afterTextChanged(Editable s) { onChange.run(); }
}
