package com.ethscalper.cockpit;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dedicated atomic SharedPreferences file for the one active final plan. */
public final class SharedPreferencesActivePlanBackend implements ActivePlanPersistence.Backend {
    private static final String PREFERENCES = "active_final_plan_v23281";
    private final SharedPreferences preferences;

    public SharedPreferencesActivePlanBackend(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override public Map<String, String> readAll() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            out.put(entry.getKey(), entry.getValue() instanceof String
                    ? (String) entry.getValue() : "__INVALID_TYPE__");
        }
        return out;
    }

    @Override public boolean replaceAll(Map<String, String> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
        }
        // SharedPreferences uses an AtomicFile internally; commit makes success synchronous.
        return editor.commit();
    }
}
