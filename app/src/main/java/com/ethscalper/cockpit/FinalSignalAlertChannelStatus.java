package com.ethscalper.cockpit;

/** Pure classification of the Android channel used by final audible signal alerts. */
public final class FinalSignalAlertChannelStatus {
    public enum State {
        CHANNEL_READY,
        NOTIFICATIONS_DISABLED,
        CHANNEL_DISABLED,
        CHANNEL_LOW_IMPORTANCE,
        CHANNEL_SOUND_MISSING
    }

    private FinalSignalAlertChannelStatus() {}

    public static State evaluate(boolean notificationsEnabled, boolean channelExists,
                                 int channelImportance, int highImportance,
                                 String channelSoundUri) {
        if (!notificationsEnabled) return State.NOTIFICATIONS_DISABLED;
        if (!channelExists || channelImportance <= 0) return State.CHANNEL_DISABLED;
        if (channelImportance < highImportance) return State.CHANNEL_LOW_IMPORTANCE;
        if (channelSoundUri == null || channelSoundUri.trim().isEmpty()) {
            return State.CHANNEL_SOUND_MISSING;
        }
        return State.CHANNEL_READY;
    }
}
