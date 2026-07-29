package com.ethscalper.cockpit;

import org.json.JSONObject;

/** Maps the canonical ActivePlanState/status representation into the UI contract. */
public final class PlanUiMapper {
    private PlanUiMapper(){}
    public static PlanUiModel from(JSONObject plan,JSONObject market,long now){
        String symbol=plan.optString("symbol","");
        String asset=plan.optString("asset",symbol.replace("USDT",""));
        double last=market==null?number(plan,"lastPrice"):number(market,"last");
        double bid=market==null?number(plan,"lastBid"):number(market,"bid");
        double ask=market==null?number(plan,"lastAsk"):number(market,"ask");
        long feedAgeMs=market==null?-1:market.optLong("feedAgeSec",-1)*1000L;
        int leverage="ETHUSDT".equals(symbol)?5:"SOLUSDT".equals(symbol)?2:1;
        return new PlanUiModel(symbol,asset,plan.optString("side",""),
                plan.optString("family",""),plan.optString("sleeve",plan.optString("family","")),
                plan.optString("status","ACTIVE"),market==null?"—":market.optString("state","—"),
                "",plan.optInt("score",-1),quantity(plan),leverage,
                number(plan,"entry"),number(plan,"takeProfit","tp"),
                number(plan,"stopLoss","sl"),last,bid,ask,
                number(plan,"resultCostPerUnit"),number(plan,"riskAllowancePerUnit"),
                number(plan,"qualityRiskBudget","riskBudgetUsdt"),
                plan.optLong("finalConfirmedAt",plan.optLong("confirmedAt",-1)),now,feedAgeMs);
    }
    private static int quantity(JSONObject o){return o.has("quantity")?o.optInt("quantity",-1):o.optInt("qty",-1);}
    private static double number(JSONObject o,String... keys){for(String key:keys)if(o.has(key)){
        double v=o.optDouble(key,Double.NaN);if(Double.isFinite(v))return v;}return Double.NaN;}
}
