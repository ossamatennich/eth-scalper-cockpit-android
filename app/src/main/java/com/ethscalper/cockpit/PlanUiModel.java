package com.ethscalper.cockpit;

/** Immutable UI contract. Missing mandatory fields are explicit, never replaced by zero. */
public final class PlanUiModel {
    public static final String DATA_INCOMPLETE="PLAN_UI_DATA_INCOMPLETE";
    public final String symbol,asset,side,family,sleeve,status,feedState,reasonCode;
    public final int score,quantity,leverage;
    public final double entry,takeProfit,stopLoss,currentPrice,bid,ask,riskBudgetUsdt;
    public final double volatilityA,adverseExcursion,baseStop,structuralAnchor,structuralBuffer;
    public final double riskPerUnit;
    public final int structuralWindowMinutes,riskQuantity,qualityCap;
    public final String stopCalculationType,stopReasonCode,selectedBudgetReason;
    public final long confirmedAt,ageMs,feedAgeMs;
    public final PlanMetricsCalculator.Result metrics;

    public PlanUiModel(String symbol,String asset,String side,String family,String sleeve,
                       String status,String feedState,String reasonCode,int score,int quantity,
                       int leverage,double entry,double takeProfit,double stopLoss,
                       double currentPrice,double bid,double ask,double resultCostPerUnit,
                       double riskAllowancePerUnit,double riskBudgetUsdt,long confirmedAt,
                       long now,long feedAgeMs){
        this(symbol,asset,side,family,sleeve,status,feedState,reasonCode,score,quantity,leverage,
                entry,takeProfit,stopLoss,currentPrice,bid,ask,resultCostPerUnit,
                riskAllowancePerUnit,riskBudgetUsdt,confirmedAt,now,feedAgeMs,
                Double.NaN,Double.NaN,Double.NaN,Double.NaN,0,Double.NaN,"","","",
                Double.NaN,0,0);
    }

    public PlanUiModel(String symbol,String asset,String side,String family,String sleeve,
                       String status,String feedState,String reasonCode,int score,int quantity,
                       int leverage,double entry,double takeProfit,double stopLoss,
                       double currentPrice,double bid,double ask,double resultCostPerUnit,
                       double riskAllowancePerUnit,double riskBudgetUsdt,long confirmedAt,
                       long now,long feedAgeMs,double volatilityA,double adverseExcursion,
                       double baseStop,double structuralAnchor,int structuralWindowMinutes,
                       double structuralBuffer,String stopCalculationType,String stopReasonCode,
                       String selectedBudgetReason,double riskPerUnit,int riskQuantity,int qualityCap){
        this.symbol=text(symbol);this.asset=text(asset);this.side=text(side);this.family=text(family);
        this.sleeve=text(sleeve);this.status=text(status);this.feedState=text(feedState);
        this.score=score;this.quantity=quantity;this.leverage=leverage;this.entry=entry;
        this.takeProfit=takeProfit;this.stopLoss=stopLoss;this.currentPrice=currentPrice;
        this.bid=bid;this.ask=ask;this.riskBudgetUsdt=riskBudgetUsdt;
        this.confirmedAt=confirmedAt;this.ageMs=confirmedAt>0?Math.max(0,now-confirmedAt):-1;
        this.feedAgeMs=feedAgeMs;
        this.volatilityA=volatilityA;this.adverseExcursion=adverseExcursion;
        this.baseStop=baseStop;this.structuralAnchor=structuralAnchor;
        this.structuralWindowMinutes=structuralWindowMinutes;this.structuralBuffer=structuralBuffer;
        this.stopCalculationType=text(stopCalculationType);this.stopReasonCode=text(stopReasonCode);
        this.selectedBudgetReason=text(selectedBudgetReason);this.riskPerUnit=riskPerUnit;
        this.riskQuantity=riskQuantity;this.qualityCap=qualityCap;
        metrics=PlanMetricsCalculator.calculate(side,quantity,entry,takeProfit,stopLoss,
                currentPrice,bid,ask,resultCostPerUnit,riskAllowancePerUnit,riskBudgetUsdt,leverage);
        reasonCode=metrics.complete?"":DATA_INCOMPLETE;
        this.reasonCode=reasonCode;
    }
    public boolean complete(){return metrics.complete;}
    private static String text(String value){return value==null?"":value;}
}
