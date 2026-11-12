package net.kopias.tapover.detect;

import android.util.Log;

import net.kopias.tapover.BuildConfig;

/**
 * Logging for lines that reveal what the user does (which apps they open, which media they view):
 * emitted in debug builds only, so a release build never leaks app usage to logcat.
 */
public final class DebugLog {

    private static final String TAG = "TapOver";

    private DebugLog() {
    }

    public static void i(String msg) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, msg);
        }
    }

    public static void w(String msg) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, msg);
        }
    }

    public static void w(String msg, Throwable t) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, msg, t);
        }
    }
}
