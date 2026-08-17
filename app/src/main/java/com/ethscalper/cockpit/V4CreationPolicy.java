package com.ethscalper.cockpit;

import java.util.List;

/** Pure guard separating one fresh parent/day from one legitimate CORE continuation. */
public final class V4CreationPolicy {
    public static final String RISK_CAP_REACHED="RISK_CAP_REACHED";
    private V4CreationPolicy(){}
    public static boolean mayCreateFresh(V4FeatureEngine.Candidate candidate,long cutoff,List<V4Plan> active,List<V4Plan> all){
        if(candidate==null||active.size()>=2||hasActiveSymbol(active,candidate.asset))return false;
        for(V4Plan p:all)if(p.parentPlanId==null&&V4FallbackHistory.day(p.dataCutoffUtc)==V4FallbackHistory.day(cutoff))return false;
        return true;}
    /**
     * MODE OFF.
     *
     * Pas de max 2 actifs.
     * Pas de max 1 nouveau signal global par jour.
     *
     * On conserve les protections logiques :
     * - pas de deuxième plan actif sur le même symbole ;
     * - pas de republication du même symbole le même jour UTC.
     */
    public static boolean mayCreateFreshUncapped(
            V4FeatureEngine.Candidate candidate,
            long cutoff,
            List<V4Plan> active,
            List<V4Plan> all
    ){
        if(candidate==null||hasActiveSymbol(active,candidate.asset))return false;
        return !hasFreshForSymbolDay(candidate.asset,cutoff,all);
    }

    public static boolean hasFreshForSymbolDay(
            String symbol,
            long cutoff,
            List<V4Plan> all
    ){
        long day=V4FallbackHistory.day(cutoff);

        for(V4Plan p:all){
            if(p.parentPlanId==null
                    &&p.symbol.equals(symbol)
                    &&V4FallbackHistory.day(p.dataCutoffUtc)==day){
                return true;
            }
        }

        return false;
    }

    public static boolean mayCreateContinuation(V4FeatureEngine.Candidate candidate,V4Plan parent,List<V4Plan> active,List<V4Plan> all,
                                                V4FeatureEngine.Candidate fresh){
        if(candidate==null||parent==null||candidate.source!=V4Plan.Source.CORE||parent.source!=V4Plan.Source.CORE||parent.parentPlanId!=null)return false;
        if(!candidate.asset.equals(parent.symbol)||candidate.side!=parent.side||!V4ContinuationPolicy.mayCreateSecondSegment(parent))return false;
        if(fresh!=null&&fresh.asset.equals(candidate.asset))return false;
        if(active.size()>=2||hasActiveSymbol(active,candidate.asset))return false;
        for(V4Plan p:all)if(parent.planId.equals(p.parentPlanId))return false;
        return true;}
    /**
     * Continuation CORE en mode OFF :
     * mêmes règles V4, sauf plafond numérique global d'actifs.
     */
    public static boolean mayCreateContinuationUncapped(
            V4FeatureEngine.Candidate candidate,
            V4Plan parent,
            List<V4Plan> active,
            List<V4Plan> all,
            V4FeatureEngine.Candidate fresh
    ){
        if(candidate==null
                ||parent==null
                ||candidate.source!=V4Plan.Source.CORE
                ||parent.source!=V4Plan.Source.CORE
                ||parent.parentPlanId!=null)return false;

        if(!candidate.asset.equals(parent.symbol)
                ||candidate.side!=parent.side
                ||!V4ContinuationPolicy.mayCreateSecondSegment(parent))return false;

        if(fresh!=null&&fresh.asset.equals(candidate.asset))return false;

        if(hasActiveSymbol(active,candidate.asset))return false;

        for(V4Plan p:all){
            if(parent.planId.equals(p.parentPlanId))return false;
        }

        return true;
    }

    private static boolean hasActiveSymbol(List<V4Plan> active,String symbol){for(V4Plan p:active)if(p.symbol.equals(symbol))return true;return false;}
    public static void rejectForRiskCap(V4Plan plan,long now){plan.status=V4Plan.Status.CLOSED_OTHER;plan.statusReason=RISK_CAP_REACHED;
        plan.closeReason=RISK_CAP_REACHED;plan.closedAt=now;}
}
