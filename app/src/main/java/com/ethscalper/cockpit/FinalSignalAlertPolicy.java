package com.ethscalper.cockpit;

/** Pure delivery and business-deduplication policy for the final audible alert path. */
public final class FinalSignalAlertPolicy {
    private FinalSignalAlertPolicy() {}

    public static boolean shouldAttempt(boolean testAlert, boolean signatureAlreadyAlerted) {
        return testAlert || !signatureAlreadyAlerted;
    }

    public static boolean shouldWriteBusinessDedupe(boolean testAlert,
                                                     boolean notificationPostedSuccessfully) {
        return shouldWriteBusinessDedupe(testAlert,notificationPostedSuccessfully,true);
    }

    /** A posted notification is not an audible delivery when Android reports a broken channel. */
    public static boolean shouldWriteBusinessDedupe(boolean testAlert,
                                                     boolean notificationPostedSuccessfully,
                                                     boolean audibleChannelReady) {
        return !testAlert && notificationPostedSuccessfully && audibleChannelReady;
    }
}
