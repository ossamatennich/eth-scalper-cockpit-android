package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded counters for live 4.8 observations only. */
public final class ScalpActionSummary {
    private long activationAt=System.currentTimeMillis(),observations,qualifications,publicOpenings;
    private long virtualOpenings,tp,sl,duplicates,internalErrors,entryExpirations,legacySuppressed;
    private long freshObservedMs,lastFreshObservedAt;private boolean previousCycleFresh;
    private double netR,netUsdt,estimatedFeesUsdt,positiveR,negativeRAbs,equityR,peakEquityR,maximumDrawdownR;
    private final LinkedHashMap<String,Long> byRoute=new LinkedHashMap<>(),skips=new LinkedHashMap<>();
    public synchronized void observation(String route){observations++;inc(byRoute,route);}
    public synchronized void qualified(boolean published){qualifications++;if(published)publicOpenings++;else virtualOpenings++;}
    public synchronized void terminal(ScalpActionPlan.Terminal t){if(t==null)return;if(ScalpActionPlan.TP.equals(t.status))tp++;else sl++;
        netR+=t.resultR;netUsdt+=t.netResultUsdt;estimatedFeesUsdt+=t.estimatedFeesUsdt;
        if(t.resultR>0)positiveR+=t.resultR;else if(t.resultR<0)negativeRAbs+=Math.abs(t.resultR);
        equityR+=t.resultR;peakEquityR=Math.max(peakEquityR,equityR);
        maximumDrawdownR=Math.max(maximumDrawdownR,peakEquityR-equityR);}
    public synchronized void observeFresh(long now,boolean allRequiredFeedsFresh){
        if(now>0&&allRequiredFeedsFresh&&previousCycleFresh&&lastFreshObservedAt>0){
            long delta=now-lastFreshObservedAt;if(delta>=0)freshObservedMs+=Math.min(5_000L,delta);}
        previousCycleFresh=allRequiredFeedsFresh;lastFreshObservedAt=now;
    }
    public synchronized void duplicate(){duplicates++;}public synchronized void internalError(){internalErrors++;}
    public synchronized void expiration(){entryExpirations++;}public synchronized void legacySuppressed(){legacySuppressed++;}
    public synchronized void skip(String reason){inc(skips,reason);}
    public synchronized Map<String,Object> snapshot(String mode,boolean active){Map<String,Object> m=new LinkedHashMap<>();
        m.put("engineId",ScalpActionPolicy.ENGINE_ID);m.put("policyId",ScalpActionPolicy.POLICY_ID);m.put("schema",ScalpActionPolicy.SCHEMA_ID);m.put("activationAt",activationAt);
        m.put("observations",observations);m.put("qualifications",qualifications);m.put("publicOpenings",publicOpenings);m.put("virtualOpenings",virtualOpenings);
        long resolved=tp+sl,openings=publicOpenings+virtualOpenings;
        m.put("tp",tp);m.put("sl",sl);m.put("unresolved",active?1:0);m.put("netR",netR);m.put("netUsdt",netUsdt);
        m.put("freshObservedMs",freshObservedMs);m.put("positiveR",positiveR);m.put("negativeRAbs",negativeRAbs);
        m.put("estimatedFeesUsdt",estimatedFeesUsdt);m.put("fees",estimatedFeesUsdt);
        m.put("profitFactorR",negativeRAbs>0?positiveR/negativeRAbs:null);
        m.put("expectancyR",resolved>0?netR/resolved:null);m.put("maximumDrawdownR",maximumDrawdownR);
        m.put("opportunitiesPerFreshHour",freshObservedMs>0?openings*3_600_000d/freshObservedMs:null);
        m.put("duplicates",duplicates);m.put("skipsByReason",new LinkedHashMap<>(skips));m.put("internalErrors",internalErrors);m.put("entryExpirations",entryExpirations);
        m.put("oldLegacyPlansSuppressed",legacySuppressed);m.put("mode",mode);m.put("observationsByRoute",new LinkedHashMap<>(byRoute));return m;}
    public synchronized void reset(){activationAt=System.currentTimeMillis();observations=qualifications=publicOpenings=virtualOpenings=tp=sl=duplicates=internalErrors=entryExpirations=legacySuppressed=0;
        freshObservedMs=lastFreshObservedAt=0;previousCycleFresh=false;netR=netUsdt=estimatedFeesUsdt=positiveR=negativeRAbs=equityR=peakEquityR=maximumDrawdownR=0;byRoute.clear();skips.clear();}
    private static void inc(Map<String,Long> m,String key){String k=key==null?"":key;m.put(k,m.getOrDefault(k,0L)+1L);}
}
