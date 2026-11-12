package net.kopias.tapover.preset;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads/saves the list of {@link Preset}s as a JSON string in SharedPreferences.
 * No third-party libraries — uses org.json. Tolerant of missing/malformed data.
 */
public class PresetStore {

    private static final String TAG = "TapOver";
    private static final String PREFS = "tapover_presets";
    private static final String KEY = "presets";
    private static final int VERSION = 1;

    private final SharedPreferences prefs;

    public PresetStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Load all presets; returns an empty list on missing/malformed data (never throws). */
    public List<Preset> load() {
        List<Preset> result = new ArrayList<>();
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("presets");
            if (arr == null) {
                return result;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    result.add(Preset.fromJson(o));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Malformed presets JSON; ignoring", e);
            return new ArrayList<>();
        }
        return result;
    }

    /** Persist the given presets, replacing any existing set. */
    public void save(List<Preset> presets) {
        try {
            JSONArray arr = new JSONArray();
            for (Preset p : presets) {
                arr.put(p.toJson());
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("presets", arr);
            prefs.edit().putString(KEY, root.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize presets", e);
        }
    }
}
