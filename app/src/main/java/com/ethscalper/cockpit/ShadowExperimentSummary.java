package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Incremental, bounded experiment counters. No journal scan and no Android dependency. */
public final class ShadowExperimentSummary {
    private static final int MAX_MOVEMENTS=256;
    private final LinkedHashMap<String,Component> components=new LinkedHashMap<>();
    private final LinkedHashSet<String> movements=new LinkedHashSet<>();
    private long startedAt=System.currentTimeMillis(),publicPlans,publicTp,publicSl,opened,tp,sl;
    private long overlaps,higherOverlaps,suppressed,internalErrors;
    private double netUsdt,netR;

    public ShadowExperimentSummary(){for(String name:new String[]{
            ShadowCalibrationPolicy.ETH_P01_GUARD,ShadowCalibrationPolicy.SOL_P01_MONITOR,
            ShadowCalibrationPolicy.P02_GUARD,ShadowCalibrationPolicy.SOL_EARLY,
            ShadowCalibrationPolicy.PULLBACK,ShadowCalibrationPolicy.ETH_FLOW_HIGH_CONFIDENCE,
            ShadowCalibrationPolicy.ETH_RANGE_FADE_LONG,"SHADOW_FEE_AWARE_SIZING"})components.put(name,new Component());}

    public synchronized void observe(String type,Map<String,Object>d){
        if(type==null||d==null)return;String name=String.valueOf(d.getOrDefault("component","UNKNOWN"));
        Component c=components.computeIfAbsent(name,k->new Component());
        String decision=String.valueOf(d.getOrDefault("decision",""));
        String reason=String.valueOf(d.getOrDefault("shadowReasonCode",""));
        String movement=String.valueOf(d.getOrDefault("movementKey",""));
        if(!movement.isEmpty()){movements.add(movement);while(movements.size()>MAX_MOVEMENTS)
            movements.remove(movements.iterator().next());}
        if("SHADOW_AB_DECISION".equals(type))publicPlans++;
        if("SHADOW_PLAN_OPENED".equals(type)){opened++;c.openings++;c.qualifications++;}
        else if("SHADOW_PLAN_SKIPPED".equals(type)){if("WOULD_QUALIFY".equals(decision)){
            c.wouldQualify++;higherOverlaps++;}else c.skipped++;}
        else if("SHADOW_PUBLIC_OVERLAP".equals(type)){overlaps++;c.publicOverlaps++;}
        else if("SHADOW_TP_TOUCHED".equals(type)){tp++;c.tp++;addResult(c,d);}
        else if("SHADOW_SL_TOUCHED".equals(type)){sl++;c.sl++;addResult(c,d);}
        else if("SHADOW_INTERNAL_ERROR".equals(type))internalErrors++;
        Object s=d.get("shadowDuplicateEventsSuppressed");if(s instanceof Number)
            suppressed=Math.max(suppressed,((Number)s).longValue());
        if("OPEN".equals(decision)||reason.endsWith("_KEEP"))c.qualifications++;
    }

    private void addResult(Component c,Map<String,Object>d){double n=num(d.get("netResultUsdt"));
        double r=num(d.get("resultR"));if(Double.isFinite(n)){netUsdt+=n;c.netUsdt+=n;}
        if(Double.isFinite(r)){netR+=r;c.netR+=r;}}
    private static double num(Object v){return v instanceof Number?((Number)v).doubleValue():Double.NaN;}
    public synchronized void publicTerminal(String status){if("TP_TOUCHED".equals(status))publicTp++;
        else if("SL_TOUCHED".equals(status))publicSl++;}
    public synchronized void reset(long now){startedAt=now;publicPlans=publicTp=publicSl=opened=tp=sl=0;
        overlaps=higherOverlaps=suppressed=internalErrors=0;netUsdt=netR=0;movements.clear();
        for(Component c:components.values())c.reset();}

    public synchronized Map<String,Object> snapshot(long now){long duration=Math.max(0,now-startedAt);
        double hours=duration/3_600_000d;LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("shadowPolicyVersion",ShadowCalibrationPolicy.VERSION);out.put("shadowSchemaVersion",ShadowCalibrationPolicy.SCHEMA_VERSION);
        out.put("diagnosticSessionStartedAt",startedAt);out.put("observedAt",now);out.put("durationMs",duration);
        out.put("publicPlans",publicPlans);out.put("publicTp",publicTp);out.put("publicSl",publicSl);
        out.put("publicSignalsPerHour",hours>0?publicPlans/hours:0d);out.put("uniqueShadowOpportunities",movements.size());
        out.put("uniqueShadowOpened",opened);out.put("shadowTp",tp);out.put("shadowSl",sl);
        out.put("shadowUnresolved",Math.max(0,opened-tp-sl));out.put("shadowNetUsdt",netUsdt);out.put("shadowNetR",netR);
        out.put("shadowSignalsPerHour",hours>0?opened/hours:0d);long combined=Math.max(0,publicPlans+movements.size()-overlaps);
        out.put("uniqueCombinedPublicAndShadowOpportunities",combined);out.put("combinedSignalsPerHour",hours>0?combined/hours:0d);
        out.put("publicShadowOverlaps",overlaps);out.put("higherPriorityLaneOverlaps",higherOverlaps);
        out.put("shadowDuplicateEventsSuppressed",suppressed);out.put("shadowInternalErrors",internalErrors);
        LinkedHashMap<String,Object> by=new LinkedHashMap<>();for(Map.Entry<String,Component>e:components.entrySet())by.put(e.getKey(),e.getValue().map());
        out.put("components",by);return out;}

    private static final class Component{long qualifications,openings,skipped,wouldQualify,tp,sl,publicOverlaps;double netUsdt,netR;
        void reset(){qualifications=openings=skipped=wouldQualify=tp=sl=publicOverlaps=0;netUsdt=netR=0;}
        Map<String,Object> map(){LinkedHashMap<String,Object>m=new LinkedHashMap<>();m.put("qualifications",qualifications);
            m.put("openings",openings);m.put("skipped",skipped);m.put("wouldQualify",wouldQualify);m.put("tp",tp);m.put("sl",sl);
            m.put("unresolved",Math.max(0,openings-tp-sl));m.put("netUsdt",netUsdt);m.put("netR",netR);
            m.put("publicOverlaps",publicOverlaps);m.put("medianQualificationToOpenMs",null);return m;}}
}
