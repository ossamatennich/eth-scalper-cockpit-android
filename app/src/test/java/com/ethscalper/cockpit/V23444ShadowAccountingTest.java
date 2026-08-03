package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Functional schema-V5 accounting, registry and fee-aware sizing regressions. */
public final class V23444ShadowAccountingTest {
    private static final String FLOW=ShadowCalibrationPolicy.ETH_FLOW_HIGH_CONFIDENCE;

    @Test public void publicDecisionAloneIsNotAShadowOpportunityAndUnionIsExact(){
        ShadowExperimentSummary s=new ShadowExperimentSummary();
        s.observe("SHADOW_AB_DECISION",event("m1","p1",FLOW,"KEEP"));
        Map<String,Object> out=s.snapshot(safeNow());
        assertEquals(1,out.get("publicPlans"));assertEquals(1,out.get("publicMovementKeys"));
        assertEquals(0,out.get("uniqueShadowOpportunities"));
        assertEquals(1,out.get("uniqueCombinedPublicAndShadowOpportunities"));
        assertEquals(1,out.get("uniqueCombinedOpenedPlans"));
    }

    @Test public void openCountsOneQualificationAndOneOpeningNotTwo(){
        ShadowExperimentSummary s=new ShadowExperimentSummary();
        s.qualified(FLOW,"m1",1_000);s.opportunity(FLOW,"m1");
        Map<String,Object> open=event("m1","s1",FLOW,"OPEN");open.put("qualificationToOpenMs",200L);
        s.observe("SHADOW_PLAN_OPENED",open);
        Map<String,Object> out=s.snapshot(safeNow());Map<String,Object> c=component(out,FLOW);
        assertEquals(1,out.get("uniqueShadowQualifications"));assertEquals(1,out.get("uniqueShadowOpened"));
        assertEquals(1,c.get("qualifications"));assertEquals(1,c.get("openings"));
        assertEquals(200d,((Number)c.get("medianQualificationToOpenMs")).doubleValue(),0d);
    }

    @Test public void activeAndPostTerminalOverlapRemainOneMovement(){
        for(String terminal:new String[]{"","SHADOW_TP_TOUCHED","SHADOW_SL_TOUCHED"}){
            ShadowExperimentSummary s=new ShadowExperimentSummary();
            s.qualified(FLOW,"same",10);s.opportunity(FLOW,"same");
            s.observe("SHADOW_PLAN_OPENED",event("same","shadow",FLOW,"OPEN"));
            if(!terminal.isEmpty()){Map<String,Object> t=event("same","shadow",FLOW,"TERMINAL");
                t.put("netResultUsdt",terminal.contains("TP")?2d:-1d);t.put("resultR",terminal.contains("TP")?.4d:-.2d);
                s.observe(terminal,t);}
            s.observe("SHADOW_AB_DECISION",event("same","public",FLOW,"KEEP"));
            s.observe("SHADOW_PUBLIC_OVERLAP",event("same","shadow",FLOW,"OVERLAP"));
            Map<String,Object> out=s.snapshot(safeNow());
            assertEquals(1,out.get("publicShadowOverlaps"));assertEquals(1,out.get("uniqueCombinedOpenedPlans"));
        }
    }

    @Test public void staleAndInsufficientEconomicsSkipsNeverBecomeOpportunities(){
        ShadowExperimentSummary s=new ShadowExperimentSummary();
        s.qualified(FLOW,"stale",10);s.observe("SHADOW_PLAN_SKIPPED",event("stale","a",FLOW,"SKIP"));
        s.qualified(FLOW,"rr",20);s.observe("SHADOW_PLAN_SKIPPED",event("rr","b",FLOW,"SKIP"));
        Map<String,Object> out=s.snapshot(safeNow());
        assertEquals(2,out.get("uniqueShadowQualifications"));assertEquals(0,out.get("uniqueShadowOpportunities"));
        assertEquals(0,out.get("uniqueShadowOpened"));
    }

    @Test public void duplicateSuppressionSumsAcrossCandidatesAndComponents(){
        ShadowExperimentSummary s=new ShadowExperimentSummary();
        for(int i=0;i<50;i++)s.duplicateSuppressed(FLOW,"a");
        for(int i=0;i<50;i++)s.duplicateSuppressed(ShadowCalibrationPolicy.PULLBACK,"b");
        Map<String,Object> out=s.snapshot(safeNow());assertEquals(100L,out.get("shadowDuplicateEventsSuppressed"));
        assertEquals(50L,component(out,FLOW).get("shadowDuplicateEventsSuppressed"));
        assertEquals(50L,component(out,ShadowCalibrationPolicy.PULLBACK).get("shadowDuplicateEventsSuppressed"));
    }

    @Test public void medianIsDeterministicForOddAndEvenSamples(){
        ShadowExperimentSummary odd=new ShadowExperimentSummary();
        for(long v:new long[]{300,100,200})openWithLatency(odd,"o"+v,v);
        assertEquals(200d,((Number)component(odd.snapshot(safeNow()),FLOW).get("medianQualificationToOpenMs")).doubleValue(),0d);
        ShadowExperimentSummary even=new ShadowExperimentSummary();
        for(long v:new long[]{400,100,300,200})openWithLatency(even,"e"+v,v);
        assertEquals(250d,(Double)component(even.snapshot(safeNow()),FLOW).get("medianQualificationToOpenMs"),0d);
    }

    @Test public void allAggregateUnionsEthAndSolWithoutApproximateSubtraction(){
        ShadowExperimentSummary eth=new ShadowExperimentSummary(),sol=new ShadowExperimentSummary();
        eth.observe("SHADOW_AB_DECISION",event("ETH|m","ep",FLOW,"KEEP"));
        eth.opportunity(FLOW,"ETH|m");eth.observe("SHADOW_PLAN_OPENED",event("ETH|m","es",FLOW,"OPEN"));
        sol.observe("SHADOW_AB_DECISION",event("SOL|p","sp",ShadowCalibrationPolicy.SOL_P01_MONITOR,"KEEP"));
        sol.opportunity(ShadowCalibrationPolicy.SOL_EARLY,"SOL|s");
        Map<String,Object> all=ShadowExperimentSummary.aggregate(List.of(eth,sol),safeNow());
        assertEquals(2,all.get("publicMovementKeys"));assertEquals(2,all.get("uniqueShadowOpportunities"));
        assertEquals(3,all.get("uniqueCombinedPublicAndShadowOpportunities"));
        assertEquals("SHADOW_SCHEMA_V7",all.get("shadowSchemaVersion"));
    }

    @Test public void openedRegistryFindsActiveTpAndSlAndEmitsOverlapOnlyOnce(){
        ShadowOpenedPlanRegistry r=new ShadowOpenedPlanRegistry();
        for(String terminal:new String[]{"","SHADOW_TP_TOUCHED","SHADOW_SL_TOUCHED"}){
            String suffix=terminal.isEmpty()?"a":terminal.substring(7,9);ShadowPlanState p=plan("p"+suffix,"s"+suffix);
            r.registerOpen(p,"m"+suffix,"CONTINUATION",false);
            if(!terminal.isEmpty())r.markTerminal(p.shadowPlanId,2_000,terminal);
            ShadowOpenedPlanRegistry.Record found=r.findOverlap(p.candidateSignature,"m"+suffix);
            assertNotNull(found);assertTrue(r.markPublicOverlap(found.shadowPlanId));assertFalse(r.markPublicOverlap(found.shadowPlanId));
        }
    }

    @Test public void registryEvictionCleansEveryIndexAt257(){
        ShadowOpenedPlanRegistry r=new ShadowOpenedPlanRegistry();
        for(int i=0;i<257;i++)r.registerOpen(plan("p"+i,"s"+i),"m"+i,"CONTINUATION",false);
        assertEquals(256,r.rememberedOpenedRecords());assertEquals(256,r.rememberedMovementKeys());
        assertEquals(256,r.rememberedSignatures());assertEquals(1,r.evictedMovementRecords());
        assertNull(r.findOverlap("s0","m0"));assertNotNull(r.findOverlap("s256","m256"));
    }

    @Test public void addedShadowFactoryUsesItsFeeAwareQuantityForEveryLane(){
        MarketProfile p=MarketProfile.eth();long now=20_000;
        SignalDecision candidate=ShadowTestFixtures.candidate(p,"LONG",95);
        MarketSnapshot snapshot=ShadowTestFixtures.snapshot(p,"LONG",now,1.2,.6,1.5,1,.5,.8,1,.4,3);
        for(String lane:new String[]{ShadowCalibrationPolicy.PULLBACK,ShadowCalibrationPolicy.SOL_EARLY,
                FLOW,ShadowCalibrationPolicy.ETH_RANGE_FADE_LONG}){
            ShadowPlanFactory.Result result=ShadowPlanFactory.build(p,candidate,"P01","sig-"+lane,lane,
                    snapshot,.2,false,Collections.emptyList(),0,now);
            assertTrue(result.reasonCode,result.valid);assertEquals(result.economics.feeAwareQuantity,result.state.quantity);
            assertTrue(result.state.quantity<=result.baselineQuantity);
            ShadowPlanState.Terminal terminal=result.state.observe(now+1,result.state.tp,result.state.tp);
            assertNotNull(terminal);assertEquals(result.state.quantity*result.state.estimatedRoundTripCostPerUnit,
                    terminal.estimatedFeesUsdt,1e-9);
        }
    }

    @Test public void summaryFailuresAreFailOpenAndReturnBoundedFallback(){
        ShadowExperimentSummary broken=new ShadowExperimentSummary((operation,action)->{throw new RuntimeException(operation);});
        broken.safePublicTerminal("TP_TOUCHED");broken.safeDuplicateSuppressed(FLOW,"s");
        broken.safeTelemetrySnapshot(ShadowCalibrationPolicy.ETH_NO_RETRACE,Collections.singletonList(event("m","s",FLOW,"OBSERVE")));
        broken.safeReset(10,true);
        Map<String,Object> fallback=broken.safeSnapshot(20);assertEquals(false,fallback.get("available"));
        MarketRuntime runtime=new MarketRuntime(MarketProfile.eth(),broken);assertFalse(runtime.hasActivePlan());
    }

    @Test public void feeSizingEventsDistinguishProductionFromAddedShadow(){
        ShadowExperimentSummary s=new ShadowExperimentSummary();
        Map<String,Object> prod=event("m1","p", "SHADOW_FEE_AWARE_SIZING","MEASURE");prod.put("activeQuantity",5);
        prod.put("sourceComponent",FLOW);s.observe("SHADOW_FEE_AWARE_SIZING",prod);
        Map<String,Object> added=event("m2","s", "SHADOW_FEE_AWARE_SIZING","MEASURE");added.put("shadowQuantity",3);
        added.put("baselineGrossQuantity",5);added.put("sourceComponent",FLOW);s.observe("SHADOW_FEE_AWARE_SIZING",added);
        assertEquals(2L,component(s.snapshot(safeNow()),"SHADOW_FEE_AWARE_SIZING").get("measurements"));
        assertFalse(added.containsKey("activeQuantity"));
    }

    @Test public void thousandIdenticalRangeFramesProduceOneSkipAnd999Suppressions(){
        MarketProfile p=MarketProfile.eth();long now=20_000;MarketRuntime runtime=new MarketRuntime(p);
        SignalDecision signal=rangeCandidate(p);ShadowObservationEngine.Candidate candidate=
                new ShadowObservationEngine.Candidate(signal,"P01","range-sig",0,.1,0,false,"");
        MarketSnapshot snapshot=rangeSnapshot(p,now);ShadowObservationEngine engine=new ShadowObservationEngine();
        ShadowObservationEngine.Context context=new ShadowObservationEngine.Context(runtime,snapshot,now,
                false,true,false,true,false,Collections.emptyList());
        for(int i=0;i<1_000;i++)engine.safeConsiderAddedPlan(context,candidate);
        long skipped=runtime.recorder.eventMaps().stream().filter(e->"SHADOW_PLAN_SKIPPED".equals(e.get("eventType"))).count();
        assertEquals(1,skipped);assertEquals(999,candidate.shadowDuplicateEventsSuppressed);
        assertEquals(999L,runtime.shadowExperiment.snapshot(safeNow()).get("shadowDuplicateEventsSuppressed"));
        assertEquals(1,candidate.lastShadowStateByComponent.size());
    }

    @Test public void diagnosticOnlyRangeFadeTraversesLegacyBridgeWithoutPublicMutation(){
        MarketProfile p=MarketProfile.eth();long now=20_000;MarketRuntime runtime=new MarketRuntime(p);
        SignalDecision signal=rangeCandidate(p);MarketSnapshot snapshot=rangeSnapshot(p,now);
        MarketWatchService.ObservedSignal item=new MarketWatchService.ObservedSignal(1,0,signal,100,snapshot);
        item.status=CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY;item.candidateSignature="range-diag";
        new LegacyEthShadowBridge().observeCandidate(runtime,item,snapshot,now,true,true,false,0,false,
                Collections.emptyList());
        assertNull(runtime.activePlan);assertNull(runtime.lastSignal);
        assertEquals(CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY,item.status);
        assertTrue(String.valueOf(runtime.recorder.eventMaps()),runtime.recorder.eventMaps().stream().anyMatch(e->"SHADOW_PLAN_SKIPPED".equals(e.get("eventType"))
                &&ShadowCalibrationPolicy.ETH_RANGE_FADE_LONG.equals(e.get("component"))));
        assertNull(runtime.shadowResearch.active());
        assertTrue(runtime.recorder.eventMaps().stream().anyMatch(e->"SHADOW_RANGE_RECLAIM_OBSERVATION".equals(e.get("eventType"))));
    }

    @Test public void failingSummaryCannotInterruptPublicTerminal(){
        ShadowExperimentSummary broken=new ShadowExperimentSummary((operation,action)->{throw new IllegalStateException(operation);});
        MarketProfile p=MarketProfile.sol();MarketRuntime runtime=new MarketRuntime(p,broken);long now=4_000_000;
        runtime.activePlan=ActivePlanState.builder().market(p).side("LONG").family("P02")
                .reasonCode("OK").reasonText("public").score(80).quantity(3).prices(75,76,74)
                .risk(1,1).times(1,1,1).premium15m(false).notification("public",1)
                .lastMarket(76,76,76.01,.4).lastP01ConfirmedAt(0).movement("",false,0,0,0)
                .unitRisk(.1,.1,14.55,10).build();
        runtime.lastSignal=runtime.activePlan.toSignalDecision();runtime.last=76;runtime.bid=76;
        runtime.ask=76.01;runtime.lastTickerAt=now;
        SharedReferenceContext btc=new SharedReferenceContext();btc.last=60_000;btc.bid=59_999;btc.ask=60_001;btc.lastTickerAt=now;
        MarketPlanOrchestrator.Event result=new MarketPlanOrchestrator().evaluate(runtime,btc,now,true,true);
        assertEquals("TERMINAL",result.type);assertEquals("TP_TOUCHED",result.status);
        assertNull(runtime.activePlan);assertEquals(now,runtime.lastTerminalAt);
    }

    private static void openWithLatency(ShadowExperimentSummary s,String key,long latency){s.qualified(FLOW,key,1);
        Map<String,Object>d=event(key,key,FLOW,"OPEN");d.put("qualificationToOpenMs",latency);s.observe("SHADOW_PLAN_OPENED",d);}
    private static Map<String,Object> event(String movement,String signature,String component,String decision){
        LinkedHashMap<String,Object>d=new LinkedHashMap<>();d.put("movementKey",movement);d.put("candidateSignature",signature);
        d.put("component",component);d.put("decision",decision);return d;}
    @SuppressWarnings("unchecked") private static Map<String,Object> component(Map<String,Object> out,String name){
        return (Map<String,Object>)((Map<String,Object>)out.get("components")).get(name);}
    private static ShadowPlanState plan(String id,String signature){return new ShadowPlanState(id,FLOW,signature,
            MarketProfile.eth(),"LONG","P01",0,1_000,100,103,99,1,3,3,7,14.55,.1,1,.2);}
    private static SignalDecision rangeCandidate(MarketProfile p){return SignalDecision.signal(p,"LONG",
            "RANGE_FADE",95,3,100.01,103.01,99.01,3,1,"P01",true,99,103,4);}
    private static MarketSnapshot rangeSnapshot(MarketProfile p,long now){return ShadowTestFixtures.snapshot(p,"LONG",
            now,1,.2,.2,.2,.4,.5,1,.05,3);}
    private static long safeNow(){return System.currentTimeMillis()+3_600_000L;}
}
