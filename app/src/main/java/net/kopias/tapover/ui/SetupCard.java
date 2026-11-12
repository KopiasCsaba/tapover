package net.kopias.tapover.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import net.kopias.tapover.R;

/**
 * The main screen's guided setup card for restricted (APK-file) installs, while overlay or
 * accessibility is missing: 1. try → Android refuses ("App was denied access"), 2. App info → ⋮ →
 * Allow restricted settings, 3. turn it on. Other installs aren't restricted and never see it.
 * Progress is kept in the main screen's preferences.
 */
public final class SetupCard {
    private static final String MAIN_PREFS = "tapover_main";
    /** Restricted-settings setup: the step (1..3) the user should do next. */
    private static final String KEY_SETUP_STEP = "setup_step";
    /** The user left for the overlay / accessibility setting from its Grant / Enable button. */
    private static final String KEY_TRIED_OVERLAY = "setup_tried_overlay";
    private static final String KEY_TRIED_ACCESSIBILITY = "setup_tried_accessibility";
    /** "No ⋮ menu? Redo step 1" was tapped: stay on step 1 until it's redone. */
    private static final String KEY_SETUP_REDO = "setup_redo";

    private final Activity activity;
    /** List header holding the permission rows, the setup card and the presets title. */
    private final View header;
    private final View card;
    private final View accessibilityButton;
    /** The draw-over-apps row's Grant button and the "finish the steps above" note under it. */
    private final View overlayButton;
    private final View overlayLockedNote;
    private final Runnable openAccessibilitySettings;
    private final Runnable openAppInfo;
    private final Runnable onChanged;

    /**
     * {@code accessibilityButton} is the accessibility row's Enable button (the card's steps
     * replace it); {@code onChanged} re-runs the permission/card update after a card action.
     */
    public SetupCard(Activity activity, View header, View accessibilityButton,
                     Runnable openAccessibilitySettings, Runnable openAppInfo, Runnable onChanged) {
        this.activity = activity;
        this.header = header;
        this.card = header.findViewById(R.id.setup_card);
        this.accessibilityButton = accessibilityButton;
        this.overlayButton = header.findViewById(R.id.btn_grant_overlay);
        this.overlayLockedNote = header.findViewById(R.id.overlay_locked_note);
        this.openAccessibilitySettings = openAccessibilitySettings;
        this.openAppInfo = openAppInfo;
        this.onChanged = onChanged;
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(MAIN_PREFS, Activity.MODE_PRIVATE);
    }

    /** Remembers that the user went to turn on the overlay setting (see {@link #update}). */
    public void markTriedOverlay() {
        markTried(KEY_TRIED_OVERLAY);
    }

    /** Remembers that the user went to turn on the accessibility setting (see {@link #update}). */
    public void markTriedAccessibility() {
        markTried(KEY_TRIED_ACCESSIBILITY);
    }

    /**
     * Android 13+ restricts apps installed by opening an APK file: the system installer records
     * package source LOCAL_FILE / DOWNLOADED_FILE, and at install the access_restricted_settings
     * app-op is set to "errored". Protected settings (accessibility everywhere; draw-over-apps too
     * on Android 15+) then show a "Restricted setting" dialog instead of turning on; showing that
     * dialog is what makes App info's ⋮ → "Allow restricted settings" appear. Apps installed by an
     * app store / installer app (session API, other source) aren't affected.
     * The app-op itself isn't readable without GET_APP_OPS_STATS, so the install source decides
     * whether the card shows, and the steps the user tapped decide which one is highlighted.
     * (Confirmed on GrapheneOS: STORE / UNSPECIFIED installs aren't restricted, file installs are.)
     */
    private boolean isInstalledFromApkFile() {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        try {
            int source = activity.getPackageManager()
                    .getInstallSourceInfo(activity.getPackageName()).getPackageSource();
            return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                    || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Show or hide the card and highlight the step to do next. A failed Grant / Enable attempt
     * also moves step 1 → 2: on Android 15+ the overlay switch shows the same refusal, which
     * already makes the ⋮ item appear.
     */
    public void update(boolean overlayGranted, boolean accessibilityEnabled) {
        SharedPreferences p = prefs();
        if (overlayGranted && accessibilityEnabled) {
            card.setVisibility(View.GONE);
            setOverlayLocked(false);
            // All set: forget the progress so a later revoke starts from a clean state.
            if (!p.getAll().isEmpty()) {
                p.edit().clear().apply();
            }
            return;
        }
        boolean show = isInstalledFromApkFile();
        card.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            setOverlayLocked(false);
            return;
        }
        boolean triedAndFailed = (p.getBoolean(KEY_TRIED_OVERLAY, false) && !overlayGranted)
                || (p.getBoolean(KEY_TRIED_ACCESSIBILITY, false) && !accessibilityEnabled);
        int current = p.getInt(KEY_SETUP_STEP, 1);
        if (current == 1 && triedAndFailed && !p.getBoolean(KEY_SETUP_REDO, false)) {
            current = 2;
            p.edit().putInt(KEY_SETUP_STEP, current).apply();
        }
        // Until restricted settings are allowed, the card's step buttons replace the row's Enable
        // button (it would only hit the block); at step 3 the rows below are where both get enabled.
        if (current < 3) {
            accessibilityButton.setVisibility(View.GONE);
        }
        // Android 15+ refuses draw-over-apps too until restricted settings are allowed (step 3).
        setOverlayLocked(!overlayGranted && current < 3 && Build.VERSION.SDK_INT >= 35);
        bindStep(R.id.setup_step1, 1, current, R.string.setup_step1_title, R.string.setup_step1_body,
                R.drawable.onboarding_denied, R.string.cd_onboarding_denied,
                R.string.setup_step1_button, openAccessibilitySettings);
        bindStep(R.id.setup_step2, 2, current, R.string.setup_step2_title, R.string.setup_step2_body,
                R.drawable.onboarding_allow, R.string.cd_onboarding_allow,
                R.string.setup_step2_button, openAppInfo);
        // ⋮ missing means Android hasn't refused TapOver yet: back to step 1, no navigation.
        Button alt = header.findViewById(R.id.setup_step2).findViewById(R.id.step_alt);
        alt.setText(R.string.setup_step2_alt);
        alt.setVisibility(current == 2 ? View.VISIBLE : View.GONE);
        alt.setOnClickListener(v -> {
            prefs().edit().putInt(KEY_SETUP_STEP, 1).putBoolean(KEY_SETUP_REDO, true).commit();
            onChanged.run();
        });
        // After allowing, enabling works: no button here, the Permissions rows below each have
        // their own. The card goes away once both permissions are on.
        bindStep(R.id.setup_step3, 3, current, R.string.setup_step3_title, R.string.setup_step3_body,
                0, 0, 0, null);
    }

    /**
     * While locked, the overlay row's Grant button is disabled (it would only show Android's
     * refusal) and a note points at the card above.
     */
    private void setOverlayLocked(boolean locked) {
        overlayButton.setEnabled(!locked);
        overlayLockedNote.setVisibility(locked ? View.VISIBLE : View.GONE);
    }

    /** Remembers the step to highlight next (synchronously: we leave the app right after). */
    private void setSetupStep(int step) {
        prefs().edit().putInt(KEY_SETUP_STEP, step).remove(KEY_SETUP_REDO).commit();
    }

    /** Remembers that the user went to turn on a protected setting (see update). */
    private void markTried(String key) {
        prefs().edit().putBoolean(key, true).remove(KEY_SETUP_REDO).commit();
    }

    /**
     * Done steps get a green ✓, the current one an accent badge, upcoming ones are dimmed. The
     * step's screenshot (what Android will show) and button appear only on the current step.
     */
    private void bindStep(int rowId, int n, int current, int titleRes, int bodyRes, int imageRes,
                          int imageDescRes, int buttonRes, Runnable action) {
        View row = header.findViewById(rowId);
        TextView badge = row.findViewById(R.id.step_badge);
        Button button = row.findViewById(R.id.step_button);
        ImageView image = row.findViewById(R.id.step_image);
        boolean done = n < current;
        boolean isCurrent = n == current;

        badge.setText(done ? activity.getString(R.string.setup_step_done) : String.valueOf(n));
        int badgeColor = done
                ? activity.getColor(R.color.permission_granted)
                : isCurrent ? accentColor() : Color.GRAY;
        badge.setBackgroundTintList(ColorStateList.valueOf(badgeColor));
        // Dark mode's accent/green are light: pick the text colour by contrast, not a fixed white.
        badge.setTextColor(Color.luminance(badgeColor) > 0.4f ? Color.BLACK : Color.WHITE);
        ((TextView) row.findViewById(R.id.step_title)).setText(titleRes);
        TextView body = row.findViewById(R.id.step_body);
        body.setText(bodyRes);
        // Finished steps collapse to their title + ✓ to keep the card short.
        body.setVisibility(done ? View.GONE : View.VISIBLE);
        row.setAlpha(isCurrent ? 1f : done ? 0.6f : 0.45f);
        row.setContentDescription(activity.getString(
                done ? R.string.cd_setup_step_done : R.string.cd_setup_step,
                n, activity.getString(titleRes)));
        if (imageRes != 0 && isCurrent) {
            image.setImageResource(imageRes);
            image.setContentDescription(activity.getString(imageDescRes));
            image.setClipToOutline(true); // rounded corners from bg_step_image
            image.setVisibility(View.VISIBLE);
        } else {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
        }
        if (action == null) { // text-only step
            button.setVisibility(View.GONE);
            row.setOnClickListener(null);
            row.setClickable(false);
            return;
        }
        button.setText(buttonRes);
        // Only the current step's button is shown; tapping any step's row (re)does it.
        button.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        View.OnClickListener go = v -> {
            setSetupStep(Math.min(n + 1, 3));
            action.run();
        };
        button.setOnClickListener(go);
        row.setOnClickListener(go);
    }

    private int accentColor() {
        TypedValue tv = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true);
        return tv.data;
    }
}
