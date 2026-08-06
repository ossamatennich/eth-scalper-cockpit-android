package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23449CvCoreContextTrackerTest {
 @Test public void sameSecondReplaces(){CvCoreContextTracker t=new CvCoreContextTracker();t.observe("ETHUSDT",100,101,1000);t.observe("ETHUSDT",102,103,1999);assertEquals(1,t.size("ETHUSDT"));assertEquals(102.5,t.sampleAtOrBefore("ETHUSDT",1999,5000).mid,0);}
 @Test public void futurePointNeverUsed(){CvCoreContextTracker t=new CvCoreContextTracker();t.observe("ETHUSDT",100,100,2000);assertNull(t.sampleAtOrBefore("ETHUSDT",1999,5000));}
 @Test public void staleAnchorRejected(){CvCoreContextTracker t=new CvCoreContextTracker();t.observe("ETHUSDT",100,100,1);t.observe("ETHUSDT",101,101,70000);assertFalse(t.metrics(70000,"LONG",0,0).directionalEthReturn60Valid);}
 @Test public void returnsHaveDirectionalSign(){CvCoreContextTracker t=tracker();assertEquals(.01,t.metrics(61000,"LONG",0,0).directionalEthReturn60,1e-12);assertEquals(-.01,t.metrics(61000,"SHORT",0,0).directionalEthReturn60,1e-12);}
 @Test public void efficiencyUsesExactPath(){CvCoreContextTracker t=new CvCoreContextTracker();t.observe("ETHUSDT",100,100,1000);t.observe("ETHUSDT",102,102,31000);t.observe("ETHUSDT",101,101,61000);CvCoreContextTracker.Metrics m=t.metrics(61000,"LONG",0,0);assertEquals(1d/3d,m.directionalEthEfficiency60,1e-12);assertEquals(3,m.ethPathPoints60);assertEquals(3,m.ethPathDistance60,0);}
 @Test public void shortEfficiencyIsSigned(){CvCoreContextTracker t=tracker();assertEquals(-1,t.metrics(61000,"SHORT",0,0).directionalEthEfficiency60,0);}
 @Test public void btcMetricsAreSignedOnce(){CvCoreContextTracker t=tracker();assertEquals(-.2,t.metrics(61000,"SHORT",.2,.1).directionalBtcMove8,0);assertEquals(-.1,t.metrics(61000,"SHORT",.2,.1).directionalBtcMove3,0);}
 @Test public void capacityEvictsOldest(){CvCoreContextTracker t=new CvCoreContextTracker();for(int i=1;i<=1201;i++)t.observe("ETHUSDT",100,100,i*1000L);assertEquals(1200,t.size("ETHUSDT"));assertNull(t.sampleAtOrBefore("ETHUSDT",1000,0));}
 @Test public void invalidQuotesIgnored(){CvCoreContextTracker t=new CvCoreContextTracker();assertFalse(t.observe("ETHUSDT",Double.NaN,1,1));assertFalse(t.observe("ETHUSDT",2,1,1));assertEquals(0,t.size("ETHUSDT"));}
 @Test public void solThirtySecondEfficiencyAvailable(){CvCoreContextTracker t=new CvCoreContextTracker();for(int i=0;i<=60;i++)t.observe("SOLUSDT",100+i,100+i,i*1000L+1);assertTrue(t.metrics(60001,"LONG",0,0).directionalSolEfficiency30Valid);assertEquals(1,t.metrics(60001,"LONG",0,0).directionalSolEfficiency30,0);}
 private static CvCoreContextTracker tracker(){CvCoreContextTracker t=new CvCoreContextTracker();t.observe("ETHUSDT",100,100,1000);t.observe("ETHUSDT",101,101,61000);return t;}
}
