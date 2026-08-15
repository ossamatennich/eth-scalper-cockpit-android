package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23449CvCoreLifecycleTest {
 @Test public void longUsesBid(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.CAPITULATION_LONG,"LONG");assertNull(p.observe(2,p.takeProfit-.01,p.takeProfit+10,true));assertEquals(CvCorePlan.TP,p.observe(2,p.takeProfit,p.takeProfit+.01,true).status);}
 @Test public void shortUsesAsk(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"SHORT");assertNull(p.observe(2,p.takeProfit-10,p.takeProfit+.01,true));assertEquals(CvCorePlan.TP,p.observe(2,p.takeProfit-.01,p.takeProfit,true).status);}
 @Test public void overshootFillsPlannedTp(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.CAPITULATION_LONG,"LONG");CvCorePlan.Terminal t=p.observe(2,p.takeProfit+100,p.takeProfit+101,true);assertEquals(p.takeProfit,t.fillPrice,0);assertNotEquals(t.touchQuote,t.fillPrice,0);}
 @Test public void stopFillsPlannedAndIsMinusOneR(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.DUAL_EXHAUSTION_SHORT,"SHORT");CvCorePlan.Terminal t=p.observe(2,p.stopLoss+99,p.stopLoss+100,true);assertEquals(p.stopLoss,t.fillPrice,0);assertEquals(-1,t.resultR,0);assertEquals(-p.quantity*p.stopDistance-p.quantity*1.43,t.netResultUsdt,1e-12);}
 @Test public void staleAndInvalidNeverTerminal(){CvCorePlan p=CvCoreTestFixtures.plan(CvCorePolicy.CAPITULATION_LONG,"LONG");assertNull(p.observe(2,p.takeProfit,p.takeProfit,false));assertNull(p.observe(2,Double.NaN,1,true));}
 @Test public void episodeCannotBeReusedAfterTerminal(){CvCoreMovementRegistry r=new CvCoreMovementRegistry();CvCoreMovementRegistry.Episode e=r.observe("ETHUSDT","LONG",1);r.markOpened(e.episodeId,"B");CvCoreTestFixtures.plan(CvCorePolicy.CAPITULATION_LONG,"LONG").observe(2,9999,10000,true);assertFalse(r.markOpened(e.episodeId,"A"));}
}
