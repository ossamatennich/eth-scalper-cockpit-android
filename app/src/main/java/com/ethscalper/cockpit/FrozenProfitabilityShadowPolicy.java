package com.ethscalper.cockpit;

import java.util.Locale;

/** Immutable v2.34.4.7 holdout policy. It has no public-engine side effects. */
public final class FrozenProfitabilityShadowPolicy {
    public static final String POLICY_VERSION="SHADOW_V23447_20260804";
    public static final String SCHEMA_VERSION="SHADOW_SCHEMA_V8";
    public static final String PROTOCOL_ID="FROZEN_PROFITABILITY_SHADOW_V1_20260804";
    public static final String PROTOCOL_SCHEMA="FROZEN_PROFITABILITY_SCHEMA_V1";
    public static final String HISTORICAL_CORPUS_ID="NMC_RELAIS_COMPLET_20260804_DEDUP_113241";
    public static final long HISTORICAL_FRAMES=113_241L;
    public static final double HISTORICAL_MARKET_HOURS=84.55;
    public static final boolean FUTURE_HOLDOUT_ONLY=true;
    public static final boolean PUBLIC_ACTIVATION_ALLOWED=false;
    public static final boolean AUTOMATIC_PROMOTION_ALLOWED=false;

    public static final String ETH_RANGE="ETH_RANGE_HIGH_VOLATILITY_V1";
    public static final String SOL_OPPORTUNITY="SOL_CONTINUATION_ACCEL38_V1";
    public static final String SOL_CANONICAL="SOL_CONTINUATION_ACCEL38_CANONICAL_V1";
    public static final String SOL_ROBUST="SOL_CONTINUATION_ACCEL38_ROBUST_V1";
    public static final double ETH_A_THRESHOLD=2.18175;
    public static final double SOL_A_THRESHOLD=.05775;
    public static final double SOL_ACCEL_THRESHOLD=.335624;
    public static final long COOLDOWN_MS=180_000L;
    public static final int MOVEMENT_CAPACITY_PER_SYMBOL=160;

    private FrozenProfitabilityShadowPolicy() {}

    public static Evaluation evaluate(MarketProfile profile,SignalDecision decision,
                                      MarketSnapshot snapshot) {
        if(profile==null||decision==null||snapshot==null||!decision.isSignal())
            return Evaluation.rejected("FROZEN_NOT_A_SIGNAL","",Double.NaN,Double.NaN);
        String family=upper(decision.family);
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate(profile,
                decision.side,decision,snapshot,0.0);
        double minimum=profile.scaledMinimum(profile.aMinimumReference,decision.entry);
        double a=Double.isFinite(snapshot.avgRange20)&&snapshot.avgRange20>0&&Double.isFinite(minimum)
                ?Math.max(minimum,snapshot.avgRange20):Double.NaN;
        int direction="LONG".equals(decision.side)?1:"SHORT".equals(decision.side)?-1:0;
        double m3=metrics.valid?metrics.m3:direction!=0&&Double.isFinite(a)&&a>0
                ?direction*snapshot.move3/a:Double.NaN;
        double m8=metrics.valid?metrics.m8:direction!=0&&Double.isFinite(a)&&a>0
                ?direction*snapshot.move8/a:Double.NaN;
        double accel=Double.isFinite(m3)&&Double.isFinite(m8)?m3/3.0-m8/8.0:Double.NaN;
        if(MarketProfile.ETH_SYMBOL.equals(profile.symbol)) {
            if(!family.contains("RANGE"))return Evaluation.rejected("FROZEN_ETH_FAMILY_NOT_RANGE",ETH_RANGE,a,accel);
            if(!Double.isFinite(a)||!(a>ETH_A_THRESHOLD))
                return Evaluation.nearMiss("FROZEN_ETH_A_NOT_ABOVE_THRESHOLD",ETH_RANGE,a,accel);
            return Evaluation.qualified(ETH_RANGE,a,accel);
        }
        if(MarketProfile.SOL_SYMBOL.equals(profile.symbol)) {
            if(!family.contains("CONTINUATION"))return Evaluation.rejected("FROZEN_SOL_FAMILY_NOT_CONTINUATION",SOL_OPPORTUNITY,a,accel);
            if(!Double.isFinite(a)||!(a>SOL_A_THRESHOLD))
                return Evaluation.nearMiss("FROZEN_SOL_A_NOT_ABOVE_THRESHOLD",SOL_OPPORTUNITY,a,accel);
            if(!Double.isFinite(accel)||!(accel>SOL_ACCEL_THRESHOLD))
                return Evaluation.nearMiss("FROZEN_SOL_ACCEL38_NOT_ABOVE_THRESHOLD",SOL_OPPORTUNITY,a,accel);
            return Evaluation.qualified(SOL_OPPORTUNITY,a,accel);
        }
        return Evaluation.rejected("FROZEN_UNSUPPORTED_SYMBOL","",a,accel);
    }

    public static String sensitivityBucket(MarketProfile profile,SignalDecision signal,
                                           double a,double accel) {
        if(profile==null||signal==null)return "";
        String family=upper(signal.family);
        if(MarketProfile.ETH_SYMBOL.equals(profile.symbol)&&family.contains("RANGE")) {
            if(!Double.isFinite(a))return "ETH_A_INVALID";
            if(a<1.75)return "ETH_A_LT_1_75";
            if(a<2.00)return "ETH_A_1_75_TO_LT_2_00";
            if(a<=ETH_A_THRESHOLD)return "ETH_A_2_00_TO_2_18175";
            if(a<2.35)return "ETH_A_GT_2_18175_TO_LT_2_35";
            if(a<2.50)return "ETH_A_2_35_TO_LT_2_50";
            if(a<2.75)return "ETH_A_2_50_TO_LT_2_75";
            return "ETH_A_GE_2_75";
        }
        if(MarketProfile.SOL_SYMBOL.equals(profile.symbol)&&family.contains("CONTINUATION")) {
            String ab=!Double.isFinite(a)?"A_INVALID":a<.052?"A_LT_0_052":a<=SOL_A_THRESHOLD?
                    "A_0_052_TO_0_05775":a<.065?"A_GT_0_05775_TO_LT_0_065":"A_GE_0_065";
            String xb=!Double.isFinite(accel)?"ACCEL_INVALID":accel<.30?"ACCEL_LT_0_30":
                    accel<=SOL_ACCEL_THRESHOLD?"ACCEL_0_30_TO_0_335624":
                            accel<.42?"ACCEL_GT_0_335624_TO_LT_0_42":"ACCEL_GE_0_42";
            return "SOL_"+ab+"|"+xb;
        }
        return "";
    }

    static String familyGroup(String family) {
        String value=upper(family);
        if(value.contains("RANGE"))return "RANGE";
        if(value.contains("CONTINUATION"))return "CONTINUATION";
        return value;
    }
    private static String upper(String value){return value==null?"":value.toUpperCase(Locale.ROOT);}

    public static final class Evaluation {
        public final boolean qualified,nearMiss;
        public final String reasonCode,component;
        public final double a,accel38Directional;
        private Evaluation(boolean qualified,boolean nearMiss,String reason,String component,double a,double accel){
            this.qualified=qualified;this.nearMiss=nearMiss;this.reasonCode=reason;
            this.component=component;this.a=a;this.accel38Directional=accel;
        }
        static Evaluation qualified(String c,double a,double x){return new Evaluation(true,false,"FROZEN_POLICY_QUALIFIED",c,a,x);}
        static Evaluation nearMiss(String r,String c,double a,double x){return new Evaluation(false,true,r,c,a,x);}
        static Evaluation rejected(String r,String c,double a,double x){return new Evaluation(false,false,r,c,a,x);}
    }
}
