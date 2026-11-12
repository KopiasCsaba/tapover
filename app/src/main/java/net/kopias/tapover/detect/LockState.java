package net.kopias.tapover.detect;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Lock state for the overlay's lock-screen rules. {@link #isLocked} is the keyguard state, plus a
 * sticky "will be locked" flag set when the screen turns off on a secure device: some devices lock
 * only after a delay, and the overlay must not show its content in that window. The flag clears on
 * unlock ({@code USER_PRESENT}) or when the screen comes back on with the keyguard not locked
 * (back within the lock delay — the keyguard never showed, so no {@code USER_PRESENT} follows).
 */
final class LockState {

    /** Called on the main thread after any lock-relevant broadcast; re-read {@link #isLocked}. */
    interface Listener {
        /** @param screenOff the screen just turned off (vs. turned on / user unlocked) */
        void onLockStateChanged(boolean screenOff);
    }

    private final Context context;
    private final KeyguardManager keyguard;
    private final Listener listener;
    private boolean registered;
    /** Screen went off on a secure device and it hasn't been unlocked/seen unlocked since. */
    private boolean assumeLocked;

    LockState(Context context, Listener listener) {
        this.context = context;
        this.keyguard = context.getSystemService(KeyguardManager.class);
        this.listener = listener;
    }

    /** True while locked, or about to be (screen off on a secure device). */
    boolean isLocked() {
        return assumeLocked || (keyguard != null && keyguard.isKeyguardLocked());
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            boolean screenOff = Intent.ACTION_SCREEN_OFF.equals(a);
            if (screenOff) {
                assumeLocked = keyguard != null && keyguard.isDeviceSecure();
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                assumeLocked = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                if (keyguard == null || !keyguard.isKeyguardLocked()) {
                    assumeLocked = false;
                }
            }
            listener.onLockStateChanged(screenOff);
        }
    };

    void register() {
        if (registered) {
            return;
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(receiver, f);
        registered = true;
    }

    void unregister() {
        if (registered) {
            context.unregisterReceiver(receiver);
            registered = false;
        }
    }
}
