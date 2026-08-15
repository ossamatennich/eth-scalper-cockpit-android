package com.ethscalper.cockpit;

public final class V4ContinuationPolicy {
    private V4ContinuationPolicy(){}
    public static boolean mayCreateSecondSegment(V4Plan closed){return closed.source==V4Plan.Source.CORE&&closed.parentPlanId==null
            &&closed.status==V4Plan.Status.CLOSED_OTHER&&"Clôture avant reset".equals(closed.closeReason);}
    public static boolean freshWins(V4FeatureEngine.Candidate fresh,V4FeatureEngine.Candidate continuation){return fresh!=null&&continuation!=null&&fresh.asset.equals(continuation.asset);}
}
