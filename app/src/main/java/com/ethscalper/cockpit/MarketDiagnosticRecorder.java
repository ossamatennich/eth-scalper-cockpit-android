package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, per-symbol diagnostic recorder. It has no Android or persistence dependency. */
public final class MarketDiagnosticRecorder {
    public static final int MAX_EVENTS = 2_000;
    public static final int MAX_FRAMES = 720;
    private final MarketProfile profile;
    private final Deque<Record> events = new ArrayDeque<>();
    private final Deque<Record> frames = new ArrayDeque<>();
    private long sequence;
    private int candidates,rejected,confirmed,restored,tp,sl;
    private long fullEventMapReads,recentEventMapReads;

    public MarketDiagnosticRecorder(MarketProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile=profile;
    }

    public synchronized Record record(long at,String type,String reasonCode,String reasonText,
                                      String classification,String historicalDiagnosticCode,
                                      String sleeve,SignalDecision signal,MarketSnapshot snapshot,
                                      long candidateAgeMs,boolean marketFresh,boolean btcFresh,
                                      double adverse,Map<String,Object> details) {
        LinkedHashMap<String,Object> value=base(at,type,reasonCode,reasonText,classification,
                historicalDiagnosticCode,sleeve,signal,snapshot,candidateAgeMs,marketFresh,
                btcFresh,adverse);
        List<SafeJsonNormalizer.Issue> normalizationIssues=new ArrayList<>();
        if(details!=null)for(Map.Entry<String,Object> entry:details.entrySet())
            value.put(entry.getKey(),SafeJsonNormalizer.normalize(entry.getValue(),
                    "$.details."+entry.getKey(),normalizationIssues));
        // Keep normalization evidence inside the original event. Creating another recorder event
        // here would recursively normalize its own diagnostic payload and could loop forever.
        if(!normalizationIssues.isEmpty()){
            List<Map<String,Object>> boundedIssues=new ArrayList<>();
            for(SafeJsonNormalizer.Issue issue:normalizationIssues)boundedIssues.add(issue.asMap());
            value.put("normalizationIssueCount",normalizationIssues.size());
            value.put("normalizationIssues",boundedIssues);
        }
        normalizeTerminal(value);
        Record record=new Record(++sequence,value);
        if("MARKET_FRAME".equals(type)){frames.addLast(record);trim(frames,MAX_FRAMES);}
        else {
            events.addLast(record);
            updateSummaryCounters(record,1);
            while(events.size()>MAX_EVENTS)updateSummaryCounters(events.removeFirst(),-1);
        }
        return record;
    }

    public Record frame(long at,SignalDecision decision,MarketSnapshot snapshot,
                        boolean marketFresh,boolean btcFresh) {
        return record(at,"MARKET_FRAME",decision==null?"":decision.reasonCode,
                decision==null?"":decision.reasonText,"STRUCTURAL_SHARED","","",
                decision,snapshot,0,marketFresh,btcFresh,0,Collections.emptyMap());
    }

    public synchronized List<Record> eventsAfter(long afterSequence) {
        List<Record> out=new ArrayList<>();
        for(Record record:events)if(record.sequence>afterSequence
                &&!"MARKET_FRAME".equals(record.values.get("eventType")))out.add(record);
        return Collections.unmodifiableList(out);
    }
    public synchronized List<Map<String,Object>> eventMaps(){fullEventMapReads++;return maps(events);}
    /** Returns only the bounded tail needed by the Android status payload. */
    public synchronized List<Map<String,Object>> recentEventMaps(int limit){
        recentEventMapReads++;
        int bounded=Math.max(0,Math.min(limit,MAX_EVENTS));
        ArrayList<Map<String,Object>> out=new ArrayList<>(bounded);
        java.util.Iterator<Record> iterator=events.descendingIterator();
        while(iterator.hasNext()&&out.size()<bounded)out.add(iterator.next().values);
        Collections.reverse(out);
        return Collections.unmodifiableList(out);
    }
    public synchronized List<Map<String,Object>> frameMaps(){return maps(frames);}
    public synchronized long latestSequence(){return sequence;}
    public synchronized void reset(){events.clear();frames.clear();candidates=rejected=confirmed=restored=tp=sl=0;}
    synchronized long fullEventMapReads(){return fullEventMapReads;}
    synchronized long recentEventMapReads(){return recentEventMapReads;}

    public synchronized Map<String,Object> summary() {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("symbol",profile.symbol);out.put("asset",profile.asset);
        out.put("profileVersion",profile.profileVersion);out.put("events",events.size());
        out.put("frames",frames.size());out.put("candidates",candidates);
        out.put("rejectedCandidates",rejected);out.put("confirmedTrades",confirmed);
        out.put("restoredActivePlans",restored);out.put("tp",tp);out.put("sl",sl);
        return Collections.unmodifiableMap(out);
    }

    private void updateSummaryCounters(Record record,int delta){
        String type=String.valueOf(record.values.get("eventType"));
        if(type.contains("CANDIDATE"))candidates+=delta;
        if(type.contains("REJECT")||type.contains("TOMBSTONE")||type.contains("MISSED"))rejected+=delta;
        if("PLAN_CONFIRMED".equals(type)||"CV_CORE_PLAN_PERSISTED".equals(type))confirmed+=delta;
        if("PLAN_RESTORED".equals(type))restored+=delta;
        if("TP_TOUCHED".equals(type)||"CV_CORE_TP_TOUCHED".equals(type))tp+=delta;
        if("SL_TOUCHED".equals(type)||"CV_CORE_SL_TOUCHED".equals(type))sl+=delta;
    }

    private LinkedHashMap<String,Object> base(long at,String type,String code,String text,
            String classification,String historical,String sleeve,SignalDecision signal,
            MarketSnapshot snapshot,long age,boolean marketFresh,boolean btcFresh,double adverse) {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("symbol",profile.symbol);out.put("asset",profile.asset);
        out.put("profileVersion",profile.profileVersion);out.put("eventAt",at);
        out.put("eventType",safe(type));out.put("reasonCode",safe(code));
        out.put("reasonText",safe(text));out.put("classification",safe(classification));
        out.put("historicalDiagnosticCode",safe(historical));out.put("sleeve",safe(sleeve));
        out.put("side",signal==null?"":safe(signal.side));out.put("family",signal==null?"":safe(signal.family));
        out.put("candidateAgeMs",Math.max(0,age));out.put("marketFeedFresh",marketFresh);
        out.put("btcFeedFresh",btcFresh);
        put(out,"last",snapshot==null?Double.NaN:snapshot.marketLast);
        put(out,"bid",snapshot==null?Double.NaN:snapshot.marketBid);
        put(out,"ask",snapshot==null?Double.NaN:snapshot.marketAsk);
        put(out,"entry",signal==null?Double.NaN:signal.entry);put(out,"tp",signal==null?Double.NaN:signal.takeProfit);
        put(out,"sl",signal==null?Double.NaN:signal.stopLoss);out.put("quantity",signal==null?0:signal.quantity);
        out.put("score",signal==null?0:signal.score);
        NormalizedSignalMetrics.Result metrics=signal==null||snapshot==null?null:
                NormalizedSignalMetrics.calculate(profile,signal.side,signal,snapshot,Math.max(0,adverse));
        put(out,"A",metrics==null?Double.NaN:metrics.a);put(out,"E60",Math.max(0,adverse));
        put(out,"R",metrics==null?Double.NaN:metrics.r);put(out,"m1",metrics==null?Double.NaN:metrics.m1);
        put(out,"m3",metrics==null?Double.NaN:metrics.m3);put(out,"m8",metrics==null?Double.NaN:metrics.m8);
        int direction=signal!=null&&"LONG".equals(signal.side)?1:signal!=null&&"SHORT".equals(signal.side)?-1:0;
        put(out,"f15",snapshot==null||direction==0?Double.NaN:direction*snapshot.flow15);
        put(out,"f30",snapshot==null||direction==0?Double.NaN:direction*snapshot.flow30);
        put(out,"f60",snapshot==null||direction==0?Double.NaN:direction*snapshot.flow60);
        put(out,"f120",snapshot==null||direction==0?Double.NaN:direction*snapshot.flow120);
        put(out,"volumeRatio",snapshot==null?Double.NaN:snapshot.volumeRatio);
        put(out,"rangePosition",snapshot==null?Double.NaN:snapshot.rangePosition);
        put(out,"room",metrics==null?Double.NaN:metrics.room);
        out.put("earlyP01Mode","");out.put("earlyP01StabilityMs",0L);out.put("earlyP01ReasonCode","");
        out.put("p02Mode","");out.put("olsCount",0);put(out,"olsSlope",Double.NaN);
        put(out,"olsT60",Double.NaN);out.put("p02ReasonCode","");
        put(out,"riskBudgetUsdt",Double.NaN);put(out,"resultCostPerUnit",Double.NaN);
        put(out,"riskAllowancePerUnit",Double.NaN);put(out,"theoreticalMaximumLoss",Double.NaN);
        out.put("terminalStatus","");return out;
    }

    private static void normalizeTerminal(Map<String,Object> value) {
        String type=String.valueOf(value.get("eventType"));
        if("TP_TOUCHED".equals(type)||"SL_TOUCHED".equals(type)
                ||"CV_CORE_TP_TOUCHED".equals(type)||"CV_CORE_SL_TOUCHED".equals(type)
                ||"SHADOW_TP_TOUCHED".equals(type)||"SHADOW_SL_TOUCHED".equals(type))
            value.put("terminalStatus",type);
        else value.put("terminalStatus","");
    }
    private static void put(Map<String,Object> map,String key,double value){map.put(key,Double.isFinite(value)?value:null);}
    private static String safe(String value){return value==null?"":value;}
    private static void trim(Deque<?> deque,int max){while(deque.size()>max)deque.removeFirst();}
    private static List<Map<String,Object>> maps(Deque<Record> source){List<Map<String,Object>> out=new ArrayList<>();for(Record r:source)out.add(r.values);return Collections.unmodifiableList(out);}

    public static final class Record {
        public final long sequence;
        public final Map<String,Object> values;
        Record(long sequence,Map<String,Object> values){this.sequence=sequence;this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));}
    }
}
