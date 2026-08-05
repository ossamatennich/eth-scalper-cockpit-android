package com.ethscalper.cockpit;

/** Pure coordinator: route selection, common gates, episode deduplication and plan creation. */
public final class ScalpActionEngine {
    public static final String ACTION_ON="ACTION_ON";
    public static final String DIAGNOSTICS_ONLY="DIAGNOSTICS_ONLY";
    private final ScalpActionMovementRegistry registry;

    public ScalpActionEngine(ScalpActionMovementRegistry registry){this.registry=registry;}

    public Result observeRaw(SignalDecision decision,MarketSnapshot snapshot,
                             ScalpActionContextTracker.Metrics metrics,Common common,long now){
        ScalpActionPolicy.Route route=ScalpActionPolicy.selectRaw(decision,snapshot,metrics);
        return route==null?Result.none():evaluate(route,decision.side,ScalpActionPolicy.RAW,
                snapshot,common,now);
    }

    public Result observeLegacy(String side,String sleeve,double sgMove3Norm,MarketSnapshot snapshot,
                                ScalpActionContextTracker.Metrics metrics,Common common,long now){
        ScalpActionPolicy.Route route=ScalpActionPolicy.selectLegacy(MarketProfile.ETH_SYMBOL,
                side,sleeve,sgMove3Norm,metrics);
        return route==null?Result.none():evaluate(route,side,ScalpActionPolicy.LEGACY_CONFIRMATION,
                snapshot,common,now);
    }

    private Result evaluate(ScalpActionPolicy.Route route,String side,String source,
                            MarketSnapshot s,Common c,long now){
        ScalpActionMovementRegistry.Episode e=registry.observe(MarketProfile.ETH_SYMBOL,side,now);
        if(!c.ethFresh)return Result.rejected(route,e,"SCALP_ACTION_ETH_FEED_STALE",false);
        if(!c.btcFresh)return Result.rejected(route,e,"SCALP_ACTION_BTC_FEED_STALE",false);
        if(!c.solFresh)return Result.rejected(route,e,"SCALP_ACTION_SOL_FEED_STALE",false);
        if(c.publicPlanActive||c.actionPlanActive)return Result.rejected(route,e,"SCALP_ACTION_PUBLIC_PLAN_ACTIVE",false);
        if(e.opened)return Result.rejected(route,e,"SCALP_ACTION_DUPLICATE_EPISODE",false);
        if(s==null||!Double.isFinite(s.marketBid)||!Double.isFinite(s.marketAsk))return Result.rejected(route,e,"SCALP_ACTION_INVALID_QUOTE",false);
        double a=Math.max(.35,s.avgRange20);if(!Double.isFinite(a))return Result.rejected(route,e,"SCALP_ACTION_CONTEXT_INVALID",false);
        ScalpActionPlan.BuildResult built=ScalpActionPlan.build(route,e.episodeId,side,source,now,
                s.marketBid,s.marketAsk,a);
        if(!built.accepted())return Result.rejected(route,e,built.reasonCode,false);
        boolean virtual=!ACTION_ON.equals(c.mode);
        return new Result(route,e,built.plan,virtual?"SCALP_ACTION_MODE_DIAGNOSTICS_ONLY":"",virtual);
    }

    public boolean markOpened(Result r){return r!=null&&r.plan!=null&&registry.markOpened(r.episode.episodeId,r.route.routeId);}
    public void reset(){registry.reset();}
    public int rememberedEpisodes(){return registry.size();}

    public static final class Common {public final String mode;public final boolean ethFresh,btcFresh,solFresh,publicPlanActive,actionPlanActive;
        public Common(String mode,boolean eth,boolean btc,boolean sol,boolean pub,boolean action){this.mode=mode;ethFresh=eth;btcFresh=btc;solFresh=sol;publicPlanActive=pub;actionPlanActive=action;}}
    public static final class Result {public final ScalpActionPolicy.Route route;public final ScalpActionMovementRegistry.Episode episode;
        public final ScalpActionPlan plan;public final String reasonCode;public final boolean virtualQualified;
        private Result(ScalpActionPolicy.Route r,ScalpActionMovementRegistry.Episode e,ScalpActionPlan p,String reason,boolean v){route=r;episode=e;plan=p;reasonCode=reason;virtualQualified=v;}
        static Result none(){return new Result(null,null,null,"",false);}static Result rejected(ScalpActionPolicy.Route r,ScalpActionMovementRegistry.Episode e,String reason,boolean v){return new Result(r,e,null,reason,v);}
        public boolean matched(){return route!=null;}public boolean accepted(){return plan!=null;}}
}
