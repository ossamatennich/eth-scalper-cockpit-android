package com.ethscalper.cockpit;

/** Pure formatter shared by the diagnostic ZIP and its JVM lifecycle tests. */
public final class MarketSummaryText {
    private MarketSummaryText() {}
    public static String format(long events,long frames,long confirmed,long restored,long tp,long sl) {
        return "Événements : "+Math.max(0,events)
                +"\nFrames : "+Math.max(0,frames)
                +"\nTrades confirmés : "+Math.max(0,confirmed)
                +"\nTP : "+Math.max(0,tp)
                +"\nSL : "+Math.max(0,sl)
                +"\nPlans restaurés : "+Math.max(0,restored);
    }
}
