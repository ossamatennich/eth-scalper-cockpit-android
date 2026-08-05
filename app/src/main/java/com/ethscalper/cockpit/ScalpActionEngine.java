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
        ScalpActionObservation observation=new ScalpActionObservation(ScalpActionPolicy.RAW,
                decision==null?"":decision.family,"",now,common==null?-1:common.solQuoteAgeMs,
                Double.NaN,snapshot,metrics,common);
        return route==null?Result.none():evaluate(route,decision.side,observation,snapshot,common,now);
    }

    public Result observeLegacy(String side,String sleeve,double sgMove3Norm,MarketSnapshot snapshot,
                                ScalpActionContextTracker.Metrics metrics,Common common,long now){
        return observeLegacy(side,"",sleeve,sgMove3Norm,snapshot,metrics,common,now);
    }

    public Result observeLegacy(String side,String family,String sleeve,double sgMove3Norm,
                                MarketSnapshot snapshot,ScalpActionContextTracker.Metrics metrics,
                                Common common,long now){
        ScalpActionPolicy.Route route=ScalpActionPolicy.selectLegacy(MarketProfile.ETH_SYMBOL,
                side,sleeve,sgMove3Norm,metrics);
        ScalpActionObservation observation=new ScalpActionObservation(
                ScalpActionPolicy.LEGACY_CONFIRMATION,family,sleeve,now,
                common==null?-1:common.solQuoteAgeMs,sgMove3Norm,snapshot,metrics,common);
        return route==null?Result.none():evaluate(route,side,observation,snapshot,common,now);
    }

    private Result evaluate(ScalpActionPolicy.Route route,String side,ScalpActionObservation observation,
                            MarketSnapshot s,Common c,long now){
        ScalpActionMovementRegistry.Episode e=registry.observe(MarketProfile.ETH_SYMBOL,side,now);
        if(!c.ethFresh)return Result.rejected(route,e,"SCALP_ACTION_ETH_FEED_STALE",observation);
        if(!c.btcFresh)return Result.rejected(route,e,"SCALP_ACTION_BTC_FEED_STALE",observation);
        if(!c.solFresh)return Result.rejected(route,e,"SCALP_ACTION_SOL_FEED_STALE",observation);
        if(c.publicPlanActive||c.actionPlanActive)return Result.rejected(route,e,"SCALP_ACTION_PUBLIC_PLAN_ACTIVE",observation);
        if(e.opened)return Result.rejected(route,e,"SCALP_ACTION_DUPLICATE_EPISODE",observation);
        if(s==null||!Double.isFinite(s.marketBid)||!Double.isFinite(s.marketAsk))return Result.rejected(route,e,"SCALP_ACTION_INVALID_QUOTE",observation);
        double a=Math.max(.35,s.avgRange20);if(!Double.isFinite(a))return Result.rejected(route,e,"SCALP_ACTION_CONTEXT_INVALID",observation);
        ScalpActionPlan.BuildResult built=ScalpActionPlan.build(route,e.episodeId,side,
                observation.sourceType,now,s.marketBid,s.marketAsk,a,observation);
        if(!built.accepted())return Result.rejected(route,e,built.reasonCode,observation);
        boolean virtual=!ACTION_ON.equals(c.mode);
        return new Result(route,e,built.plan,virtual?"SCALP_ACTION_MODE_DIAGNOSTICS_ONLY":"",
                virtual,observation);
    }

    public boolean markOpened(Result r){return r!=null&&r.plan!=null&&registry.markOpened(r.episode.episodeId,r.route.routeId);}
    public void reset(){registry.reset();}
    public int rememberedEpisodes(){return registry.size();}

    public static final class Common {public final String mode;public final boolean ethFresh,btcFresh,solFresh,publicPlanActive,actionPlanActive;public final long solQuoteAgeMs;
        public Common(String mode,boolean eth,boolean btc,boolean sol,boolean pub,boolean action){this(mode,eth,btc,sol,pub,action,-1);}
        public Common(String mode,boolean eth,boolean btc,boolean sol,boolean pub,boolean action,long solAge){this.mode=mode;ethFresh=eth;btcFresh=btc;solFresh=sol;publicPlanActive=pub;actionPlanActive=action;solQuoteAgeMs=solAge;}}
    public static final class Result {public final ScalpActionPolicy.Route route;public final ScalpActionMovementRegistry.Episode episode;
        public final ScalpActionPlan plan;public final String reasonCode;public final boolean virtualQualified;public final ScalpActionObservation observation;
        private Result(ScalpActionPolicy.Route r,ScalpActionMovementRegistry.Episode e,ScalpActionPlan p,String reason,boolean v,ScalpActionObservation observation){route=r;episode=e;plan=p;reasonCode=reason;virtualQualified=v;this.observation=observation;}
        static Result none(){return new Result(null,null,null,"",false,null);}static Result rejected(ScalpActionPolicy.Route r,ScalpActionMovementRegistry.Episode e,String reason,ScalpActionObservation observation){return new Result(r,e,null,reason,false,observation);}
        public boolean matched(){return route!=null;}public boolean accepted(){return plan!=null;}}
}
