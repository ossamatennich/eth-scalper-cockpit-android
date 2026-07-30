package com.ethscalper.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Enforces the small, current-state-only Android broadcast contract. */
public final class StatusPayloadPolicy {
    public static final int MAX_STATUS_BYTES=200_000;
    public static final int MAX_RECENT_DIAGNOSTICS=20;
    public static final String[] FORBIDDEN_COLLECTIONS={"marketDiagnostics","marketCandidates",
            "marketPlanHistory","multiMarketFrames","observedSignals"};
    private StatusPayloadPolicy() {}

    public static void compact(JSONObject state,Collection<MarketRuntime> runtimes)throws Exception {
        if(state==null)throw new IllegalArgumentException("state");
        for(String key:FORBIDDEN_COLLECTIONS)state.remove(key);
        state.remove("aiLastDecision");
        state.put("diagnostics",recentDiagnostics(runtimes,MAX_RECENT_DIAGNOSTICS));
        if(sizeBytes(state)>=MAX_STATUS_BYTES){
            state.remove("engineMetrics");state.remove("calibrationSummary");
            state.remove("observationSummary");state.remove("overnightRecorder");
        }
        if(sizeBytes(state)>=MAX_STATUS_BYTES)state.put("diagnostics",new JSONArray());
        if(sizeBytes(state)>=MAX_STATUS_BYTES)throw new IllegalStateException("status payload too large");
    }

    public static void compactMap(Map<String,Object> state,Collection<MarketRuntime> runtimes) {
        if(state==null)throw new IllegalArgumentException("state");
        for(String key:FORBIDDEN_COLLECTIONS)state.remove(key);state.remove("aiLastDecision");
        state.put("diagnostics",recentDiagnosticMaps(runtimes,MAX_RECENT_DIAGNOSTICS));
        if(sizeBytes(state)>=MAX_STATUS_BYTES){state.remove("engineMetrics");
            state.remove("calibrationSummary");state.remove("observationSummary");
            state.remove("overnightRecorder");}
        if(sizeBytes(state)>=MAX_STATUS_BYTES)state.put("diagnostics",new ArrayList<>());
        if(sizeBytes(state)>=MAX_STATUS_BYTES)throw new IllegalStateException("status payload too large");
    }

    public static JSONArray recentDiagnostics(Collection<MarketRuntime> runtimes,int limit)throws Exception {
        JSONArray out=new JSONArray();for(Map<String,Object> value:recentDiagnosticMaps(runtimes,limit)){
            JSONObject item=new JSONObject(value);
            item.put("at",item.optLong("eventAt",0));
            item.put("code",item.optString("reasonCode",""));
            item.put("message",item.optString("reasonText",""));out.put(item);
        }
        return out;
    }

    public static List<Map<String,Object>> recentDiagnosticMaps(Collection<MarketRuntime> runtimes,
                                                                 int limit) {
        List<Map<String,Object>> values=new ArrayList<>();
        int bounded=Math.max(0,Math.min(limit,MAX_RECENT_DIAGNOSTICS));
        if(runtimes!=null)for(MarketRuntime runtime:runtimes)
            if(runtime!=null)values.addAll(runtime.recorder.recentEventMaps(bounded));
        values.sort(Comparator.comparingLong(StatusPayloadPolicy::eventAt));
        List<Map<String,Object>> out=new ArrayList<>();
        int start=Math.max(0,values.size()-bounded);
        for(int i=start;i<values.size();i++){Map<String,Object> item=new LinkedHashMap<>(values.get(i));
            item.put("at",eventAt(item));item.put("code",String.valueOf(item.get("reasonCode")));
            item.put("message",String.valueOf(item.get("reasonText")));out.add(item);}
        return out;
    }

    public static int sizeBytes(JSONObject state){return state.toString()
            .getBytes(StandardCharsets.UTF_8).length;}
    public static int sizeBytes(Map<String,Object> state){return jsonValue(state)
            .getBytes(StandardCharsets.UTF_8).length;}
    private static long eventAt(Map<String,Object> value){Object at=value.get("eventAt");
        return at instanceof Number?((Number)at).longValue():0;}
    private static String jsonValue(Object value){if(value==null)return"null";
        if(value instanceof Number||value instanceof Boolean)return String.valueOf(value);
        if(value instanceof Map){StringBuilder out=new StringBuilder("{");boolean first=true;
            for(Object entryObject:((Map<?,?>)value).entrySet()){Map.Entry<?,?> entry=(Map.Entry<?,?>)entryObject;
                if(!first)out.append(',');first=false;out.append(jsonString(String.valueOf(entry.getKey())))
                        .append(':').append(jsonValue(entry.getValue()));}return out.append('}').toString();}
        if(value instanceof Iterable){StringBuilder out=new StringBuilder("[");boolean first=true;
            for(Object item:(Iterable<?>)value){if(!first)out.append(',');first=false;out.append(jsonValue(item));}
            return out.append(']').toString();}return jsonString(String.valueOf(value));}
    private static String jsonString(String value){return"\""+value.replace("\\","\\\\")
            .replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
}
