package com.ethscalper.cockpit;

/** Pure final publication gate. It never changes the already qualified plan geometry. */
public final class ScalpActionPublicationGuard {
    private ScalpActionPublicationGuard(){}

    public static PublicationResult validate(ScalpActionPlan plan,Context c){
        if(plan==null||c==null)return PublicationResult.rejected("SCALP_ACTION_CONTEXT_INVALID");
        if(!ScalpActionEngine.ACTION_ON.equals(c.mode))return PublicationResult.rejected("SCALP_ACTION_MODE_DIAGNOSTICS_ONLY");
        if(!c.ethFresh)return PublicationResult.rejected("SCALP_ACTION_ETH_FEED_STALE");
        if(!c.btcFresh)return PublicationResult.rejected("SCALP_ACTION_BTC_FEED_STALE");
        if(!c.solFresh)return PublicationResult.rejected("SCALP_ACTION_SOL_FEED_STALE");
        if(c.publicPlanActive)return PublicationResult.rejected("SCALP_ACTION_PUBLIC_PLAN_ACTIVE");
        if(c.actionPlanActive)return PublicationResult.rejected("SCALP_ACTION_PUBLIC_PLAN_ACTIVE");
        if(c.now>plan.entryValidUntil)return PublicationResult.rejected("SCALP_ACTION_ENTRY_WINDOW_EXPIRED");
        if(!validQuote(c.bid,c.ask))return PublicationResult.rejected("SCALP_ACTION_INVALID_QUOTE");
        String economics=validateEconomics(plan.quantity,plan.netRewardPerUnit,
                plan.plannedNetRewardRisk,plan.theoreticalMaximumLoss);
        if(!economics.isEmpty())return PublicationResult.rejected(economics);
        return PublicationResult.ready();
    }

    public static String validateEconomics(int quantity,double netRewardPerUnit,
                                           double netRewardRisk,double maximumLoss){
        if(quantity<=0)return "SCALP_ACTION_QUANTITY_ZERO";
        if(!Double.isFinite(netRewardPerUnit)||netRewardPerUnit<=0)
            return "SCALP_ACTION_TARGET_NOT_NET_POSITIVE";
        if(!Double.isFinite(netRewardRisk)||netRewardRisk<.40)return "SCALP_ACTION_NET_RR_TOO_LOW";
        if(!Double.isFinite(maximumLoss)||maximumLoss>MarketProfile.eth().finalRiskBudgetUsdt+1e-9)
            return "SCALP_ACTION_RISK_BUDGET_EXCEEDED";
        return "";
    }

    private static boolean validQuote(double bid,double ask){return Double.isFinite(bid)&&bid>0
            &&Double.isFinite(ask)&&ask>0&&ask>=bid;}

    public static final class Context {
        public final String mode;public final long now;public final boolean ethFresh,btcFresh,solFresh;
        public final boolean publicPlanActive,actionPlanActive;public final double bid,ask;
        public Context(String mode,long now,boolean ethFresh,boolean btcFresh,boolean solFresh,
                       boolean publicPlanActive,boolean actionPlanActive,double bid,double ask){
            this.mode=mode;this.now=now;this.ethFresh=ethFresh;this.btcFresh=btcFresh;
            this.solFresh=solFresh;this.publicPlanActive=publicPlanActive;
            this.actionPlanActive=actionPlanActive;this.bid=bid;this.ask=ask;}
    }

    public static final class PublicationResult {
        public final boolean published;public final String reasonCode;
        private PublicationResult(boolean published,String reasonCode){this.published=published;this.reasonCode=reasonCode;}
        public static PublicationResult ready(){return new PublicationResult(true,"");}
        public static PublicationResult rejected(String reason){return new PublicationResult(false,reason==null?"":reason);}
    }
}
