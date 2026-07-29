package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class V23420RecoveryAndPlansTest {
    private static String source(String name)throws Exception{
        return new String(Files.readAllBytes(Path.of("src/main/java/com/ethscalper/cockpit/"+name)),
                StandardCharsets.UTF_8);
    }

    @Test public void validatedPublicTimingStartsAtFifteenSeconds(){
        assertEquals(15_000L,CandidateLifecycle.MIN_CONFIRMATION_AGE_MS);
        assertEquals(90_000L,CandidateLifecycle.MAX_PENDING_AGE_MS);
    }

    @Test public void earlyP01IsExplicitlyShadowOnlyInBothRuntimes()throws Exception{
        String service=source("MarketWatchService.java"),orchestrator=source("MarketPlanOrchestrator.java");
        assertTrue(service.contains("EARLY_P01_SHADOW_WOULD_CONFIRM"));
        assertTrue(service.contains("SHADOW_RESEARCH"));
        assertTrue(orchestrator.contains("EARLY_P01_SHADOW_WOULD_CONFIRM"));
        assertTrue(orchestrator.contains("branch remains observability-only and can never publish"));
    }

    @Test public void earlyLegacyPathNeverAppliesFillResult()throws Exception{
        String service=source("MarketWatchService.java");
        int start=service.indexOf("private boolean processEarlyP01Candidate");
        int end=service.indexOf("private static void updateEarlyP01Diagnostics",start);
        String method=service.substring(start,end);
        assertFalse(method.contains("applyCandidateFillResult("));
        assertTrue(method.contains("Public confirmation remains governed by the"));
        assertTrue(method.contains("return false;"));
    }

    @Test public void p02TimingRemainsUnchanged(){
        assertEquals(20_000L,CandidateLifecycle.P02_MIN_CONFIRMATION_AGE_MS);
        assertEquals(45_000L,CandidateLifecycle.P02_MAX_PENDING_AGE_MS);
    }

    @Test public void validatedFixtureContainsSixteenPlansAndNoEarlyPublicPlan()throws Exception{
        List<String> lines=Files.readAllLines(Path.of("../tools/fixtures/eth_v2331_validated_plans.csv"));
        assertEquals(17,lines.size());int p01=0,p02=0;
        for(String line:lines.subList(1,lines.size())){String[] v=line.split(",",-1);
            if("P01".equals(v[1]))p01++;if("P02".equals(v[1]))p02++;
            assertTrue(Long.parseLong(v[5])-Long.parseLong(v[4])>=15_000L);
            assertEquals("TP",v[15]);}
        assertEquals(7,p01);assertEquals(9,p02);
    }

    @Test public void planMetricsUsePublishedImmutableLevels(){
        PlanMetricsCalculator.Result r=PlanMetricsCalculator.calculate("LONG",3,100,105,98,
                102,101.9,102.1,1.43,2.35,14.55,5);
        assertTrue(r.complete);assertEquals(15,r.grossProfit,1e-12);
        assertEquals(6,r.grossLoss,1e-12);assertEquals(4.29,r.estimatedFees,1e-12);
        assertEquals(10.71,r.netProfit,1e-12);assertEquals(10.29,r.netLoss,1e-12);
        assertEquals(13.05,r.theoreticalMaximumLoss,1e-12);assertEquals(2.5,r.rewardRisk,1e-12);
    }

    @Test public void longAndShortPresentationMathIsSymmetric(){
        PlanMetricsCalculator.Result l=PlanMetricsCalculator.calculate("LONG",3,100,105,98,102,99,100,1.43,2.35,14.55,5);
        PlanMetricsCalculator.Result s=PlanMetricsCalculator.calculate("SHORT",3,100,95,102,98,100,101,1.43,2.35,14.55,5);
        assertEquals(l.grossProfit,s.grossProfit,0);assertEquals(l.grossLoss,s.grossLoss,0);
        assertEquals(l.netProfit,s.netProfit,0);assertEquals(l.theoreticalMaximumLoss,s.theoreticalMaximumLoss,0);
        assertEquals(l.progressPercent,s.progressPercent,0);
    }

    @Test public void missingMandatoryUiDataNeverBecomesZero(){
        PlanUiModel model=new PlanUiModel("ETHUSDT","ETH","LONG","P01","P01","ACTIVE","FRESH","",
                96,3,5,100,105,98,102,101,102,Double.NaN,2.35,14.55,1,2,10);
        assertFalse(model.complete());assertEquals(PlanUiModel.DATA_INCOMPLETE,model.reasonCode);
        assertTrue(Double.isNaN(model.metrics.netProfit));
    }

    @Test public void ethAndSolVisualLeverageArePresentationOnly()throws Exception{
        String mapper=source("PlanUiMapper.java");
        assertTrue(mapper.contains("\"ETHUSDT\".equals(symbol)?5"));
        assertTrue(mapper.contains("\"SOLUSDT\".equals(symbol)?2"));
        assertFalse(mapper.contains("DynamicTradePlan.calculate"));
    }

    @Test public void professionalPlanCardSupportsAllCopyActions()throws Exception{
        String view=source("ActivePlanCardView.java");
        for(String label:new String[]{"LIMIT","TP","SL","TOUT"})assertTrue(view.contains("\""+label+"\""));
        assertTrue(view.contains("PLAN IMMUTABLE · TP/SL UNIQUEMENT"));
        assertTrue(view.contains("DONNÉE INDISPONIBLE"));
    }

    @Test public void notificationClickTargetsPlansAndCarriesScoreSleeve()throws Exception{
        String service=source("MarketWatchService.java");
        assertTrue(service.contains("putExtra(\"nmc_section\",\"plans\")"));
        assertTrue(service.contains("Score %d · %s"));
    }

    @Test public void publicSizingPoliciesRemainV2331AndUplift()throws Exception{
        String plan=source("DynamicTradePlan.java");
        assertTrue(plan.contains("calculateLegacy"));assertTrue(plan.contains("LEGACY_RISK_BUDGET_USDT = 10.00"));
        assertTrue(plan.contains("DEFAULT_RISK_BUDGET_USDT = 14.55"));
        assertTrue(plan.contains("Math.max(3, baselineFinalQuantity + 1)"));
    }

    @Test public void safetyInvariantsRemainManualTpSlOnly(){
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
        assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));
        assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT_45M"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("SCENARIO_INVALIDATED"));
    }
}
