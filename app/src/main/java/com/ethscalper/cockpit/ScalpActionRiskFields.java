package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical fee-inclusive risk fields for a public Scalp Action plan. */
public final class ScalpActionRiskFields {
    private ScalpActionRiskFields(){}
    public static Map<String,Object> action(int quantity,double stopDistance){
        double cost=MarketProfile.eth().resultRoundTripCostReference;
        double gross=quantity*stopDistance,fees=quantity*cost,total=gross+fees;
        Map<String,Object> out=new LinkedHashMap<>();out.put("resultCostPerUnit",cost);
        out.put("riskAllowancePerUnit",0d);out.put("qualityRiskBudget",MarketProfile.eth().finalRiskBudgetUsdt);
        out.put("grossLossAtSl",gross);out.put("estimatedRoundTripFees",fees);
        out.put("estimatedTotalLossAtSl",total);out.put("theoreticalMaximumLoss",total);
        out.put("modeledRiskUsdt",total);out.put("riskBudgetIncludingFees",MarketProfile.eth().finalRiskBudgetUsdt);
        return out;
    }

    public static Map<String,Object> legacy(int quantity,double stopDistance,double cost,
                                            double allowance,double qualityBudget){
        double gross=quantity*stopDistance,fees=quantity*cost;
        Map<String,Object> out=new LinkedHashMap<>();out.put("resultCostPerUnit",cost);
        out.put("riskAllowancePerUnit",allowance);out.put("qualityRiskBudget",qualityBudget);
        out.put("theoreticalMaximumLoss",gross);out.put("grossLossAtSl",gross);
        out.put("estimatedRoundTripFees",fees);out.put("estimatedTotalLossAtSl",gross+fees);
        out.put("riskBudgetExcludingFees",DynamicTradePlan.GROSS_RISK_BUDGET_USDT);
        out.put("modeledRiskUsdt",gross);return out;
    }
}
