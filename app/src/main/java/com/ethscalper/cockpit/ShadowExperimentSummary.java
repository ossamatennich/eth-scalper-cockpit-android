package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Incremental schema-V7 counters with exact movement-set semantics and fail-open entry points. */
public class ShadowExperimentSummary {
    public static final int MAX_KEYS=256;
    @FunctionalInterface public interface OperationRunner{void run(String operation,Runnable action);}
    private final OperationRunner runner;
    private final LinkedHashMap<String,Component> components=new LinkedHashMap<>();
    private final BoundedKeys publicPlanSignatures=new BoundedKeys(),publicMovementKeys=new BoundedKeys();
    private final BoundedKeys qualifiedMovementKeys=new BoundedKeys(),opportunityMovementKeys=new BoundedKeys();
    private final BoundedKeys openedMovementKeys=new BoundedKeys(),overlapMovementKeys=new BoundedKeys();
    private final BoundedKeys higherOverlapKeys=new BoundedKeys();
    private long startedAt=System.currentTimeMillis(),publicTp,publicSl,shadowTp,shadowSl;
    private long duplicateSuppressed,internalErrors,evictedMovementRecords,terminalShadowRecords,activeShadowPlans;
    private double netUsdt,netR;private boolean dedupCapacityReached,publicPlanCarriedAtReset;
    private String pendingSolP01Decision="";

    public ShadowExperimentSummary(){this((operation,action)->action.run());}
    public ShadowExperimentSummary(OperationRunner runner){this.runner=runner==null?(o,a)->a.run():runner;
        for(String name:requiredComponents())components.put(name,new Component());}

    public void safeObserve(String type,Map<String,Object>d){safe("OBSERVE",()->observe(type,d));}
    public void safeQualified(String component,String movement,long qualificationAt){safe("QUALIFIED",
            ()->qualified(component,movement,qualificationAt));}
    public void safeOpportunity(String component,String movement){safe("OPPORTUNITY",()->opportunity(component,movement));}
    public void safeDuplicateSuppressed(String component,String signature){safe("DUPLICATE_SUPPRESSED",
            ()->duplicateSuppressed(component,signature));}
    public void safeTelemetryDeduplicated(String component){safe("TELEMETRY_DEDUPLICATED",
            ()->telemetryDeduplicated(component));}
    public void safeTelemetrySnapshot(String component,List<Map<String,Object>> records){safe("TELEMETRY_SNAPSHOT",
            ()->telemetrySnapshot(component,records));}
    public void safePublicTerminal(String status){safe("PUBLIC_TERMINAL",()->publicTerminal(status));}
    public void safeRegistryStats(Map<String,Object> stats){safe("REGISTRY_STATS",()->registryStats(stats));}
    public void safeReset(long now,boolean carried){safe("RESET",()->reset(now,carried));}
    public Map<String,Object> safeSnapshot(long now){SnapshotHolder out=new SnapshotHolder();
        safe("SNAPSHOT",()->out.value=snapshot(now));return out.value==null?fallback(now):out.value;}
    private void safe(String operation,Runnable action){try{runner.run(operation,action);}catch(RuntimeException ignored){
        synchronized(this){internalErrors++;}
    }}

    public synchronized void observe(String type,Map<String,Object>d){
        if(type==null||d==null)return;String component=str(d.get("component"));Component c=component(component);
        String key=str(d.get("movementKey")),signature=str(d.get("candidateSignature"));
        if("SHADOW_AB_DECISION".equals(type)){if(!signature.isEmpty())publicPlanSignatures.add(signature);
            if(!key.isEmpty())publicMovementKeys.add(key);String decision=str(d.get("decision"));
            if(ShadowCalibrationPolicy.SOL_P01_MONITOR.equals(component)){
                if("KEEP".equals(decision))c.keep++;else if("BLOCK".equals(decision))c.block++;
                if("BLOCK".equals(decision))c.blockByReason.merge(str(d.get("shadowReasonCode")),1L,Long::sum);
                pendingSolP01Decision=decision;}}
        else if("SHADOW_PLAN_OPENED".equals(type)){if(!key.isEmpty()){openedMovementKeys.add(key);c.opened.add(key);}
            long latency=longValue(d.get("qualificationToOpenMs"),-1);if(latency>=0)c.addLatency(latency);
            long stability=longValue(d.get("stabilityMs"),-1);if(stability>=0)c.addStability(stability);}
        else if("SHADOW_PLAN_SKIPPED".equals(type)){String decision=str(d.get("decision"));
            if("WOULD_QUALIFY".equals(decision)){if(!key.isEmpty()){c.wouldQualify.add(key);
                if("SHADOW_DUPLICATE_HIGHER_PRIORITY_LANE".equals(str(d.get("shadowReasonCode"))))higherOverlapKeys.add(key);}}
            else c.skipped++;
            if(ShadowCalibrationPolicy.ETH_REACCELERATION.equals(component)){
                if("BRANCH_A".equals(str(d.get("branch"))))c.branchAQualifications++;
                else if("BRANCH_B".equals(str(d.get("branch"))))c.branchBQualifications++;
                if("STABILITY_PENDING".equals(decision))c.stabilityPending++;
                if("STABILITY_RESET".equals(decision))c.stabilityReset++;}
            if(ShadowCalibrationPolicy.SOL_EARLY.equals(component)){
                if("STABILITY_PENDING".equals(decision))c.stabilityPending++;
                if("STABILITY_RESET".equals(decision))c.stabilityReset++;}}
        else if("SHADOW_PUBLIC_OVERLAP".equals(type)){if(!key.isEmpty()){overlapMovementKeys.add(key);c.publicOverlaps.add(key);}}
        else if("SHADOW_FEE_AWARE_SIZING".equals(type))c.measurements++;
        else if("SHADOW_RANGE_RECLAIM_OBSERVATION".equals(type)
                ||"SHADOW_NO_RETRACE_BREAKOUT_OBSERVATION".equals(type)){
            c.observations++;if(!key.isEmpty())c.telemetryMovements.add(key);}
        else if("SHADOW_RANGE_RECLAIM_TERMINAL_OBSERVATION".equals(type)){
            c.terminalObservations++;if(!key.isEmpty())c.telemetryMovements.add(key);}
        else if("SHADOW_TP_TOUCHED".equals(type)){shadowTp++;c.tp++;addResult(c,d);}
        else if("SHADOW_SL_TOUCHED".equals(type)){shadowSl++;c.sl++;addResult(c,d);}
        else if("SHADOW_INTERNAL_ERROR".equals(type))internalErrors++;
    }
    public synchronized void qualified(String component,String movement,long qualificationAt){if(empty(movement))return;
        qualifiedMovementKeys.add(movement);Component c=component(component);c.qualified.add(movement);
        if(qualificationAt>0)c.qualificationAt.put(movement,qualificationAt);trimMap(c.qualificationAt);}
    public synchronized void opportunity(String component,String movement){if(empty(movement))return;
        opportunityMovementKeys.add(movement);component(component).opportunities.add(movement);}
    public synchronized void duplicateSuppressed(String component,String signature){duplicateSuppressed++;
        component(component).duplicateSuppressed++;}
    public synchronized void telemetryDeduplicated(String component){component(component).telemetryDeduplicated++;}
    public synchronized void telemetrySnapshot(String component,List<Map<String,Object>> records){
        Component c=component(component);c.telemetrySnapshots.clear();if(records==null)return;
        for(Map<String,Object> record:records){if(record!=null)c.telemetrySnapshots.add(new LinkedHashMap<>(record));
            if(c.telemetrySnapshots.size()>=ShadowTelemetryRegistry.CAPACITY)break;}}
    public synchronized void publicTerminal(String status){if("TP_TOUCHED".equals(status))publicTp++;
        else if("SL_TOUCHED".equals(status))publicSl++;
        Component sol=component(ShadowCalibrationPolicy.SOL_P01_MONITOR);
        if("KEEP".equals(pendingSolP01Decision)){if("TP_TOUCHED".equals(status))sol.publicTpAfterKeep++;
            else if("SL_TOUCHED".equals(status))sol.publicSlAfterKeep++;}
        else if("BLOCK".equals(pendingSolP01Decision)){if("TP_TOUCHED".equals(status))sol.publicTpAfterBlock++;
            else if("SL_TOUCHED".equals(status))sol.publicSlAfterBlock++;}pendingSolP01Decision="";}
    public synchronized void registryStats(Map<String,Object>s){if(s==null)return;
        evictedMovementRecords=longValue(s.get("evictedMovementRecords"),evictedMovementRecords);
        terminalShadowRecords=longValue(s.get("terminalShadowRecords"),terminalShadowRecords);
        activeShadowPlans=longValue(s.get("activeShadowPlans"),activeShadowPlans);
        dedupCapacityReached=Boolean.TRUE.equals(s.get("dedupCapacityReached"));}
    public synchronized void reset(long now,boolean carried){startedAt=now;publicTp=publicSl=shadowTp=shadowSl=0;
        duplicateSuppressed=internalErrors=evictedMovementRecords=terminalShadowRecords=activeShadowPlans=0;netUsdt=netR=0;
        dedupCapacityReached=false;publicPlanCarriedAtReset=carried;pendingSolP01Decision="";for(BoundedKeys k:allKeys())k.clear();
        for(Component c:components.values())c.reset();}

    public synchronized Map<String,Object> snapshot(long now){long duration=Math.max(0,now-startedAt);
        double hours=duration/3_600_000d;LinkedHashSet<String> combinedOpportunity=union(publicMovementKeys.values,opportunityMovementKeys.values);
        LinkedHashSet<String> combinedOpened=union(publicMovementKeys.values,openedMovementKeys.values);
        LinkedHashMap<String,Object> out=base(now,duration);out.put("publicPlans",publicPlanSignatures.size());
        out.put("publicMovementKeys",publicMovementKeys.size());out.put("publicTp",publicTp);out.put("publicSl",publicSl);
        out.put("publicSignalsPerHour",rate(publicMovementKeys.size(),hours));
        out.put("uniqueShadowQualifications",qualifiedMovementKeys.size());out.put("uniqueShadowOpportunities",opportunityMovementKeys.size());
        out.put("uniqueShadowOpened",openedMovementKeys.size());out.put("shadowTp",shadowTp);out.put("shadowSl",shadowSl);
        out.put("shadowUnresolved",Math.max(0,openedMovementKeys.size()-shadowTp-shadowSl));out.put("shadowNetUsdt",netUsdt);out.put("shadowNetR",netR);
        out.put("shadowSignalsPerHour",rate(opportunityMovementKeys.size(),hours));
        out.put("uniqueCombinedPublicAndShadowOpportunities",combinedOpportunity.size());
        out.put("uniqueCombinedOpenedPlans",combinedOpened.size());out.put("combinedSignalsPerHour",rate(combinedOpportunity.size(),hours));
        out.put("publicShadowOverlaps",overlapMovementKeys.size());out.put("higherPriorityLaneOverlaps",higherOverlapKeys.size());
        out.put("shadowDuplicateEventsSuppressed",duplicateSuppressed);out.put("shadowInternalErrors",internalErrors);
        out.put("dedupCapacity",ShadowOpenedPlanRegistry.CAPACITY);out.put("dedupCapacityReached",dedupCapacityReached);
        out.put("evictedMovementRecords",evictedMovementRecords);out.put("activeShadowPlan",activeShadowPlans>0);
        out.put("terminalShadowRecords",terminalShadowRecords);out.put("publicPlanCarriedAtReset",publicPlanCarriedAtReset);
        out.put("noRetraceMovements",component(ShadowCalibrationPolicy.ETH_NO_RETRACE).snapshotRecords());
        out.put("rangeReclaimMovements",component(ShadowCalibrationPolicy.ETH_RANGE_RECLAIM).snapshotRecords());
        LinkedHashMap<String,Object> by=new LinkedHashMap<>();for(Map.Entry<String,Component>e:components.entrySet())by.put(e.getKey(),e.getValue().map());
        out.put("components",by);return out;}

    public static Map<String,Object> aggregate(Iterable<ShadowExperimentSummary> summaries,long now){
        ShadowExperimentSummary all=new ShadowExperimentSummary();boolean first=true;
        if(summaries!=null)for(ShadowExperimentSummary s:summaries)if(s!=null){s.mergeInto(all);if(first){all.startedAt=s.startedAt;first=false;}else all.startedAt=Math.min(all.startedAt,s.startedAt);}
        return all.snapshot(now);
    }
    private synchronized void mergeInto(ShadowExperimentSummary target){synchronized(target){
        target.publicPlanSignatures.addAll(publicPlanSignatures.values);target.publicMovementKeys.addAll(publicMovementKeys.values);
        target.qualifiedMovementKeys.addAll(qualifiedMovementKeys.values);target.opportunityMovementKeys.addAll(opportunityMovementKeys.values);
        target.openedMovementKeys.addAll(openedMovementKeys.values);target.overlapMovementKeys.addAll(overlapMovementKeys.values);
        target.higherOverlapKeys.addAll(higherOverlapKeys.values);target.publicTp+=publicTp;target.publicSl+=publicSl;
        target.shadowTp+=shadowTp;target.shadowSl+=shadowSl;target.duplicateSuppressed+=duplicateSuppressed;
        target.internalErrors+=internalErrors;target.evictedMovementRecords+=evictedMovementRecords;
        target.terminalShadowRecords+=terminalShadowRecords;target.netUsdt+=netUsdt;target.netR+=netR;
        target.activeShadowPlans+=activeShadowPlans;
        target.dedupCapacityReached|=dedupCapacityReached;target.publicPlanCarriedAtReset|=publicPlanCarriedAtReset;
        for(Map.Entry<String,Component>e:components.entrySet())e.getValue().mergeInto(target.component(e.getKey()));}}

    private void addResult(Component c,Map<String,Object>d){double n=num(d.get("netResultUsdt")),r=num(d.get("resultR"));
        if(Double.isFinite(n)){netUsdt+=n;c.netUsdt+=n;}if(Double.isFinite(r)){netR+=r;c.netR+=r;}}
    private Component component(String name){return components.computeIfAbsent(empty(name)?"UNKNOWN":name,k->new Component());}
    private List<BoundedKeys> allKeys(){return List.of(publicPlanSignatures,publicMovementKeys,qualifiedMovementKeys,
            opportunityMovementKeys,openedMovementKeys,overlapMovementKeys,higherOverlapKeys);}
    private LinkedHashMap<String,Object> base(long now,long duration){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("shadowPolicyVersion",ShadowCalibrationPolicy.VERSION);out.put("shadowSchemaVersion",ShadowCalibrationPolicy.SCHEMA_VERSION);
        out.put("diagnosticSessionStartedAt",startedAt);out.put("observedAt",now);out.put("durationMs",duration);return out;}
    private Map<String,Object> fallback(long now){LinkedHashMap<String,Object> out=base(now,Math.max(0,now-startedAt));
        out.put("available",false);out.put("error","SHADOW_SUMMARY_UNAVAILABLE");
        out.put("shadowInternalErrors",internalErrors);return out;}
    private static String[] requiredComponents(){return new String[]{ShadowCalibrationPolicy.ETH_P01_GUARD,
            ShadowCalibrationPolicy.SOL_P01_MONITOR,ShadowCalibrationPolicy.P02_GUARD,ShadowCalibrationPolicy.SOL_EARLY,
            ShadowCalibrationPolicy.PULLBACK,ShadowCalibrationPolicy.ETH_FLOW_HIGH_CONFIDENCE,
            ShadowCalibrationPolicy.ETH_REACCELERATION,ShadowCalibrationPolicy.ETH_RANGE_FADE_LONG,
            ShadowCalibrationPolicy.ETH_RANGE_RECLAIM,ShadowCalibrationPolicy.ETH_NO_RETRACE,
            "SHADOW_FEE_AWARE_SIZING"};}
    private static double rate(long count,double hours){return hours>0?count/hours:0d;}
    private static boolean empty(String s){return s==null||s.isEmpty();}private static String str(Object v){return v==null?"":String.valueOf(v);}
    private static double num(Object v){return v instanceof Number?((Number)v).doubleValue():Double.NaN;}
    private static long longValue(Object v,long fallback){return v instanceof Number?((Number)v).longValue():fallback;}
    private static LinkedHashSet<String> union(LinkedHashSet<String>a,LinkedHashSet<String>b){LinkedHashSet<String> out=new LinkedHashSet<>(a);out.addAll(b);return out;}
    private static <K,V>void trimMap(LinkedHashMap<K,V>m){while(m.size()>MAX_KEYS)m.remove(m.keySet().iterator().next());}

    private static final class BoundedKeys{final LinkedHashSet<String> values=new LinkedHashSet<>();
        void add(String key){if(empty(key))return;values.add(key);while(values.size()>MAX_KEYS)values.remove(values.iterator().next());}
        void addAll(Iterable<String> keys){for(String k:keys)add(k);}int size(){return values.size();}void clear(){values.clear();}}
    private static final class Component{final BoundedKeys qualified=new BoundedKeys(),opportunities=new BoundedKeys(),opened=new BoundedKeys(),wouldQualify=new BoundedKeys(),publicOverlaps=new BoundedKeys(),telemetryMovements=new BoundedKeys();
        final LinkedHashMap<String,Long> qualificationAt=new LinkedHashMap<>(),blockByReason=new LinkedHashMap<>();final List<Long> latencies=new ArrayList<>(),stabilities=new ArrayList<>();
        final List<Map<String,Object>> telemetrySnapshots=new ArrayList<>();
        long skipped,tp,sl,measurements,duplicateSuppressed,keep,block,branchAQualifications,branchBQualifications,
                stabilityPending,stabilityReset,observations,terminalObservations,telemetryDeduplicated,
                publicTpAfterKeep,publicSlAfterKeep,publicTpAfterBlock,publicSlAfterBlock;double netUsdt,netR;
        void addLatency(long value){latencies.add(value);while(latencies.size()>MAX_KEYS)latencies.remove(0);}
        void addStability(long value){stabilities.add(value);while(stabilities.size()>MAX_KEYS)stabilities.remove(0);}
        void reset(){qualified.clear();opportunities.clear();opened.clear();wouldQualify.clear();publicOverlaps.clear();telemetryMovements.clear();qualificationAt.clear();blockByReason.clear();latencies.clear();stabilities.clear();telemetrySnapshots.clear();skipped=tp=sl=measurements=duplicateSuppressed=keep=block=branchAQualifications=branchBQualifications=stabilityPending=stabilityReset=observations=terminalObservations=telemetryDeduplicated=publicTpAfterKeep=publicSlAfterKeep=publicTpAfterBlock=publicSlAfterBlock=0;netUsdt=netR=0;}
        void mergeInto(Component t){t.qualified.addAll(qualified.values);t.opportunities.addAll(opportunities.values);t.opened.addAll(opened.values);t.wouldQualify.addAll(wouldQualify.values);t.publicOverlaps.addAll(publicOverlaps.values);
            t.telemetryMovements.addAll(telemetryMovements.values);for(long l:latencies)t.addLatency(l);for(long l:stabilities)t.addStability(l);
            for(Map<String,Object> record:telemetrySnapshots){if(t.telemetrySnapshots.size()>=ShadowTelemetryRegistry.CAPACITY)break;
                t.telemetrySnapshots.add(new LinkedHashMap<>(record));}
            for(Map.Entry<String,Long>e:blockByReason.entrySet())t.blockByReason.merge(e.getKey(),e.getValue(),Long::sum);
            t.skipped+=skipped;t.tp+=tp;t.sl+=sl;t.measurements+=measurements;t.duplicateSuppressed+=duplicateSuppressed;
            t.keep+=keep;t.block+=block;t.branchAQualifications+=branchAQualifications;t.branchBQualifications+=branchBQualifications;
            t.stabilityPending+=stabilityPending;t.stabilityReset+=stabilityReset;t.observations+=observations;
            t.terminalObservations+=terminalObservations;t.telemetryDeduplicated+=telemetryDeduplicated;
            t.publicTpAfterKeep+=publicTpAfterKeep;t.publicSlAfterKeep+=publicSlAfterKeep;t.publicTpAfterBlock+=publicTpAfterBlock;t.publicSlAfterBlock+=publicSlAfterBlock;t.netUsdt+=netUsdt;t.netR+=netR;}
        Map<String,Object> map(){LinkedHashMap<String,Object>m=new LinkedHashMap<>();m.put("qualifications",qualified.size());m.put("opportunities",opportunities.size());
            m.put("openings",opened.size());m.put("skipped",skipped);m.put("wouldQualify",wouldQualify.size());m.put("measurements",measurements);m.put("tp",tp);m.put("sl",sl);
            m.put("unresolved",Math.max(0,opened.size()-tp-sl));m.put("netUsdt",netUsdt);m.put("netR",netR);
            m.put("publicOverlaps",publicOverlaps.size());m.put("shadowDuplicateEventsSuppressed",duplicateSuppressed);
            m.put("medianQualificationToOpenMs",median(latencies));m.put("medianStabilityMs",median(stabilities));
            m.put("keep",keep);m.put("block",block);m.put("blockByReason",new LinkedHashMap<>(blockByReason));
            m.put("branchAQualifications",branchAQualifications);m.put("branchBQualifications",branchBQualifications);
            m.put("stabilityPending",stabilityPending);m.put("stabilityReset",stabilityReset);
            m.put("uniqueMovements",telemetryMovements.size());m.put("observations",observations);
            m.put("terminalObservations",terminalObservations);m.put("deduplicatedEvents",telemetryDeduplicated);
            m.put("publicTpAfterKeep",publicTpAfterKeep);m.put("publicSlAfterKeep",publicSlAfterKeep);
            m.put("publicTpAfterBlock",publicTpAfterBlock);m.put("publicSlAfterBlock",publicSlAfterBlock);return m;}
        List<Map<String,Object>> snapshotRecords(){List<Map<String,Object>> out=new ArrayList<>();
            for(Map<String,Object> record:telemetrySnapshots)out.add(new LinkedHashMap<>(record));return out;}
        private static Object median(List<Long> values){if(values.isEmpty())return null;List<Long> copy=new ArrayList<>(values);Collections.sort(copy);int n=copy.size();
            return n%2==1?copy.get(n/2):(copy.get(n/2-1)+copy.get(n/2))/2.0;}}
    private static final class SnapshotHolder{Map<String,Object> value;}
}
