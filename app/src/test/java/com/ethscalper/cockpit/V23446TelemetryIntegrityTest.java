package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Functional regressions for schema V7 telemetry integrity. */
public final class V23446TelemetryIntegrityTest {
    @Test public void rangeReclaimLongUsesMovementExtremeAtExactQuarterHalfAndThreeQuarters(){
        MarketProfile p=MarketProfile.eth();ShadowTelemetryRegistry r=new ShadowTelemetryRegistry();
        SignalDecision signal=range(p,"LONG",110,100);NormalizedSignalMetrics.Result m=metrics(p,"LONG",2);
        ShadowTelemetryRegistry.RangeUpdate before=r.observeRange("long","sig-long",signal,m,quote(p,1,100.49),1,.2,true,true);
        assertNull(before.details.get("firstPriceReclaim025AAt"));
        Map<String,Object> q=r.observeRange("long","sig-long",signal,m,quote(p,2,100.50),2,.3,true,true).details;
        assertEquals(2L,q.get("firstPriceReclaim025AAt"));assertNull(q.get("firstPriceReclaim050AAt"));
        q=r.observeRange("long","sig-long",signal,m,quote(p,3,101.00),3,.4,true,true).details;
        assertEquals(3L,q.get("firstPriceReclaim050AAt"));
        q=r.observeRange("long","sig-long",signal,m,quote(p,4,101.50),4,.5,true,true).details;
        assertEquals(4L,q.get("firstPriceReclaim075AAt"));assertEquals("MOVEMENT_EXTREME",q.get("reclaimReference"));
        assertEquals(100d,(Double)q.get("reclaimReferencePrice"),0d);assertEquals(.75,(Double)q.get("reclaimInA"),1e-12);
        assertEquals(-4.25,(Double)q.get("distanceFromMovementOriginInA"),1e-12);
        assertEquals(.5,(Double)q.get("maxAdverseBeforeReclaim075A"),1e-12);
    }

    @Test public void rangeReclaimShortIsSymmetricFromMovementExtreme(){
        MarketProfile p=MarketProfile.eth();ShadowTelemetryRegistry r=new ShadowTelemetryRegistry();
        SignalDecision signal=range(p,"SHORT",100,110);NormalizedSignalMetrics.Result m=metrics(p,"SHORT",2);
        assertNull(r.observeRange("short","sig-short",signal,m,quote(p,1,109.51),1,.1,true,true).details.get("firstPriceReclaim025AAt"));
        assertEquals(2L,r.observeRange("short","sig-short",signal,m,quote(p,2,109.50),2,.2,true,true).details.get("firstPriceReclaim025AAt"));
        assertEquals(3L,r.observeRange("short","sig-short",signal,m,quote(p,3,109.00),3,.3,true,true).details.get("firstPriceReclaim050AAt"));
        Map<String,Object> q=r.observeRange("short","sig-short",signal,m,quote(p,4,108.50),4,.4,true,true).details;
        assertEquals(4L,q.get("firstPriceReclaim075AAt"));assertEquals(.75,(Double)q.get("reclaimInA"),1e-12);
    }

    @Test public void noRetraceUsesOfficialComponentAndBoundedSnapshotWithoutFakePlan(){
        MarketProfile p=MarketProfile.eth();MarketRuntime runtime=new MarketRuntime(p);ShadowObservationEngine engine=new ShadowObservationEngine();
        SignalDecision signal=continuation(p);ShadowObservationEngine.Candidate c=new ShadowObservationEngine.Candidate(
                signal,"P01","no-retrace-signature",1_000,.2,3.1,false,"");
        ShadowObservationEngine.Context context=context(runtime,quote(p,5_000,100),5_000);
        engine.safeObserveMissedMove(context,c,Collections.singletonMap("reasonCode","V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL"));
        engine.safeObserveMissedMove(context,c,Collections.singletonMap("reasonCode","V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL"));
        List<Map<String,Object>> events=runtime.recorder.eventMaps();
        assertEquals(1,events.stream().filter(e->"SHADOW_NO_RETRACE_BREAKOUT_OBSERVATION".equals(e.get("eventType"))).count());
        Map<String,Object> event=events.stream().filter(e->"SHADOW_NO_RETRACE_BREAKOUT_OBSERVATION".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertEquals(ShadowCalibrationPolicy.ETH_NO_RETRACE,event.get("component"));
        assertNotEquals(ShadowObservationEngine.ETH_BTC_LED_BREAKOUT_RESEARCH,event.get("component"));
        assertEquals("no-retrace-signature",event.get("candidateSignature"));assertNull(runtime.shadowResearch.active());
        assertFalse(events.stream().anyMatch(e->String.valueOf(e.get("eventType")).contains("SHADOW_TP_TOUCHED")
                ||String.valueOf(e.get("eventType")).contains("SHADOW_SL_TOUCHED")));
        @SuppressWarnings("unchecked") List<Map<String,Object>> movements=(List<Map<String,Object>>)runtime.shadowExperiment.snapshot(6_000).get("noRetraceMovements");
        assertEquals(1,movements.size());assertEquals(1L,movements.get(0).get("duplicatesGrouped"));
    }

    @Test public void activeShadowPlanResetsReaccelerationAndFullTenSecondsAreRequiredAgain(){
        for(String component:new String[]{ShadowCalibrationPolicy.PULLBACK,ShadowCalibrationPolicy.ETH_REACCELERATION})
            for(boolean tp:new boolean[]{true,false})verifyActiveReset(component,tp);
    }

    @Test public void telemetrySnapshotsAreFifoBoundedAndDuplicateCountsExact(){
        ShadowTelemetryRegistry r=new ShadowTelemetryRegistry();MarketProfile p=MarketProfile.eth();
        NormalizedSignalMetrics.Result m=metrics(p,"LONG",2);
        for(int i=0;i<161;i++){
            SignalDecision range=range(p,"LONG",110+i,100+i);
            r.observeRange("r"+i,"rs"+i,range,m,quote(p,i+1,100+i),i+1,0,true,true);
            r.observeBreakout("b"+i,"bs"+i,continuation(p),quote(p,i+1,100),0,i+1,0,3,"V2329",0);
        }
        r.observeBreakout("b160","bs160",continuation(p),quote(p,200,100),0,200,0,3,"V2329",0);
        r.observeBreakout("b160","bs160",continuation(p),quote(p,201,100),0,201,0,3,"V2329",0);
        assertEquals(160,r.rangeSnapshots().size());assertEquals(160,r.noRetraceSnapshots().size());
        assertEquals("r1",r.rangeSnapshots().get(0).get("movementKey"));assertEquals("b1",r.noRetraceSnapshots().get(0).get("movementKey"));
        assertEquals(2L,r.noRetraceSnapshots().get(159).get("duplicatesGrouped"));
        assertFalse(String.valueOf(r.rangeSnapshots()).contains("NaN"));assertFalse(String.valueOf(r.noRetraceSnapshots()).contains("Infinity"));
    }

    private static void verifyActiveReset(String activeComponent,boolean tp){
        MarketProfile p=MarketProfile.eth();MarketRuntime runtime=new MarketRuntime(p);ShadowObservationEngine engine=new ShadowObservationEngine();
        ShadowObservationEngine.Candidate c=new ShadowObservationEngine.Candidate(continuation(p),"P01","reset-"+activeComponent+tp,20_000,0,0,false,"");
        engine.safeConsiderAddedPlan(context(runtime,reaccelQuote(p,30_000),30_000),c);
        engine.safeConsiderAddedPlan(context(runtime,reaccelQuote(p,36_000),36_000),c);assertEquals(6_000,c.reaccelStabilityMs);
        ShadowPlanState blocker=plan(activeComponent,"blocker-"+activeComponent+tp);assertTrue(runtime.shadowResearch.open(blocker));
        engine.safeConsiderAddedPlan(context(runtime,reaccelQuote(p,37_000),37_000),c);
        assertEquals(0,c.reaccelQualitySince);assertEquals("",c.reaccelBranch);assertEquals(0,c.reaccelStabilityMs);
        assertEquals("SHADOW_ETH_REACCEL_SHADOW_PLAN_ACTIVE",c.reaccelLastReasonCode);
        double terminalQuote=tp?blocker.tp:blocker.sl;assertNotNull(runtime.shadowResearch.observe(38_000,terminalQuote,terminalQuote,true));
        long restart=38_000+ShadowResearchCoordinator.COOLDOWN_MS;
        engine.safeConsiderAddedPlan(context(runtime,reaccelQuote(p,restart),restart),c);
        engine.safeConsiderAddedPlan(context(runtime,reaccelQuote(p,restart+9_999),restart+9_999),c);assertNull(runtime.shadowResearch.active());
        engine.safeConsiderAddedPlan(context(runtime,reaccelQuote(p,restart+10_000),restart+10_000),c);assertNotNull(runtime.shadowResearch.active());
    }

    private static SignalDecision continuation(MarketProfile p){return SignalDecision.signal(p,"LONG","SCALP_CONTINUATION",95,5,
            100.02,103.02,99.02,3,1,"P01",true,99,103,4);}
    private static SignalDecision range(MarketProfile p,String side,double origin,double extreme){int d="LONG".equals(side)?1:-1;
        return SignalDecision.signal(p,side,"RANGE_FADE",95,3,105,105+d*20,105-d*20,20,20,"P01",true,origin,extreme,10);}
    private static NormalizedSignalMetrics.Result metrics(MarketProfile p,String side,double a){return ShadowTestFixtures.metrics(p,side,1,a,.3,1,1,.3,.5,1,.05,3);}
    private static MarketSnapshot quote(MarketProfile p,long now,double last){return MarketSnapshot.builder(now).market(p,last,last-.01,last+.01)
            .btc(60_000,59_999,60_001).averages(2,100).movement(0,0,0,last+6,last-6).move15(0)
            .flow(0,100).professionalFeatures(12,1,.5,6,6,6,6,0,0,0,0,0,0,0,0,0,0)
            .flowWindows(0,0,0,0).btcMoves(0,0,0,0).candleCounts(180,180).build();}
    private static MarketSnapshot reaccelQuote(MarketProfile p,long now){return ShadowTestFixtures.snapshot(p,"LONG",now,1,.25,0,.5,.22,.70,.8,.2,3);}
    private static ShadowObservationEngine.Context context(MarketRuntime runtime,MarketSnapshot x,long now){return new ShadowObservationEngine.Context(
            runtime,x,now,true,true,false,true,false,Collections.emptyList());}
    private static ShadowPlanState plan(String component,String id){return new ShadowPlanState(id,component,id,MarketProfile.eth(),"LONG","P01",
            0,1_000,100,103,99,1,3,3,7,14.55,.1,1,.2);}
}
