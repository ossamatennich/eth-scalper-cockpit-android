package com.ethscalper.cockpit;

/** Bounded foreground recovery policy. It never changes market decisions or plan state. */
public final class MarketServiceRecoveryPolicy {
    private static final long[] DELAYS_MS={1_500L,5_000L,12_000L,30_000L};

    private MarketServiceRecoveryPolicy() {}

    public static long delayForAttempt(int attempt){
        int index=Math.max(0,Math.min(attempt,DELAYS_MS.length-1));
        return DELAYS_MS[index];
    }

    public static boolean isOperational(boolean nativeActive,boolean connected,long feedAgeSec){
        return nativeActive&&connected&&feedAgeSec>=0&&feedAgeSec<=10;
    }
}
