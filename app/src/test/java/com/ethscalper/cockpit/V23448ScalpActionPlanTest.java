package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23448ScalpActionPlanTest {
 @Test public void shortFixtureHasExactGeometryAndSizing(){ScalpActionPlan p=build(ScalpActionPolicy.RANGE_EXTREME,"SHORT",1900,1900.01,1.2);assertEquals(1900,p.entry,0);assertEquals(1897,p.takeProfit,0);assertEquals(1901.5,p.stopLoss,0);assertEquals(4,p.quantity);assertEquals(1.57/2.93,p.plannedNetRewardRisk,1e-12);}
 @Test public void longUsesAskAndConservativeTicks(){ScalpActionPlan p=build(ScalpActionPolicy.CONFIRM_MOVE3,"LONG",1899.99,1900.001,2.1);assertEquals(1900.01,p.entry,1e-9);assertTrue(p.takeProfit>p.entry);assertTrue(p.stopLoss<p.entry);}
 @Test public void quantityNeverExceedsSeven(){ScalpActionPlan p=build(ScalpActionPolicy.RANGE_EXTREME,"LONG",100,100.01,1.2);assertTrue(p.quantity<=7);assertTrue(p.quantity>=1);}
 @Test public void lossNeverExceedsBudget(){ScalpActionPlan p=build(ScalpActionPolicy.RANGE_EXTREME,"SHORT",1900,1900.01,1.2);assertTrue(p.theoreticalMaximumLoss<=14.55+1e-9);}
 @Test public void invalidQuoteIsRejected(){assertEquals("SCALP_ACTION_INVALID_QUOTE",ScalpActionPlan.build(ScalpActionPolicy.RANGE_EXTREME,"e","LONG","RAW",1,0,0,1).reasonCode);}
 private static ScalpActionPlan build(ScalpActionPolicy.Route r,String side,double bid,double ask,double a){ScalpActionPlan.BuildResult b=ScalpActionPlan.build(r,"episode",side,"RAW",1,bid,ask,a);assertTrue(b.reasonCode,b.accepted());return b.plan;}
}
