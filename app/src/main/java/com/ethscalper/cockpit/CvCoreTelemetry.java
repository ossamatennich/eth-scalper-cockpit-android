package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-safe details captured at the causal observation timestamp. */
public final class CvCoreTelemetry {
    private CvCoreTelemetry(){}
    public static Map<String,Object> details(CvCoreEngine.Result result,CvCorePlan plan,long eventAt){Map<String,Object>d=new LinkedHashMap<>();
        d.put("engineId",CvCorePolicy.ENGINE_ID);d.put("policyId",CvCorePolicy.POLICY_ID);d.put("schema",CvCorePolicy.SCHEMA_ID);d.put("versionName",CvCorePolicy.VERSION_NAME);
        CvCoreObservation o=result!=null?result.observation:plan==null?null:plan.observation;CvCoreMovementRegistry.Episode e=result==null?null:result.episode;
        CvCorePolicy.Route route=result!=null?result.route:plan==null?null:plan.route;if(route!=null){d.put("routeId",route.routeId);d.put("priority",route.priority);}
        if(e!=null){d.put("episodeId",e.episodeId);d.put("firstSeenAt",e.firstSeenAt);d.put("lastSeenAt",e.lastSeenAt);d.put("duplicateCount",e.duplicateCount);}
        if(o!=null){d.put("sourceType",o.sourceType);d.put("sourceFamily",o.sourceFamily);d.put("sourceSleeve",o.sourceSleeve);d.put("observedAt",o.observedAt);
            d.put("symbol",o.symbol);d.put("side",o.side);d.put("marketFeedFresh",o.ethFeedFresh);d.put("btcFeedFresh",o.btcFeedFresh);d.put("solFeedFresh",o.solFeedFresh);
            d.put("ethQuoteAgeMs",o.ethQuoteAgeMs);d.put("btcQuoteAgeMs",o.btcQuoteAgeMs);d.put("solQuoteAgeMs",o.solQuoteAgeMs);finite(d,"bid",o.bid);finite(d,"ask",o.ask);finite(d,"mid",o.mid);finite(d,"A",o.a);finite(d,"directionalMove3Norm",o.directionalMove3Norm);
            metrics(d,o.metrics);}else d.put("observedAt",eventAt);
        d.put("persisted",false);d.put("alerted",false);
        if(plan!=null){d.put("sourceType",plan.sourceType);d.put("side",plan.side);d.put("entry",plan.entry);d.put("tp",plan.takeProfit);d.put("sl",plan.stopLoss);
            d.put("targetMultiple",plan.route.targetMultiple);d.put("stopMultiple",plan.route.stopMultiple);d.put("routeRiskBudget",plan.route.riskBudgetUsdt);
            d.put("targetDistance",plan.targetDistance);d.put("stopDistance",plan.stopDistance);d.put("quantity",plan.quantity);d.put("resultCostPerUnit",plan.resultCostPerUnit);
            d.put("netRewardPerUnit",plan.netRewardPerUnit);d.put("netRiskPerUnit",plan.netRiskPerUnit);d.put("plannedNetRR",plan.plannedNetRewardRisk);
            d.put("theoreticalMaximumLoss",plan.theoreticalMaximumLoss);d.put("qualificationAt",plan.qualificationAt);d.put("entryValidUntil",plan.entryValidUntil);}
        return d;}
    private static void metrics(Map<String,Object>d,CvCoreContextTracker.Metrics m){if(m==null)return;finite(d,"directionalEthReturn60",m.directionalEthReturn60);finite(d,"directionalSolReturn60",m.directionalSolReturn60);
        finite(d,"directionalEthEfficiency60",m.directionalEthEfficiency60);finite(d,"directionalSolEfficiency30",m.directionalSolEfficiency30);finite(d,"directionalBtcMove8",m.directionalBtcMove8);finite(d,"directionalBtcMove3",m.directionalBtcMove3);
        d.put("ethCoverage60",m.ethCoverage60);d.put("solCoverage60",m.solCoverage60);d.put("solCoverage30",m.solCoverage30);d.put("ethAnchorAge60Ms",m.ethAnchorAge60Ms);
        d.put("solAnchorAge60Ms",m.solAnchorAge60Ms);d.put("solAnchorAge30Ms",m.solAnchorAge30Ms);d.put("ethPathPoints60",m.ethPathPoints60);d.put("solPathPoints60",m.solPathPoints60);
        d.put("solPathPoints30",m.solPathPoints30);finite(d,"ethPathDistance60",m.ethPathDistance60);finite(d,"solPathDistance60",m.solPathDistance60);finite(d,"solPathDistance30",m.solPathDistance30);}
    public static Map<String,Object>alert(boolean posted,boolean already){Map<String,Object>m=new LinkedHashMap<>();m.put("posted",posted);m.put("alreadyAlerted",already);m.put("retryScheduled",!posted&&!already);m.put("alerted",posted||already);return m;}
    private static void finite(Map<String,Object>m,String k,double v){m.put(k,Double.isFinite(v)?v:null);}
}
