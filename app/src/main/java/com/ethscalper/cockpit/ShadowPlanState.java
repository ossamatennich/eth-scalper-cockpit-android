package com.ethscalper.cockpit;

/** Immutable shadow plan. It is deliberately unrelated to ActivePlanState. */
public final class ShadowPlanState {
    public final String shadowPlanId,component,candidateSignature,symbol,asset,profileVersion,side,sleeve;
    public final long sourceCandidateCreatedAt,openedAt;
    public final double entry,tp,sl,stopDistance,targetDistance;
    public final int quantity,qualityCap;
    public final double riskBudgetUsdt,estimatedRoundTripCostPerUnit;

    public ShadowPlanState(String id,String component,String signature,MarketProfile profile,
                           String side,String sleeve,long candidateCreatedAt,long openedAt,
                           double entry,double tp,double sl,double stopDistance,
                           double targetDistance,int quantity,int qualityCap,double riskBudget,
                           double cost) {
        shadowPlanId=id;this.component=component;candidateSignature=signature;
        symbol=profile.symbol;asset=profile.asset;profileVersion=profile.profileVersion;
        this.side=side;this.sleeve=sleeve;sourceCandidateCreatedAt=candidateCreatedAt;
        this.openedAt=openedAt;this.entry=entry;this.tp=tp;this.sl=sl;
        this.stopDistance=stopDistance;this.targetDistance=targetDistance;
        this.quantity=quantity;this.qualityCap=qualityCap;riskBudgetUsdt=riskBudget;
        estimatedRoundTripCostPerUnit=cost;
    }

    public Terminal observe(long now,double bid,double ask) {
        double quote="LONG".equals(side)?bid:ask;
        if(!Double.isFinite(quote)||quote<=0)return null;
        boolean tpTouched="LONG".equals(side)?quote>=tp:quote<=tp;
        boolean slTouched="LONG".equals(side)?quote<=sl:quote>=sl;
        if(!tpTouched&&!slTouched)return null;
        String status=slTouched?"SHADOW_SL_TOUCHED":"SHADOW_TP_TOUCHED";
        double gross=("LONG".equals(side)?quote-entry:entry-quote)*quantity;
        double fees=estimatedRoundTripCostPerUnit*quantity;
        double net=gross-fees;
        return new Terminal(status,now,quote,gross,fees,net,
                stopDistance>0?net/(stopDistance*quantity):Double.NaN);
    }

    public static final class Terminal {
        public final String status;public final long at;public final double exitQuote;
        public final double grossResultUsdt,estimatedFeesUsdt,netResultUsdt,resultR;
        private Terminal(String status,long at,double quote,double gross,double fees,double net,double r){
            this.status=status;this.at=at;exitQuote=quote;grossResultUsdt=gross;
            estimatedFeesUsdt=fees;netResultUsdt=net;resultR=r;
        }
    }
}
