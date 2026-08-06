package com.ethscalper.cockpit;

/** Immutable observation-time CV Core telemetry. */
public final class CvCoreObservation {
    public final String sourceType,sourceFamily,sourceSleeve,symbol,side;
    public final long observedAt,ethQuoteAgeMs,btcQuoteAgeMs,solQuoteAgeMs;
    public final double bid,ask,mid,a,directionalMove3Norm;
    public final boolean ethFeedFresh,btcFeedFresh,solFeedFresh,publicPlanActive;
    public final CvCoreContextTracker.Metrics metrics;
    public CvCoreObservation(String source,String family,String sleeve,String symbol,String side,long at,
                             long ethAge,long btcAge,long solAge,double move3Norm,MarketSnapshot snapshot,
                             CvCoreContextTracker.Metrics metrics,CvCoreEngine.Common common){
        sourceType=text(source);sourceFamily=text(family);sourceSleeve=text(sleeve);this.symbol=text(symbol);this.side=text(side);
        observedAt=at;ethQuoteAgeMs=ethAge;btcQuoteAgeMs=btcAge;solQuoteAgeMs=solAge;directionalMove3Norm=move3Norm;
        bid=snapshot==null?Double.NaN:snapshot.marketBid;ask=snapshot==null?Double.NaN:snapshot.marketAsk;
        mid=snapshot==null?Double.NaN:snapshot.marketLast;a=snapshot==null?Double.NaN:Math.max(.35,snapshot.avgRange20);
        this.metrics=metrics;ethFeedFresh=common!=null&&common.ethFresh;btcFeedFresh=common!=null&&common.btcFresh;
        solFeedFresh=common!=null&&common.solFresh;publicPlanActive=common!=null&&(common.publicPlanActive||common.cvPlanActive);
    }
    private static String text(String v){return v==null?"":v;}
}
