package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23448ScalpActionContextTrackerTest {
 @Test public void sameSecondKeepsLastQuote(){ScalpActionContextTracker t=new ScalpActionContextTracker();t.observe("ETHUSDT",99,101,1000);t.observe("ETHUSDT",109,111,1999);assertEquals(110,t.sampleAtOrBefore("ETHUSDT",1999,5000).mid,0);assertEquals(1,t.size("ETHUSDT"));}
 @Test public void futurePointIsNeverUsed(){ScalpActionContextTracker t=new ScalpActionContextTracker();t.observe("ETHUSDT",99,101,2000);assertNull(t.sampleAtOrBefore("ETHUSDT",1999,5000));}
 @Test public void anchorTooOldIsRejected(){ScalpActionContextTracker t=new ScalpActionContextTracker();t.observe("ETHUSDT",99,101,1);t.observe("ETHUSDT",109,111,486001);assertFalse(t.metrics(486001,"LONG").ethDret480Valid);}
 @Test public void directionalReturnIsSymmetric(){ScalpActionContextTracker t=new ScalpActionContextTracker();t.observe("ETHUSDT",99,101,1000);t.observe("ETHUSDT",100,102,481000);assertEquals(-t.metrics(481000,"LONG").ethDret480,t.metrics(481000,"SHORT").ethDret480,1e-12);}
 @Test public void solRmsUsesConsecutiveValidSlots(){ScalpActionContextTracker t=new ScalpActionContextTracker();for(int i=0;i<=30;i++){double m=100*Math.exp(i*.001);t.observe("SOLUSDT",m-.01,m+.01,1000+i*1000);}ScalpActionContextTracker.Metrics m=t.metrics(31000,"LONG");assertTrue(m.solRv30Valid);assertEquals(.001,m.solRv30,1e-9);}
 @Test public void holesDoNotBridgeReturns(){ScalpActionContextTracker t=new ScalpActionContextTracker();for(int i=0;i<=30;i++)if(i<8||i>20)t.observe("SOLUSDT",99+i,101+i,1000+i*1000);assertFalse(t.metrics(31000,"LONG").solRv30Valid);}
 @Test public void coverageIsExact(){ScalpActionContextTracker t=new ScalpActionContextTracker();for(int i=0;i<181;i++)t.observe("SOLUSDT",99,101,1000+i*1000);assertEquals(1d,t.metrics(181000,"LONG").solCov180,0);}
 @Test public void storageIsBounded(){ScalpActionContextTracker t=new ScalpActionContextTracker();for(int i=0;i<1300;i++)t.observe("BTCUSDT",99,101,1000L*i);assertEquals(1200,t.size("BTCUSDT"));}
}
