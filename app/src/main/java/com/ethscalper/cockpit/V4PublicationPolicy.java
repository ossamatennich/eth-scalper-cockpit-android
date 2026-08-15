package com.ethscalper.cockpit;

/** Migration guard: V4 is the sole source of new public actionable plans. */
public final class V4PublicationPolicy {
    private V4PublicationPolicy(){}
    public static boolean mayPublishNewPlan(String engineId){return V4Universe.ENGINE_ID.equals(engineId);}
}
