package com.ethscalper.cockpit;

/** Pure CV Core evaluator. Episodes are supplied after base observation, never created by a route. */
public final class CvCoreEngine {
    private final CvCoreMovementRegistry registry;
    public CvCoreEngine(CvCoreMovementRegistry registry){this.registry=registry;}
    public Result observeRaw(SignalDecision decision,MarketSnapshot snapshot,CvCoreContextTracker.Metrics metrics,
                             Common common,long now,CvCoreMovementRegistry.Episode episode){
        CvCoreObservation o=new CvCoreObservation(CvCorePolicy.RAW,decision==null?"":decision.family,"",
                decision==null?MarketProfile.ETH_SYMBOL:decision.symbol,decision==null?"":decision.side,now,
                common==null?-1:common.ethQuoteAgeMs,common==null?-1:common.btcQuoteAgeMs,common==null?-1:common.solQuoteAgeMs,
                Double.NaN,snapshot,metrics,common);CvCorePolicy.Route r=CvCorePolicy.selectRaw(decision,metrics);
        return r==null?Result.none(o):evaluate(r,decision.side,o,snapshot,common,now,episode);}
    public Result observeLegacy(String side,String family,String sleeve,double move3Norm,MarketSnapshot snapshot,
                                CvCoreContextTracker.Metrics metrics,Common common,long now,CvCoreMovementRegistry.Episode episode){
        CvCoreObservation o=new CvCoreObservation(CvCorePolicy.LEGACY_CONFIRMATION,family,sleeve,MarketProfile.ETH_SYMBOL,side,now,
                common==null?-1:common.ethQuoteAgeMs,common==null?-1:common.btcQuoteAgeMs,common==null?-1:common.solQuoteAgeMs,
                move3Norm,snapshot,metrics,common);CvCorePolicy.Route r=CvCorePolicy.selectLegacy(MarketProfile.ETH_SYMBOL,side,family,sleeve,move3Norm,metrics);
        return r==null?Result.none(o):evaluate(r,side,o,snapshot,common,now,episode);}
    private Result evaluate(CvCorePolicy.Route route,String side,CvCoreObservation o,MarketSnapshot s,Common c,long now,CvCoreMovementRegistry.Episode e){
        if(e==null)return Result.rejected(route,e,"CV_CORE_CONTEXT_INVALID",o);if(c==null)return Result.rejected(route,e,"CV_CORE_CONTEXT_INVALID",o);
        if(!c.ethFresh)return Result.rejected(route,e,"CV_CORE_ETH_FEED_STALE",o);if(!c.btcFresh)return Result.rejected(route,e,"CV_CORE_BTC_FEED_STALE",o);
        if(!c.solFresh)return Result.rejected(route,e,"CV_CORE_SOL_FEED_STALE",o);if(c.publicPlanActive||c.cvPlanActive)return Result.rejected(route,e,"CV_CORE_PUBLIC_PLAN_ACTIVE",o);
        if(e.opened)return Result.rejected(route,e,"CV_CORE_DUPLICATE_EPISODE",o);if(s==null||!Double.isFinite(s.marketBid)||!Double.isFinite(s.marketAsk))return Result.rejected(route,e,"CV_CORE_INVALID_QUOTE",o);
        double a=Math.max(.35,s.avgRange20);if(!Double.isFinite(a)||a<=0)return Result.rejected(route,e,"CV_CORE_CONTEXT_INVALID",o);
        CvCorePlan.BuildResult built=CvCorePlan.build(route,e.episodeId,side,o.sourceType,now,s.marketBid,s.marketAsk,a,o);
        return built.accepted()?new Result(route,e,built.plan,"",o):Result.rejected(route,e,built.reasonCode,o);}
    public boolean markOpened(Result r){return r!=null&&r.accepted()&&registry.markOpened(r.episode.episodeId,r.route.routeId);}
    public void reset(){registry.reset();}public int rememberedEpisodes(){return registry.size();}
    public static final class Common{public final boolean ethFresh,btcFresh,solFresh,publicPlanActive,cvPlanActive;public final long ethQuoteAgeMs,btcQuoteAgeMs,solQuoteAgeMs;
        public Common(boolean eth,boolean btc,boolean sol,boolean pub,boolean cv,long ethAge,long btcAge,long solAge){ethFresh=eth;btcFresh=btc;solFresh=sol;publicPlanActive=pub;cvPlanActive=cv;ethQuoteAgeMs=ethAge;btcQuoteAgeMs=btcAge;solQuoteAgeMs=solAge;}}
    public static final class Result{public final CvCorePolicy.Route route;public final CvCoreMovementRegistry.Episode episode;public final CvCorePlan plan;public final String reasonCode;public final CvCoreObservation observation;
        private Result(CvCorePolicy.Route r,CvCoreMovementRegistry.Episode e,CvCorePlan p,String reason,CvCoreObservation o){route=r;episode=e;plan=p;reasonCode=reason;observation=o;}
        static Result none(CvCoreObservation o){return new Result(null,null,null,"",o);}static Result rejected(CvCorePolicy.Route r,CvCoreMovementRegistry.Episode e,String reason,CvCoreObservation o){return new Result(r,e,null,reason,o);}
        public boolean matched(){return route!=null;}public boolean accepted(){return plan!=null;}}
}
