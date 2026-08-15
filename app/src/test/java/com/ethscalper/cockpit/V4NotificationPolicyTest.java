package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class V4NotificationPolicyTest {
    @Test public void firstLimitOrExecutableStateIsActionable(){
        assertEquals(V4NotificationPolicy.Event.ACTIONABLE,V4NotificationPolicy.event(V4Plan.Status.WAITING,V4Plan.Status.LIMIT_ORDER_POSSIBLE));
        assertEquals(V4NotificationPolicy.Event.ACTIONABLE,V4NotificationPolicy.event(V4Plan.Status.WAITING,V4Plan.Status.EXECUTABLE));
    }
    @Test public void orderPlacedToOpenIsEntryFilled(){assertEquals(V4NotificationPolicy.Event.ENTRY_FILLED,
            V4NotificationPolicy.event(V4Plan.Status.ORDER_PLACED,V4Plan.Status.OPEN));}
    @Test public void tpAndSlAreTerminalEvents(){
        assertEquals(V4NotificationPolicy.Event.TP,V4NotificationPolicy.event(V4Plan.Status.OPEN,V4Plan.Status.CLOSED_TP));
        assertEquals(V4NotificationPolicy.Event.SL,V4NotificationPolicy.event(V4Plan.Status.OPEN,V4Plan.Status.CLOSED_SL));
    }
    @Test public void unchangedAndNonAlertingStatesAreSilent(){
        assertEquals(V4NotificationPolicy.Event.NONE,V4NotificationPolicy.event(V4Plan.Status.OPEN,V4Plan.Status.OPEN));
        assertEquals(V4NotificationPolicy.Event.NONE,V4NotificationPolicy.event(V4Plan.Status.WAITING,V4Plan.Status.EXPIRED));
        assertEquals(V4NotificationPolicy.Event.NONE,V4NotificationPolicy.event(V4Plan.Status.LIMIT_ORDER_POSSIBLE,V4Plan.Status.ORDER_PLACED));
    }
    @Test public void contentContainsOnlyManualExecutionFields(){
        V4Plan plan=plan(V4Plan.Side.SHORT);
        V4NotificationPolicy.Message actionable=V4NotificationPolicy.message(plan,V4NotificationPolicy.Event.ACTIONABLE);
        assertEquals("ONDO SHORT — Nouveau plan",actionable.title);
        for(String value:new String[]{"Qté 2366,2","Entry 0,326","TP 0,295171","SL 0,341414"})assertTrue(actionable.body.contains(value));
        assertEquals("ONDO SHORT — Entrée exécutée",V4NotificationPolicy.message(plan,V4NotificationPolicy.Event.ENTRY_FILLED).title);
        assertTrue(V4NotificationPolicy.message(plan,V4NotificationPolicy.Event.ENTRY_FILLED).body.contains("EN COURS"));
        assertEquals("ONDO SHORT — TP ATTEINT",V4NotificationPolicy.message(plan,V4NotificationPolicy.Event.TP).title);
        assertEquals("ONDO SHORT — SL ATTEINT",V4NotificationPolicy.message(plan,V4NotificationPolicy.Event.SL).title);
    }
    private static V4Plan plan(V4Plan.Side side){return new V4Plan("p",null,V4Plan.Source.CORE,"ONDO",side,2366.2,.326,.29517143,.34141429,.01,1,1,99_999,
            V4Plan.Status.WAITING,"",5000,.01,0,null);}
}
