package com.ethscalper.cockpit;

/** Pure delivery and business-deduplication policy for the final audible alert path. */
public final class FinalSignalAlertPolicy {
    private FinalSignalAlertPolicy() {}

    public static boolean shouldAttempt(boolean testAlert, boolean signatureAlreadyAlerted) {
        return testAlert || !signatureAlreadyAlerted;
    }

    public static boolean shouldWriteBusinessDedupe(boolean testAlert,
                                                     boolean notificationPostedSuccessfully) {
        return !testAlert && notificationPostedSuccessfully;
    }
}
