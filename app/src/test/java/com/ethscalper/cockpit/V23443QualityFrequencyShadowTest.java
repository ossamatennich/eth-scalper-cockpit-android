package com.ethscalper.cockpit;

import org.junit.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class V23443QualityFrequencyShadowTest {
    @Test public void p01GuardIsSymbolicRatherThanUniversal(){
        NormalizedSignalMetrics.Result weak=ShadowTestFixtures.metrics(MarketProfile.eth(),"LONG",
                20_000,1,1,2,1,.2,.20,.8,.3,2);
        P01SleeveFilter.Result filter=P01SleeveFilter.evaluate(weak,20_000);
        assertEquals("BLOCK",ShadowCalibrationPolicy.p01Symbolic(MarketProfile.eth(),96,weak,filter,"").decision);
        ShadowCalibrationPolicy.Decision sol=ShadowCalibrationPolicy.p01Symbolic(
                MarketProfile.sol(),96,weak,filter,"");
        assertTrue(sol.keep);assertEquals("SHADOW_SOL_P01_PUBLIC_BASELINE_KEEP",sol.reasonCode);
        assertEquals(ShadowCalibrationPolicy.ETH_P01_GUARD,
                ShadowCalibrationPolicy.p01Component(MarketProfile.eth()));
        assertEquals(ShadowCalibrationPolicy.SOL_P01_MONITOR,
                ShadowCalibrationPolicy.p01Component(MarketProfile.sol()));
    }

    @Test public void highConfidenceFlowBoundsAndProfilesAreExact(){
        MarketProfile eth=MarketProfile.eth();SignalDecision candidate=ShadowTestFixtures.candidate(eth,"LONG",95);
        NormalizedSignalMetrics.Result edge=ShadowTestFixtures.metrics(eth,"LONG",20_000,
                .80,-.299,0,0,.22,.70,1.80,.4,2);
        assertTrue(ShadowCalibrationPolicy.ethFlowContinuationHighConfidence(eth,candidate,edge).keep);
        NormalizedSignalMetrics.Result upper=ShadowTestFixtures.metrics(eth,"LONG",20_000,
                1.65,-.299,0,0,.22,.70,1.80,.4,2);
        assertTrue(ShadowCalibrationPolicy.ethFlowContinuationHighConfidence(eth,candidate,upper).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowContinuationHighConfidence(MarketProfile.sol(),candidate,edge).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowContinuationHighConfidence(eth,candidate,
                ShadowTestFixtures.metrics(eth,"LONG",20_000,1,-.30,0,0,.22,.70,1,.4,2)).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowContinuationHighConfidence(eth,candidate,
                ShadowTestFixtures.metrics(eth,"LONG",20_000,1,0,-.001,0,.22,.70,1,.4,2)).keep);
    }

    @Test public void rangeFadeIsEthLongOnlyAndKeepsEveryBoundary(){
        MarketProfile eth=MarketProfile.eth();SignalDecision range=SignalDecision.signal(eth,"LONG",
                "RANGE_FADE",95,3,100,103,99,3,1,"RANGE",true,99,103,4);
        NormalizedSignalMetrics.Result edge=ShadowTestFixtures.metrics(eth,"LONG",20_000,
                1,0,0,0,.30,.4,1,.10,2.50);
        assertTrue(ShadowCalibrationPolicy.ethRangeFadeLongHighConfidence(eth,range,edge).keep);
        SignalDecision shortRange=SignalDecision.signal(eth,"SHORT","RANGE_FADE",95,3,100,97,101,
                3,1,"RANGE",true,101,97,4);
        assertFalse(ShadowCalibrationPolicy.ethRangeFadeLongHighConfidence(eth,shortRange,edge).keep);
        assertFalse(ShadowCalibrationPolicy.ethRangeFadeLongHighConfidence(MarketProfile.sol(),range,edge).keep);
    }

    @Test public void reanchoredGeometryUsesAskForLongAndBidForShort(){
        MarketProfile p=MarketProfile.eth();long now=20_000;
        for(String side:new String[]{"LONG","SHORT"}){
            SignalDecision candidate=ShadowTestFixtures.candidate(p,side,95);
            MarketSnapshot snapshot=ShadowTestFixtures.snapshot(p,side,now,1,.5,1,1,.4,.8,1,.4,3);
            ShadowPlanFactory.Result result=ShadowPlanFactory.buildReanchored(p,candidate,"P01","sig",
                    ShadowCalibrationPolicy.ETH_FLOW_HIGH_CONFIDENCE,snapshot,.1,false,
                    Collections.emptyList(),0,now);
            assertTrue(result.reasonCode,result.valid);
            double quote="LONG".equals(side)?snapshot.marketAsk:snapshot.marketBid;
            double expected="LONG".equals(side)?p.ceilToTick(quote):p.floorToTick(quote);
            assertEquals(expected,result.state.entry,1e-12);
        }
    }

    @Test public void experimentSummaryIsBoundedTypedAndOverlapAware(){
        ShadowExperimentSummary summary=new ShadowExperimentSummary();
        LinkedHashMap<String,Object> open=new LinkedHashMap<>();
        open.put("component",ShadowCalibrationPolicy.ETH_FLOW_HIGH_CONFIDENCE);
        open.put("decision","OPEN");open.put("movementKey","ETH|LONG|CONTINUATION|1|100");
        open.put("candidateSignature","public-signature");
        summary.observe("SHADOW_PLAN_OPENED",open);summary.observe("SHADOW_AB_DECISION",open);
        summary.observe("SHADOW_PUBLIC_OVERLAP",open);
        Map<String,Object> out=summary.snapshot(System.currentTimeMillis()+3_600_000L);
        assertEquals(1,((Number)out.get("publicPlans")).intValue());
        assertEquals(1,((Number)out.get("uniqueShadowOpened")).intValue());
        assertEquals(1,((Number)out.get("publicShadowOverlaps")).intValue());
        assertTrue(out.get("combinedSignalsPerHour") instanceof Number);
        assertEquals("SHADOW_SCHEMA_V5",out.get("shadowSchemaVersion"));
    }

    @Test public void shadowStateDedupIsComponentIndependentAndBoundedByContract(){
        ShadowObservationEngine.Candidate c=new ShadowObservationEngine.Candidate(
                ShadowTestFixtures.candidate(MarketProfile.eth(),"LONG",95),"P01","sig",0,0,0,false,"");
        for(int i=0;i<16;i++)c.lastShadowStateByComponent.put("C"+i,"SKIP|R");
        assertEquals(16,c.lastShadowStateByComponent.size());
        c.lastShadowStateByComponent.put("C0","SKIP|R2");
        assertEquals("SKIP|R2",c.lastShadowStateByComponent.get("C0"));
    }
}
