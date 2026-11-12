package net.kopias.tapover.preset;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import net.kopias.tapover.overlay.OverlayConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A persisted user configuration: which app to watch, what overlay to draw, and what the
 * tap does. Content may be a solid color, a system icon, or the latest image/video from a
 * folder; the tap either launches an app or opens that latest image.
 */
public class Preset {

    /** Tap action kind. */
    public enum Action { LAUNCH_APP, OPEN_LATEST_MEDIA_FILE }

    public String id = UUID.randomUUID().toString();
    public String name = "";

    // Target matching
    public String targetPackage = "";
    /** Activities the preset applies to; empty = any activity in the package. */
    public List<String> targetActivities = new ArrayList<>();

    // Overlay appearance. Position = top-left corner as % of screen width/height (resolved to px
    // at show time, see toOverlayConfig).
    public int sizePx = 200;
    public float xPercent = 5f;
    public float yPercent = 12f;
    public float cornerRadiusPx = 0f; // >= sizePx / 2 draws a circle

    // Also show over the lock screen (e.g. a camera opened while locked). Off = hidden while locked.
    public boolean showOnLockScreen = false;

    // Optional separate landscape size/position. Used only when sameInBothOrientations is false
    // and a landscape set exists (landSizePx > 0); otherwise the portrait values apply everywhere.
    public boolean sameInBothOrientations = true;
    public int landSizePx = 0;
    public float landXPercent;
    public float landYPercent;

    public OverlayConfig.Content content = OverlayConfig.Content.SOLID_COLOR;
    public int colorArgb = Color.RED;
    public int iconResId = 0; // framework drawable for SYSTEM_ICON; 0 = none

    // Tap action
    public Action action = Action.LAUNCH_APP;
    public String actionPackage = "";  // for LAUNCH_APP
    // For IMAGE content / OPEN_LATEST_MEDIA_FILE: the image folder as the folder picker's tree URI
    // (only its path is used, see LatestImageFinder), or "" when none is picked yet.
    public String actionFolder = "";

    /** True when the preset is unconstrained (matches any activity in the package). */
    public boolean anyActivity() {
        return targetActivities.isEmpty();
    }

    /**
     * Config-time guard against two overlays fighting over one target: two presets collide when
     * they share the target package AND either is "any activity" or they name a common activity. Deliberately strict equality (not the suffix-tolerant
     * {@link #matches} logic) — this compares stored configuration, not a live foreground class.
     */
    public boolean targetsSameAs(Preset other) {
        if (other == null || !targetPackage.equals(other.targetPackage)) {
            return false;
        }
        if (anyActivity() || other.anyActivity()) {
            return true;
        }
        for (String a : targetActivities) {
            if (other.targetActivities.contains(a)) {
                return true;
            }
        }
        return false;
    }

    /** True if this preset targets the given foreground package (and class, if constrained). */
    public boolean matches(String pkg, String cls) {
        if (pkg == null || !targetPackage.equals(pkg)) {
            return false;
        }
        if (anyActivity()) {
            return true;
        }
        if (cls == null) {
            return false;
        }
        // Tolerant: exact, or the foreground class ends with the configured name. The suffix
        // form lets a short name (e.g. "CameraActivity") match a fully-qualified runtime class
        // and absorbs package-prefix differences. The leading dot keeps it on a class boundary.
        for (String a : targetActivities) {
            if (cls.equals(a) || cls.endsWith("." + a)) {
                return true;
            }
        }
        return false;
    }

    /** True once a landscape size/position has been stored. */
    public boolean hasLandscapeSet() {
        return landSizePx > 0;
    }

    /**
     * Build the overlay appearance for this preset. Ticked "same position": the position is in the
     * device's natural (rotation 0) frame and is mapped to display coords for {@code rotation}
     * (a {@code Surface.ROTATION_*} value), so the dot stays physically fixed. Unticked with a
     * landscape set: display coords of the matching orientation. The whole dot stays on-screen.
     */
    public OverlayConfig toOverlayConfig(int screenW, int screenH, int rotation) {
        OverlayConfig c = new OverlayConfig();
        boolean split = !sameInBothOrientations && hasLandscapeSet();
        if (split) {
            boolean land = screenW > screenH;
            int size = land ? landSizePx : sizePx;
            float xPct = land ? landXPercent : xPercent;
            float yPct = land ? landYPercent : yPercent;
            c.sizePx = size;
            c.x = clampPx(xPct, screenW, size);
            c.y = clampPx(yPct, screenH, size);
        } else {
            boolean swap = (rotation & 1) == 1;
            int w = swap ? screenH : screenW;
            int h = swap ? screenW : screenH;
            int size = sizePx;
            int nx = clampPx(xPercent, w, size);
            int ny = clampPx(yPercent, h, size);
            c.sizePx = size;
            switch (rotation) {
                case 1: c.x = ny; c.y = w - nx - size; break;
                case 2: c.x = w - nx - size; c.y = h - ny - size; break;
                case 3: c.x = h - ny - size; c.y = nx; break;
                default: c.x = nx; c.y = ny; break;
            }
        }
        c.cornerRadiusPx = cornerRadiusPx;
        c.content = content;
        c.colorArgb = colorArgb;
        c.iconResId = iconResId;
        return c;
    }

    private static int clampPx(float pct, int extent, int size) {
        int max = Math.max(0, extent - size);
        return Math.max(0, Math.min(max, Math.round(pct / 100f * extent)));
    }

    /**
     * Inverse of the ticked mapping: a dot at display position (dx, dy) of the given size on a
     * screenW x screenH display in {@code rotation} -> {xPercent, yPercent} of the natural frame.
     */
    public static float[] displayToNaturalPercent(int dx, int dy, int size,
                                                  int screenW, int screenH, int rotation) {
        boolean swap = (rotation & 1) == 1;
        int w = swap ? screenH : screenW;
        int h = swap ? screenW : screenH;
        int nx, ny;
        switch (rotation) {
            case 1: nx = w - size - dy; ny = dx; break;
            case 2: nx = w - size - dx; ny = h - size - dy; break;
            case 3: nx = dy; ny = h - size - dx; break;
            default: nx = dx; ny = dy; break;
        }
        return new float[] {nx * 100f / w, ny * 100f / h};
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("targetPackage", targetPackage);
        o.put("targetActivities", new JSONArray(targetActivities));
        o.put("sizePx", sizePx);
        o.put("xPercent", (double) xPercent);
        o.put("yPercent", (double) yPercent);
        o.put("cornerRadiusPx", (double) cornerRadiusPx);
        o.put("sameInBothOrientations", sameInBothOrientations);
        o.put("landSizePx", landSizePx);
        o.put("landXPercent", (double) landXPercent);
        o.put("landYPercent", (double) landYPercent);
        o.put("content", content.name());
        o.put("colorArgb", colorArgb);
        o.put("iconResId", iconResId);
        o.put("action", action.name());
        o.put("actionPackage", actionPackage);
        o.put("actionFolder", actionFolder);
        o.put("showOnLockScreen", showOnLockScreen);
        return o;
    }

    /** Overlay size bounds (px), shared with the editor's size stepper. */
    public static final int MIN_SIZE_PX = 24;
    public static final int MAX_SIZE_PX = 600;

    /**
     * Parse a stored preset. Tolerant of missing fields; out-of-range sizes are clamped and
     * non-finite numbers replaced so a corrupt entry can neither draw a full-screen window
     * (size -1 = MATCH_PARENT) nor make every later save fail (NaN isn't valid JSON).
     */
    public static Preset fromJson(JSONObject o) {
        Preset p = new Preset();
        p.id = o.optString("id", p.id);
        p.name = str(o, "name");
        p.targetPackage = str(o, "targetPackage");
        JSONArray acts = o.optJSONArray("targetActivities");
        if (acts != null) {
            for (int i = 0; i < acts.length(); i++) {
                String a = acts.optString(i, "").trim();
                if (!a.isEmpty() && !p.targetActivities.contains(a)) {
                    p.targetActivities.add(a);
                }
            }
        } else { // older single-activity format
            String activity = str(o, "targetActivity");
            if (!activity.isEmpty()) {
                p.targetActivities.add(activity);
            }
        }
        p.sizePx = clampSize(o.optInt("sizePx", p.sizePx));
        p.xPercent = percent(o, "xPercent", p.xPercent);
        p.yPercent = percent(o, "yPercent", p.yPercent);
        p.cornerRadiusPx = Math.max(0f, finite(o.optDouble("cornerRadiusPx", 0d), 0f));
        p.sameInBothOrientations = o.optBoolean("sameInBothOrientations", true);
        int land = o.optInt("landSizePx", 0);
        p.landSizePx = land > 0 ? clampSize(land) : 0;
        p.landXPercent = percent(o, "landXPercent", 0f);
        p.landYPercent = percent(o, "landYPercent", 0f);
        p.content = parseContent(o.optString("content", p.content.name()));
        p.colorArgb = o.optInt("colorArgb", Color.RED);
        p.iconResId = o.optInt("iconResId", 0);
        p.action = parseAction(o.optString("action", p.action.name()));
        p.actionPackage = str(o, "actionPackage");
        p.actionFolder = str(o, "actionFolder");
        p.showOnLockScreen = o.optBoolean("showOnLockScreen", false);
        return p;
    }

    /** String field, or "" when missing or JSON null (optString would return "null"). */
    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    private static int clampSize(int px) {
        return Math.max(MIN_SIZE_PX, Math.min(MAX_SIZE_PX, px));
    }

    private static float percent(JSONObject o, String key, float def) {
        return Math.max(0f, Math.min(100f, finite(o.optDouble(key, def), def)));
    }

    private static float finite(double v, float def) {
        return Double.isNaN(v) || Double.isInfinite(v) ? def : (float) v;
    }

    private static OverlayConfig.Content parseContent(String s) {
        try {
            return OverlayConfig.Content.valueOf(s);
        } catch (IllegalArgumentException e) {
            return OverlayConfig.Content.SOLID_COLOR;
        }
    }

    private static Action parseAction(String s) {
        if ("OPEN_LATEST_IMAGE".equals(s)) {
            return Action.OPEN_LATEST_MEDIA_FILE; // name used by presets saved before the rename
        }
        try {
            return Action.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Action.LAUNCH_APP;
        }
    }
}
