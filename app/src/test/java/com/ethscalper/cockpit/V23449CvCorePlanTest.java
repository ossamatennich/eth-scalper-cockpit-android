package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23449CvCorePlanTest {
 @Test public void routeAShortGeometry(){CvCorePlan p=build(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"SHORT",1900,1900.01,1.2);assertEquals(1900,p.entry,0);assertEquals(1895.2,p.takeProfit,1e-9);assertEquals(1902.1,p.stopLoss,1e-9);assertEquals(4.8,p.targetDistance,1e-9);assertEquals(2.1,p.stopDistance,1e-9);}
 @Test public void routeBLongUsesAsk(){CvCorePlan p=build(CvCorePolicy.CAPITULATION_LONG,"LONG",1900,1900.001,1.2);assertEquals(1900.01,p.entry,1e-9);assertTrue(p.takeProfit>p.entry);assertTrue(p.stopLoss<p.entry);}
 @Test public void routeCBudgetIsExact(){CvCorePlan p=build(CvCorePolicy.P02_BALANCED_SHORT,"SHORT",1900,1900.01,1.2);assertEquals(7.275,p.route.riskBudgetUsdt,0);assertTrue(p.theoreticalMaximumLoss<=7.275+1e-9);}
 @Test public void costIsExact(){assertEquals(1.43,CvCoreTestFixtures.plan(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"SHORT").resultCostPerUnit,0);}
 @Test public void quantityNeverExceedsProfileCapSeven(){CvCorePlan p=build(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"LONG",100,100.01,1.2);assertTrue(p.quantity<=7);assertEquals(MarketProfile.eth().maximumQuantity,7);}
 @Test public void lossIncludesFeesAndStaysInBudget(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"SHORT");assertEquals(p.quantity*(p.stopDistance+1.43),p.theoreticalMaximumLoss,1e-12);assertTrue(p.theoreticalMaximumLoss<=14.55+1e-9);}
 @Test public void targetAndRrAreNet(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"SHORT");assertEquals(p.targetDistance-1.43,p.netRewardPerUnit,0);assertEquals(p.netRewardPerUnit/p.netRiskPerUnit,p.plannedNetRewardRisk,0);}
 @Test public void invalidQuoteRejected(){assertEquals("CV_CORE_INVALID_QUOTE",CvCorePlan.build(CvCorePolicy.CAPITULATION_LONG,"e","LONG","RAW",1,2,1,1).reasonCode);}
 @Test public void signatureContainsEngineRouteEpisodeAndLevels(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.CAPITULATION_LONG,"LONG");assertTrue(p.signature.contains(CvCorePolicy.ENGINE_ID));assertTrue(p.signature.contains(p.route.routeId));assertTrue(p.signature.contains("episode"));assertTrue(p.signature.contains(String.valueOf(p.takeProfit)));}
 private static CvCorePlan build(CvCorePolicy.Route r,String side,double bid,double ask,double a){CvCorePlan.BuildResult b=CvCorePlan.build(r,"e",side,"RAW",1,bid,ask,a);assertTrue(b.reasonCode,b.accepted());return b.plan;}
}
