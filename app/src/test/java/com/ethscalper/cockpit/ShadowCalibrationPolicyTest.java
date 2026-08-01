package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadowCalibrationPolicyTest {
    @Test public void p01EarlyAndDelayedRespectRealFilterSemantics(){
        NormalizedSignalMetrics.Result m=ShadowTestFixtures.metrics(MarketProfile.eth(),"LONG",
                20_000,1,1,2,2,.4,.6,1,.5,2);
        P01SleeveFilter.Result early=P01SleeveFilter.evaluate(m,20_000);
        assertTrue(early.accepted);assertTrue(early.flowBacked||early.priceLed);
        assertTrue(ShadowCalibrationPolicy.p01FinalGuard(95,m,early,"").keep);
        P01SleeveFilter.Result delayed=P01SleeveFilter.evaluate(m,30_000);
        assertTrue(delayed.accepted);assertFalse(delayed.flowBacked);assertFalse(delayed.priceLed);
        assertTrue(ShadowCalibrationPolicy.p01FinalGuard(95,m,delayed,"").keep);
    }

    @Test public void p01GuardChecksEveryBoundaryAndCurrentRevalidation(){
        MarketProfile p=MarketProfile.eth();long n=30_000;
        NormalizedSignalMetrics.Result ok=ShadowTestFixtures.metrics(p,"LONG",n,1,1,2,2,.20,.4,1.5,.6,1.6);
        assertTrue(ShadowCalibrationPolicy.p01FinalGuard(95,ok,P01SleeveFilter.evaluate(ok,n),"").keep);
        assertFalse(guard(p,n,94,1,2,2,.2,.5,1,.5,2,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.2,.5,1.5000001,.5,2,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.2,.5,1,.5,.99,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.2,.399999,1,.5,2,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.2,1.000001,1,.5,2,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.2,.5,1,.600001,2,"").keep);
        assertFalse(guard(p,n,95,1,2,3.500001,.2,.5,1,.5,2,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.149,.5,1,.5,2,"").keep);
        assertFalse(guard(p,n,95,1,2,2,.2,.5,1,.5,2,
                ContinuationConfirmation.P01_MOVE1_REJECT).keep);
        assertTrue(guard(p,n,95,1,2,2,.2,.5,1,.5,2,"PRIX_DEJA_TROP_LOIN").keep);
    }

    @Test public void p02Score80IsObservedButNotAVeto(){
        MarketProfile p=MarketProfile.eth();NormalizedSignalMetrics.Result m=ShadowTestFixtures.metrics(
                p,"LONG",30_000,1,1,2,2,.3,0,.30,.85,1);
        assertTrue(ShadowCalibrationPolicy.p02AntiExhaustion(80,m).keep);
        assertFalse(ShadowCalibrationPolicy.p02AntiExhaustion(100,
                ShadowTestFixtures.metrics(p,"LONG",30_000,1,1,2,2,.3,-.0001,.3,.8,1)).keep);
        assertFalse(ShadowCalibrationPolicy.p02AntiExhaustion(80,
                ShadowTestFixtures.metrics(p,"LONG",30_000,1,1,2,2,.3,0,.299,.8,1)).keep);
        assertFalse(ShadowCalibrationPolicy.p02AntiExhaustion(80,
                ShadowTestFixtures.metrics(p,"LONG",30_000,1,1,2,2,.3,0,.3,.8501,1)).keep);
        assertFalse(ShadowCalibrationPolicy.p02AntiExhaustion(80,
                ShadowTestFixtures.metrics(p,"LONG",30_000,1,1,2,3.51,.3,0,.3,.8,1)).keep);
        assertFalse(ShadowCalibrationPolicy.p02AntiExhaustion(80,
                ShadowTestFixtures.metrics(p,"LONG",30_000,1,1,2,2,.3,0,.3,.8,.29)).keep);
    }

    @Test public void pullbackAndMidVolAreSymmetricAndProfileBound(){
        for(String side:new String[]{"LONG","SHORT"})assertTrue(ShadowCalibrationPolicy.pullback(95,
                ShadowTestFixtures.metrics(MarketProfile.eth(),side,10_000,1,1,2,-2,.5,.6,1,.5,2)).keep);
        assertTrue(ShadowCalibrationPolicy.ethMidVol(MarketProfile.eth(),95,
                ShadowTestFixtures.metrics(MarketProfile.eth(),"LONG",10_000,1.2,0,2,0,.22,.6,1,.5,2)).keep);
        assertTrue(ShadowCalibrationPolicy.ethMidVol(MarketProfile.eth(),95,
                ShadowTestFixtures.metrics(MarketProfile.eth(),"LONG",10_000,1.65,0,2,0,.22,.6,1,.5,2)).keep);
        assertFalse(ShadowCalibrationPolicy.ethMidVol(MarketProfile.sol(),95,
                ShadowTestFixtures.metrics(MarketProfile.sol(),"LONG",10_000,.02,0,2,0,.22,.6,1,.5,2)).keep);
    }

    private static ShadowCalibrationPolicy.Decision guard(MarketProfile p,long n,int score,double m1,
            double m3,double m8,double f30,double f60,double vr,double edge,double room,String code){
        NormalizedSignalMetrics.Result m=ShadowTestFixtures.metrics(p,"LONG",n,1,m1,m3,m8,f30,f60,vr,edge,room);
        return ShadowCalibrationPolicy.p01FinalGuard(score,m,P01SleeveFilter.evaluate(m,n),code);
    }
}
