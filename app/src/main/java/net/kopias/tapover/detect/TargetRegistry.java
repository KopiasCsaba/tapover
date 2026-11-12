package net.kopias.tapover.detect;

import net.kopias.tapover.preset.Preset;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of active {@link Preset}s, read by the accessibility service on each
 * foreground change. Populated from persisted presets (see PresetStore) when the service
 * connects; replaced wholesale via {@link #replaceAll}.
 */
public final class TargetRegistry {

    private static final List<Preset> PRESETS = new CopyOnWriteArrayList<>();

    private TargetRegistry() {
    }

    /** First preset matching the foreground package/class, or {@code null}. */
    public static Preset findMatch(String pkg, String cls) {
        for (Preset p : PRESETS) {
            if (p.matches(pkg, cls)) {
                return p;
            }
        }
        return null;
    }

    public static void replaceAll(List<Preset> presets) {
        PRESETS.clear();
        PRESETS.addAll(presets);
    }

    public static int size() {
        return PRESETS.size();
    }
}
