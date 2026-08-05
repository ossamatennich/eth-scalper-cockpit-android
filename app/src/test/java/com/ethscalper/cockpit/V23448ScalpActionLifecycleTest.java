package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23448ScalpActionLifecycleTest {
 @Test public void longUsesBidAndOvershootFillsAtTarget(){ScalpActionPlan p=plan("LONG");ScalpActionPlan.Terminal t=p.observe(2,p.takeProfit+10,p.takeProfit+11,true);assertEquals(p.takeProfit,t.fillPrice,0);}
 @Test public void shortUsesAskAndOvershootFillsAtStop(){ScalpActionPlan p=plan("SHORT");ScalpActionPlan.Terminal t=p.observe(2,p.stopLoss+10,p.stopLoss+11,true);assertEquals(p.stopLoss,t.fillPrice,0);assertEquals(-1,t.resultR,0);}
 @Test public void staleAndInvalidQuoteCannotTerminate(){ScalpActionPlan p=plan("LONG");assertNull(p.observe(2,p.takeProfit,p.takeProfit,false));assertNull(p.observe(2,Double.NaN,p.takeProfit,true));}
 @Test public void tpEconomicsUsePlannedLevel(){ScalpActionPlan p=plan("LONG");ScalpActionPlan.Terminal t=p.observe(2,p.takeProfit,p.takeProfit+.01,true);assertEquals(p.quantity*p.targetDistance-p.quantity*1.43,t.netResultUsdt,1e-9);}
 private static ScalpActionPlan plan(String side){double bid="LONG".equals(side)?1900:1899.99,ask=1900.01;ScalpActionPlan.BuildResult b=ScalpActionPlan.build(ScalpActionPolicy.RANGE_EXTREME,"e",side,"RAW",1,bid,ask,1.2);assertTrue(b.accepted());return b.plan;}
}
