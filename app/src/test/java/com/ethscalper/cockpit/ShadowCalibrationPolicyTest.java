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

    @Test public void p02PolicyIsSymbolAwareWithoutChangingPublicScore(){
        NormalizedSignalMetrics.Result ok=ShadowTestFixtures.metrics(MarketProfile.eth(),"LONG",
                30_000,1,1,2,2,.3,.4,.5,.5,1);
        assertEquals("SHADOW_SOL_P02_QUARANTINE",
                ShadowCalibrationPolicy.p02Symbolic(MarketProfile.sol(),80,ok).reasonCode);
        assertEquals("SHADOW_ETH_P02_SCORE_TOO_LOW",
                ShadowCalibrationPolicy.p02Symbolic(MarketProfile.eth(),84,ok).reasonCode);
        assertTrue(ShadowCalibrationPolicy.p02Symbolic(MarketProfile.eth(),85,ok).keep);
    }

    @Test public void offSampleNumericProfilesHaveDeterministicShadowDecisions(){
        NormalizedSignalMetrics.Result sol=ShadowTestFixtures.metrics(MarketProfile.sol(),"LONG",
                30_000,.038,.789474,2.894737,1.842105,.516234,.939451,.621643,.730769,.789474);
        assertEquals("SHADOW_SOL_P02_QUARANTINE",
                ShadowCalibrationPolicy.p02Symbolic(MarketProfile.sol(),80,sol).reasonCode);
        NormalizedSignalMetrics.Result exhausted=ShadowTestFixtures.metrics(MarketProfile.eth(),"SHORT",
                30_000,.548,.547445,2.189781,5.145985,.268345,.989300,2.007345,.958333,0);
        assertEquals("SHADOW_P02_ROOM_TOO_LOW",
                ShadowCalibrationPolicy.p02Symbolic(MarketProfile.eth(),87,exhausted).reasonCode);
        NormalizedSignalMetrics.Result weakFlow=ShadowTestFixtures.metrics(MarketProfile.eth(),"SHORT",
                20_000,1.179,.890585,1.882952,-1.348601,.207444,.172561,.428817,.445248,2.188295);
        assertEquals("SHADOW_P01_FLOW60_TOO_LOW",ShadowCalibrationPolicy.p01FinalGuard(96,weakFlow,
                P01SleeveFilter.evaluate(weakFlow,20_000),"").reasonCode);
        NormalizedSignalMetrics.Result extended=ShadowTestFixtures.metrics(MarketProfile.eth(),"LONG",
                20_000,.996,-.281124,2.228916,3.644578,.655920,.865977,.815324,.649688,2);
        assertTrue(ShadowCalibrationPolicy.ethFlowExpansionExtended(MarketProfile.eth(),96,extended).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowExpansionExtended(MarketProfile.sol(),96,extended).keep);
    }

    @Test public void extendedLaneBoundariesAreExact(){
        MarketProfile p=MarketProfile.eth();
        assertTrue(ShadowCalibrationPolicy.ethFlowExpansionExtended(p,95,
                ShadowTestFixtures.metrics(p,"LONG",20_000,.80,-.299,1,0,.22,.70,.5,.5,2)).keep);
        assertTrue(ShadowCalibrationPolicy.ethFlowExpansionExtended(p,95,
                ShadowTestFixtures.metrics(p,"LONG",20_000,1.65,-.299,1,0,.22,.70,.5,.5,2)).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowExpansionExtended(p,94,
                ShadowTestFixtures.metrics(p,"LONG",20_000,1,-.299,1,0,.22,.70,.5,.5,2)).keep);
        assertFalse(ShadowCalibrationPolicy.ethFlowExpansionExtended(p,95,
                ShadowTestFixtures.metrics(p,"LONG",20_000,1,-.30,1,0,.22,.70,.5,.5,2)).keep);
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

    @Test public void currentRevalidationRecognizesRealFrenchAndEnglishFamilies(){
        String[] critical={ContinuationConfirmation.P01_MOVE1_REJECT,
                ContinuationConfirmation.P01_MOVE3_REJECT,ContinuationConfirmation.C04_REJECT,
                ContinuationConfirmation.C07_REJECT,ContinuationConfirmation.C08_REJECT,
                ContinuationConfirmation.P01_FLOW_REJECT,"FEED_STALE_AT_FILL",
                "REPLAY_QUALITY_TOO_LOW","REPLAY_QUALITÉ_INSUFFISANTE",
                "DIVERGENCE_FLOW_PRIX_ACTUELLE","MOUVEMENT_CONSOMMÉ"};
        for(String code:critical)assertTrue(code,ShadowCalibrationPolicy.isCriticalCurrentRevalidation(code));
        assertFalse(ShadowCalibrationPolicy.isCriticalCurrentRevalidation("PRIX_DEJA_TROP_LOIN"));
    }

    @Test public void historicalTargetTouchBlocksOpeningEvenAfterPriceReturns(){
        SignalDecision candidate=ShadowTestFixtures.candidate(MarketProfile.eth(),"LONG",95);
        assertTrue(ShadowCalibrationPolicy.targetUntouchedBeforeOpen(candidate,candidate.targetMove-.01));
        assertFalse(ShadowCalibrationPolicy.targetUntouchedBeforeOpen(candidate,candidate.targetMove));
        assertFalse(ShadowCalibrationPolicy.targetUntouchedBeforeOpen(candidate,candidate.targetMove+1));
    }

    private static ShadowCalibrationPolicy.Decision guard(MarketProfile p,long n,int score,double m1,
            double m3,double m8,double f30,double f60,double vr,double edge,double room,String code){
        NormalizedSignalMetrics.Result m=ShadowTestFixtures.metrics(p,"LONG",n,1,m1,m3,m8,f30,f60,vr,edge,room);
        return ShadowCalibrationPolicy.p01FinalGuard(score,m,P01SleeveFilter.evaluate(m,n),code);
    }
}
