package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded counters for live 4.8 observations only. */
public final class ScalpActionSummary {
    private long activationAt=System.currentTimeMillis(),observations,qualifications,publicOpenings;
    private long virtualOpenings,tp,sl,duplicates,internalErrors,entryExpirations,legacySuppressed;
    private double netR,netUsdt,fees;
    private final LinkedHashMap<String,Long> byRoute=new LinkedHashMap<>(),skips=new LinkedHashMap<>();
    public synchronized void observation(String route){observations++;inc(byRoute,route);}
    public synchronized void qualified(boolean published){qualifications++;if(published)publicOpenings++;else virtualOpenings++;}
    public synchronized void terminal(ScalpActionPlan.Terminal t){if(t==null)return;if(ScalpActionPlan.TP.equals(t.status))tp++;else sl++;netR+=t.resultR;netUsdt+=t.netResultUsdt;fees+=t.estimatedFeesUsdt;}
    public synchronized void duplicate(){duplicates++;}public synchronized void internalError(){internalErrors++;}
    public synchronized void expiration(){entryExpirations++;}public synchronized void legacySuppressed(){legacySuppressed++;}
    public synchronized void skip(String reason){inc(skips,reason);}
    public synchronized Map<String,Object> snapshot(String mode,boolean active){Map<String,Object> m=new LinkedHashMap<>();
        m.put("engineId",ScalpActionPolicy.ENGINE_ID);m.put("policyId",ScalpActionPolicy.POLICY_ID);m.put("schema",ScalpActionPolicy.SCHEMA_ID);m.put("activationAt",activationAt);
        m.put("observations",observations);m.put("qualifications",qualifications);m.put("publicOpenings",publicOpenings);m.put("virtualOpenings",virtualOpenings);
        m.put("tp",tp);m.put("sl",sl);m.put("unresolved",active?1:0);m.put("netR",netR);m.put("netUsdt",netUsdt);m.put("fees",fees);
        m.put("duplicates",duplicates);m.put("skipsByReason",new LinkedHashMap<>(skips));m.put("internalErrors",internalErrors);m.put("entryExpirations",entryExpirations);
        m.put("oldLegacyPlansSuppressed",legacySuppressed);m.put("mode",mode);m.put("observationsByRoute",new LinkedHashMap<>(byRoute));return m;}
    public synchronized void reset(){activationAt=System.currentTimeMillis();observations=qualifications=publicOpenings=virtualOpenings=tp=sl=duplicates=internalErrors=entryExpirations=legacySuppressed=0;netR=netUsdt=fees=0;byRoute.clear();skips.clear();}
    private static void inc(Map<String,Long> m,String key){String k=key==null?"":key;m.put(k,m.getOrDefault(k,0L)+1L);}
}
