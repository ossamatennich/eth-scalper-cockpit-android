package com.ethscalper.cockpit;

public final class SignalDecision {
    public final String decision;
    public final String reasonCode;
    public final String reasonText;
    public final String side;
    public final String family;
    public final int score;
    public final int quantity;
    public final double entry;
    public final double takeProfit;
    public final double stopLoss;
    public final double targetMove;
    public final double stopDistance;
    public final String impulse;
    public final boolean resetConfirmed;
    public final double movementOrigin;
    public final double movementExtreme;
    public final double movementDistance;
    public final boolean movementConsumed;

    private SignalDecision(String decision, String reasonCode, String reasonText, String side,
                           String family, int score, int quantity, double entry, double takeProfit,
                           double stopLoss, double targetMove, double stopDistance, String impulse,
                           boolean resetConfirmed, double movementOrigin, double movementExtreme,
                           double movementDistance, boolean movementConsumed) {
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.reasonText = reasonText;
        this.side = side;
        this.family = family;
        this.score = score;
        this.quantity = quantity;
        this.entry = entry;
        this.takeProfit = takeProfit;
        this.stopLoss = stopLoss;
        this.targetMove = targetMove;
        this.stopDistance = stopDistance;
        this.impulse = impulse;
        this.resetConfirmed = resetConfirmed;
        this.movementOrigin = movementOrigin;
        this.movementExtreme = movementExtreme;
        this.movementDistance = movementDistance;
        this.movementConsumed = movementConsumed;
    }

    public static SignalDecision waiting(String code, String text, int score, String impulse,
                                         boolean reset, double origin, double extreme,
                                         double distance, boolean consumed) {
        return new SignalDecision("ATTENDRE", code, text, "", "", score, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                impulse, reset, origin, extreme, distance, consumed);
    }

    public static SignalDecision signal(String side, String family, int score, int quantity,
                                        double entry, double takeProfit, double stopLoss,
                                        double targetMove, double stopDistance, String impulse,
                                        boolean reset, double origin, double extreme,
                                        double distance) {
        String code = "OK_SIGNAL_" + side;
        return new SignalDecision("ENTRER", code, family + " confirmée", side, family, score,
                quantity, entry, takeProfit, stopLoss, targetMove, stopDistance,
                impulse, reset, origin, extreme, distance, false);
    }

    public boolean isSignal() { return "ENTRER".equals(decision); }
}
