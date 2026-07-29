package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless lifecycle recorder with bounded aggregation for repetitive analytical noise. */
public final class DiagnosticEventCoalescer {
    public static final long SUMMARY_INTERVAL_MS=5*60*1000L;
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> activeBySlot=new LinkedHashMap<>();

    public synchronized List<Map<String,Object>> accept(Map<String,Object> input,long now) {
        if(input==null)return Collections.emptyList();Map<String,Object> event=copy(input);
        if(!coalescible(event))return Collections.singletonList(event);
        String identity=identity(event),slot=string(event.get("symbol"))+"|"+string(event.get("eventType"));
        List<Map<String,Object>> out=new ArrayList<>();String active=activeBySlot.get(slot);
        if(active!=null&&!active.equals(identity)){Pending previous=pending.remove(active);if(previous!=null&&previous.repeats>0)out.add(previous.summary(now));}
        activeBySlot.put(slot,identity);Pending value=pending.get(identity);
        if(value==null){pending.put(identity,new Pending(event,now));out.add(event);return out;}
        value.add(event,now);if(now-value.lastSummaryAt>=SUMMARY_INTERVAL_MS){out.add(value.summary(now));value.afterSummary(now);}
        return out;
    }

    public synchronized List<Map<String,Object>> flush(long now) {
        List<Map<String,Object>> out=new ArrayList<>();for(Pending value:pending.values())if(value.repeats>0)out.add(value.summary(now));
        pending.clear();activeBySlot.clear();return out;
    }
    public synchronized void reset(){pending.clear();activeBySlot.clear();}

    public static boolean coalescible(Map<String,Object> event) {
        String type=string(event.get("eventType")),code=string(event.get("reasonCode"));
        if(type.contains("CANDIDATE")||type.startsWith("P01")||type.startsWith("P02")
                ||"CONFIRMATION_READY".equals(type)||"PLAN_CONFIRMED".equals(type)
                ||"PLAN_RESTORED".equals(type)||"TP_TOUCHED".equals(type)||"SL_TOUCHED".equals(type)
                ||"PERSISTENCE_FAILED".equals(type)||type.contains("RESET")||type.startsWith("REARM_"))return false;
        if("RAW_DECISION".equals(type)||"ENGINE_DIAGNOSTIC".equals(type)||"ADMISSION_REJECTED".equals(type))return true;
        return code.contains("FEED_STALE")||code.contains("NO_EDGE")||code.contains("PRIX_DEJA_TROP_LOIN");
    }
    private static String identity(Map<String,Object> event){return string(event.get("symbol"))+"|"+string(event.get("eventType"))+"|"
            +string(event.get("reasonCode"))+"|"+string(event.get("classification"))+"|"+string(event.get("sleeve"))+"|"
            +string(event.get("side"))+"|"+normalize(string(event.get("reasonText")));}
    private static String normalize(String value){return value.trim().replaceAll("\\s+"," ");}
    private static LinkedHashMap<String,Object> copy(Map<String,Object> source){return new LinkedHashMap<>(source);}
    private static String string(Object value){return value==null?"":String.valueOf(value);}

    private static final class Pending {
        final Map<String,Object> first;Map<String,Object> last;long firstAt,lastAt,lastSummaryAt,repeats;
        Pending(Map<String,Object> event,long now){first=copy(event);last=copy(event);firstAt=lastAt=lastSummaryAt=now;}
        void add(Map<String,Object> event,long now){last=copy(event);lastAt=now;repeats++;}
        Map<String,Object> summary(long now){LinkedHashMap<String,Object> out=copy(last);out.put("eventAt",Math.max(lastAt,now));
            out.put("coalesced",true);out.put("firstAt",firstAt);out.put("lastAt",lastAt);
            out.put("repeatCount",repeats);out.put("firstPayload",first);out.put("lastPayload",last);return out;}
        void afterSummary(long now){firstAt=lastAt;lastSummaryAt=now;repeats=0;}
    }
}
