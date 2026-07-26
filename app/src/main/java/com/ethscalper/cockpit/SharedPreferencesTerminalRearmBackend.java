package com.ethscalper.cockpit;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** SharedPreferences commit is atomic for the single terminal timestamp record. */
public final class SharedPreferencesTerminalRearmBackend implements TerminalRearmPersistence.Backend {
    private static final String FILE = "terminal_rearm_state_v2330";
    private final SharedPreferences preferences;

    public SharedPreferencesTerminalRearmBackend(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override public Map<String, String> read() {
        Map<String, String> values = new HashMap<>();
        String value = preferences.getString(TerminalRearmPersistence.KEY_LAST_TERMINAL_AT, "");
        if (value != null && !value.isEmpty()) {
            values.put(TerminalRearmPersistence.KEY_LAST_TERMINAL_AT, value);
        }
        return values;
    }

    @Override public boolean write(Map<String, String> values) {
        String value = values == null ? null
                : values.get(TerminalRearmPersistence.KEY_LAST_TERMINAL_AT);
        return value != null && preferences.edit()
                .putString(TerminalRearmPersistence.KEY_LAST_TERMINAL_AT, value).commit();
    }
}
