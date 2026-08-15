package com.ethscalper.cockpit;

/** Pure final guard. It validates without changing qualified geometry. */
public final class CvCorePublicationGuard {
    private CvCorePublicationGuard(){}
    public static PublicationResult validate(CvCorePlan p,Context c){
        if(p==null||c==null||!CvCorePolicy.ENGINE_ID.equals(c.engineId)||!CvCorePolicy.known(p.route))return reject("CV_CORE_CONTEXT_INVALID");
        if(!c.ethFresh||c.ethQuoteAgeMs<0||c.ethQuoteAgeMs>5_000)return reject("CV_CORE_ETH_FEED_STALE");
        if(!c.btcFresh||c.btcQuoteAgeMs<0||c.btcQuoteAgeMs>5_000)return reject("CV_CORE_BTC_FEED_STALE");
        if(!c.solFresh||c.solQuoteAgeMs<0||c.solQuoteAgeMs>5_000)return reject("CV_CORE_SOL_FEED_STALE");
        if(c.publicPlanActive||c.cvPlanActive)return reject("CV_CORE_PUBLIC_PLAN_ACTIVE");if(c.episodeOpened)return reject("CV_CORE_DUPLICATE_EPISODE");
        if(c.now>p.entryValidUntil)return reject("CV_CORE_ENTRY_WINDOW_EXPIRED");if(!validQuote(c.bid,c.ask))return reject("CV_CORE_INVALID_QUOTE");
        if(!Double.isFinite(p.a)||p.a<=0)return reject("CV_CORE_CONTEXT_INVALID");String economics=validateEconomics(p.quantity,p.netRewardPerUnit,p.plannedNetRewardRisk,p.theoreticalMaximumLoss,p.route.riskBudgetUsdt);
        if(!economics.isEmpty())return reject(economics);if(!c.persistenceAvailable)return reject("CV_CORE_PERSISTENCE_FAILED");return new PublicationResult(true,"");}
    public static String validateEconomics(int qty,double reward,double rr,double loss,double budget){if(qty<MarketProfile.eth().minimumQuantity)return "CV_CORE_QUANTITY_ZERO";
        if(!Double.isFinite(reward)||reward<=0)return "CV_CORE_TARGET_NOT_NET_POSITIVE";if(!Double.isFinite(rr)||rr<.40)return "CV_CORE_NET_RR_TOO_LOW";
        if(!Double.isFinite(loss)||!Double.isFinite(budget)||loss>budget+1e-9)return "CV_CORE_RISK_BUDGET_EXCEEDED";return "";}
    private static PublicationResult reject(String r){return new PublicationResult(false,r);}
    private static boolean validQuote(double b,double a){return Double.isFinite(b)&&b>0&&Double.isFinite(a)&&a>0&&a>=b;}
    public static final class Context{public final String engineId;public final long now;public final boolean ethFresh,btcFresh,solFresh,publicPlanActive,cvPlanActive,episodeOpened,persistenceAvailable;
        public final long ethQuoteAgeMs,btcQuoteAgeMs,solQuoteAgeMs;public final double bid,ask;
        public Context(String engine,long now,boolean eth,boolean btc,boolean sol,long ethAge,long btcAge,long solAge,boolean pub,boolean cv,boolean opened,boolean persistence,double bid,double ask){
            engineId=engine;this.now=now;ethFresh=eth;btcFresh=btc;solFresh=sol;ethQuoteAgeMs=ethAge;btcQuoteAgeMs=btcAge;solQuoteAgeMs=solAge;
            publicPlanActive=pub;cvPlanActive=cv;episodeOpened=opened;persistenceAvailable=persistence;this.bid=bid;this.ask=ask;}}
    public static final class PublicationResult{public final boolean published;public final String reasonCode;PublicationResult(boolean p,String r){published=p;reasonCode=r;}}
}
