package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure JSON-safe action-event detail builder using observation-time values. */
public final class ScalpActionTelemetry {
    private ScalpActionTelemetry(){}

    public static Map<String,Object> details(ScalpActionEngine.Result result,ScalpActionPlan plan,long eventAt){
        Map<String,Object> d=new LinkedHashMap<>();d.put("engineId",ScalpActionPolicy.ENGINE_ID);
        d.put("policyId",ScalpActionPolicy.POLICY_ID);d.put("schema",ScalpActionPolicy.SCHEMA_ID);
        if(result!=null&&result.route!=null){d.put("routeId",result.route.routeId);
            d.put("routePriority",result.route.priority);d.put("episodeId",result.episode.episodeId);
            d.put("duplicateCount",result.episode.duplicateCount);}
        ScalpActionObservation o=result!=null?result.observation:plan==null?null:plan.observation;
        if(o!=null){d.put("observedAt",o.observedAt);d.put("sourceType",o.sourceType);
            d.put("sourceFamily",o.sourceFamily);d.put("sourceSleeve",o.sourceSleeve);
            finite(d,"sg_move3Norm",o.sgMove3Norm);d.put("marketFeedFresh",o.marketFeedFresh);
            d.put("btcFeedFresh",o.btcFeedFresh);d.put("solFeedFresh",o.solFeedFresh);
            d.put("solQuoteAgeMs",o.solQuoteAgeMs);finite(d,"bid",o.bid);finite(d,"ask",o.ask);
            finite(d,"mid",(o.bid+o.ask)/2d);finite(d,"A",o.a);finite(d,"d_range_pos",o.dRangePos);
            finite(d,"rangePosition",o.rangePosition);finite(d,"sourceMove3",o.sourceMove3);
            finite(d,"eth_dret_480",o.ethDret480);finite(d,"sol_rv_30",o.solRv30);
            finite(d,"sol_cov_180",o.solCov180);d.put("publicPlanActive",o.publicPlanActive);
        }else d.put("observedAt",eventAt);
        d.put("persisted",false);d.put("alerted",false);d.put("legacySuppressed",false);
        d.put("entryWindowStatus",plan==null?null:eventAt<=plan.entryValidUntil?"VALIDE":"EXPIRÃ‰E");
        if(plan!=null){d.put("sourceType",plan.sourceType);d.put("side",plan.side);
            d.put("entry",plan.entry);d.put("tp",plan.takeProfit);d.put("sl",plan.stopLoss);
            d.put("targetMultiple",plan.route.targetMultiple);d.put("stopMultiple",plan.route.stopMultiple);
            d.put("targetDistance",plan.targetDistance);d.put("stopDistance",plan.stopDistance);
            d.put("resultCostPerUnit",plan.resultCostPerUnit);d.put("netRewardPerUnit",plan.netRewardPerUnit);
            d.put("netRiskPerUnit",plan.netRiskPerUnit);d.put("quantity",plan.quantity);
            d.put("plannedNetRewardRisk",plan.plannedNetRewardRisk);
            d.put("theoreticalMaximumLoss",plan.theoreticalMaximumLoss);
            d.put("qualificationAt",plan.qualificationAt);d.put("entryValidUntil",plan.entryValidUntil);}
        return d;
    }

    public static Map<String,Object> alert(boolean posted,boolean alreadyAlerted){
        Map<String,Object> out=new LinkedHashMap<>();out.put("posted",posted);
        out.put("alreadyAlerted",alreadyAlerted);
        out.put("retryScheduled",!posted&&!alreadyAlerted);
        out.put("alerted",posted||alreadyAlerted);return out;
    }

    private static void finite(Map<String,Object> out,String key,double value){
        out.put(key,Double.isFinite(value)?value:null);}
}
