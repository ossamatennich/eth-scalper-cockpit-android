package com.ethscalper.cockpit;

/** Compatibility is restoration-only: it can identify and follow a persisted 4.8 plan, never create one. */
public final class LegacyV23448ActivePlanCompatibility {
    private static final String LEGACY_ENGINE="NMC_SCALP_ACTION_V1";
    private LegacyV23448ActivePlanCompatibility(){}
    public static boolean isRestoredPlan(ActivePlanState state){return state!=null&&state.formatVersion==3&&LEGACY_ENGINE.equals(state.engineId);}
    public static boolean isRestoredEngine(String engineId){return LEGACY_ENGINE.equals(engineId);}
    public static boolean isRestoredFamily(String family){return family!=null&&family.startsWith(LEGACY_ENGINE+"/");}
    public static void purgeInactivePreferences(android.content.Context context){if(context==null)return;
        context.getSharedPreferences("nmc_scalp_action_preferences",android.content.Context.MODE_PRIVATE).edit().clear().apply();}
}
