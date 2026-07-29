package com.ethscalper.cockpit;

import java.util.Locale;

/**
 * Single immutable projection of a published plan for notification, screen and diagnostics.
 */
public final class ConfirmedSignalPayload {
    public final SignalDecision plan;
    private final int quantity;

    private ConfirmedSignalPayload(SignalDecision plan) {
        if (plan == null) throw new IllegalArgumentException("published plan required");
        this.plan = plan;
        this.quantity = plan.quantity;
    }

    public static ConfirmedSignalPayload from(SignalDecision plan) {
        return new ConfirmedSignalPayload(plan);
    }

    public int quantityForNotification() {
        return quantity;
    }

    public int quantityForScreen() {
        return quantity;
    }

    public int quantityForDiagnostic() {
        return quantity;
    }

    public String notificationBody(boolean premium15m) {
        return String.format(Locale.US,
                "LIMIT %.2f · TP %.2f · SL %.2f · %d ETH%s",
                plan.entry, plan.takeProfit, plan.stopLoss, quantityForNotification(),
                premium15m ? " · PREMIUM 15M" : "");
    }
}
