package net.kopias.tapover.detect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Most recent foreground activity class per package, for the last few packages only (LRU), offered
 * by the editor's activity picker ("Detected now: …"). It is the runtime class matching sees; a
 * manifest {@code <activity-alias>} name would never match. Bounded so it never accumulates the
 * user's app history; the service clears it when it unbinds. Thread-safe.
 */
final class RecentActivities {

    private final Map<String, String> byPkg;

    RecentActivities(int maxPackages) {
        byPkg = Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxPackages;
            }
        });
    }

    void put(String pkg, String cls) {
        byPkg.put(pkg, cls);
    }

    /** Last class seen for {@code pkg}, or null. */
    String get(String pkg) {
        return pkg == null ? null : byPkg.get(pkg);
    }

    void clear() {
        byPkg.clear();
    }
}
