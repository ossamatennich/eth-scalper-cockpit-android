package com.ethscalper.cockpit;

import android.content.Context;
import android.content.SharedPreferences;

/** Dedicated persistent local rollback switch. */
public final class ScalpActionModeStore {
    public static final String PREFERENCES="nmc_scalp_action_preferences";
    public static final String KEY="scalp_action_mode";
    private final SharedPreferences preferences;
    public ScalpActionModeStore(Context context){preferences=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);}
    public String get(){String v=preferences.getString(KEY,ScalpActionEngine.ACTION_ON);return ScalpActionEngine.DIAGNOSTICS_ONLY.equals(v)?v:ScalpActionEngine.ACTION_ON;}
    public boolean set(String value){if(!ScalpActionEngine.ACTION_ON.equals(value)&&!ScalpActionEngine.DIAGNOSTICS_ONLY.equals(value))return false;return preferences.edit().putString(KEY,value).commit();}
}
