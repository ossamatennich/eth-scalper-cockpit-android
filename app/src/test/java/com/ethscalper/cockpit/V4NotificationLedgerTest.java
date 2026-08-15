package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class V4NotificationLedgerTest {
    @Test public void eventIsDeliveredOnceAndSurvivesRestart(){
        Memory memory=new Memory();V4NotificationLedger first=new V4NotificationLedger(memory);
        assertTrue(first.claim("plan-1",V4NotificationPolicy.Event.ACTIONABLE));
        assertFalse(first.claim("plan-1",V4NotificationPolicy.Event.ACTIONABLE));
        assertTrue(first.claim("plan-1",V4NotificationPolicy.Event.ENTRY_FILLED));
        V4NotificationLedger restarted=new V4NotificationLedger(memory);
        assertFalse(restarted.claim("plan-1",V4NotificationPolicy.Event.ACTIONABLE));
        assertFalse(restarted.claim("plan-1",V4NotificationPolicy.Event.ENTRY_FILLED));
        assertEquals(2,restarted.size());
    }
    @Test public void plansAndLifecycleEventsHaveIndependentKeys(){
        Memory memory=new Memory();V4NotificationLedger ledger=new V4NotificationLedger(memory);
        assertTrue(ledger.claim("a",V4NotificationPolicy.Event.TP));
        assertTrue(ledger.claim("a",V4NotificationPolicy.Event.SL));
        assertTrue(ledger.claim("b",V4NotificationPolicy.Event.TP));
        assertEquals(3,ledger.size());
    }
    @Test public void failedPersistenceDoesNotConsumeEvent(){
        V4NotificationLedger ledger=new V4NotificationLedger(new V4NotificationLedger.Backend(){public String load(){return "[]";}public boolean save(String value){return false;}});
        assertFalse(ledger.claim("p",V4NotificationPolicy.Event.ACTIONABLE));assertFalse(ledger.contains("p",V4NotificationPolicy.Event.ACTIONABLE));
    }
    @Test public void releaseAllowsRetryAfterPostingFailure(){
        Memory memory=new Memory();V4NotificationLedger ledger=new V4NotificationLedger(memory);
        assertTrue(ledger.claim("p",V4NotificationPolicy.Event.SL));ledger.release("p",V4NotificationPolicy.Event.SL);
        assertTrue(ledger.claim("p",V4NotificationPolicy.Event.SL));
    }
    private static final class Memory implements V4NotificationLedger.Backend{String value="[]";public String load(){return value;}public boolean save(String json){value=json;return true;}}
}
