package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23449CvCorePolicyTest {
 @Test public void aSolBoundaryInclusive(){assertEquals(CvCorePolicy.DUAL_EXHAUSTION_SHORT,rawA(-.00035,-.00030,-.399999));}
 @Test public void aSolAboveRejects(){assertNull(rawA(-.00035,-.000299999,-.399999));}
 @Test public void aEthBoundaryInclusive(){assertEquals(CvCorePolicy.DUAL_EXHAUSTION_SHORT,rawA(-.00035,-.00030,-.399999));}
 @Test public void aEthAboveRejects(){assertNull(rawA(-.000349999,-.00030,-.399999));}
 @Test public void aEfficiencyMinusPointFourExclusive(){assertNull(rawA(-.001,-.001,-.40));}
 @Test public void aEfficiencyJustAboveQualifies(){assertNotNull(rawA(-.001,-.001,-.399999));}
 @Test public void aRejectsLongAndWrongFamily(){assertNull(CvCorePolicy.selectRaw(CvCoreTestFixtures.signal("LONG","RANGE_FADE"),CvCoreTestFixtures.metrics(-1,-1,0,1,0,0)));assertNull(CvCorePolicy.selectRaw(CvCoreTestFixtures.signal("SHORT","CONTINUATION"),CvCoreTestFixtures.metrics(-1,-1,0,1,0,0)));}
 @Test public void bBoundariesInclusive(){assertEquals(CvCorePolicy.CAPITULATION_LONG,rawB(-.0010,-.0016));}
 @Test public void bRejectsAboveEitherBoundary(){assertNull(rawB(-.000999999,-.0016));assertNull(rawB(-.001,-.001599999));}
 @Test public void bRejectsShortAndWrongFamily(){assertNull(CvCorePolicy.selectRaw(CvCoreTestFixtures.signal("SHORT","RANGE_FADE"),CvCoreTestFixtures.metrics(-1,0,-1,1,-1,0)));assertNull(CvCorePolicy.selectRaw(CvCoreTestFixtures.signal("LONG","OTHER"),CvCoreTestFixtures.metrics(-1,-1,0,1,-1,0)));}
 @Test public void cBoundariesInclusiveAndEfficiencyStrict(){assertEquals(CvCorePolicy.P02_BALANCED_SHORT,legacy(.0002,2,.000001));assertNull(legacy(.000200001,2,.1));assertNull(legacy(.0002,2.000001,.1));assertNull(legacy(.0002,2,0));}
 @Test public void cRequiresP02ShortContinuationAndMetrics(){CvCoreContextTracker.Metrics m=CvCoreTestFixtures.metrics(0,0,0,.1,0,.0002);assertNull(CvCorePolicy.selectLegacy("ETHUSDT","SHORT","CONTINUATION","P01",1,m));assertNull(CvCorePolicy.selectLegacy("ETHUSDT","LONG","CONTINUATION","P02",1,m));assertNull(CvCorePolicy.selectLegacy("ETHUSDT","SHORT","RANGE","P02",1,m));assertNull(CvCorePolicy.selectLegacy("ETHUSDT","SHORT","CONTINUATION","P02",Double.NaN,m));}
 @Test public void identifiersAndBudgetsAreExact(){assertEquals("NMC_SCALP_CV_CORE_V1",CvCorePolicy.ENGINE_ID);assertEquals("SCALP_CV_CORE_V1_20260806",CvCorePolicy.POLICY_ID);assertEquals("SCALP_CV_SCHEMA_V1",CvCorePolicy.SCHEMA_ID);assertEquals(14.55,CvCorePolicy.DUAL_EXHAUSTION_SHORT.riskBudgetUsdt,0);assertEquals(7.275,CvCorePolicy.P02_BALANCED_SHORT.riskBudgetUsdt,0);}
 private static CvCorePolicy.Route rawA(double er,double sr,double eff){return CvCorePolicy.selectRaw(CvCoreTestFixtures.signal("SHORT","RANGE_FADE"),CvCoreTestFixtures.metrics(er,sr,eff,1,0,0));}
 private static CvCorePolicy.Route rawB(double er,double b8){return CvCorePolicy.selectRaw(CvCoreTestFixtures.signal("LONG","RANGE_FADE"),CvCoreTestFixtures.metrics(er,0,0,1,b8,0));}
 private static CvCorePolicy.Route legacy(double b3,double m3,double se){return CvCorePolicy.selectLegacy("ETHUSDT","SHORT","CONTINUATION","P02",m3,CvCoreTestFixtures.metrics(0,0,0,se,0,b3));}
}
