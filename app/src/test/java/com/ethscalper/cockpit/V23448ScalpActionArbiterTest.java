package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class V23448ScalpActionArbiterTest {
    @Test public void rawRangeBeatsLegacyMove3InSameCycle(){
        ScalpActionEngine e=engine();ScalpActionCandidateArbiter a=new ScalpActionCandidateArbiter();a.beginCycle(10);
        ScalpActionEngine.Result legacy=e.observeLegacy("SHORT","CONTINUATION","P01",-.3,snap(.5),metrics(),common("ACTION_ON"),10);
        ScalpActionEngine.Result raw=e.observeRaw(sig("SHORT","RANGE"),snap(.99),metrics(),common("ACTION_ON"),10);
        a.collect(legacy,snap(.5),10);a.collect(raw,snap(.99),10);
        ScalpActionCandidateArbiter.Resolution r=a.resolve();assertEquals(ScalpActionPolicy.RANGE_EXTREME,r.winner.result.route);
        assertEquals(1,r.notSelected.size());assertFalse(legacy.episode.opened);assertFalse(raw.episode.opened);
    }

    @Test public void legacyP01BeatsRawCoverage(){
        ScalpActionEngine e=engine();ScalpActionCandidateArbiter a=new ScalpActionCandidateArbiter();a.beginCycle(10);
        ScalpActionContextTracker.Metrics lowRv=new ScalpActionContextTracker.Metrics(0,true,.00001,true,1,true);
        ScalpActionEngine.Result legacy=e.observeLegacy("SHORT","CONTINUATION","P01",-.5,snap(.5),lowRv,common("ACTION_ON"),10);
        ScalpActionEngine.Result raw=e.observeRaw(sig("SHORT","CONTINUATION"),snap(.5),lowRv,common("ACTION_ON"),10);
        a.collect(raw,snap(.5),10);a.collect(legacy,snap(.5),10);
        assertEquals(ScalpActionPolicy.P01_SHORT_MICROVOL,a.resolve().winner.result.route);
    }

    @Test public void legacyOrderCannotChangeWinner(){
        assertEquals(ScalpActionPolicy.CONFIRM_MOVE3.routeId,winnerForLegacyOrder(false));
        assertEquals(ScalpActionPolicy.CONFIRM_MOVE3.routeId,winnerForLegacyOrder(true));
    }

    @Test public void oppositeSidesStillYieldOneWinner(){
        ScalpActionEngine e=engine();ScalpActionCandidateArbiter a=new ScalpActionCandidateArbiter();a.beginCycle(1);
        ScalpActionEngine.Result shortRange=e.observeRaw(sig("SHORT","RANGE"),snap(.99),metrics(),common("ACTION_ON"),1);
        ScalpActionContextTracker.Metrics reversal=new ScalpActionContextTracker.Metrics(-.01,true,0,true,0,true);
        ScalpActionEngine.Result longReversal=e.observeRaw(sig("LONG","OTHER"),snap(.5),reversal,common("ACTION_ON"),1);
        a.collect(longReversal,snap(.5),1);a.collect(shortRange,snap(.99),1);
        ScalpActionCandidateArbiter.Resolution r=a.resolve();assertEquals(ScalpActionPolicy.RANGE_EXTREME,r.winner.result.route);assertEquals(1,r.notSelected.size());
    }

    @Test public void diagnosticsOnlyUsesSamePriorityAndOneVirtualWinner(){
        ScalpActionEngine e=engine();ScalpActionCandidateArbiter a=new ScalpActionCandidateArbiter();a.beginCycle(1);
        ScalpActionEngine.Result legacy=e.observeLegacy("SHORT","CONTINUATION","P01",-.3,snap(.5),metrics(),common("DIAGNOSTICS_ONLY"),1);
        ScalpActionEngine.Result raw=e.observeRaw(sig("SHORT","RANGE"),snap(.99),metrics(),common("DIAGNOSTICS_ONLY"),1);
        a.collect(legacy,snap(.5),1);a.collect(raw,snap(.99),1);ScalpActionCandidateArbiter.Resolution r=a.resolve();
        assertTrue(r.winner.result.virtualQualified);assertEquals(ScalpActionPolicy.RANGE_EXTREME,r.winner.result.route);
        assertTrue(e.markOpened(r.winner.result));assertFalse(e.markOpened(r.notSelected.get(0).result));
    }

    @Test public void lexicalTieBreakIsDeterministicAfterPriorityAndTime(){
        ScalpActionEngine e=engine();ScalpActionEngine.Result result=e.observeRaw(sig("SHORT","RANGE"),snap(.99),metrics(),common("ACTION_ON"),1);
        ScalpActionCandidateArbiter a=new ScalpActionCandidateArbiter();a.beginCycle(1);a.collect(result,snap(.99),2);a.collect(result,snap(.99),1);
        assertEquals(1,a.resolve().winner.observedAt);
    }

    private static String winnerForLegacyOrder(boolean reverse){
        ScalpActionEngine e=engine();ScalpActionContextTracker.Metrics lowRv=new ScalpActionContextTracker.Metrics(0,true,.00001,true,1,true);
        ScalpActionEngine.Result move3=e.observeLegacy("LONG","CONTINUATION","P01",-.3,snap(.5),lowRv,common("ACTION_ON"),10);
        ScalpActionEngine.Result micro=e.observeLegacy("SHORT","CONTINUATION","P01",-.5,snap(.5),lowRv,common("ACTION_ON"),10);
        ScalpActionCandidateArbiter a=new ScalpActionCandidateArbiter();a.beginCycle(10);
        if(reverse){a.collect(micro,snap(.5),10);a.collect(move3,snap(.5),10);}else{a.collect(move3,snap(.5),10);a.collect(micro,snap(.5),10);}
        return a.resolve().winner.result.route.routeId;
    }

    private static ScalpActionEngine engine(){return new ScalpActionEngine(new ScalpActionMovementRegistry());}
    private static ScalpActionEngine.Common common(String mode){return new ScalpActionEngine.Common(mode,true,true,true,false,false,10);}
    private static ScalpActionContextTracker.Metrics metrics(){return new ScalpActionContextTracker.Metrics(0,true,0,true,1,true);}
    private static SignalDecision sig(String side,String family){return SignalDecision.signal(MarketProfile.eth(),side,family,1,1,1900,1901,1899,1,1,"",false,0,0,0);}
    private static MarketSnapshot snap(double pos){return MarketSnapshot.builder(1).eth(1900,1899.99,1900.01).btc(1,1,1).averages(2.1,1).movement(0,0,0,1910,1890).professionalFeatures(20,1,pos,1,1,1,1,0,0,0,0,0,0,0,0,0,0).build();}
}
