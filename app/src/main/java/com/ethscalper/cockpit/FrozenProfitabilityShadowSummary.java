package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded future-holdout counters. Historical research values are never imported here. */
public final class FrozenProfitabilityShadowSummary {
    private long protocolStartedAt,observedAt,lastFreshAt,freshObservedMs;
    private long qualified,nearMiss,opportunities,openedGroups,openedBranches,tp,sl,duplicates;
    private long publicOverlaps,legacyOverlaps,internalErrors,evicted;
    private int maxConsecutiveLosses,currentLosses;private double netR,netUsdt,peakR,maxDrawdownR;
    private final Set<String> qualifiedKeys=new LinkedHashSet<>(),nearMissKeys=new LinkedHashSet<>(),opportunityKeys=new LinkedHashSet<>();
    private final Map<String,Component> components=new LinkedHashMap<>();
    private final Map<String,Long> skipped=new LinkedHashMap<>(),buckets=new LinkedHashMap<>();

    public synchronized void observeTime(long now,boolean marketFresh,boolean btcFresh){
        if(protocolStartedAt==0)protocolStartedAt=now;observedAt=Math.max(observedAt,now);
        if(marketFresh&&btcFresh){if(lastFreshAt>0&&now>=lastFreshAt)freshObservedMs+=Math.min(5_000L,now-lastFreshAt);lastFreshAt=now;}
        else lastFreshAt=0;
    }
    public synchronized void qualified(String component,String movement){
        if(qualifiedKeys.add(movement)){trim(qualifiedKeys);qualified++;component(component).qualified++;}
    }
    public synchronized void nearMiss(String component,String movement){
        if(nearMissKeys.add(movement)){trim(nearMissKeys);nearMiss++;component(component).nearMiss++;}
    }
    public synchronized void opportunity(String component,String movement){
        if(opportunityKeys.add(movement)){trim(opportunityKeys);opportunities++;component(component).opportunities++;}
    }
    public synchronized void groupOpened(String component,String movement,boolean legacy){
        openedGroups++;component(component).openedGroups++;if(legacy){legacyOverlaps++;component(component).legacyOverlaps++;}
    }
    public synchronized void branchOpened(String component){openedBranches++;component(component).openedBranches++;}
    public synchronized void terminal(String component,FrozenProfitabilityShadowPlan.Terminal t){
        Component c=component(component);netR+=t.resultR;netUsdt+=t.netResultUsdt;c.netR+=t.resultR;c.netUsdt+=t.netResultUsdt;
        if(t.resultR>=0)c.positiveR+=t.resultR;else c.negativeR+=-t.resultR;
        if("TP_TOUCHED".equals(t.terminalStatus)){tp++;c.tp++;currentLosses=0;}else{sl++;c.sl++;currentLosses++;maxConsecutiveLosses=Math.max(maxConsecutiveLosses,currentLosses);}
        peakR=Math.max(peakR,netR);maxDrawdownR=Math.max(maxDrawdownR,peakR-netR);
    }
    public synchronized void duplicate(String component){duplicates++;component(component).duplicates++;}
    public synchronized void skipped(String component,String reason){skipped.put(reason,skipped.getOrDefault(reason,0L)+1);component(component).skipped++;}
    public synchronized void publicOverlap(String component){publicOverlaps++;component(component).publicOverlaps++;}
    public synchronized void internalError(String component){internalErrors++;component(component).internalErrors++;}
    public synchronized void evicted(long count){evicted=Math.max(evicted,count);}
    public synchronized void bucket(String bucket,String side){if(bucket==null||bucket.isEmpty())return;String key=bucket+"|"+side;buckets.put(key,buckets.getOrDefault(key,0L)+1);}

    public synchronized Map<String,Object> snapshot(long now,int activeGroups,int unresolved){
        LinkedHashMap<String,Object> out=metadata();long duration=protocolStartedAt==0?0:Math.max(0,now-protocolStartedAt);
        out.put("protocolStartedAt",protocolStartedAt);out.put("observedAt",now);out.put("durationMs",duration);
        out.put("freshObservedMs",freshObservedMs);out.put("uniqueQualifiedMovements",qualified);
        out.put("uniqueNearMissMovements",nearMiss);out.put("uniqueOpportunities",opportunities);
        out.put("openedGroups",openedGroups);out.put("openedBranches",openedBranches);out.put("activeGroups",activeGroups);
        out.put("resolvedGroups",Math.max(0,openedGroups-activeGroups));out.put("TP",tp);out.put("SL",sl);
        out.put("unresolved",unresolved);out.put("winRate",tp+sl==0?null:(double)tp/(tp+sl));
        out.put("netR",netR);out.put("netUsdt",netUsdt);out.put("profitFactorR",profitFactor());
        out.put("positiveR",positiveR());out.put("negativeR",negativeR());
        out.put("expectancyR",tp+sl==0?null:netR/(tp+sl));out.put("maximumConsecutiveLosses",maxConsecutiveLosses);
        out.put("maximumDrawdownR",maxDrawdownR);out.put("opportunitiesPerFreshHour",freshObservedMs==0?null:opportunities*3_600_000d/freshObservedMs);
        out.put("duplicatesGrouped",duplicates);out.put("skippedByReason",new LinkedHashMap<>(skipped));
        out.put("publicOverlaps",publicOverlaps);out.put("legacyShadowOverlaps",legacyOverlaps);
        out.put("internalErrors",internalErrors);out.put("evictedMovementRecords",evicted);
        out.put("sensitivityBuckets",new LinkedHashMap<>(buckets));
        LinkedHashMap<String,Object> cm=new LinkedHashMap<>();for(Map.Entry<String,Component> e:components.entrySet())cm.put(e.getKey(),e.getValue().map());out.put("components",cm);
        return out;
    }
    private Double profitFactor(){double gains=0,losses=0;for(Component c:components.values()){gains+=c.positiveR;losses+=c.negativeR;}return losses==0?null:gains/losses;}
    private double positiveR(){double value=0;for(Component c:components.values())value+=c.positiveR;return value;}
    private double negativeR(){double value=0;for(Component c:components.values())value+=c.negativeR;return value;}
    private Component component(String name){return components.computeIfAbsent(name==null?"":name,k->new Component());}
    public synchronized void reset(){protocolStartedAt=observedAt=lastFreshAt=freshObservedMs=0;qualified=nearMiss=opportunities=openedGroups=openedBranches=tp=sl=duplicates=0;
        publicOverlaps=legacyOverlaps=internalErrors=evicted=0;maxConsecutiveLosses=currentLosses=0;netR=netUsdt=peakR=maxDrawdownR=0;
        qualifiedKeys.clear();nearMissKeys.clear();opportunityKeys.clear();components.clear();skipped.clear();buckets.clear();}

    private static LinkedHashMap<String,Object> metadata(){LinkedHashMap<String,Object> m=new LinkedHashMap<>();
        m.put("shadowPolicyVersion",FrozenProfitabilityShadowPolicy.POLICY_VERSION);m.put("shadowSchemaVersion",FrozenProfitabilityShadowPolicy.SCHEMA_VERSION);
        m.put("protocolId",FrozenProfitabilityShadowPolicy.PROTOCOL_ID);m.put("protocolSchema",FrozenProfitabilityShadowPolicy.PROTOCOL_SCHEMA);
        m.put("historicalCorpusId",FrozenProfitabilityShadowPolicy.HISTORICAL_CORPUS_ID);m.put("historicalFrames",FrozenProfitabilityShadowPolicy.HISTORICAL_FRAMES);
        m.put("historicalMarketHours",FrozenProfitabilityShadowPolicy.HISTORICAL_MARKET_HOURS);m.put("futureHoldoutOnly",true);
        m.put("publicActivationAllowed",false);m.put("automaticPromotionAllowed",false);return m;}

    public static Map<String,Object> aggregate(List<Map<String,Object>> markets,long now){
        LinkedHashMap<String,Object> out=metadata();long started=0,fresh=0,qualified=0,near=0,opps=0,groups=0,branches=0,active=0,resolved=0,tp=0,sl=0,unresolved=0,dups=0,pub=0,legacy=0,errors=0,evicted=0;
        double netR=0,netUsdt=0,positiveR=0,negativeR=0,dd=0;long maxLosses=0;LinkedHashMap<String,Long> skipped=new LinkedHashMap<>(),buckets=new LinkedHashMap<>();
        LinkedHashMap<String,Map<String,Double>> componentTotals=new LinkedHashMap<>();
        for(Map<String,Object> m:markets){long ps=number(m.get("protocolStartedAt"));if(ps>0&&(started==0||ps<started))started=ps;
            fresh=Math.max(fresh,number(m.get("freshObservedMs")));qualified+=number(m.get("uniqueQualifiedMovements"));near+=number(m.get("uniqueNearMissMovements"));opps+=number(m.get("uniqueOpportunities"));groups+=number(m.get("openedGroups"));branches+=number(m.get("openedBranches"));active+=number(m.get("activeGroups"));resolved+=number(m.get("resolvedGroups"));tp+=number(m.get("TP"));sl+=number(m.get("SL"));unresolved+=number(m.get("unresolved"));dups+=number(m.get("duplicatesGrouped"));pub+=number(m.get("publicOverlaps"));legacy+=number(m.get("legacyShadowOverlaps"));errors+=number(m.get("internalErrors"));evicted+=number(m.get("evictedMovementRecords"));netR+=decimal(m.get("netR"));netUsdt+=decimal(m.get("netUsdt"));positiveR+=decimal(m.get("positiveR"));negativeR+=decimal(m.get("negativeR"));maxLosses=Math.max(maxLosses,number(m.get("maximumConsecutiveLosses")));dd=Math.max(dd,decimal(m.get("maximumDrawdownR")));mergeCounts(skipped,m.get("skippedByReason"));mergeCounts(buckets,m.get("sensitivityBuckets"));mergeComponents(componentTotals,m.get("components"));}
        out.put("protocolStartedAt",started);out.put("observedAt",now);out.put("durationMs",started==0?0:Math.max(0,now-started));out.put("freshObservedMs",fresh);
        out.put("uniqueQualifiedMovements",qualified);out.put("uniqueNearMissMovements",near);out.put("uniqueOpportunities",opps);out.put("openedGroups",groups);out.put("openedBranches",branches);out.put("activeGroups",active);out.put("resolvedGroups",resolved);out.put("TP",tp);out.put("SL",sl);out.put("unresolved",unresolved);out.put("winRate",tp+sl==0?null:(double)tp/(tp+sl));out.put("netR",netR);out.put("netUsdt",netUsdt);out.put("positiveR",positiveR);out.put("negativeR",negativeR);out.put("profitFactorR",negativeR==0?null:positiveR/negativeR);out.put("expectancyR",tp+sl==0?null:netR/(tp+sl));out.put("maximumConsecutiveLosses",maxLosses);out.put("maximumDrawdownR",dd);out.put("opportunitiesPerFreshHour",fresh==0?null:opps*3_600_000d/fresh);out.put("duplicatesGrouped",dups);out.put("skippedByReason",skipped);out.put("publicOverlaps",pub);out.put("legacyShadowOverlaps",legacy);out.put("internalErrors",errors);out.put("evictedMovementRecords",evicted);out.put("sensitivityBuckets",buckets);out.put("components",componentTotals);return out;
    }
    @SuppressWarnings("unchecked") private static void mergeCounts(Map<String,Long> out,Object value){if(!(value instanceof Map))return;for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.put(String.valueOf(e.getKey()),out.getOrDefault(String.valueOf(e.getKey()),0L)+number(e.getValue()));}
    @SuppressWarnings("unchecked") private static void mergeComponents(Map<String,Map<String,Double>> out,Object value){if(!(value instanceof Map))return;for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet()){if(!(e.getValue() instanceof Map))continue;Map<String,Double> target=out.computeIfAbsent(String.valueOf(e.getKey()),k->new LinkedHashMap<>());for(Map.Entry<?,?> metric:((Map<?,?>)e.getValue()).entrySet())if(metric.getValue() instanceof Number)target.put(String.valueOf(metric.getKey()),target.getOrDefault(String.valueOf(metric.getKey()),0d)+((Number)metric.getValue()).doubleValue());}}
    private static void trim(Set<String> values){while(values.size()>FrozenProfitabilityShadowPolicy.MOVEMENT_CAPACITY_PER_SYMBOL){java.util.Iterator<String> it=values.iterator();if(it.hasNext()){it.next();it.remove();}}}
    private static long number(Object v){return v instanceof Number?((Number)v).longValue():0;}
    private static double decimal(Object v){return v instanceof Number?((Number)v).doubleValue():0;}

    private static final class Component {
        long qualified,nearMiss,opportunities,openedGroups,openedBranches,tp,sl,duplicates,skipped,publicOverlaps,legacyOverlaps,internalErrors;
        double netR,netUsdt,positiveR,negativeR;
        Map<String,Object> map(){LinkedHashMap<String,Object> m=new LinkedHashMap<>();m.put("qualified",qualified);m.put("nearMiss",nearMiss);m.put("opportunities",opportunities);m.put("openedGroups",openedGroups);m.put("openedBranches",openedBranches);m.put("TP",tp);m.put("SL",sl);m.put("unresolved",Math.max(0,openedBranches-tp-sl));m.put("winRate",tp+sl==0?null:(double)tp/(tp+sl));m.put("netR",netR);m.put("netUsdt",netUsdt);m.put("profitFactorR",negativeR==0?null:positiveR/negativeR);m.put("expectancyR",tp+sl==0?null:netR/(tp+sl));m.put("duplicatesGrouped",duplicates);m.put("skipped",skipped);m.put("publicOverlaps",publicOverlaps);m.put("legacyShadowOverlaps",legacyOverlaps);m.put("internalErrors",internalErrors);return m;}
    }
}
