package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadowPlanLifecycleTest {
    private static ShadowPlanState plan(String side){return plan(side,"sig",10);}
    private static ShadowPlanState plan(String side,String signature,long openedAt){return new ShadowPlanState("id","lane",signature,
            MarketProfile.eth(),side,"P01",1,openedAt,100,"LONG".equals(side)?105:95,
            "LONG".equals(side)?95:105,5,5,2,7,14.55,1,2,.5);}
    @Test public void longAndShortUseBidAndAskAndOnlyTpOrSl(){
        assertEquals("SHADOW_TP_TOUCHED",plan("LONG").observe(20,105,110).status);
        assertEquals("SHADOW_SL_TOUCHED",plan("LONG").observe(20,95,96).status);
        assertEquals("SHADOW_TP_TOUCHED",plan("SHORT").observe(20,94,95).status);
        assertEquals("SHADOW_SL_TOUCHED",plan("SHORT").observe(20,104,105).status);
        assertNull(plan("LONG").observe(Long.MAX_VALUE,100,101));
    }
    @Test public void conservativeSlWinsAmbiguousObservation(){
        ShadowPlanState malformed=new ShadowPlanState("id","lane","sig",MarketProfile.eth(),
                "LONG","P01",1,10,100,95,105,5,5,2,7,14.55,1,2,.5);
        assertEquals("SHADOW_SL_TOUCHED",malformed.observe(20,100,101).status);
    }
    @Test public void coordinatorIsBoundedDeduplicatedAndResettable(){
        ShadowResearchCoordinator c=new ShadowResearchCoordinator();ShadowPlanState p=plan("LONG");
        assertTrue(c.canOpen("sig",10));assertTrue(c.open(p));assertFalse(c.open(p));
        assertSame(p,c.reset());assertNull(c.active());assertTrue(c.canOpen("sig",11));
    }
    @Test public void resultRUsesPlannedNetRiskIncludingFeesForLongAndShortAndGaps(){
        ShadowPlanState longPlan=plan("LONG");
        assertEquals(8.0/12.0,longPlan.observe(20,105,106).resultR,1e-12);
        assertEquals(-1.0,plan("LONG").observe(20,95,96).resultR,1e-12);
        assertEquals(10.0/12.0,plan("LONG").observe(20,106,107).resultR,1e-12);
        assertEquals(-14.0/12.0,plan("LONG").observe(20,94,95).resultR,1e-12);
        assertEquals(8.0/12.0,plan("SHORT").observe(20,94,95).resultR,1e-12);
        assertEquals(-1.0,plan("SHORT").observe(20,104,105).resultR,1e-12);
        assertEquals(10.0/12.0,plan("SHORT").observe(20,93,94).resultR,1e-12);
        assertEquals(-14.0/12.0,plan("SHORT").observe(20,105,106).resultR,1e-12);
        ShadowPlanState.Terminal terminal=plan("LONG").observe(20,105,106);
        assertEquals(10,terminal.plannedGrossStopUsdt,0);assertEquals(2,terminal.plannedFeesUsdt,0);
        assertEquals(12,terminal.plannedNetStopUsdt,0);
    }
    @Test public void staleOrInvalidQuotesCannotTerminateAndFreshQuoteTerminatesOnce(){
        for(String side:new String[]{"LONG","SHORT"}){
            ShadowResearchCoordinator c=new ShadowResearchCoordinator();assertTrue(c.open(plan(side)));
            double bid="LONG".equals(side)?105:94,doubleAsk="LONG".equals(side)?106:95;
            assertNull(c.observe(20,bid,doubleAsk,false));assertNotNull(c.active());
            assertNull(c.observe(20,Double.NaN,doubleAsk,true));assertNotNull(c.active());
            assertNotNull(c.observe(20,bid,doubleAsk,true));assertNull(c.active());
            assertNull(c.observe(21,bid,doubleAsk,true));
        }
    }
    @Test public void cooldownBoundariesAndSignatureEvictionAreExact(){
        ShadowResearchCoordinator c=new ShadowResearchCoordinator();
        assertTrue(c.open(plan("LONG","first",10)));assertNotNull(c.observe(100,105,106,true));
        assertFalse(c.canOpen("next",100+ShadowResearchCoordinator.COOLDOWN_MS-1));
        assertTrue(c.canOpen("next",100+ShadowResearchCoordinator.COOLDOWN_MS));
        long now=100+ShadowResearchCoordinator.COOLDOWN_MS;
        for(int i=0;i<160;i++){
            String signature="s"+i;ShadowPlanState p=plan("LONG",signature,now);
            assertTrue(c.open(p));assertNotNull(c.observe(now+1,105,106,true));
            now+=ShadowResearchCoordinator.COOLDOWN_MS+1;
        }
        assertEquals(160,c.rememberedSignatures());
        ShadowPlanState newest=plan("LONG","newest",now);assertTrue(c.open(newest));
        assertNotNull(c.observe(now+1,105,106,true));assertEquals(160,c.rememberedSignatures());
        assertTrue(c.canOpen("first",now+1+ShadowResearchCoordinator.COOLDOWN_MS));
        assertFalse(c.canOpen("newest",now+1+ShadowResearchCoordinator.COOLDOWN_MS));
    }
}
