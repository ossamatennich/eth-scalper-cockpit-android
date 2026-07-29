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
        for(Map.Entry<String,?> entry:preferences.getAll().entrySet())
            if(entry.getValue() instanceof String)values.put(entry.getKey(),(String)entry.getValue());
        return values;
    }

    @Override public boolean write(Map<String, String> values) {
        if(values==null)return false;
        SharedPreferences.Editor editor=preferences.edit().clear();
        for(Map.Entry<String,String> entry:values.entrySet())editor.putString(entry.getKey(),entry.getValue());
        return editor.commit();
    }
}
