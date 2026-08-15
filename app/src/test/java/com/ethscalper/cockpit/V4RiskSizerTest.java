package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class V4RiskSizerTest {
    @Test public void desiredRisksFrozen(){assertEquals(.0225,V4RiskSizer.CORE_RISK,0);assertEquals(.0075,V4RiskSizer.FALLBACK_RISK,0);assertEquals(.024,V4RiskSizer.COMBINED_CAP,0);}
    @Test public void combinedRiskScalesProportionally(){double[] a=V4RiskSizer.allocate(.0225,.0075);assertEquals(.018,a[0],1e-12);assertEquals(.006,a[1],1e-12);}
    @Test public void quantityRoundsDown(){V4MarketMetadata m=new V4MarketMetadata(.01,.1,.1,5);V4RiskSizer.Result r=V4RiskSizer.size("ETH",5000,2000,1980,.0225,m);assertTrue(r.available);assertEquals(Math.floor((r.notionalUsd/2000)*10)/10,r.quantity,1e-12);assertTrue(r.quantity*2000<=r.notionalUsd+1e-8);}
    @Test public void fixedLeverageHeadroomCapsNotional(){V4RiskSizer.Result r=V4RiskSizer.size("BTC",5000,100,99.99,.5,new V4MarketMetadata(.01,.001,.001,5));assertEquals(8.0,r.notionalMultiple,1e-12);}
    @Test public void minimumNotionalNeverInvented(){assertFalse(V4RiskSizer.size("ADA",5000,.1,.09,.001,new V4MarketMetadata(.0001,1,1,1_000_000)).available);}
}
