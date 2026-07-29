package com.ethscalper.cockpit;

/** Pure post-stop budget selection. Engine score alone can never raise the budget. */
public final class AdaptiveRiskSizing {
    public static final double STANDARD_BUDGET_USDT = 10.00;
    public static final double REINFORCED_BUDGET_USDT = 12.00;
    public static final double PREMIUM_BUDGET_USDT = 14.55;
    public static final String STANDARD = "STANDARD_10_USDT";
    public static final String REINFORCED = "RENFORCÉ_12_USDT";
    public static final String PREMIUM = "PREMIUM_14_55_USDT";

    private AdaptiveRiskSizing() {}

    public static Result select(Evidence e) {
        if (e == null || !e.completeData || !e.feedPerfectlyFresh
                || e.historicalReplayRiskVeto || !e.sleeveAccepted
                || !e.qualityConfirmed || !e.cleanContext || !e.confluenceSufficient) {
            return new Result(STANDARD_BUDGET_USDT, STANDARD);
        }
        if (e.premium15m && e.qualityCap >= 6 && e.exceptionalContext) {
            return new Result(PREMIUM_BUDGET_USDT, PREMIUM);
        }
        if (e.qualityCap >= 4) return new Result(REINFORCED_BUDGET_USDT, REINFORCED);
        return new Result(STANDARD_BUDGET_USDT, STANDARD);
    }

    public static final class Evidence {
        public final boolean sleeveAccepted,qualityConfirmed,premium15m,cleanContext;
        public final boolean historicalReplayRiskVeto,feedPerfectlyFresh;
        public final boolean confluenceSufficient,completeData,exceptionalContext;
        public final int qualityCap;
        public Evidence(boolean sleeveAccepted,boolean qualityConfirmed,boolean premium15m,
                        boolean cleanContext,boolean historicalReplayRiskVeto,
                        boolean feedPerfectlyFresh,boolean confluenceSufficient,
                        boolean completeData,boolean exceptionalContext,int qualityCap) {
            this.sleeveAccepted=sleeveAccepted;this.qualityConfirmed=qualityConfirmed;
            this.premium15m=premium15m;this.cleanContext=cleanContext;
            this.historicalReplayRiskVeto=historicalReplayRiskVeto;
            this.feedPerfectlyFresh=feedPerfectlyFresh;
            this.confluenceSufficient=confluenceSufficient;this.completeData=completeData;
            this.exceptionalContext=exceptionalContext;this.qualityCap=qualityCap;
        }
    }
    public static final class Result {
        public final double budgetUsdt;public final String reason;
        private Result(double budget,String reason){budgetUsdt=budget;this.reason=reason;}
    }
}
