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
        if(details!=null)value.putAll(details);
        normalizeTerminal(value);
        Record record=new Record(++sequence,value);
        events.addLast(record);trim(events,MAX_EVENTS);
        if("MARKET_FRAME".equals(type)){frames.addLast(record);trim(frames,MAX_FRAMES);}
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
        for(Record record:events)if(record.sequence>afterSequence)out.add(record);
        return Collections.unmodifiableList(out);
    }
    public synchronized List<Map<String,Object>> eventMaps(){return maps(events);}
    public synchronized List<Map<String,Object>> frameMaps(){return maps(frames);}
    public synchronized long latestSequence(){return sequence;}
    public synchronized void reset(){events.clear();frames.clear();}

    public synchronized Map<String,Object> summary() {
        int candidates=0,rejected=0,confirmed=0,tp=0,sl=0;
        for(Record record:events){String type=String.valueOf(record.values.get("eventType"));
            if(type.contains("CANDIDATE"))candidates++;
            if(type.contains("REJECT")||type.contains("TOMBSTONE")||type.contains("MISSED"))rejected++;
            if("PLAN_CONFIRMED".equals(type)||"PLAN_RESTORED".equals(type))confirmed++;
            if("TP_TOUCHED".equals(type))tp++;if("SL_TOUCHED".equals(type))sl++;
        }
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("symbol",profile.symbol);out.put("asset",profile.asset);
        out.put("profileVersion",profile.profileVersion);out.put("events",events.size());
        out.put("frames",frames.size());out.put("candidates",candidates);
        out.put("rejectedCandidates",rejected);out.put("confirmedTrades",confirmed);
        out.put("tp",tp);out.put("sl",sl);return Collections.unmodifiableMap(out);
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
        if("TP_TOUCHED".equals(type)||"SL_TOUCHED".equals(type))value.put("terminalStatus",type);
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
