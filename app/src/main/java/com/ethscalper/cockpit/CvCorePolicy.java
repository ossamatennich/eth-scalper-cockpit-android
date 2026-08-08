package com.ethscalper.cockpit;

import java.util.Locale;

/** Frozen CV Core V1 public route selection. Android-free and immutable. */
public final class CvCorePolicy {
    public static final String ENGINE_ID="NMC_SCALP_CV_CORE_V1";
    public static final String POLICY_ID="SCALP_CV_CORE_V1_20260806";
    public static final String SCHEMA_ID="SCALP_CV_SCHEMA_V1";
    public static final String VERSION_NAME="2.34.5.0";
    public static final String RAW="RAW",LEGACY_CONFIRMATION="LEGACY_CONFIRMATION";
    public static final double RESULT_COST_PER_UNIT=1.43;

    public static final Route DUAL_EXHAUSTION_SHORT=new Route(
            "ETH_RANGE_DUAL_EXHAUSTION_SHORT_V1",1,4.0,1.75,14.55,96,
            "CV_CORE_DUAL_EXHAUSTION_SHORT");
    public static final Route CAPITULATION_LONG=new Route(
            "ETH_CAPITULATION_LONG_V1",2,2.5,1.5,14.55,94,
            "CV_CORE_CAPITULATION_LONG");
    public static final Route P02_BALANCED_SHORT=new Route(
            "ETH_P02_CONFIRMED_BALANCED_SHORT_V1",3,3.0,1.25,7.275,90,
            "CV_CORE_P02_BALANCED_SHORT");
    private static final Route[] ROUTES={DUAL_EXHAUSTION_SHORT,CAPITULATION_LONG,P02_BALANCED_SHORT};

    private CvCorePolicy(){}

    public static Route selectRaw(SignalDecision decision,CvCoreContextTracker.Metrics metrics){
        if(!eligibleEthSignal(decision)||metrics==null)return null;
        String family=text(decision.family).toUpperCase(Locale.US);
        if(!family.contains("RANGE_FADE"))return null;
        if("SHORT".equals(decision.side)&&metrics.directionalSolReturn60Valid
                &&metrics.directionalEthReturn60Valid&&metrics.directionalEthEfficiency60Valid
                &&metrics.directionalSolReturn60<=-0.00030
                &&metrics.directionalEthReturn60<=-0.00035
                &&metrics.directionalEthEfficiency60>-0.40)return DUAL_EXHAUSTION_SHORT;
        if("LONG".equals(decision.side)&&metrics.directionalEthReturn60Valid
                &&Double.isFinite(metrics.directionalBtcMove8)
                &&metrics.directionalBtcMove8<=-0.0016
                &&metrics.directionalEthReturn60<=-0.0010)return CAPITULATION_LONG;
        return null;
    }

    public static Route selectLegacy(String symbol,String side,String sourceFamily,String sleeve,
                                     double directionalMove3Norm,
                                     CvCoreContextTracker.Metrics metrics){
        if(!MarketProfile.ETH_SYMBOL.equals(symbol)||!"SHORT".equals(side)
                ||!CandidateLifecycle.SLEEVE_P02.equals(sleeve)
                ||!text(sourceFamily).toUpperCase(Locale.US).contains("CONTINUATION")
                ||metrics==null||!Double.isFinite(directionalMove3Norm)
                ||!Double.isFinite(metrics.directionalBtcMove3)
                ||!metrics.directionalSolEfficiency30Valid)return null;
        return metrics.directionalBtcMove3<=0.0002&&directionalMove3Norm<=2.0
                &&metrics.directionalSolEfficiency30>0.0?P02_BALANCED_SHORT:null;
    }

    public static Route route(String id){if(id!=null)for(Route r:ROUTES)if(r.routeId.equals(id))return r;return null;}
    public static boolean known(Route route){return route!=null&&route(route.routeId)==route;}
    private static boolean eligibleEthSignal(SignalDecision d){return d!=null&&d.isSignal()
            &&MarketProfile.ETH_SYMBOL.equals(d.symbol)
            &&("LONG".equals(d.side)||"SHORT".equals(d.side));}
    private static String text(String v){return v==null?"":v;}

    public static final class Route{
        public final String routeId,family,reasonCode;public final int priority,score;
        public final double targetMultiple,stopMultiple,riskBudgetUsdt;
        Route(String id,int priority,double target,double stop,double budget,int score,String reason){
            routeId=id;this.priority=priority;targetMultiple=target;stopMultiple=stop;
            riskBudgetUsdt=budget;this.score=score;reasonCode=reason;family=ENGINE_ID+"/"+id;}
    }
}
