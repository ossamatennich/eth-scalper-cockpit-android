package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadowPlanLifecycleTest {
    private static ShadowPlanState plan(String side){return new ShadowPlanState("id","lane","sig",
            MarketProfile.eth(),side,"P01",1,10,100,"LONG".equals(side)?105:95,
            "LONG".equals(side)?95:105,5,5,2,7,14.55,1);}
    @Test public void longAndShortUseBidAndAskAndOnlyTpOrSl(){
        assertEquals("SHADOW_TP_TOUCHED",plan("LONG").observe(20,105,110).status);
        assertEquals("SHADOW_SL_TOUCHED",plan("LONG").observe(20,95,96).status);
        assertEquals("SHADOW_TP_TOUCHED",plan("SHORT").observe(20,94,95).status);
        assertEquals("SHADOW_SL_TOUCHED",plan("SHORT").observe(20,104,105).status);
        assertNull(plan("LONG").observe(Long.MAX_VALUE,100,101));
    }
    @Test public void conservativeSlWinsAmbiguousObservation(){
        ShadowPlanState malformed=new ShadowPlanState("id","lane","sig",MarketProfile.eth(),
                "LONG","P01",1,10,100,95,105,5,5,2,7,14.55,1);
        assertEquals("SHADOW_SL_TOUCHED",malformed.observe(20,100,101).status);
    }
    @Test public void coordinatorIsBoundedDeduplicatedAndResettable(){
        ShadowResearchCoordinator c=new ShadowResearchCoordinator();ShadowPlanState p=plan("LONG");
        assertTrue(c.canOpen("sig",10));assertTrue(c.open(p));assertFalse(c.open(p));
        assertSame(p,c.reset());assertNull(c.active());assertTrue(c.canOpen("sig",11));
    }
}
