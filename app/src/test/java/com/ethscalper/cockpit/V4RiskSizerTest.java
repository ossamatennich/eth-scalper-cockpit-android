package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class V4RiskSizerTest {
    @Test public void desiredRisksFrozen(){assertEquals(.0225,V4RiskSizer.CORE_RISK,0);assertEquals(.0075,V4RiskSizer.FALLBACK_RISK,0);assertEquals(.024,V4RiskSizer.COMBINED_CAP,0);}
    @Test public void combinedRiskScalesProportionally(){double[] a=V4RiskSizer.allocate(.0225,.0075);assertEquals(.018,a[0],1e-12);assertEquals(.006,a[1],1e-12);}
    @Test public void quantityRoundsDown(){V4MarketMetadata m=new V4MarketMetadata(.01,.1,.1,5);V4RiskSizer.Result r=V4RiskSizer.size("ETH",5000,2000,1980,.0225,m);assertTrue(r.available);assertEquals(Math.floor((r.notionalUsd/2000)*10)/10,r.quantity,1e-12);assertTrue(r.quantity*2000<=r.notionalUsd+1e-8);}
    @Test public void fixedLeverageHeadroomCapsNotional(){V4RiskSizer.Result r=V4RiskSizer.size("BTC",5000,100,99.99,.5,new V4MarketMetadata(.01,.001,.001,5));assertEquals(8.0,r.notionalMultiple,1e-12);}
    @Test public void minimumNotionalNeverInvented(){assertFalse(V4RiskSizer.size("ADA",5000,.1,.09,.001,new V4MarketMetadata(.0001,1,1,1_000_000)).available);}
    @Test public void committedQuantityIsImmutableAndNewUsesRemainingRisk(){V4Plan open=plan(V4Plan.Status.OPEN,1);double original=open.quantity();
        assertThrows(IllegalStateException.class,()->open.restoreUncommittedQuantity(.5));assertEquals(original,open.quantity(),0);
        double used=V4RiskSizer.theoreticalRiskFraction(open,5000),remaining=V4RiskSizer.remainingRisk(5000,java.util.List.of(open));assertEquals(V4RiskSizer.COMBINED_CAP-used,remaining,1e-12);
        V4RiskSizer.Result next=V4RiskSizer.size("ETH",5000,100,95,remaining,new V4MarketMetadata(.01,.001,.001,1));assertTrue(next.available);
        V4Plan candidate=plan(V4Plan.Status.WAITING,next.quantity);assertTrue(used+V4RiskSizer.theoreticalRiskFraction(candidate,5000)<=V4RiskSizer.COMBINED_CAP+1e-12);}
    @Test public void orderPlacedQuantityImmutableMetadataCannotZeroIt(){V4Plan order=plan(V4Plan.Status.ORDER_PLACED,2);assertThrows(IllegalStateException.class,()->order.restoreUncommittedQuantity(0));assertEquals(2,order.quantity(),0);}
    @Test public void noRemainingBudgetAndTwoNewPlansMayScale(){V4Plan oversized=plan(V4Plan.Status.OPEN,100);assertEquals(0,V4RiskSizer.remainingRisk(5000,java.util.List.of(oversized)),0);
        double[] allocation=V4RiskSizer.allocate(V4RiskSizer.CORE_RISK,V4RiskSizer.FALLBACK_RISK);assertEquals(.024,allocation[0]+allocation[1],1e-12);}
    private static V4Plan plan(V4Plan.Status status,double qty){return new V4Plan("p"+status+qty,null,V4Plan.Source.CORE,"ETH",V4Plan.Side.LONG,qty,100,110,95,5,1,1,1000,status,"",5000,.02,0,null);}
}
