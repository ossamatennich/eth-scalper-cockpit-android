package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable frozen plan. Terminal accounting always fills at the planned TP or SL. */
public final class FrozenProfitabilityShadowPlan {
    public final String opportunityId,branchId,component,movementSignature,signatureMode;
    public final String symbol,side,family;
    public final long sourceObservedAt,openedAt;
    public final double a,entry,tp,sl,targetMultipleA,stopMultipleA;
    public final double roundedTargetDistance,roundedStopDistance,quantity,cost;
    public final double grossTargetUsdt,grossStopUsdt,estimatedFeesUsdt;
    public final double plannedNetTargetUsdt,plannedNetStopUsdt,plannedNetRewardRisk;
    public final boolean legacyShadowOverlap;
    private Terminal terminal;
    private boolean publicOverlap;

    private FrozenProfitabilityShadowPlan(String opportunityId,String branchId,String component,
            String movementSignature,String signatureMode,MarketProfile profile,SignalDecision source,
            long now,double a,double entry,double tp,double sl,double targetMultiple,
            double stopMultiple,double quantity,double cost,boolean legacyOverlap) {
        this.opportunityId=opportunityId;this.branchId=branchId;this.component=component;
        this.movementSignature=movementSignature;this.signatureMode=signatureMode;
        this.symbol=profile.symbol;this.side=source.side;this.family=source.family;
        this.sourceObservedAt=now;this.openedAt=now;this.a=a;this.entry=entry;this.tp=tp;this.sl=sl;
        this.targetMultipleA=targetMultiple;this.stopMultipleA=stopMultiple;
        roundedTargetDistance=Math.abs(tp-entry);roundedStopDistance=Math.abs(entry-sl);
        this.quantity=quantity;this.cost=cost;this.legacyShadowOverlap=legacyOverlap;
        grossTargetUsdt=roundedTargetDistance*quantity;grossStopUsdt=roundedStopDistance*quantity;
        estimatedFeesUsdt=cost*quantity;plannedNetTargetUsdt=(roundedTargetDistance-cost)*quantity;
        plannedNetStopUsdt=(roundedStopDistance+cost)*quantity;
        plannedNetRewardRisk=plannedNetStopUsdt>0?plannedNetTargetUsdt/plannedNetStopUsdt:Double.NaN;
    }

    public static BuildResult build(MarketProfile profile,SignalDecision source,long now,double a,
            String opportunityId,String branchId,String component,String movementSignature,
            String signatureMode,double targetMultiple,double stopMultiple,double bid,double ask,
            boolean legacyOverlap) {
        if(profile==null||source==null||!Double.isFinite(a)||a<=0||!positive(bid)||!positive(ask))
            return BuildResult.invalid("FROZEN_INVALID_GEOMETRY_INPUT");
        boolean longSide="LONG".equals(source.side),shortSide="SHORT".equals(source.side);
        if(!longSide&&!shortSide)return BuildResult.invalid("FROZEN_INVALID_SIDE");
        double entry=longSide?profile.ceilToTick(ask):profile.floorToTick(bid);
        double tp=longSide?profile.floorToTick(entry+targetMultiple*a)
                :profile.ceilToTick(entry-targetMultiple*a);
        double sl=longSide?profile.floorToTick(entry-stopMultiple*a)
                :profile.ceilToTick(entry+stopMultiple*a);
        double target=Math.abs(tp-entry),stop=Math.abs(entry-sl);
        double cost=profile.scaledMinimum(profile.resultRoundTripCostReference,entry);
        if(!positive(entry)||!positive(tp)||!positive(sl)||!positive(target)||!positive(stop)||!positive(cost))
            return BuildResult.invalid("FROZEN_INVALID_ROUNDED_GEOMETRY");
        double riskPerUnit=stop+cost;
        int raw=(int)Math.floor((profile.finalRiskBudgetUsdt+1e-12)/riskPerUnit);
        int stepped=(raw/profile.quantityStep)*profile.quantityStep;
        int quantity=Math.min(profile.maximumQuantity,stepped);
        quantity=(quantity/profile.quantityStep)*profile.quantityStep;
        if(quantity<profile.minimumQuantity)return BuildResult.invalid("FROZEN_FEE_AWARE_QUANTITY_UNAVAILABLE");
        FrozenProfitabilityShadowPlan plan=new FrozenProfitabilityShadowPlan(opportunityId,branchId,
                component,movementSignature,signatureMode,profile,source,now,a,entry,tp,sl,
                targetMultiple,stopMultiple,quantity,cost,legacyOverlap);
        return new BuildResult(plan,"FROZEN_PLAN_VALID");
    }

    public synchronized Terminal observe(long now,double bid,double ask,boolean marketFresh) {
        if(terminal!=null||!marketFresh||!positive(bid)||!positive(ask))return null;
        double quote="LONG".equals(side)?bid:ask;
        boolean tpTouched="LONG".equals(side)?quote>=tp:quote<=tp;
        boolean slTouched="LONG".equals(side)?quote<=sl:quote>=sl;
        return terminal(now,quote,tpTouched,slTouched);
    }

    synchronized Terminal terminal(long now,double touchQuote,boolean tpTouched,boolean slTouched) {
        if(terminal!=null||(!tpTouched&&!slTouched)||!positive(touchQuote))return null;
        boolean stopped=slTouched;String status=stopped?"SL_TOUCHED":"TP_TOUCHED";
        double fill=stopped?sl:tp;
        double grossPerUnit=stopped?-roundedStopDistance:roundedTargetDistance;
        double netPerUnit=stopped?-(roundedStopDistance+cost):roundedTargetDistance-cost;
        double resultR=stopped?-1.0:netPerUnit/(roundedStopDistance+cost);
        terminal=new Terminal(status,now,Math.max(0,now-openedAt),touchQuote,fill,
                grossPerUnit*quantity,estimatedFeesUsdt,netPerUnit*quantity,resultR);
        return terminal;
    }
    public synchronized boolean terminal(){return terminal!=null;}
    public synchronized Terminal terminalValue(){return terminal;}
    public synchronized void markPublicOverlap(){publicOverlap=true;}
    public synchronized boolean publicOverlap(){return publicOverlap;}

    public Map<String,Object> details(){
        LinkedHashMap<String,Object> d=new LinkedHashMap<>();
        d.put("opportunityId",opportunityId);d.put("branchId",branchId);d.put("component",component);
        d.put("movementSignature",movementSignature);d.put("signatureMode",signatureMode);
        d.put("symbol",symbol);d.put("side",side);d.put("family",family);
        d.put("sourceObservedAt",sourceObservedAt);d.put("openedAt",openedAt);d.put("A",a);
        d.put("entry",entry);d.put("tp",tp);d.put("sl",sl);d.put("targetMultipleA",targetMultipleA);
        d.put("stopMultipleA",stopMultipleA);d.put("roundedTargetDistance",roundedTargetDistance);
        d.put("roundedStopDistance",roundedStopDistance);d.put("quantity",quantity);d.put("cost",cost);
        d.put("riskBudgetUsdt",MarketProfile.ETH_SYMBOL.equals(symbol)?MarketProfile.eth().finalRiskBudgetUsdt:MarketProfile.sol().finalRiskBudgetUsdt);
        d.put("estimatedRoundTripCostPerUnit",cost);d.put("feeAwareRiskPerUnit",roundedStopDistance+cost);
        d.put("feeAwareQuantity",quantity);d.put("grossTargetUsdt",grossTargetUsdt);
        d.put("grossStopUsdt",grossStopUsdt);d.put("estimatedFeesUsdt",estimatedFeesUsdt);
        d.put("plannedNetTargetUsdt",plannedNetTargetUsdt);d.put("plannedNetStopUsdt",plannedNetStopUsdt);
        d.put("plannedNetRewardRisk",plannedNetRewardRisk);d.put("legacyShadowOverlap",legacyShadowOverlap);
        d.put("publicOverlap",publicOverlap);return d;
    }
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}

    public static final class BuildResult {
        public final FrozenProfitabilityShadowPlan plan;public final String reasonCode;
        BuildResult(FrozenProfitabilityShadowPlan plan,String reason){this.plan=plan;this.reasonCode=reason;}
        static BuildResult invalid(String r){return new BuildResult(null,r);}
        public boolean valid(){return plan!=null;}
    }
    public static final class Terminal {
        public final String terminalStatus;public final long terminalAt,durationMs;
        public final double touchQuote,fillPrice,grossResultUsdt,estimatedFeesUsdt,netResultUsdt,resultR;
        Terminal(String status,long at,long duration,double touch,double fill,double gross,double fees,double net,double r){
            terminalStatus=status;terminalAt=at;durationMs=duration;touchQuote=touch;fillPrice=fill;
            grossResultUsdt=gross;estimatedFeesUsdt=fees;netResultUsdt=net;resultR=r;
        }
    }
}
