package net.kopias.tapover.preset;

import android.graphics.Color;

import net.kopias.tapover.overlay.OverlayConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The registry of predefined presets the user can load from the editor's "Load template" button.
 * A loaded template just fills the editor form with sensible starting values — it becomes a normal,
 * fully editable {@link Preset} on save, with no separate "template" storage.
 *
 * <p><b>Adding a template is one line:</b> call {@link #register} in the static initializer with a
 * label and a factory ({@code () -> someBuilder(...)}). The label and the preset it builds live
 * together, so there is no second index-aligned structure to keep in sync. The editor enumerates
 * {@link #all()} to offer them.
 *
 * <p>Templates never preset an image folder (folder layouts differ per device), so a template that
 * draws the latest photo leaves it unset and the editor's save check makes the user pick one.
 */
public final class PresetTemplates {

    private PresetTemplates() {
    }

    /** Builds a fresh, fully-populated preset for a template. */
    private interface Factory {
        Preset build();
    }

    /** One predefined preset: its display label and the factory that produces a fresh instance. */
    public static final class Template {
        public final String label;
        private final Factory factory;

        Template(String label, Factory factory) {
            this.label = label;
            this.factory = factory;
        }

        /** A fresh preset (new id) for this template. */
        public Preset toPreset() {
            return factory.build();
        }
    }

    private static final List<Template> TEMPLATES = new ArrayList<>();

    static {
        // Add a template here, one line each: register(label, () -> buildPreset(...)).
        register("Google Camera → latest photo",
                () -> cameraThumbnail("Google Camera → latest photo", "com.google.android.GoogleCamera"));
    }

    private static void register(String label, Factory factory) {
        TEMPLATES.add(new Template(label, factory));
    }

    /** All templates, in registration order. */
    public static List<Template> all() {
        return Collections.unmodifiableList(TEMPLATES);
    }

    /**
     * A rounded latest-photo thumbnail over a camera app that opens the latest image on tap.
     * {@code pkg} is the target package; {@code name} is the preset's display name. Shared defaults
     * across every stock-camera template (all tunable after the template is loaded); the image
     * folder is left unset for the user to pick.
     */
    private static Preset cameraThumbnail(String name, String pkg) {
        Preset p = new Preset();
        p.content = OverlayConfig.Content.IMAGE;
        p.colorArgb = Color.RED;
        p.action = Preset.Action.OPEN_LATEST_MEDIA_FILE;
        p.actionFolder = ""; // picked in the editor
        p.targetActivities.add("com.google.android.apps.camera.activity.main.CameraActivity");
        // The camera opened from the lock screen. Showing over it needs showOnLockScreen, which
        // stays off here (privacy): the user opts in with the editor's lock-screen toggle.
        p.targetActivities.add("com.google.android.apps.camera.activity.main.SecureCameraActivity");
        p.targetActivities.add("com.google.android.apps.camera.activity.SecureCameraActivity");
        p.showOnLockScreen = false;
        p.name = name;
        p.targetPackage = pkg;
        p.sizePx = 147;
        p.cornerRadiusPx = 30f; // rounded square (>= size / 2 would be a circle)
        p.xPercent = 13.25f;
        p.yPercent = 71.75f;
        return p;
    }
}
