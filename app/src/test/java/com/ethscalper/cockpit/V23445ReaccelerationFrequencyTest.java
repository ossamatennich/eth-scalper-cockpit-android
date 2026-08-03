package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

/** Functional V6 regressions for guarded frequency research. */
public final class V23445ReaccelerationFrequencyTest {
    @Test public void solP01GuardHasItsOwnExactBoundaries(){
        MarketProfile sol=MarketProfile.sol();
        NormalizedSignalMetrics.Result edge=ShadowTestFixtures.metrics(sol,"LONG",20_000,1,.75,1,1,.3,.5,.80,.2,2);
        assertEquals("SHADOW_SOL_P01_KEEP",ShadowCalibrationPolicy.solP01QualityGuard(95,edge,true,true).reasonCode);
        assertEquals("SHADOW_SOL_P01_SCORE_TOO_LOW",ShadowCalibrationPolicy.solP01QualityGuard(94,edge,true,true).reasonCode);
        assertEquals("SHADOW_SOL_P01_M1_TOO_WEAK",ShadowCalibrationPolicy.solP01QualityGuard(95,
                ShadowTestFixtures.metrics(sol,"LONG",20_000,1,.749,1,1,.3,.5,.80,.2,2),true,true).reasonCode);
        assertEquals("SHADOW_SOL_P01_VOLUME_TOO_LOW",ShadowCalibrationPolicy.solP01QualityGuard(95,
                ShadowTestFixtures.metrics(sol,"LONG",20_000,1,.75,1,1,.3,.5,.799,.2,2),true,true).reasonCode);
        assertEquals("SHADOW_SOL_P01_FEED_STALE",ShadowCalibrationPolicy.solP01QualityGuard(95,edge,false,true).reasonCode);
    }

    @Test public void reaccelerationBranchesAndBoundsAreDeterministic(){
        MarketProfile eth=MarketProfile.eth();SignalDecision c=executableCandidate(eth,"LONG");
        ShadowCalibrationPolicy.ReaccelerationDecision a=ShadowCalibrationPolicy.ethFlowReaccelerationV2(eth,c,
                ShadowTestFixtures.metrics(eth,"LONG",20_000,.80,.25,0,0,.22,.70,.50,.2,2));
        assertTrue(a.keep);assertEquals("BRANCH_A",a.branch);
        ShadowCalibrationPolicy.ReaccelerationDecision b=ShadowCalibrationPolicy.ethFlowReaccelerationV2(eth,c,
                ShadowTestFixtures.metrics(eth,"LONG",20_000,1.65,.15,2.50,0,1,.70,1.80,.2,2));
        assertTrue(b.keep);assertEquals("BRANCH_B",b.branch);
        assertFalse(ShadowCalibrationPolicy.ethFlowReaccelerationV2(eth,c,
                ShadowTestFixtures.metrics(eth,"LONG",20_000,1,-.001,3,1,1,.8,1,.2,2)).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowReaccelerationV2(MarketProfile.sol(),c,
                ShadowTestFixtures.metrics(eth,"LONG",20_000,1,.3,3,1,1,.8,1,.2,2)).keep);
    }

    @Test public void reaccelerationRequiresContinuousTenSecondsAndUsesFeeAwareQuantity(){
        MarketProfile eth=MarketProfile.eth();MarketRuntime runtime=new MarketRuntime(eth);
        SignalDecision signal=executableCandidate(eth,"LONG");
        ShadowObservationEngine.Candidate candidate=new ShadowObservationEngine.Candidate(signal,"P01","reaccel",20_000,0,0,false,"");
        ShadowObservationEngine engine=new ShadowObservationEngine();
        engine.safeConsiderAddedPlan(context(runtime,snapshot(eth,"LONG",30_000,.25,0,.22,.70,.50),30_000),candidate);
        assertNull(runtime.shadowResearch.active());assertEquals(30_000,candidate.reaccelQualitySince);
        engine.safeConsiderAddedPlan(context(runtime,snapshot(eth,"LONG",39_999,.25,0,.22,.70,.50),39_999),candidate);
        assertNull(runtime.shadowResearch.active());assertEquals(9_999,candidate.reaccelStabilityMs);
        engine.safeConsiderAddedPlan(context(runtime,snapshot(eth,"LONG",40_000,.25,0,.22,.70,.50),40_000),candidate);
        ShadowPlanState opened=runtime.shadowResearch.active();assertNotNull(opened);
        assertEquals(10_000,candidate.reaccelStabilityMs);assertEquals("ETH_FLOW_REACCELERATION_V2",opened.component);
        assertTrue(opened.quantity>0);
        Map<String,Object> event=runtime.recorder.eventMaps().stream().filter(e->"SHADOW_PLAN_OPENED".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertEquals("BRANCH_A",event.get("branch"));assertEquals(10_000L,event.get("stabilityMs"));
    }

    @Test public void staleOrBranchChangeResetsReaccelerationClock(){
        MarketProfile eth=MarketProfile.eth();MarketRuntime runtime=new MarketRuntime(eth);SignalDecision signal=executableCandidate(eth,"LONG");
        ShadowObservationEngine.Candidate c=new ShadowObservationEngine.Candidate(signal,"P01","reset",20_000,0,0,false,"");
        ShadowObservationEngine engine=new ShadowObservationEngine();MarketSnapshot a=snapshot(eth,"LONG",30_000,.25,0,.22,.70,.50);
        engine.safeConsiderAddedPlan(context(runtime,a,30_000),c);engine.safeConsiderAddedPlan(new ShadowObservationEngine.Context(
                runtime,snapshot(eth,"LONG",35_000,.25,0,.22,.70,.50),35_000,false,true,false,true,false,Collections.emptyList()),c);
        assertEquals(0,c.reaccelQualitySince);
        engine.safeConsiderAddedPlan(context(runtime,snapshot(eth,"LONG",36_000,.15,2.5,1,.70,.50),36_000),c);
        assertEquals("BRANCH_B",c.reaccelBranch);assertEquals(36_000,c.reaccelQualitySince);
    }

    @Test public void oldFlowBaselineAndRangeFadeNeverOpen(){
        MarketProfile eth=MarketProfile.eth();ShadowObservationEngine engine=new ShadowObservationEngine();
        MarketRuntime flowRuntime=new MarketRuntime(eth);ShadowObservationEngine.Candidate flow=new ShadowObservationEngine.Candidate(
                executableCandidate(eth,"LONG"),"P01","baseline",20_000,0,0,false,"");
        engine.safeConsiderAddedPlan(context(flowRuntime,snapshot(eth,"LONG",30_000,.10,0,.22,.70,.80),30_000),flow);
        assertNull(flowRuntime.shadowResearch.active());
        MarketRuntime rangeRuntime=new MarketRuntime(eth);SignalDecision range=SignalDecision.signal(eth,"LONG","RANGE_FADE",95,3,
                100.02,103.02,99.02,3,1,"P01",true,99,103,4);
        ShadowObservationEngine.Candidate rc=new ShadowObservationEngine.Candidate(range,"P01","range",0,0,0,false,"");
        engine.safeConsiderAddedPlan(context(rangeRuntime,ShadowTestFixtures.snapshot(eth,"LONG",20_000,1,.3,1,1,.3,.5,1,.05,3),20_000),rc);
        assertNull(rangeRuntime.shadowResearch.active());
        assertTrue(rangeRuntime.recorder.eventMaps().stream().anyMatch(e->"SHADOW_RANGE_RECLAIM_OBSERVATION".equals(e.get("eventType"))));
    }

    @Test public void noRetraceTelemetryDeduplicatesAndNeverCreatesPlan(){
        MarketProfile eth=MarketProfile.eth();MarketRuntime runtime=new MarketRuntime(eth);ShadowObservationEngine engine=new ShadowObservationEngine();
        SignalDecision signal=executableCandidate(eth,"LONG");ShadowObservationEngine.Candidate c=
                new ShadowObservationEngine.Candidate(signal,"P01","missed",1_000,.2,3.1,false,"");
        ShadowObservationEngine.Context context=context(runtime,snapshot(eth,"LONG",5_000,.25,0,.22,.70,.8),5_000);
        engine.safeObserveMissedMove(context,c,Collections.singletonMap("reasonCode","V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL"));
        engine.safeObserveMissedMove(context,c,Collections.singletonMap("reasonCode","V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL"));
        assertNull(runtime.shadowResearch.active());assertEquals(1,engine.telemetryRegistry().rememberedBreakouts());
        assertEquals(1,runtime.recorder.eventMaps().stream().filter(e->"SHADOW_NO_RETRACE_BREAKOUT_OBSERVATION".equals(e.get("eventType"))).count());
    }

    @Test public void telemetryRegistriesAreBoundedAt160(){
        ShadowTelemetryRegistry registry=new ShadowTelemetryRegistry();MarketProfile eth=MarketProfile.eth();
        for(int i=0;i<161;i++)registry.observeBreakout("m"+i,executableCandidate(eth,"LONG"),
                snapshot(eth,"LONG",i+1,.25,0,.22,.70,.8),0,i+1,0,3,"reason",0);
        assertEquals(160,registry.rememberedBreakouts());assertEquals(1,registry.breakoutEvictions());
    }

    @Test public void v6SummaryContainsAllNewComponentsAndTypedTelemetry(){
        ShadowExperimentSummary s=new ShadowExperimentSummary();
        s.safeTelemetryDeduplicated(ShadowCalibrationPolicy.ETH_RANGE_RECLAIM);
        Map<String,Object> out=s.snapshot(System.currentTimeMillis()+3_600_000L);
        assertEquals("SHADOW_SCHEMA_V6",out.get("shadowSchemaVersion"));
        @SuppressWarnings("unchecked") Map<String,Object> components=(Map<String,Object>)out.get("components");
        for(String name:new String[]{ShadowCalibrationPolicy.SOL_P01_MONITOR,ShadowCalibrationPolicy.ETH_REACCELERATION,
                ShadowCalibrationPolicy.ETH_RANGE_FADE_LONG,ShadowCalibrationPolicy.ETH_RANGE_RECLAIM,
                ShadowCalibrationPolicy.ETH_NO_RETRACE})assertTrue(name,components.containsKey(name));
        @SuppressWarnings("unchecked") Map<String,Object> range=(Map<String,Object>)components.get(ShadowCalibrationPolicy.ETH_RANGE_RECLAIM);
        assertTrue(range.get("deduplicatedEvents") instanceof Number);
    }

    private static SignalDecision executableCandidate(MarketProfile p,String side){int d="LONG".equals(side)?1:-1;
        double entry=100+d*.02;return SignalDecision.signal(p,side,"SCALP_CONTINUATION",95,5,entry,
                entry+d*3,entry-d,3,1,"P01",true,entry-d,entry+d*3,4);}
    private static MarketSnapshot snapshot(MarketProfile p,String side,long now,double m1,double m3,double f30,double f60,double volume){
        return ShadowTestFixtures.snapshot(p,side,now,1,m1,m3,.5,f30,f60,volume,.2,3);}
    private static ShadowObservationEngine.Context context(MarketRuntime runtime,MarketSnapshot snapshot,long now){
        return new ShadowObservationEngine.Context(runtime,snapshot,now,true,true,false,true,false,Collections.emptyList());}
}
