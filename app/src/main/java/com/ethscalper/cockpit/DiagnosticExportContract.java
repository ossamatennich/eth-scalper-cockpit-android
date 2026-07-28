package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, testable contract for the multi-market diagnostic ZIP. */
public final class DiagnosticExportContract {
    private DiagnosticExportContract() {}
    public static final List<String> REQUIRED_FILES=Collections.unmodifiableList(Arrays.asList(
            "status.json","markets.json","active_plans.json","profiles_manifest.json",
            "market_diagnostics.json","market_diagnostics.csv","market_candidates.json",
            "market_candidates.csv","market_plan_history.json","market_frames.json",
            "market_frames.csv","persistent_market_events.json",
            "persistent_market_events.jsonl","persistent_market_frames.json",
            "persistent_market_frames.jsonl","market_summary.json","market_summary.txt",
            "health_check.txt","instructions.txt"));

    public static String zipPrefix(String versionName){
        if(versionName==null||versionName.isEmpty())throw new IllegalArgumentException("version");
        return "ETH_SOL_Scalper_Diagnostic_v"+versionName.replace('.','_')+"_";
    }

    public static String instructions(String versionName){return
            "ETH + SOL Scalper Cockpit v"+versionName+" — diagnostic multi-marchés.\n"
            +"Chaque événement tradable porte symbol, asset et profileVersion.\n"
            +"BTCUSDT est un contexte partagé non tradable.\n"
            +"Aucune clé API, aucun ordre automatique ; le trading reste manuel.\n"
            +"Un plan publié termine exclusivement au TP ou au SL.\n";}
    /** Rebuilds all potentially large exports from the persistent JSONL sources. */
    public static ExportData rebuild(List<Map<String,Object>> persistentEvents,
                                     List<Map<String,Object>> persistentFrames) {
        List<Map<String,Object>> diagnostics=new ArrayList<>(),candidates=new ArrayList<>(),
                plans=new ArrayList<>(),frames=new ArrayList<>();
        Map<String,Map<String,Object>> summary=new LinkedHashMap<>();
        if(persistentEvents!=null)for(Map<String,Object> event:persistentEvents){if(event==null)continue;
            String type=string(event.get("eventType"));if("MARKET_FRAME".equals(type))continue;
            diagnostics.add(event);if(isCandidate(type))candidates.add(event);
            if(isPlan(type))plans.add(event);updateSummary(summary,event,type);
        }
        if(persistentFrames!=null)for(Map<String,Object> frame:persistentFrames)
            if(frame!=null)frames.add(frame);
        return new ExportData(diagnostics,candidates,plans,frames,summary);
    }

    public static boolean isCandidate(String type){return type!=null&&(type.contains("ADMISSION")
            ||type.contains("CANDIDATE")||type.contains("P01")||type.contains("P02"));}
    public static boolean isPlan(String type){return "PLAN_CONFIRMED".equals(type)
            ||"PLAN_RESTORED".equals(type)||"TP_TOUCHED".equals(type)||"SL_TOUCHED".equals(type);}

    private static void updateSummary(Map<String,Map<String,Object>> summaries,
                                      Map<String,Object> event,String type) {
        String symbol=string(event.get("symbol"));if(symbol.isEmpty())symbol="UNKNOWN";
        Map<String,Object> value=summaries.get(symbol);
        if(value==null){value=new LinkedHashMap<>();value.put("symbol",symbol);
            value.put("asset",string(event.get("asset")));
            value.put("profileVersion",string(event.get("profileVersion")));
            value.put("events",0);value.put("candidates",0);value.put("rejectedCandidates",0);
            value.put("confirmedTrades",0);value.put("restoredActivePlans",0);
            value.put("tp",0);value.put("sl",0);summaries.put(symbol,value);}
        increment(value,"events");if(type.contains("CANDIDATE"))increment(value,"candidates");
        if(type.contains("REJECT")||type.contains("TOMBSTONE")||type.contains("MISSED"))
            increment(value,"rejectedCandidates");
        if("PLAN_CONFIRMED".equals(type))increment(value,"confirmedTrades");
        if("PLAN_RESTORED".equals(type))increment(value,"restoredActivePlans");
        if("TP_TOUCHED".equals(type))increment(value,"tp");
        if("SL_TOUCHED".equals(type))increment(value,"sl");
    }
    private static void increment(Map<String,Object> value,String key){Object current=value.get(key);
        value.put(key,(current instanceof Number?((Number)current).intValue():0)+1);}
    private static String string(Object value){return value==null?"":String.valueOf(value);}

    public static final class ExportData {
        public final List<Map<String,Object>> diagnostics,candidates,plans,frames;
        public final Map<String,Map<String,Object>> summary;
        ExportData(List<Map<String,Object>> diagnostics,List<Map<String,Object>> candidates,
                   List<Map<String,Object>> plans,List<Map<String,Object>> frames,
                   Map<String,Map<String,Object>> summary){this.diagnostics=diagnostics;this.candidates=candidates;
            this.plans=plans;this.frames=frames;this.summary=summary;}
    }
}
