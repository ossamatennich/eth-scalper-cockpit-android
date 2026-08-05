package com.ethscalper.cockpit;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class V23448ScalpActionTelemetryAuditTest {
    @Test public void confirmationTelemetryKeepsExactObservationValues(){
        ScalpActionEngine e=new ScalpActionEngine(new ScalpActionMovementRegistry());MarketSnapshot s=snap();
        ScalpActionEngine.Result r=e.observeLegacy("SHORT","SCALP_CONTINUATION","P01",-.123456,s,
                new ScalpActionContextTracker.Metrics(-.01,true,.00001,true,.99,true),
                new ScalpActionEngine.Common("ACTION_ON",true,true,true,false,false,1234),42);
        Map<String,Object> d=ScalpActionTelemetry.details(r,r.plan,50);assertEquals("LEGACY_CONFIRMATION",d.get("sourceType"));
        assertEquals("SCALP_CONTINUATION",d.get("sourceFamily"));assertEquals("P01",d.get("sourceSleeve"));
        assertEquals(-.123456,(Double)d.get("sg_move3Norm"),0);assertEquals(42L,d.get("observedAt"));
        String json=new JSONObject(d).toString();assertFalse(json.contains("NaN"));assertFalse(json.contains("Infinity"));
    }

    @Test public void actionRiskIncludesFeesAndBudget(){Map<String,Object> d=ScalpActionRiskFields.action(4,1.5);
        assertEquals(1.43,(Double)d.get("resultCostPerUnit"),0);assertEquals(0d,(Double)d.get("riskAllowancePerUnit"),0);
        assertEquals(14.55,(Double)d.get("qualityRiskBudget"),0);assertEquals(6d,(Double)d.get("grossLossAtSl"),0);
        assertEquals(5.72,(Double)d.get("estimatedRoundTripFees"),1e-12);assertEquals(11.72,(Double)d.get("estimatedTotalLossAtSl"),1e-12);
        assertEquals(d.get("estimatedTotalLossAtSl"),d.get("theoreticalMaximumLoss"));assertEquals(d.get("estimatedTotalLossAtSl"),d.get("modeledRiskUsdt"));assertTrue((Double)d.get("estimatedTotalLossAtSl")<=14.55);}

    @Test public void comparatorReportsDedicatedSkip(){LegacyPublicComparator c=new LegacyPublicComparator();ActivePlanState p=state();assertTrue(c.openResult(p,1).opened);LegacyPublicComparator.OpenResult skipped=c.openResult(p,2);assertFalse(skipped.opened);assertEquals("LEGACY_COMPARATOR_SKIPPED_ACTIVE",skipped.reasonCode);}

    @Test public void legacyFormatTwoRiskRenderingStaysGrossBased(){Map<String,Object> d=ScalpActionRiskFields.legacy(2,3,1.43,2.35,10);
        assertEquals(6d,(Double)d.get("theoreticalMaximumLoss"),0);assertEquals(6d,(Double)d.get("modeledRiskUsdt"),0);
        assertEquals(2.35,(Double)d.get("riskAllowancePerUnit"),0);assertEquals(10d,(Double)d.get("qualityRiskBudget"),0);}

    @Test public void alertTelemetrySeparatesPostDedupAndRetry(){Map<String,Object> posted=ScalpActionTelemetry.alert(true,false);
        assertEquals(Boolean.TRUE,posted.get("posted"));assertEquals(Boolean.FALSE,posted.get("alreadyAlerted"));assertEquals(Boolean.FALSE,posted.get("retryScheduled"));
        Map<String,Object> dedup=ScalpActionTelemetry.alert(false,true);assertEquals(Boolean.TRUE,dedup.get("alerted"));assertEquals(Boolean.FALSE,dedup.get("retryScheduled"));
        Map<String,Object> retry=ScalpActionTelemetry.alert(false,false);assertEquals(Boolean.TRUE,retry.get("retryScheduled"));}

    private static MarketSnapshot snap(){return MarketSnapshot.builder(42).eth(1900,1899.99,1900.01).btc(1,1,1).averages(2.1,1).movement(0,3.5,0,1910,1890).professionalFeatures(20,1,.5,1,1,1,1,0,0,0,0,0,0,0,0,0,0).build();}
    private static ActivePlanState state(){return ActivePlanState.builder().formatVersion(2).market(MarketProfile.eth()).status("ACTIVE").side("LONG").family("P01").reasonCode("x").reasonText("x").score(1).quantity(1).prices(100,101,99).risk(1,1).times(1,1,1).notification("x",1).lastMarket(100,100,100.01,1).lastP01ConfirmedAt(0).movement("",false,0,0,0).replayRisk("","").p01(0,0,0,0,0).sizingDiagnostic("").unitRisk(1.43,0,14.55,2.43).build();}
}
