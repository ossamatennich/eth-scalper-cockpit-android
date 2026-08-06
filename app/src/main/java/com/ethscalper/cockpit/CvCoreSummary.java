package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded live-only CV Core counters; no retrospective data is imported. */
public final class CvCoreSummary {
    private long activationAt=System.currentTimeMillis(),rawObservations,confirmationObservations,qualifications,openings,tp,sl,duplicates,alerts,expirations,errors,legacySuppressed;
    private long freshObservedMs,lastFreshAt;private boolean previousFresh;private double netR,netUsdt,fees,positiveR,negativeRAbs,equity,peak,maxDrawdown;
    private final LinkedHashMap<String,Long> byRoute=new LinkedHashMap<>(),rejects=new LinkedHashMap<>();
    public synchronized void observation(String source,String route){if(CvCorePolicy.RAW.equals(source))rawObservations++;else confirmationObservations++;inc(byRoute,route);}
    public synchronized void qualified(){qualifications++;}public synchronized void opened(){openings++;}public synchronized void duplicate(){duplicates++;}
    public synchronized void alert(){alerts++;}public synchronized void expiration(){expirations++;}public synchronized void error(){errors++;}public synchronized void legacySuppressed(){legacySuppressed++;}
    public synchronized void reject(String reason){inc(rejects,reason);}
    public synchronized void terminal(CvCorePlan.Terminal t){if(t==null)return;if(CvCorePlan.TP.equals(t.status))tp++;else sl++;netR+=t.resultR;netUsdt+=t.netResultUsdt;fees+=t.estimatedFeesUsdt;
        if(t.resultR>0)positiveR+=t.resultR;else negativeRAbs+=Math.abs(t.resultR);equity+=t.resultR;peak=Math.max(peak,equity);maxDrawdown=Math.max(maxDrawdown,peak-equity);}
    public synchronized void observeFresh(long now,boolean fresh){if(now>0&&fresh&&previousFresh&&lastFreshAt>0){long d=now-lastFreshAt;if(d>=0)freshObservedMs+=Math.min(5_000L,d);}previousFresh=fresh;lastFreshAt=now;}
    public synchronized Map<String,Object> snapshot(boolean active){Map<String,Object>m=new LinkedHashMap<>();m.put("engineId",CvCorePolicy.ENGINE_ID);m.put("policyId",CvCorePolicy.POLICY_ID);m.put("schema",CvCorePolicy.SCHEMA_ID);m.put("activationAt",activationAt);
        m.put("rawObservations",rawObservations);m.put("confirmationObservations",confirmationObservations);m.put("observationsByRoute",new LinkedHashMap<>(byRoute));m.put("qualifications",qualifications);m.put("openings",openings);
        m.put("tp",tp);m.put("sl",sl);m.put("unresolved",active?1:0);m.put("netR",netR);m.put("netUsdt",netUsdt);m.put("estimatedFeesUsdt",fees);m.put("positiveR",positiveR);m.put("negativeRAbs",negativeRAbs);
        long resolved=tp+sl;m.put("profitFactorR",negativeRAbs>0?positiveR/negativeRAbs:null);m.put("expectancyR",resolved>0?netR/resolved:null);m.put("maximumDrawdownR",maxDrawdown);
        m.put("freshObservedMs",freshObservedMs);m.put("opportunitiesPerFreshHour",freshObservedMs>0?openings*3_600_000d/freshObservedMs:null);m.put("duplicates",duplicates);m.put("rejectionsByReason",new LinkedHashMap<>(rejects));
        m.put("alerts",alerts);m.put("entryExpirations",expirations);m.put("internalErrors",errors);m.put("legacySuppressed",legacySuppressed);return m;}
    public synchronized void reset(){activationAt=System.currentTimeMillis();rawObservations=confirmationObservations=qualifications=openings=tp=sl=duplicates=alerts=expirations=errors=legacySuppressed=0;
        freshObservedMs=lastFreshAt=0;previousFresh=false;netR=netUsdt=fees=positiveR=negativeRAbs=equity=peak=maxDrawdown=0;byRoute.clear();rejects.clear();}
    private static void inc(Map<String,Long>m,String key){String k=key==null?"":key;m.put(k,m.getOrDefault(k,0L)+1);}
}
