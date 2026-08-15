package com.ethscalper.cockpit;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class V4PlanLifecycleTest {
    private V4Plan plan(V4Plan.Status s){return new V4Plan("p",null,V4Plan.Source.CORE,"BTC",V4Plan.Side.LONG,1,100,110,95,5,1000,1000,10_000,s,"",5000,.0225,0,null);}
    private V4Plan shortPlan(V4Plan.Status s){return new V4Plan("s",null,V4Plan.Source.CORE,"BTC",V4Plan.Side.SHORT,1,100,90,105,5,1000,1000,10_000,s,"",5000,.0225,0,null);}
    private V4PlanLifecycle.PricePoint bar(long at,double lo,double hi,double bid,double ask){return new V4PlanLifecycle.PricePoint(at,100,hi,lo,100,bid,ask);}
    @Test public void targetBeforeEntryIsTooLate(){V4Plan p=plan(V4Plan.Status.WAITING);assertEquals(V4Plan.Status.MISSED_TOO_LATE,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,111,105,106)),3000,new V4MarketMetadata(1,1,1,1)));assertEquals("Mouvement déjà consommé",p.statusReason);}
    @Test public void stopBeforeEntryInvalidates(){V4Plan p=plan(V4Plan.Status.WAITING);assertEquals(V4Plan.Status.INVALIDATED,V4PlanLifecycle.evaluate(p,List.of(bar(2000,94,101,99,100)),3000,new V4MarketMetadata(1,1,1,1)));}
    @Test public void expiryWinsWhenNoTouch(){V4Plan p=plan(V4Plan.Status.WAITING);assertEquals(V4Plan.Status.EXPIRED,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,101,104,105)),10_001,new V4MarketMetadata(1,1,1,1)));}
    @Test public void executableUsesTickOrSpreadOnly(){V4Plan p=plan(V4Plan.Status.WAITING);assertEquals(V4Plan.Status.EXECUTABLE,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,101,99.5,100.5)),3000,new V4MarketMetadata(.1,1,1,1)));}
    @Test public void offEntryIsLimitPossible(){V4Plan p=plan(V4Plan.Status.WAITING);assertEquals(V4Plan.Status.LIMIT_ORDER_POSSIBLE,V4PlanLifecycle.evaluate(p,List.of(bar(2000,101,102,101,102)),3000,new V4MarketMetadata(.1,1,1,1)));}
    @Test public void orderPlacedOpensOnEntryTouch(){V4Plan p=plan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(p,1500);assertEquals(V4Plan.Status.OPEN,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,101,100,101)),3000,new V4MarketMetadata(.1,1,1,1)));assertEquals(2000,p.openedAt);}
    @Test public void orderPlacedShortOpensOnEntryTouch(){V4Plan p=shortPlan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(p,1500);assertEquals(V4Plan.Status.OPEN,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,101,99,100)),3000,null));}
    @Test public void orderPlacedLongEntryAndStopSameBarFillsThenStops(){V4Plan p=plan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(p,1500);
        assertEquals(V4Plan.Status.CLOSED_SL,V4PlanLifecycle.evaluate(p,List.of(bar(2000,94,101,95,96)),3000,null));assertEquals(100,p.entry,0);assertEquals(95,p.closePrice,0);assertTrue(V4AccountProfile.closedPlanPnl(p)<0);}
    @Test public void orderPlacedShortEntryAndStopSameBarFillsThenStops(){V4Plan p=shortPlan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(p,1500);
        assertEquals(V4Plan.Status.CLOSED_SL,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,106,105,106)),3000,null));assertEquals(105,p.closePrice,0);assertTrue(V4AccountProfile.closedPlanPnl(p)<0);}
    @Test public void orderPlacedEntryTpAndStopAlwaysStopsBothDirections(){V4Plan l=plan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(l,1500);
        assertEquals(V4Plan.Status.CLOSED_SL,V4PlanLifecycle.evaluate(l,List.of(bar(2000,94,111,100,101)),3000,null));V4Plan s=shortPlan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(s,1500);
        assertEquals(V4Plan.Status.CLOSED_SL,V4PlanLifecycle.evaluate(s,List.of(bar(2000,89,106,100,101)),3000,null));}
    @Test public void orderPlacedEntryAndTpWithoutStopClosesTpBothDirections(){V4Plan l=plan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(l,1500);
        assertEquals(V4Plan.Status.CLOSED_TP,V4PlanLifecycle.evaluate(l,List.of(bar(2000,99,111,110,111)),3000,null));V4Plan s=shortPlan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(s,1500);
        assertEquals(V4Plan.Status.CLOSED_TP,V4PlanLifecycle.evaluate(s,List.of(bar(2000,89,101,89,90)),3000,null));}
    @Test public void orderPlacedGenuinePreFillInvalidationRemainsInvalidBothDirections(){V4Plan l=plan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(l,1500);
        assertEquals(V4Plan.Status.INVALIDATED,V4PlanLifecycle.evaluate(l,List.of(bar(2000,94,96,95,96)),3000,null));V4Plan s=shortPlan(V4Plan.Status.LIMIT_ORDER_POSSIBLE);V4PlanLifecycle.markOrderPlaced(s,1500);
        assertEquals(V4Plan.Status.INVALIDATED,V4PlanLifecycle.evaluate(s,List.of(bar(2000,105,106,105,106)),3000,null));}
    @Test public void executableCanBeMarkedTaken(){V4Plan p=plan(V4Plan.Status.EXECUTABLE);V4PlanLifecycle.markTaken(p,2000);assertEquals(V4Plan.Status.OPEN,p.status);}
    @Test public void openTargetCloses(){V4Plan p=plan(V4Plan.Status.OPEN);assertEquals(V4Plan.Status.CLOSED_TP,V4PlanLifecycle.evaluate(p,List.of(bar(2000,100,111,110,111)),3000,null));}
    @Test public void openStopCloses(){V4Plan p=plan(V4Plan.Status.OPEN);assertEquals(V4Plan.Status.CLOSED_SL,V4PlanLifecycle.evaluate(p,List.of(bar(2000,94,100,94,95)),3000,null));}
    @Test public void ambiguousBarIsStopFirst(){V4Plan p=plan(V4Plan.Status.OPEN);assertEquals(V4Plan.Status.CLOSED_SL,V4PlanLifecycle.evaluate(p,List.of(bar(2000,94,111,100,101)),3000,null));}
    @Test public void terminalStateCannotBecomeGreenAgain(){V4Plan p=plan(V4Plan.Status.MISSED_TOO_LATE);assertEquals(V4Plan.Status.MISSED_TOO_LATE,V4PlanLifecycle.evaluate(p,List.of(bar(2000,99,101,99,100)),3000,new V4MarketMetadata(1,1,1,1)));}
}
