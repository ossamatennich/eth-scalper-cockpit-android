package com.ethscalper.cockpit;

/** Pure thresholds for the v2.34.4.7 shadow-only quality/frequency experiment. */
public final class ShadowCalibrationPolicy {
    public static final String VERSION = "SHADOW_V23447_20260804";
    public static final String SCHEMA_VERSION = "SHADOW_SCHEMA_V8";
    public static final String ETH_P01_GUARD = "ETH_P01_FINAL_CONFIRMATION_GUARD";
    public static final String SOL_P01_MONITOR = "SOL_P01_QUALITY_GUARD_V2";
    public static final String P02_GUARD = "P02_ANTI_EXHAUSTION";
    public static final String PULLBACK = "P01_PULLBACK_RESUMPTION";
    public static final String ETH_MID_VOL = "ETH_MID_VOL_TREND_EXPANSION";
    public static final String ETH_FLOW_EXTENDED = "ETH_FLOW_EXPANSION_EXTENDED";
    public static final String SOL_EARLY = "SOL_P01_EARLY_RESUMPTION";
    public static final String ETH_FLOW_HIGH_CONFIDENCE = "ETH_FLOW_CONTINUATION_HIGH_CONFIDENCE_BASELINE";
    public static final String ETH_REACCELERATION = "ETH_FLOW_REACCELERATION_V2";
    public static final String ETH_RANGE_FADE_LONG = "ETH_RANGE_FADE_QUARANTINE";
    public static final String ETH_RANGE_RECLAIM = "ETH_RANGE_RECLAIM_RESEARCH";
    public static final String ETH_NO_RETRACE = "ETH_NO_RETRACE_BREAKOUT_RESEARCH";

    private static final double EPS = 1e-12;
    private ShadowCalibrationPolicy() {}

    public static Decision p01FinalGuard(int score, NormalizedSignalMetrics.Result m,
                                         P01SleeveFilter.Result filter,
                                         String entryRevalidationCode) {
        if (score < 95) return block("SHADOW_P01_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_P01_METRICS_INVALID");
        if (filter == null || !filter.accepted) return block("SHADOW_P01_PUBLIC_FILTER_REJECTED");
        if (isCriticalCurrentRevalidation(entryRevalidationCode))
            return block("SHADOW_P01_CURRENT_REVALIDATION_BLOCKED");
        if (m.volumeRatio > 1.50 + EPS) return block("SHADOW_P01_VOLUME_TOO_HIGH");
        if (m.room + EPS < 1.00) return block("SHADOW_P01_ROOM_TOO_LOW");
        if (m.f60 + EPS < .40) return block("SHADOW_P01_FLOW60_TOO_LOW");
        if (m.f60 > 1.00 + EPS) return block("SHADOW_P01_FLOW60_TOO_HIGH");
        if (m.directionalEdge > .60 + EPS) return block("SHADOW_P01_DIRECTIONAL_EDGE_EXHAUSTED");
        if (m.m8 > 3.50 + EPS) return block("SHADOW_P01_MOVE8_OVEREXTENDED");
        if (m.f30 + EPS < .15) return block("SHADOW_P01_FLOW30_TOO_LOW");
        if ("EARLY".equals(filter.phase)
                && !(filter.flowBacked || filter.priceLed))
            return block("SHADOW_P01_EARLY_NOT_FLOW_OR_PRICE_BACKED");
        return keep("SHADOW_P01_KEEP");
    }

    /** Symbolic only: ETH keeps the strict research guard; SOL records its public baseline. */
    public static Decision p01Symbolic(MarketProfile profile,int score,
                                       NormalizedSignalMetrics.Result metrics,
                                       P01SleeveFilter.Result filter,String revalidation) {
        if(profile!=null&&MarketProfile.ETH_SYMBOL.equals(profile.symbol))
            return p01FinalGuard(score,metrics,filter,revalidation);
        if(profile!=null&&MarketProfile.SOL_SYMBOL.equals(profile.symbol))
            return solP01QualityGuard(score,metrics,true,true);
        return block("UNSUPPORTED_SHADOW_PROFILE");
    }

    public static Decision solP01QualityGuard(int score,NormalizedSignalMetrics.Result metrics,
                                               boolean marketFresh,boolean btcFresh) {
        if(!marketFresh||!btcFresh)return block("SHADOW_SOL_P01_FEED_STALE");
        if(score<95)return block("SHADOW_SOL_P01_SCORE_TOO_LOW");
        if(metrics==null||!metrics.valid)return block("SHADOW_SOL_P01_METRICS_INVALID");
        if(metrics.m1+EPS<.75)return block("SHADOW_SOL_P01_M1_TOO_WEAK");
        if(metrics.volumeRatio+EPS<.80)return block("SHADOW_SOL_P01_VOLUME_TOO_LOW");
        return keep("SHADOW_SOL_P01_KEEP");
    }

    public static String p01Component(MarketProfile profile) {
        if(profile!=null&&MarketProfile.ETH_SYMBOL.equals(profile.symbol))return ETH_P01_GUARD;
        if(profile!=null&&MarketProfile.SOL_SYMBOL.equals(profile.symbol))return SOL_P01_MONITOR;
        return "UNSUPPORTED_SHADOW_PROFILE";
    }

    public static Decision p02AntiExhaustion(int score, NormalizedSignalMetrics.Result m) {
        if (m == null || !m.valid) return block("SHADOW_P02_METRICS_INVALID");
        if (m.volumeRatio + EPS < .30) return block("SHADOW_P02_VOLUME_TOO_LOW");
        if (m.room + EPS < .30) return block("SHADOW_P02_ROOM_TOO_LOW");
        if (m.f60 + EPS < 0.0) return block("SHADOW_P02_FLOW60_OPPOSED");
        if (m.directionalEdge > .85 + EPS)
            return block("SHADOW_P02_DIRECTIONAL_EDGE_EXHAUSTED");
        if (m.m8 > 3.50 + EPS) return block("SHADOW_P02_MOVE8_OVEREXTENDED");
        return keep("SHADOW_P02_KEEP");
    }

    /** Symbol-aware shadow classification. It never changes the public P02 result. */
    public static Decision p02Symbolic(MarketProfile profile, int score,
                                       NormalizedSignalMetrics.Result m) {
        if (profile != null && MarketProfile.SOL_SYMBOL.equals(profile.symbol))
            return block("SHADOW_SOL_P02_QUARANTINE");
        if (profile != null && MarketProfile.ETH_SYMBOL.equals(profile.symbol) && score < 85)
            return block("SHADOW_ETH_P02_SCORE_TOO_LOW");
        return p02AntiExhaustion(score, m);
    }

    public static Decision pullback(int score, NormalizedSignalMetrics.Result m) {
        if (score < 95) return block("SHADOW_PULLBACK_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_PULLBACK_METRICS_INVALID");
        if (m.volumeRatio > 1.20 + EPS) return block("SHADOW_PULLBACK_VOLUME_TOO_HIGH");
        if (m.room + EPS < 1.50) return block("SHADOW_PULLBACK_ROOM_TOO_LOW");
        if (m.f30 + EPS < .50) return block("SHADOW_PULLBACK_FLOW30_TOO_LOW");
        if (m.f60 + EPS < .60 || m.f60 > 1.10 + EPS)
            return block("SHADOW_PULLBACK_FLOW60_OUTSIDE_RANGE");
        if (m.m1 + EPS < .60) return block("SHADOW_PULLBACK_MOVE1_TOO_LOW");
        if (m.m3 + EPS < 1.50) return block("SHADOW_PULLBACK_MOVE3_TOO_LOW");
        if (m.m8 + EPS < -3.00 || m.m8 >= -1.00 - EPS)
            return block("SHADOW_PULLBACK_MOVE8_OUTSIDE_RANGE");
        if (m.directionalEdge > .55 + EPS)
            return block("SHADOW_PULLBACK_DIRECTIONAL_EDGE_EXHAUSTED");
        return keep("SHADOW_PULLBACK_KEEP");
    }

    public static Decision ethMidVol(MarketProfile profile, int score,
                                     NormalizedSignalMetrics.Result m) {
        if (profile == null || !MarketProfile.ETH_SYMBOL.equals(profile.symbol))
            return block("SHADOW_MID_VOL_ETH_ONLY");
        if (score < 95) return block("SHADOW_MID_VOL_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_MID_VOL_METRICS_INVALID");
        if (m.a + EPS < 1.20 || m.a > 1.65 + EPS)
            return block("SHADOW_MID_VOL_A_OUTSIDE_RANGE");
        if (m.m1 <= -.30 + EPS) return block("SHADOW_MID_VOL_MOVE1_TOO_LOW");
        if (m.m8 + EPS < 0.0) return block("SHADOW_MID_VOL_MOVE8_TOO_LOW");
        if (m.f30 + EPS < .22) return block("SHADOW_MID_VOL_FLOW30_TOO_LOW");
        if (m.f60 + EPS < .60) return block("SHADOW_MID_VOL_FLOW60_TOO_LOW");
        return keep("SHADOW_MID_VOL_KEEP");
    }

    public static Decision ethFlowExpansionExtended(MarketProfile profile, int score,
                                                     NormalizedSignalMetrics.Result m) {
        if (profile == null || !MarketProfile.ETH_SYMBOL.equals(profile.symbol))
            return block("SHADOW_FLOW_EXTENDED_ETH_ONLY");
        if (score < 95) return block("SHADOW_FLOW_EXTENDED_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_FLOW_EXTENDED_METRICS_INVALID");
        if (m.a + EPS < .80 || m.a > 1.65 + EPS)
            return block("SHADOW_FLOW_EXTENDED_A_OUTSIDE_RANGE");
        if (m.m1 <= -.30 + EPS) return block("SHADOW_FLOW_EXTENDED_MOVE1_TOO_LOW");
        if (m.m3 + EPS < 1.00) return block("SHADOW_FLOW_EXTENDED_MOVE3_TOO_LOW");
        if (m.m8 + EPS < 0.0) return block("SHADOW_FLOW_EXTENDED_MOVE8_TOO_LOW");
        if (m.f30 + EPS < .22) return block("SHADOW_FLOW_EXTENDED_FLOW30_TOO_LOW");
        if (m.f60 + EPS < .70) return block("SHADOW_FLOW_EXTENDED_FLOW60_TOO_LOW");
        return keep("SHADOW_FLOW_EXTENDED_KEEP");
    }

    public static Decision ethFlowContinuationHighConfidence(MarketProfile profile,
            SignalDecision candidate,NormalizedSignalMetrics.Result m) {
        if(profile==null||!MarketProfile.ETH_SYMBOL.equals(profile.symbol))
            return block("SHADOW_FLOW_HIGH_CONFIDENCE_ETH_ONLY");
        if(candidate==null||candidate.family==null||!candidate.family.contains("CONTINUATION"))
            return block("SHADOW_FLOW_HIGH_CONFIDENCE_FAMILY");
        if(candidate.score<95)return block("SHADOW_FLOW_HIGH_CONFIDENCE_SCORE_TOO_LOW");
        if(m==null||!m.valid)return block("SHADOW_FLOW_HIGH_CONFIDENCE_METRICS_INVALID");
        if(m.a+EPS<.80||m.a>1.65+EPS)return block("SHADOW_FLOW_HIGH_CONFIDENCE_A_OUTSIDE_RANGE");
        if(m.m1<=-.30+EPS)return block("SHADOW_FLOW_HIGH_CONFIDENCE_MOVE1_TOO_LOW");
        if(m.m3+EPS<0)return block("SHADOW_FLOW_HIGH_CONFIDENCE_MOVE3_TOO_LOW");
        if(m.m8+EPS<0)return block("SHADOW_FLOW_HIGH_CONFIDENCE_MOVE8_TOO_LOW");
        if(m.f30+EPS<.22)return block("SHADOW_FLOW_HIGH_CONFIDENCE_FLOW30_TOO_LOW");
        if(m.f60+EPS<.70)return block("SHADOW_FLOW_HIGH_CONFIDENCE_FLOW60_TOO_LOW");
        if(m.volumeRatio>1.80+EPS)return block("SHADOW_FLOW_HIGH_CONFIDENCE_VOLUME_TOO_HIGH");
        return keep("SHADOW_FLOW_HIGH_CONFIDENCE_KEEP");
    }

    /** Candidate-only quality classification; operational prerequisites and stability live in the engine. */
    public static ReaccelerationDecision ethFlowReaccelerationV2(MarketProfile profile,
            SignalDecision candidate,NormalizedSignalMetrics.Result m) {
        if(profile==null||!MarketProfile.ETH_SYMBOL.equals(profile.symbol)||candidate==null)
            return ReaccelerationDecision.block("SHADOW_ETH_REACCEL_PROFILE_REJECTED");
        if(candidate.family==null||!candidate.family.contains("CONTINUATION")||candidate.score<95)
            return ReaccelerationDecision.block("SHADOW_ETH_REACCEL_PROFILE_REJECTED");
        if(m==null||!m.valid||m.a+EPS<.80||m.a>1.65+EPS||m.m3+EPS<0||m.m8+EPS<0
                ||m.f30+EPS<.22||m.f60+EPS<.70||m.volumeRatio+EPS<.50||m.volumeRatio>1.80+EPS)
            return ReaccelerationDecision.block("SHADOW_ETH_REACCEL_PROFILE_REJECTED");
        if(m.m1<-.0-EPS)return ReaccelerationDecision.block("SHADOW_ETH_REACCEL_M1_TOO_WEAK");
        if(m.m1+EPS>=.25)return ReaccelerationDecision.keep("BRANCH_A","SHADOW_ETH_REACCEL_BRANCH_A");
        if(m.m1+EPS>=.15&&m.m3+EPS>=2.50&&m.f30+EPS>=1.00)
            return ReaccelerationDecision.keep("BRANCH_B","SHADOW_ETH_REACCEL_BRANCH_B");
        return ReaccelerationDecision.block("SHADOW_ETH_REACCEL_M1_TOO_WEAK");
    }

    public static Decision ethRangeFadeLongHighConfidence(MarketProfile profile,
            SignalDecision candidate,NormalizedSignalMetrics.Result m) {
        if(profile==null||!MarketProfile.ETH_SYMBOL.equals(profile.symbol))
            return block("SHADOW_RANGE_FADE_ETH_ONLY");
        if(candidate==null||!"LONG".equals(candidate.side)||candidate.family==null
                ||!candidate.family.contains("RANGE_FADE"))
            return block("SHADOW_RANGE_FADE_LONG_FAMILY_REQUIRED");
        if(candidate.score<95)return block("SHADOW_RANGE_FADE_SCORE_TOO_LOW");
        if(m==null||!m.valid)return block("SHADOW_RANGE_FADE_METRICS_INVALID");
        if(m.f30+EPS<.30)return block("SHADOW_RANGE_FADE_FLOW30_TOO_LOW");
        if(m.directionalEdge>.10+EPS)return block("SHADOW_RANGE_FADE_DIRECTIONAL_EDGE_TOO_HIGH");
        if(m.room+EPS<2.50)return block("SHADOW_RANGE_FADE_ROOM_TOO_LOW");
        return keep("SHADOW_RANGE_FADE_LONG_KEEP");
    }

    public static boolean isCriticalCurrentRevalidation(String code) {
        if (code == null || code.isEmpty() || "PRIX_DEJA_TROP_LOIN".equals(code)) return false;
        if (ContinuationConfirmation.P01_MOVE1_REJECT.equals(code)
                || ContinuationConfirmation.P01_MOVE3_REJECT.equals(code)
                || ContinuationConfirmation.C04_REJECT.equals(code)
                || ContinuationConfirmation.C07_REJECT.equals(code)
                || ContinuationConfirmation.C08_REJECT.equals(code)
                || ContinuationConfirmation.P01_FLOW_REJECT.equals(code)) return true;
        String c = java.text.Normalizer.normalize(code, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(java.util.Locale.ROOT);
        return c.contains("MOVE1") || c.contains("MOVE3") || c.contains("FRAICHEUR")
                || c.contains("FEED_STALE") || c.contains("STALE")
                || c.contains("CONFLIT_1M_8M") || c.contains("FLOW_OPPOSE")
                || c.contains("REPLAY_QUALITY") || c.contains("REPLAY_QUALITE")
                || c.contains("MOUVEMENT_CONSOMME") || c.contains("CONTINUATION_CONSOMMEE")
                || c.contains("DIVERGENCE_FLOW_PRIX");
    }

    public static boolean targetUntouchedBeforeOpen(SignalDecision candidate,double favorable) {
        return candidate!=null&&Double.isFinite(candidate.targetMove)&&candidate.targetMove>0
                &&Double.isFinite(favorable)&&favorable+EPS<candidate.targetMove;
    }

    private static Decision keep(String reason) { return new Decision(true, "KEEP", reason); }
    private static Decision block(String reason) { return new Decision(false, "BLOCK", reason); }

    public static final class Decision {
        public final boolean keep;
        public final String decision, reasonCode;
        private Decision(boolean keep, String decision, String reasonCode) {
            this.keep=keep;this.decision=decision;this.reasonCode=reasonCode;
        }
    }
    public static final class ReaccelerationDecision {
        public final boolean keep;public final String branch,reasonCode;
        private ReaccelerationDecision(boolean keep,String branch,String reason){this.keep=keep;this.branch=branch;reasonCode=reason;}
        static ReaccelerationDecision keep(String branch,String reason){return new ReaccelerationDecision(true,branch,reason);}
        static ReaccelerationDecision block(String reason){return new ReaccelerationDecision(false,"",reason);}
    }
}
