package com.ethscalper.cockpit;

import java.util.Objects;

/** Immutable per-symbol research and risk configuration. */
public final class MarketProfile {
    public static final String ETH_SYMBOL = "ETHUSDT";
    public static final String SOL_SYMBOL = "SOLUSDT";
    public static final String BTC_SYMBOL = "BTCUSDT";

    public final String symbol;
    public final String asset;
    public final String profileVersion;
    public final double referencePrice;
    public final double priceTick;
    public final int quantityStep;
    public final int minimumQuantity;
    public final int maximumQuantity;
    public final boolean researchCandidate;
    public final boolean adaptivePriceScale;

    public final double aMinimumReference;
    public final double p02AppearanceFloorReference;
    public final double maximumSpreadReference;
    public final double stopMinimumReference;
    public final double stopMaximumReference;
    public final double targetFloorReference;
    public final double targetMaximumReference;
    public final double p02SeedTargetReference;
    public final double p02SeedStopReference;
    public final double revalidationMinimumStopReference;
    public final double revalidationMaximumAdverseReference;
    public final double lateFavorableDistanceReference;
    public final double lateAdverseDistanceReference;
    public final double resultRoundTripCostReference;
    public final double riskExecutionAllowanceReference;
    public final double legacyRiskBudgetUsdt;
    public final double finalRiskBudgetUsdt;
    public final String staleReasonCode;

    private final double[] qualityRiskBudgets;

    private MarketProfile(Builder b) {
        symbol = required(b.symbol, "symbol");
        asset = required(b.asset, "asset");
        profileVersion = required(b.profileVersion, "profileVersion");
        referencePrice = positive(b.referencePrice, "referencePrice");
        priceTick = positive(b.priceTick, "priceTick");
        if (b.quantityStep < 1 || b.minimumQuantity < 1
                || b.maximumQuantity < b.minimumQuantity) {
            throw new IllegalArgumentException("Invalid quantity bounds for " + symbol);
        }
        quantityStep = b.quantityStep;
        minimumQuantity = b.minimumQuantity;
        maximumQuantity = b.maximumQuantity;
        researchCandidate = b.researchCandidate;
        adaptivePriceScale = b.adaptivePriceScale;
        aMinimumReference = positive(b.aMinimumReference, "aMinimumReference");
        p02AppearanceFloorReference = positive(b.p02AppearanceFloorReference,
                "p02AppearanceFloorReference");
        maximumSpreadReference = positive(b.maximumSpreadReference, "maximumSpreadReference");
        stopMinimumReference = positive(b.stopMinimumReference, "stopMinimumReference");
        stopMaximumReference = positive(b.stopMaximumReference, "stopMaximumReference");
        targetFloorReference = positive(b.targetFloorReference, "targetFloorReference");
        targetMaximumReference = positive(b.targetMaximumReference, "targetMaximumReference");
        p02SeedTargetReference = positive(b.p02SeedTargetReference, "p02SeedTargetReference");
        p02SeedStopReference = positive(b.p02SeedStopReference, "p02SeedStopReference");
        revalidationMinimumStopReference = positive(b.revalidationMinimumStopReference,
                "revalidationMinimumStopReference");
        revalidationMaximumAdverseReference = positive(b.revalidationMaximumAdverseReference,
                "revalidationMaximumAdverseReference");
        lateFavorableDistanceReference = positive(b.lateFavorableDistanceReference,
                "lateFavorableDistanceReference");
        lateAdverseDistanceReference = positive(b.lateAdverseDistanceReference,
                "lateAdverseDistanceReference");
        resultRoundTripCostReference = positive(b.resultRoundTripCostReference,
                "resultRoundTripCostReference");
        riskExecutionAllowanceReference = positive(b.riskExecutionAllowanceReference,
                "riskExecutionAllowanceReference");
        legacyRiskBudgetUsdt = positive(b.legacyRiskBudgetUsdt, "legacyRiskBudgetUsdt");
        finalRiskBudgetUsdt = positive(b.finalRiskBudgetUsdt, "finalRiskBudgetUsdt");
        staleReasonCode = required(b.staleReasonCode, "staleReasonCode");
        qualityRiskBudgets = b.qualityRiskBudgets == null
                ? new double[]{10.00, 10.00, 10.00, 10.00, 10.00}
                : b.qualityRiskBudgets.clone();
        if (qualityRiskBudgets.length != 5) {
            throw new IllegalArgumentException("Exactly five quality budgets are required");
        }
        for (double budget : qualityRiskBudgets) positive(budget, "qualityRiskBudget");
    }

    public static MarketProfile eth() {
        return builder(ETH_SYMBOL, "ETH", "ETH_V23321")
                .referencePrice(1900.00).priceTick(.01).quantity(1, 1, 7)
                .researchCandidate(true).adaptivePriceScale(false)
                .detection(.35, .75, .55)
                .stops(.55, 2.50).targets(2.80, 5.50).p02Seed(2.80, 1.35)
                .revalidation(.10, .30).lateDistances(2.00, 2.20)
                .costs(1.43, 2.35).riskBudgets(10.00, 14.55)
                .qualityBudgets(10.00, 10.00, 10.00, 10.00, 10.00)
                .staleReasonCode("V2326_ETH_FEED_STALE").build();
    }

    public static MarketProfile sol() {
        return builder(SOL_SYMBOL, "SOL", "SOL_V1_20260727")
                .referencePrice(75.80).priceTick(.01).quantity(1, 1, 120)
                .researchCandidate(true).adaptivePriceScale(true)
                .detection(.015, .03147, .03)
                .stops(.03, .10).targets(.12, .23).p02Seed(.12, .06)
                .revalidation(.01, .02).lateDistances(.03, .02)
                .costs(.06, .10).riskBudgets(10.00, 14.55)
                .qualityBudgets(10.00, 11.14, 12.28, 13.41, 14.55)
                .staleReasonCode("V2340_SOL_FEED_STALE").build();
    }

    public static Builder builder(String symbol, String asset, String version) {
        return new Builder(symbol, asset, version);
    }

    public double priceScale(double entry) {
        if (!adaptivePriceScale) return 1.0;
        if (!Double.isFinite(entry) || entry <= 0.0) return Double.NaN;
        return entry / referencePrice;
    }

    public double scaledMinimum(double referenceValue, double entry) {
        if (!adaptivePriceScale) return referenceValue;
        return ceilToTick(referenceValue * priceScale(entry));
    }

    public double scaledMaximum(double referenceValue, double entry,
                                double correspondingMinimum) {
        if (!adaptivePriceScale) return referenceValue;
        return Math.max(correspondingMinimum,
                Math.max(priceTick, floorToTick(referenceValue * priceScale(entry))));
    }

    public double scaledRaw(double referenceValue, double entry) {
        return adaptivePriceScale ? referenceValue * priceScale(entry) : referenceValue;
    }

    /** Converts an ETH absolute detector distance while preserving ETH exactly. */
    public double detectorDistance(double ethReferenceDistance, double entry,
                                   boolean mandatoryMinimum) {
        if (!adaptivePriceScale) return ethReferenceDistance;
        double solReference = ethReferenceDistance * (aMinimumReference / .35);
        double raw = solReference * priceScale(entry);
        return mandatoryMinimum ? ceilToTick(raw) : raw;
    }

    public double qualityRiskBudget(int level) {
        int bounded = Math.max(3, Math.min(7, level));
        return qualityRiskBudgets[bounded - 3];
    }

    public double[] qualityRiskBudgets() { return qualityRiskBudgets.clone(); }

    public double ceilToTick(double value) {
        return Math.ceil((value - 1e-12) / priceTick) * priceTick;
    }

    public double floorToTick(double value) {
        return Math.floor((value + 1e-12) / priceTick) * priceTick;
    }

    public double roundPriceConservative(double value, boolean floor) {
        return floor ? floorToTick(value) : ceilToTick(value);
    }

    @Override public boolean equals(Object other) {
        return other instanceof MarketProfile && symbol.equals(((MarketProfile) other).symbol);
    }

    @Override public int hashCode() { return Objects.hash(symbol); }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name);
        return value;
    }

    private static double positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name);
        return value;
    }

    public static final class Builder {
        private final String symbol, asset, profileVersion;
        private double referencePrice, priceTick;
        private int quantityStep, minimumQuantity, maximumQuantity;
        private boolean researchCandidate, adaptivePriceScale;
        private double aMinimumReference, p02AppearanceFloorReference, maximumSpreadReference;
        private double stopMinimumReference, stopMaximumReference;
        private double targetFloorReference, targetMaximumReference;
        private double p02SeedTargetReference, p02SeedStopReference;
        private double revalidationMinimumStopReference, revalidationMaximumAdverseReference;
        private double lateFavorableDistanceReference, lateAdverseDistanceReference;
        private double resultRoundTripCostReference, riskExecutionAllowanceReference;
        private double legacyRiskBudgetUsdt, finalRiskBudgetUsdt;
        private double[] qualityRiskBudgets;
        private String staleReasonCode;

        private Builder(String symbol, String asset, String version) {
            this.symbol = symbol; this.asset = asset; this.profileVersion = version;
        }
        public Builder referencePrice(double v) { referencePrice=v; return this; }
        public Builder priceTick(double v) { priceTick=v; return this; }
        public Builder quantity(int step,int min,int max) { quantityStep=step;minimumQuantity=min;maximumQuantity=max;return this; }
        public Builder researchCandidate(boolean v) { researchCandidate=v; return this; }
        public Builder adaptivePriceScale(boolean v) { adaptivePriceScale=v; return this; }
        public Builder detection(double a,double p02,double spread) { aMinimumReference=a;p02AppearanceFloorReference=p02;maximumSpreadReference=spread;return this; }
        public Builder stops(double min,double max) { stopMinimumReference=min;stopMaximumReference=max;return this; }
        public Builder targets(double min,double max) { targetFloorReference=min;targetMaximumReference=max;return this; }
        public Builder p02Seed(double tp,double sl) { p02SeedTargetReference=tp;p02SeedStopReference=sl;return this; }
        public Builder revalidation(double min,double max) { revalidationMinimumStopReference=min;revalidationMaximumAdverseReference=max;return this; }
        public Builder lateDistances(double favorable,double adverse) { lateFavorableDistanceReference=favorable;lateAdverseDistanceReference=adverse;return this; }
        public Builder costs(double result,double allowance) { resultRoundTripCostReference=result;riskExecutionAllowanceReference=allowance;return this; }
        public Builder riskBudgets(double legacy,double finalBudget) { legacyRiskBudgetUsdt=legacy;finalRiskBudgetUsdt=finalBudget;return this; }
        public Builder qualityBudgets(double q3,double q4,double q5,double q6,double q7) { qualityRiskBudgets=new double[]{q3,q4,q5,q6,q7};return this; }
        public Builder staleReasonCode(String v) { staleReasonCode=v; return this; }
        public MarketProfile build() { return new MarketProfile(this); }
    }
}
