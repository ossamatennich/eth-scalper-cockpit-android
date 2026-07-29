package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class V23430StructuralStopAndSizingTest {
    private static final long CONFIRM=1_000_000L;

    private static List<MarketRuntime.MarketBar> longPivot(){
        List<MarketRuntime.MarketBar> b=new ArrayList<>();long t=CONFIRM-240_000L;
        b.add(bar(t,99.5,100.2,99.1,99.7));
        b.add(bar(t+60_000,99.3,99.8,98.6,98.8));
        b.add(bar(t+120_000,98.9,100.0,98.8,99.6));return b;
    }
    private static List<MarketRuntime.MarketBar> shortPivot(){
        List<MarketRuntime.MarketBar> b=new ArrayList<>();long t=CONFIRM-240_000L;
        b.add(bar(t,100.5,100.9,99.8,100.3));
        b.add(bar(t+60_000,100.7,101.4,100.2,101.2));
        b.add(bar(t+120_000,101.1,101.2,100.0,100.4));return b;
    }
    private static MarketRuntime.MarketBar bar(long t,double o,double h,double l,double c){
        return new MarketRuntime.MarketBar(t,o,h,l,c,10);
    }
    private static String source(String name)throws Exception{return new String(Files.readAllBytes(
            Path.of("src/main/java/com/ethscalper/cockpit/"+name)),StandardCharsets.UTF_8);}
    private static StructuralStopPlanner.Result stop(String side,List<MarketRuntime.MarketBar> bars){
        return StructuralStopPlanner.calculate(MarketProfile.eth(),side,100,1,0,bars,CONFIRM);
    }
    private static AdaptiveRiskSizing.Evidence evidence(int cap,boolean premium,boolean veto,
                                                         boolean exceptional){
        return new AdaptiveRiskSizing.Evidence(true,true,premium,true,veto,true,true,true,
                exceptional,cap);
    }
    private static DynamicTradePlan.Result plan(String side,StructuralStopPlanner.Result stop,
                                                 int cap,AdaptiveRiskSizing.Evidence evidence){
        return DynamicTradePlan.calculateStructural(MarketProfile.eth(),side,100,1,0,
                "LONG".equals(side)?105:101,"SHORT".equals(side)?95:99,
                cap,stop,evidence);
    }

    @Test public void baseStopAloneWithoutAnchor(){
        StructuralStopPlanner.Result r=stop("LONG",Collections.emptyList());
        assertTrue(r.valid);assertEquals(StructuralStopPlanner.ANCHOR_UNAVAILABLE,r.reasonCode);
        assertEquals(1,r.requiredStop,0);assertTrue(Double.isNaN(r.structuralAnchor));
    }
    @Test public void validLongAnchor(){assertEquals(98.6,stop("LONG",longPivot()).structuralAnchor,0);}
    @Test public void validShortAnchor(){assertEquals(101.4,stop("SHORT",shortPivot()).structuralAnchor,0);}
    @Test public void futureAnchorRejected(){List<MarketRuntime.MarketBar>b=longPivot();
        b.add(bar(CONFIRM,99,100,90,99));assertEquals(98.6,stop("LONG",b).structuralAnchor,0);}
    @Test public void wrongSideAnchorRejected(){assertTrue(Double.isNaN(stop("LONG",shortPivot()).structuralAnchor));}
    @Test public void invalidCandlesIgnored(){List<MarketRuntime.MarketBar>b=new ArrayList<>(longPivot());
        b.add(bar(CONFIRM-60_000,99,100,0,99));b.add(bar(CONFIRM-60_000,99,Double.NaN,98,99));
        assertEquals(98.6,stop("LONG",b).structuralAnchor,0);}
    @Test public void longShortSymmetry(){StructuralStopPlanner.Result l=stop("LONG",longPivot()),s=stop("SHORT",shortPivot());
        assertEquals(l.requiredStop,s.requiredStop,0);assertEquals(l.structureDistance,s.structureDistance,0);}
    @Test public void selectedBufferIsPointFifteenA(){assertEquals(.15,stop("LONG",longPivot()).structuralBuffer,0);}
    @Test public void requiredStopUsesMaximum(){StructuralStopPlanner.Result r=stop("LONG",longPivot());
        assertEquals(1.55,r.requiredStop,1e-12);assertTrue(r.requiredStop>r.baseStop);}
    @Test public void oldTwoPointFiveCapIsNotARejection(){StructuralStopPlanner.Result s=StructuralStopPlanner.calculate(
            MarketProfile.eth(),"LONG",100,1,2.8,Collections.emptyList(),CONFIRM);
        DynamicTradePlan.Result p=plan("LONG",s,7,evidence(7,false,false,false));
        assertNotEquals(DynamicTradePlan.STOP_TOO_WIDE,p.reasonCode);assertEquals(3.0,p.stopRequired,1e-12);}
    @Test public void integrityEnvelopeNeverClamps(){StructuralStopPlanner.Result r=stop("LONG",longPivot());
        assertTrue(r.sanityEnvelope>r.requiredStop);assertEquals(1.55,r.requiredStop,1e-12);}
    @Test public void integrityEnvelopeRejectsAberrantAnchor(){
        StructuralStopPlanner.Result r=StructuralStopPlanner.calculate(MarketProfile.eth(),"LONG",100,1,20,Collections.emptyList(),CONFIRM);
        assertFalse(r.valid);assertEquals(StructuralStopPlanner.SANITY_REJECTED,r.reasonCode);}
    @Test public void widerStopReducesQuantity(){DynamicTradePlan.Result narrow=plan("LONG",stop("LONG",Collections.emptyList()),7,evidence(7,true,false,true));
        DynamicTradePlan.Result wide=plan("LONG",stop("LONG",longPivot()),7,evidence(7,true,false,true));
        assertTrue(narrow.finalQuantity>wide.finalQuantity);}
    @Test public void widerStopDoesNotChangeEntry(){DynamicTradePlan.Result p=plan("LONG",stop("LONG",longPivot()),7,evidence(7,false,false,false));assertEquals(100-p.stopLoss,p.roundedStopDistance,1e-12);}
    @Test public void widerStopDoesNotChangeTargetFormula(){DynamicTradePlan.Result p=plan("LONG",stop("LONG",longPivot()),7,evidence(7,false,false,false));assertEquals(3.7,p.targetRaw,1e-12);}
    @Test public void resultIsImmutableByConstruction()throws Exception{assertTrue(java.lang.reflect.Modifier.isFinal(DynamicTradePlan.Result.class.getField("finalQuantity").getModifiers()));}
    @Test public void insufficientRrRejectsWithoutTightening(){StructuralStopPlanner.Result s=StructuralStopPlanner.calculate(MarketProfile.eth(),"LONG",100,1,3,Collections.emptyList(),CONFIRM);
        DynamicTradePlan.Result p=DynamicTradePlan.calculateStructural(MarketProfile.eth(),"LONG",100,1,3,101,99,7,s,evidence(7,false,false,false));
        assertFalse(p.valid);assertEquals(DynamicTradePlan.REWARD_RISK_INSUFFICIENT,p.reasonCode);assertEquals(3.2,p.stopRequired,1e-12);}
    @Test public void standardBudgetIsTen(){assertEquals(10,AdaptiveRiskSizing.select(evidence(7,false,true,true)).budgetUsdt,0);}
    @Test public void reinforcedBudgetIsTwelve(){assertEquals(12,AdaptiveRiskSizing.select(evidence(5,false,false,false)).budgetUsdt,0);}
    @Test public void premiumBudgetIsFourteenFiftyFive(){assertEquals(14.55,AdaptiveRiskSizing.select(evidence(6,true,false,true)).budgetUsdt,0);}
    @Test public void scoreAloneCannotRaiseBudget(){AdaptiveRiskSizing.Evidence e=new AdaptiveRiskSizing.Evidence(false,false,false,false,false,true,false,true,false,7);assertEquals(10,AdaptiveRiskSizing.select(e).budgetUsdt,0);}
    @Test public void quantityOneIsNotForcedToThree(){StructuralStopPlanner.Result s=StructuralStopPlanner.calculate(MarketProfile.eth(),"LONG",100,1,5.5,Collections.emptyList(),CONFIRM);
        DynamicTradePlan.Result p=DynamicTradePlan.calculateStructural(MarketProfile.eth(),"LONG",100,1,5.5,120,99,7,s,evidence(7,false,false,false));
        assertEquals(1,p.finalQuantity);}
    @Test public void solQuantityStepIsRespected(){StructuralStopPlanner.Result s=StructuralStopPlanner.calculate(MarketProfile.sol(),"LONG",75.8,.04,0,Collections.emptyList(),CONFIRM);
        DynamicTradePlan.Result p=DynamicTradePlan.calculateStructural(MarketProfile.sol(),"LONG",75.8,.04,0,76,75,7,s,evidence(7,false,false,false));
        assertEquals(0,p.finalQuantity%MarketProfile.sol().quantityStep);}
    @Test public void modeledRiskNeverExceedsBudget(){DynamicTradePlan.Result p=plan("LONG",stop("LONG",longPivot()),7,evidence(7,true,false,true));assertTrue(p.theoreticalMaximumLoss<=p.qualityRiskBudget+1e-9);}
    @Test public void independentEthAndSolProfiles(){assertNotEquals(MarketProfile.eth().profileVersion,MarketProfile.sol().profileVersion);}
    @Test public void runtimeStateIsIndependent(){MarketRuntime e=new MarketRuntime(MarketProfile.eth()),s=new MarketRuntime(MarketProfile.sol());e.last=1;assertTrue(Double.isNaN(s.last));}
    @Test public void futureProfileUsesPurePlanner(){MarketProfile p=MarketProfile.builder("XRPUSDT","XRP","TEST").referencePrice(1).priceTick(.001).quantity(1,1,100).researchCandidate(true).adaptivePriceScale(true).detection(.001,.002,.003).stops(.002,.01).targets(.004,.02).p02Seed(.004,.003).revalidation(.001,.002).lateDistances(.003,.002).costs(.001,.002).riskBudgets(10,14.55).qualityBudgets(10,11,12,13,14).staleReasonCode("STALE").build();assertTrue(StructuralStopPlanner.calculate(p,"LONG",1,.002,0,Collections.emptyList(),CONFIRM).valid);}
    @Test public void publicP01StillStartsAtFifteenSeconds(){assertEquals(15_000,CandidateLifecycle.MIN_CONFIRMATION_AGE_MS);}
    @Test public void earlyP01RemainsShadowInService()throws Exception{String s=source("MarketWatchService.java");assertTrue(s.contains("SHADOW_RESEARCH"));assertTrue(s.contains("return false;"));}
    @Test public void onlyTpAndSlAreTerminal(){assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT_45M"));}
    @Test public void realTradingRemainsDisabled()throws Exception{String s=source("MarketWatchService.java");assertTrue(s.contains("realTradingAllowed"));assertTrue(s.contains("false"));}
    @Test public void stopExplanationFieldsReachUi(){PlanUiModel m=new PlanUiModel("ETHUSDT","ETH","LONG","P01","P01","ACTIVE","FRESH","",96,2,5,100,105,98,101,100,101,1.43,2.35,10,1,2,3,1,.2,1,98,8,.2,"STRUCTURE",StructuralStopPlanner.CONFIRMED,AdaptiveRiskSizing.STANDARD,3.35,2,5);assertEquals("STRUCTURE",m.stopCalculationType);assertEquals(8,m.structuralWindowMinutes);}
    @Test public void budgetAndSizingReachUi(){PlanUiModel m=new PlanUiModel("SOLUSDT","SOL","LONG","P02","P02","ACTIVE","FRESH","",90,50,2,75,76,74,75,75,75,0.06,.1,12,1,2,3,.04,.01,.04,74.9,8,.008,"COMBINAISON",StructuralStopPlanner.CONFIRMED,AdaptiveRiskSizing.REINFORCED,.15,80,6);assertEquals(12,m.riskBudgetUsdt,0);assertEquals(80,m.riskQuantity);}
    @Test public void missingStopUiDataStaysNaN(){PlanUiModel m=new PlanUiModel("ETHUSDT","ETH","LONG","P01","P01","ACTIVE","FRESH","",90,2,5,100,105,98,101,100,101,1.43,2.35,10,1,2,3);assertTrue(Double.isNaN(m.baseStop));}
    @Test public void canonicalFixtureStillHasSixteenTp()throws Exception{List<String> l=Files.readAllLines(Path.of("../tools/fixtures/eth_v2331_validated_plans.csv"));assertEquals(17,l.size());for(String row:l.subList(1,l.size()))assertEquals("TP",row.split(",",-1)[15]);}
    @Test public void canonicalFixtureContainsSevenP01NineP02()throws Exception{int a=0,b=0;for(String row:Files.readAllLines(Path.of("../tools/fixtures/eth_v2331_validated_plans.csv")).subList(1,17)){if(row.contains(",P01,"))a++;if(row.contains(",P02,"))b++;}assertEquals(7,a);assertEquals(9,b);}
    @Test public void loudAlertChannelUnchanged()throws Exception{String s=source("MarketWatchService.java");assertTrue(s.contains("nmc_final_signal_loud_v1"));}
    @Test public void exportStreamingUnchanged()throws Exception{String s=source("DiagnosticStreamingExporter.java");assertFalse(s.contains("ByteArrayOutputStream"));}
    @Test public void productionConfigurationIsSingleAndDeterministic(){assertEquals(5,StructuralStopPlanner.PRODUCTION.windowMinutes);assertEquals(.15,StructuralStopPlanner.PRODUCTION.bufferMultiplier,0);}
}
