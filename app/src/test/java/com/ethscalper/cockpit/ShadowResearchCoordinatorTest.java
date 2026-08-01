package com.ethscalper.cockpit;

import org.junit.Test;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ShadowResearchCoordinatorTest {
    @Test public void resetDoesNotTouchAnyPublicRuntimeField(){
        MarketRuntime runtime=new MarketRuntime(MarketProfile.eth());
        ActivePlanState publicPlan=ActivePlanState.builder().market(runtime.profile).side("LONG")
                .family("P01").reasonCode("R").reasonText("T").score(95).quantity(3)
                .prices(100,105,95).risk(5,5).times(1,1,1).premium15m(false)
                .notification("public",123).lastMarket(100,99,101,1).lastP01ConfirmedAt(7)
                .movement("",false,0,0,0).unitRisk(1,1,14.55,15)
                .structural(1,0,1,99,5,.15,"STRUCTURE","OK","B",5,2,7)
                .sizingDiagnostic("public").build();
        runtime.activePlan=publicPlan;runtime.lastTerminalAt=77;runtime.lastSignal=publicPlan.toSignalDecision();
        SignalDecision publicSignal=runtime.lastSignal;
        runtime.resetDiagnosticsPreservingActivePlan();
        assertSame(publicPlan,runtime.activePlan);assertEquals(77,runtime.lastTerminalAt);
        assertSame(publicSignal,runtime.lastSignal);
        assertTrue(runtime.recorder.eventMaps().stream().map(v->String.valueOf(v.get("eventType")))
                .anyMatch("SHADOW_STATE_RESET"::equals));
    }
    @Test public void recorderExportsTypedShadowValuesAndNeverCountsThemAsPublicTrades(){
        MarketDiagnosticRecorder r=new MarketDiagnosticRecorder(MarketProfile.eth());
        r.record(1,"SHADOW_AB_DECISION","SHADOW_P01_KEEP","shadow","SHADOW_OBSERVABILITY",
                "","P01",null,null,1,true,true,0,Map.of("shadowPolicyVersion",
                        ShadowCalibrationPolicy.VERSION,"productionConfirmed",true,"quantity",3));
        Map<String,Object> e=r.eventMaps().get(0);assertTrue(e.get("productionConfirmed") instanceof Boolean);
        assertTrue(e.get("quantity") instanceof Integer);assertEquals(0,r.summary().get("confirmedTrades"));
        r.record(2,"SHADOW_TP_TOUCHED","SHADOW_TP_TOUCHED","shadow terminal",
                "SHADOW_OBSERVABILITY","","P01",null,null,1,true,true,0,
                java.util.Collections.emptyMap());
        assertEquals("SHADOW_TP_TOUCHED",r.eventMaps().get(1).get("terminalStatus"));
        assertEquals(0,r.summary().get("tp"));
    }
    @Test public void absoluteE60AndNormalizedEStayDistinctAndNumericAfterJsonSerialization() throws Exception {
        SignalDecision signal=ShadowTestFixtures.candidate(MarketProfile.eth(),"LONG",95);
        MarketSnapshot snapshot=ShadowTestFixtures.snapshot(MarketProfile.eth(),"LONG",30_000,
                2,1,2,2,.4,.6,1,.5,2);
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate(MarketProfile.eth(),
                "LONG",signal,snapshot,.50);
        LinkedHashMap<String,Object> values=new LinkedHashMap<>();
        MarketPlanOrchestrator.putMetrics(values,metrics,.50);
        assertTrue(values.get("E60") instanceof Number);assertTrue(values.get("eNormalized") instanceof Number);
        assertEquals(.50,((Number)values.get("E60")).doubleValue(),0);
        assertEquals(.25,((Number)values.get("eNormalized")).doubleValue(),0);
        JSONObject json=new JSONObject(values);String serialized=json.toString();JSONObject restored=new JSONObject(serialized);
        assertEquals(.50,restored.getDouble("E60"),0);assertEquals(.25,restored.getDouble("eNormalized"),0);
    }
}
