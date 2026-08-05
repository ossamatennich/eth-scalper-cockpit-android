package com.ethscalper.cockpit;

/** Immutable, observation-time telemetry used by arbitration and later diagnostics. */
public final class ScalpActionObservation {
    public final String sourceType,sourceFamily,sourceSleeve;
    public final long observedAt,solQuoteAgeMs;
    public final double sgMove3Norm,bid,ask,last,a,ethDret480,solRv30,solCov180;
    public final double dRangePos,rangePosition,sourceMove3;
    public final boolean marketFeedFresh,btcFeedFresh,solFeedFresh,publicPlanActive;

    public ScalpActionObservation(String sourceType,String sourceFamily,String sourceSleeve,
                                  long observedAt,long solQuoteAgeMs,double sgMove3Norm,
                                  MarketSnapshot snapshot,ScalpActionContextTracker.Metrics metrics,
                                  ScalpActionEngine.Common common){
        this.sourceType=text(sourceType);this.sourceFamily=text(sourceFamily);
        this.sourceSleeve=text(sourceSleeve);this.observedAt=observedAt;
        this.solQuoteAgeMs=solQuoteAgeMs;this.sgMove3Norm=sgMove3Norm;
        bid=snapshot==null?Double.NaN:snapshot.marketBid;
        ask=snapshot==null?Double.NaN:snapshot.marketAsk;
        last=snapshot==null?Double.NaN:snapshot.marketLast;
        a=snapshot==null?Double.NaN:Math.max(.35,snapshot.avgRange20);
        dRangePos=snapshot==null?Double.NaN:1d-snapshot.rangePosition;
        rangePosition=snapshot==null?Double.NaN:snapshot.rangePosition;
        sourceMove3=snapshot==null?Double.NaN:snapshot.move3;
        ethDret480=metrics==null?Double.NaN:metrics.ethDret480;
        solRv30=metrics==null?Double.NaN:metrics.solRv30;
        solCov180=metrics==null?Double.NaN:metrics.solCov180;
        marketFeedFresh=common!=null&&common.ethFresh;
        btcFeedFresh=common!=null&&common.btcFresh;
        solFeedFresh=common!=null&&common.solFresh;
        publicPlanActive=common!=null&&(common.publicPlanActive||common.actionPlanActive);
    }

    private static String text(String value){return value==null?"":value;}
}
