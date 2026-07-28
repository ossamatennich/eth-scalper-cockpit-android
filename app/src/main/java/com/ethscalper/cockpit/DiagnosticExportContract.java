package com.ethscalper.cockpit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
}
