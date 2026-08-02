package com.ethscalper.cockpit;

import java.util.List;
import java.util.Map;

/**
 * Observation-only adapter for the authoritative legacy ETH lifecycle.
 * It deliberately receives public state by value and never writes public plan fields.
 */
final class LegacyEthShadowBridge {
    private final ShadowObservationEngine engine;

    LegacyEthShadowBridge() { this(new ShadowObservationEngine()); }
    LegacyEthShadowBridge(ShadowObservationEngine.OperationRunner runner) {
        this(new ShadowObservationEngine(runner));
    }
    LegacyEthShadowBridge(ShadowObservationEngine engine) {
        this.engine=engine==null?new ShadowObservationEngine():engine;
    }

    void observeTerminal(MarketRuntime runtime,MarketSnapshot snapshot,long now,
                         boolean marketFresh,boolean btcFresh,boolean productionActive,
                         long lastTerminalAt,List<MarketRuntime.MarketBar> bars) {
        ShadowObservationEngine.Context c=context(runtime,snapshot,now,marketFresh,btcFresh,
                productionActive,lastTerminalAt,bars,false);
        try { engine.safeObserveTerminal(c); }
        catch(RuntimeException failure){engine.safeRecordInternalError(c,"LEGACY_ETH_BRIDGE",
                "OBSERVE_TERMINAL",failure);}
    }

    void observeCandidate(MarketRuntime runtime,MarketWatchService.ObservedSignal item,
                          MarketSnapshot snapshot,long now,boolean marketFresh,boolean btcFresh,
                          boolean productionActive,long lastTerminalAt,
                          boolean tombstoned,List<MarketRuntime.MarketBar> bars) {
        if(item==null)return;ShadowObservationEngine.Context c=context(runtime,snapshot,now,
                marketFresh,btcFresh,productionActive,lastTerminalAt,bars,tombstoned);
        try {ShadowObservationEngine.Candidate candidate=adapt(item,item.signal);
            engine.safeConsiderAddedPlan(c,candidate);sync(item,candidate);
        }catch(RuntimeException failure){engine.safeRecordInternalError(c,"LEGACY_ETH_BRIDGE",
                "CONSIDER_OPEN",failure);}
    }

    void observeConfirmation(MarketRuntime runtime,MarketWatchService.ObservedSignal item,
                             SignalDecision sourceCandidate,MarketSnapshot snapshot,long now,
                             boolean marketFresh,boolean btcFresh,long lastTerminalAt,
                             CandidateLifecycle.FillResult result,
                             List<MarketRuntime.MarketBar> bars) {
        if(item==null||sourceCandidate==null)return;ShadowObservationEngine.Context c=context(runtime,
                snapshot,now,marketFresh,btcFresh,true,lastTerminalAt,bars,false);
        try {ShadowObservationEngine.Candidate candidate=adapt(item,sourceCandidate);
            String currentRevalidation=CandidateLifecycle.entryRevalidationCode(runtime.profile,
                    sourceCandidate,snapshot,snapshot==null?Double.NaN:snapshot.marketLast);
            engine.safeObserveProductionConfirmation(c,candidate,result,currentRevalidation);
            sync(item,candidate);
        }catch(RuntimeException failure){engine.safeRecordInternalError(c,"LEGACY_ETH_BRIDGE",
                "OBSERVE_CONFIRMATION",failure);}
    }

    void observeMissedMove(MarketRuntime runtime,MarketWatchService.ObservedSignal item,
                           MarketSnapshot snapshot,long now,boolean marketFresh,boolean btcFresh,
                           boolean productionActive,long lastTerminalAt,
                           List<MarketRuntime.MarketBar> bars,Map<String,Object> creationContext) {
        if(item==null)return;ShadowObservationEngine.Context c=context(runtime,snapshot,now,
                marketFresh,btcFresh,productionActive,lastTerminalAt,bars,false);
        try {ShadowObservationEngine.Candidate candidate=adapt(item,item.signal);
            engine.safeObserveMissedMove(c,candidate,creationContext);
        }catch(RuntimeException failure){engine.safeRecordInternalError(c,"LEGACY_ETH_BRIDGE",
                "OBSERVE_MISSED_MOVE",failure);}
    }

    void resetResearchMemory(){engine.resetResearchMemory();}

    private static ShadowObservationEngine.Context context(MarketRuntime runtime,
            MarketSnapshot snapshot,long now,boolean marketFresh,boolean btcFresh,
            boolean productionActive,long lastTerminalAt,List<MarketRuntime.MarketBar> bars,
            boolean tombstoned) {
        return new ShadowObservationEngine.Context(runtime,snapshot,now,marketFresh,btcFresh,
                productionActive,TerminalRearmPersistence.allowsNewCandidate(now,lastTerminalAt),
                tombstoned,bars);
    }

    private static ShadowObservationEngine.Candidate adapt(MarketWatchService.ObservedSignal item,
                                                            SignalDecision signal) {
        ShadowObservationEngine.Candidate out=new ShadowObservationEngine.Candidate(signal,item.sleeve,
                item.candidateSignature,item.createdAt,item.adverseExcursion60,
                item.prefillFavorableExcursion,!item.replayRiskReasonCode.isEmpty(),
                item.replayRiskReasonCode);
        out.lastShadowStateByComponent.putAll(item.lastShadowStateByComponent);
        out.shadowDuplicateEventsSuppressed=item.shadowDuplicateEventsSuppressed;
        out.extendedQualificationAt=item.shadowExtendedQualificationAt;
        out.extendedFirstExecutableAt=item.shadowExtendedFirstExecutableAt;
        out.solEarlyQualitySince=item.solEarlyQualitySince;out.solEarlyQualityMode=item.solEarlyQualityMode;
        out.solEarlyStabilityMs=item.solEarlyStabilityMs;out.solEarlyLastReasonCode=item.solEarlyLastReasonCode;
        out.solEarlyConfirmedAt=item.solEarlyConfirmedAt;
        return out;
    }

    private static void sync(MarketWatchService.ObservedSignal item,
                             ShadowObservationEngine.Candidate candidate) {
        item.lastShadowStateByComponent.clear();
        item.lastShadowStateByComponent.putAll(candidate.lastShadowStateByComponent);
        item.shadowDuplicateEventsSuppressed=candidate.shadowDuplicateEventsSuppressed;
        item.shadowExtendedQualificationAt=candidate.extendedQualificationAt;
        item.shadowExtendedFirstExecutableAt=candidate.extendedFirstExecutableAt;
        item.solEarlyQualitySince=candidate.solEarlyQualitySince;item.solEarlyQualityMode=candidate.solEarlyQualityMode;
        item.solEarlyStabilityMs=candidate.solEarlyStabilityMs;item.solEarlyLastReasonCode=candidate.solEarlyLastReasonCode;
        item.solEarlyConfirmedAt=candidate.solEarlyConfirmedAt;
    }
}
