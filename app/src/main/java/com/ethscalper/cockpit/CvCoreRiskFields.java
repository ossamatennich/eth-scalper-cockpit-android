package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical fee-inclusive risk fields for CV plans; legacy rendering stays unchanged. */
public final class CvCoreRiskFields {
    private CvCoreRiskFields(){}
    public static Map<String,Object> cv(int quantity,double stop,double budget){double gross=quantity*stop,fees=quantity*CvCorePolicy.RESULT_COST_PER_UNIT,total=gross+fees;
        Map<String,Object> out=new LinkedHashMap<>();out.put("resultCostPerUnit",CvCorePolicy.RESULT_COST_PER_UNIT);out.put("riskAllowancePerUnit",0d);
        out.put("qualityRiskBudget",budget);out.put("grossLossAtSl",gross);out.put("estimatedRoundTripFees",fees);out.put("estimatedTotalLossAtSl",total);
        out.put("theoreticalMaximumLoss",total);out.put("modeledRiskUsdt",total);out.put("riskBudgetIncludingFees",budget);return out;}
    public static Map<String,Object> legacy(int quantity,double stop,double cost,double allowance,double qualityBudget){double gross=quantity*stop,fees=quantity*cost;
        Map<String,Object> out=new LinkedHashMap<>();out.put("resultCostPerUnit",cost);out.put("riskAllowancePerUnit",allowance);out.put("qualityRiskBudget",qualityBudget);
        out.put("theoreticalMaximumLoss",gross);out.put("grossLossAtSl",gross);out.put("estimatedRoundTripFees",fees);out.put("estimatedTotalLossAtSl",gross+fees);
        out.put("riskBudgetExcludingFees",DynamicTradePlan.GROSS_RISK_BUDGET_USDT);out.put("modeledRiskUsdt",gross);return out;}
}
