package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, validated representation of the one live final plan. */
public final class ActivePlanState {
    public static final int FORMAT_VERSION = 2;

    public final int formatVersion;
    public final String symbol;
    public final String asset;
    public final String profileVersion;
    public final String status;
    public final String side;
    public final String family;
    public final String reasonCode;
    public final String reasonText;
    public final int score;
    public final int quantity;
    public final double entry;
    public final double takeProfit;
    public final double stopLoss;
    public final double targetMove;
    public final double stopDistance;
    public final long createdAt;
    public final long entryTriggeredAt;
    public final long finalConfirmedAt;
    public final boolean premium15m;
    public final String notificationSignature;
    public final int notificationId;
    public final double lastPrice;
    public final double lastBid;
    public final double lastAsk;
    public final double avgRange20;
    public final long lastP01ConfirmedAt;
    public final String impulse;
    public final boolean resetConfirmed;
    public final double movementOrigin;
    public final double movementExtreme;
    public final double movementDistance;
    public final String replayRiskReasonCode;
    public final String replayRiskDetail;
    public final double p01Move1Aligned;
    public final double p01Move3Aligned;
    public final double p01Move8Aligned;
    public final double p01Move15Aligned;
    public final double p01Flow30Aligned;
    public final String sizingDiagnostic;
    public final double resultCostPerUnit;
    public final double riskAllowancePerUnit;
    public final double qualityRiskBudget;
    public final double theoreticalMaximumLoss;
    public final double volatilityA;
    public final double adverseExcursion;
    public final double baseStop;
    public final double structuralAnchor;
    public final int structuralWindowMinutes;
    public final double structuralBuffer;
    public final String stopCalculationType;
    public final String stopReasonCode;
    public final String selectedBudgetReason;
    public final double riskPerUnit;
    public final int riskQuantity;
    public final int qualityCap;

    private ActivePlanState(Builder b) {
        formatVersion = b.formatVersion;
        symbol = text(b.symbol);
        asset = text(b.asset);
        profileVersion = text(b.profileVersion);
        status = text(b.status);
        side = text(b.side);
        family = text(b.family);
        reasonCode = text(b.reasonCode);
        reasonText = text(b.reasonText);
        score = b.score;
        quantity = b.quantity;
        entry = b.entry;
        takeProfit = b.takeProfit;
        stopLoss = b.stopLoss;
        targetMove = b.targetMove;
        stopDistance = b.stopDistance;
        createdAt = b.createdAt;
        entryTriggeredAt = b.entryTriggeredAt;
        finalConfirmedAt = b.finalConfirmedAt;
        premium15m = b.premium15m;
        notificationSignature = text(b.notificationSignature);
        notificationId = b.notificationId;
        lastPrice = b.lastPrice;
        lastBid = b.lastBid;
        lastAsk = b.lastAsk;
        avgRange20 = b.avgRange20;
        lastP01ConfirmedAt = b.lastP01ConfirmedAt;
        impulse = text(b.impulse);
        resetConfirmed = b.resetConfirmed;
        movementOrigin = b.movementOrigin;
        movementExtreme = b.movementExtreme;
        movementDistance = b.movementDistance;
        replayRiskReasonCode = text(b.replayRiskReasonCode);
        replayRiskDetail = text(b.replayRiskDetail);
        p01Move1Aligned = b.p01Move1Aligned;
        p01Move3Aligned = b.p01Move3Aligned;
        p01Move8Aligned = b.p01Move8Aligned;
        p01Move15Aligned = b.p01Move15Aligned;
        p01Flow30Aligned = b.p01Flow30Aligned;
        sizingDiagnostic = text(b.sizingDiagnostic);
        resultCostPerUnit = b.resultCostPerUnit;
        riskAllowancePerUnit = b.riskAllowancePerUnit;
        qualityRiskBudget = b.qualityRiskBudget;
        theoreticalMaximumLoss = b.theoreticalMaximumLoss;
        volatilityA=b.volatilityA;adverseExcursion=b.adverseExcursion;baseStop=b.baseStop;
        structuralAnchor=b.structuralAnchor;structuralWindowMinutes=b.structuralWindowMinutes;
        structuralBuffer=b.structuralBuffer;stopCalculationType=text(b.stopCalculationType);
        stopReasonCode=text(b.stopReasonCode);selectedBudgetReason=text(b.selectedBudgetReason);
        riskPerUnit=b.riskPerUnit;riskQuantity=b.riskQuantity;qualityCap=b.qualityCap;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isValid() {
        if (formatVersion != FORMAT_VERSION || !"ACTIVE".equals(status)) return false;
        if (!MarketProfile.ETH_SYMBOL.equals(symbol) && !MarketProfile.SOL_SYMBOL.equals(symbol)) return false;
        if (!"LONG".equals(side) && !"SHORT".equals(side)) return false;
        int maximum = MarketProfile.SOL_SYMBOL.equals(symbol) ? 120 : 7;
        if (family.isEmpty() || quantity < 1 || quantity > maximum || score < 0 || score > 100) return false;
        if (!positive(entry) || !positive(takeProfit) || !positive(stopLoss)
                || !positive(targetMove) || !positive(stopDistance) || !positive(lastPrice)
                || !positive(lastBid) || !positive(lastAsk) || !positive(avgRange20)) return false;
        if ("LONG".equals(side) && !(takeProfit > entry && stopLoss < entry)) return false;
        if ("SHORT".equals(side) && !(takeProfit < entry && stopLoss > entry)) return false;
        if (createdAt <= 0 || entryTriggeredAt < createdAt || finalConfirmedAt < entryTriggeredAt) return false;
        if (lastP01ConfirmedAt < 0 || notificationSignature.isEmpty() || notificationId <= 0) return false;
        return finite(movementOrigin) && finite(movementExtreme) && finite(movementDistance)
                && finite(resultCostPerUnit) && resultCostPerUnit >= 0.0
                && finite(riskAllowancePerUnit) && riskAllowancePerUnit >= 0.0
                && finite(qualityRiskBudget) && qualityRiskBudget >= 0.0
                && finite(theoreticalMaximumLoss) && theoreticalMaximumLoss >= 0.0;
    }

    public SignalDecision toSignalDecision() {
        if (!isValid()) return null;
        MarketProfile profile = MarketProfile.SOL_SYMBOL.equals(symbol)
                ? MarketProfile.sol() : MarketProfile.eth();
        return SignalDecision.confirmed(profile, side, family,
                reasonCode.isEmpty() ? ContinuationConfirmation.P01_CONFIRMED : reasonCode,
                reasonText.isEmpty() ? "Plan final restauré" : reasonText,
                score, quantity, entry, takeProfit, stopLoss, targetMove, stopDistance,
                impulse, resetConfirmed, movementOrigin, movementExtreme, movementDistance);
    }

    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "formatVersion", formatVersion); put(out, "symbol", symbol);
        put(out, "asset", asset); put(out, "profileVersion", profileVersion);
        put(out, "status", status);
        put(out, "side", side); put(out, "family", family);
        put(out, "reasonCode", reasonCode); put(out, "reasonText", reasonText);
        put(out, "score", score); put(out, "quantity", quantity);
        put(out, "entry", entry); put(out, "takeProfit", takeProfit); put(out, "stopLoss", stopLoss);
        put(out, "targetMove", targetMove); put(out, "stopDistance", stopDistance);
        put(out, "createdAt", createdAt); put(out, "entryTriggeredAt", entryTriggeredAt);
        put(out, "finalConfirmedAt", finalConfirmedAt); put(out, "premium15m", premium15m);
        put(out, "notificationSignature", notificationSignature); put(out, "notificationId", notificationId);
        put(out, "lastPrice", lastPrice); put(out, "lastBid", lastBid); put(out, "lastAsk", lastAsk);
        put(out, "avgRange20", avgRange20); put(out, "lastP01ConfirmedAt", lastP01ConfirmedAt);
        put(out, "impulse", impulse); put(out, "resetConfirmed", resetConfirmed);
        put(out, "movementOrigin", movementOrigin); put(out, "movementExtreme", movementExtreme);
        put(out, "movementDistance", movementDistance);
        put(out, "replayRiskReasonCode", replayRiskReasonCode); put(out, "replayRiskDetail", replayRiskDetail);
        put(out, "p01Move1Aligned", p01Move1Aligned); put(out, "p01Move3Aligned", p01Move3Aligned);
        put(out, "p01Move8Aligned", p01Move8Aligned); put(out, "p01Move15Aligned", p01Move15Aligned);
        put(out, "p01Flow30Aligned", p01Flow30Aligned); put(out, "sizingDiagnostic", sizingDiagnostic);
        put(out, "resultCostPerUnit", resultCostPerUnit);
        put(out, "riskAllowancePerUnit", riskAllowancePerUnit);
        put(out, "qualityRiskBudget", qualityRiskBudget);
        put(out, "theoreticalMaximumLoss", theoreticalMaximumLoss);
        put(out,"volatilityA",volatilityA);put(out,"adverseExcursion",adverseExcursion);
        put(out,"baseStop",baseStop);put(out,"structuralAnchor",structuralAnchor);
        put(out,"structuralWindowMinutes",structuralWindowMinutes);
        put(out,"structuralBuffer",structuralBuffer);put(out,"stopCalculationType",stopCalculationType);
        put(out,"stopReasonCode",stopReasonCode);put(out,"selectedBudgetReason",selectedBudgetReason);
        put(out,"riskPerUnit",riskPerUnit);put(out,"riskQuantity",riskQuantity);
        put(out,"qualityCap",qualityCap);
        return out;
    }

    public static ActivePlanState fromMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            String symbol = optional(values, "symbol", MarketProfile.ETH_SYMBOL);
            MarketProfile profile = MarketProfile.SOL_SYMBOL.equals(symbol)
                    ? MarketProfile.sol() : MarketProfile.eth();
            Builder b = builder()
                    .formatVersion(FORMAT_VERSION)
                    .market(profile)
                    .status(value(values, "status")).side(value(values, "side"))
                    .family(value(values, "family")).reasonCode(value(values, "reasonCode"))
                    .reasonText(value(values, "reasonText")).score(integer(values, "score"))
                    .quantity(integer(values, "quantity")).prices(decimal(values, "entry"),
                            decimal(values, "takeProfit"), decimal(values, "stopLoss"))
                    .risk(decimal(values, "targetMove"), decimal(values, "stopDistance"))
                    .times(longValue(values, "createdAt"), longValue(values, "entryTriggeredAt"),
                            longValue(values, "finalConfirmedAt"))
                    .premium15m(bool(values, "premium15m"))
                    .notification(value(values, "notificationSignature"), integer(values, "notificationId"))
                    .lastMarket(decimal(values, "lastPrice"), decimal(values, "lastBid"),
                            decimal(values, "lastAsk"), decimal(values, "avgRange20"))
                    .lastP01ConfirmedAt(longValue(values, "lastP01ConfirmedAt"))
                    .movement(value(values, "impulse"), bool(values, "resetConfirmed"),
                            decimal(values, "movementOrigin"), decimal(values, "movementExtreme"),
                            decimal(values, "movementDistance"))
                    .replayRisk(value(values, "replayRiskReasonCode"), value(values, "replayRiskDetail"))
                    .p01(decimal(values, "p01Move1Aligned"), decimal(values, "p01Move3Aligned"),
                            decimal(values, "p01Move8Aligned"), decimal(values, "p01Move15Aligned"),
                            decimal(values, "p01Flow30Aligned"))
                    .sizingDiagnostic(value(values, "sizingDiagnostic"))
                    .unitRisk(optionalDecimal(values, "resultCostPerUnit", 0.0),
                            optionalDecimal(values, "riskAllowancePerUnit", 0.0),
                            optionalDecimal(values, "qualityRiskBudget", 0.0),
                            optionalDecimal(values, "theoreticalMaximumLoss", 0.0))
                    .structural(optionalDecimal(values,"volatilityA",Double.NaN),
                            optionalDecimal(values,"adverseExcursion",Double.NaN),
                            optionalDecimal(values,"baseStop",Double.NaN),
                            optionalDecimal(values,"structuralAnchor",Double.NaN),
                            optionalInteger(values,"structuralWindowMinutes",0),
                            optionalDecimal(values,"structuralBuffer",Double.NaN),
                            optional(values,"stopCalculationType",""),
                            optional(values,"stopReasonCode",""),
                            optional(values,"selectedBudgetReason",""),
                            optionalDecimal(values,"riskPerUnit",Double.NaN),
                            optionalInteger(values,"riskQuantity",0),
                            optionalInteger(values,"qualityCap",0));
            ActivePlanState state = b.build();
            return state.isValid() ? state : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String value(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null) throw new IllegalArgumentException(key);
        return value;
    }
    private static String optional(Map<String,String> map,String key,String fallback) {
        String value=map.get(key); return value==null?fallback:value;
    }
    private static double optionalDecimal(Map<String,String> map,String key,double fallback) {
        String value=map.get(key); return value==null?fallback:Double.parseDouble(value);
    }
    private static int optionalInteger(Map<String,String> map,String key,int fallback) {
        String value=map.get(key); return value==null?fallback:Integer.parseInt(value);
    }

    private static int integer(Map<String, String> map, String key) { return Integer.parseInt(value(map, key)); }
    private static long longValue(Map<String, String> map, String key) { return Long.parseLong(value(map, key)); }
    private static double decimal(Map<String, String> map, String key) { return Double.parseDouble(value(map, key)); }
    private static boolean bool(Map<String, String> map, String key) {
        String value = value(map, key);
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException(key);
        return Boolean.parseBoolean(value);
    }
    private static void put(Map<String, String> map, String key, Object value) { map.put(key, String.valueOf(value)); }
    private static boolean positive(double value) { return finite(value) && value > 0.0; }
    private static boolean finite(double value) { return Double.isFinite(value); }
    private static String text(String value) { return value == null ? "" : value; }

    public static final class Builder {
        private int formatVersion = FORMAT_VERSION;
        private String symbol = MarketProfile.ETH_SYMBOL, asset = "ETH", profileVersion = "ETH_V23321";
        private String status = "ACTIVE", side = "", family = "", reasonCode = "", reasonText = "";
        private int score, quantity;
        private double entry, takeProfit, stopLoss, targetMove, stopDistance, lastPrice;
        private double lastBid, lastAsk, avgRange20;
        private long createdAt, entryTriggeredAt, finalConfirmedAt, lastP01ConfirmedAt;
        private boolean premium15m, resetConfirmed;
        private String notificationSignature = "", impulse = "", replayRiskReasonCode = "";
        private String replayRiskDetail = "", sizingDiagnostic = "";
        private int notificationId;
        private double movementOrigin, movementExtreme, movementDistance;
        private double p01Move1Aligned = Double.NaN, p01Move3Aligned = Double.NaN;
        private double p01Move8Aligned = Double.NaN, p01Move15Aligned = Double.NaN;
        private double p01Flow30Aligned = Double.NaN;
        private double resultCostPerUnit, riskAllowancePerUnit, qualityRiskBudget;
        private double theoreticalMaximumLoss;
        private double volatilityA=Double.NaN,adverseExcursion=Double.NaN,baseStop=Double.NaN;
        private double structuralAnchor=Double.NaN,structuralBuffer=Double.NaN,riskPerUnit=Double.NaN;
        private int structuralWindowMinutes,riskQuantity,qualityCap;
        private String stopCalculationType="",stopReasonCode="",selectedBudgetReason="";

        public Builder formatVersion(int v) { formatVersion=v; return this; }
        public Builder market(MarketProfile profile) { symbol=profile.symbol;asset=profile.asset;profileVersion=profile.profileVersion;return this; }
        public Builder status(String v) { status=v; return this; }
        public Builder side(String v) { side=v; return this; }
        public Builder family(String v) { family=v; return this; }
        public Builder reasonCode(String v) { reasonCode=v; return this; }
        public Builder reasonText(String v) { reasonText=v; return this; }
        public Builder score(int v) { score=v; return this; }
        public Builder quantity(int v) { quantity=v; return this; }
        public Builder prices(double entry, double tp, double sl) { this.entry=entry; takeProfit=tp; stopLoss=sl; return this; }
        public Builder risk(double target, double stop) { targetMove=target; stopDistance=stop; return this; }
        public Builder times(long created, long triggered, long confirmed) { createdAt=created; entryTriggeredAt=triggered; finalConfirmedAt=confirmed; return this; }
        public Builder premium15m(boolean v) { premium15m=v; return this; }
        public Builder notification(String signature, int id) { notificationSignature=signature; notificationId=id; return this; }
        public Builder lastPrice(double v) { lastPrice=v; lastBid=v; lastAsk=v; avgRange20=0.35; return this; }
        public Builder lastMarket(double price, double bid, double ask, double avgRange) { lastPrice=price; lastBid=bid; lastAsk=ask; avgRange20=avgRange; return this; }
        public Builder lastP01ConfirmedAt(long v) { lastP01ConfirmedAt=v; return this; }
        public Builder movement(String impulse, boolean reset, double origin, double extreme, double distance) { this.impulse=impulse; resetConfirmed=reset; movementOrigin=origin; movementExtreme=extreme; movementDistance=distance; return this; }
        public Builder replayRisk(String code, String detail) { replayRiskReasonCode=code; replayRiskDetail=detail; return this; }
        public Builder p01(double move1, double move3, double move8, double move15, double flow30) { p01Move1Aligned=move1; p01Move3Aligned=move3; p01Move8Aligned=move8; p01Move15Aligned=move15; p01Flow30Aligned=flow30; return this; }
        public Builder sizingDiagnostic(String v) { sizingDiagnostic=v; return this; }
        public Builder unitRisk(double cost,double allowance,double budget,double loss) { resultCostPerUnit=cost;riskAllowancePerUnit=allowance;qualityRiskBudget=budget;theoreticalMaximumLoss=loss;return this; }
        public Builder structural(double a,double adverse,double base,double anchor,int window,
                                  double buffer,String type,String stopReason,String budgetReason,
                                  double perUnit,int riskQty,int cap) {
            volatilityA=a;adverseExcursion=adverse;baseStop=base;structuralAnchor=anchor;
            structuralWindowMinutes=window;structuralBuffer=buffer;stopCalculationType=type;
            stopReasonCode=stopReason;selectedBudgetReason=budgetReason;riskPerUnit=perUnit;
            riskQuantity=riskQty;qualityCap=cap;return this;
        }
        public ActivePlanState build() { return new ActivePlanState(this); }
    }
}
