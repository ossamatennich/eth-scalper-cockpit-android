package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23448LegacySuppressionTest {
 @Test public void comparatorOpensWithoutPublicRuntime(){LegacyPublicComparator c=new LegacyPublicComparator();assertTrue(c.open(state("ETHUSDT","LONG"),1));assertEquals(1L,c.snapshot().get("opened"));}
 @Test public void secondComparatorSameSymbolIsSkipped(){LegacyPublicComparator c=new LegacyPublicComparator();c.open(state("ETHUSDT","LONG"),1);assertFalse(c.open(state("ETHUSDT","SHORT"),2));assertEquals(1L,c.snapshot().get("skippedActive"));}
 @Test public void longComparatorUsesBid(){LegacyPublicComparator c=new LegacyPublicComparator();ActivePlanState p=state("ETHUSDT","LONG");c.open(p,1);assertNotNull(c.observe("ETHUSDT",2,p.takeProfit,p.takeProfit+1,true));}
 @Test public void staleComparatorDoesNotTerminate(){LegacyPublicComparator c=new LegacyPublicComparator();ActivePlanState p=state("ETHUSDT","LONG");c.open(p,1);assertNull(c.observe("ETHUSDT",2,p.takeProfit,p.takeProfit+1,false));}
 private static ActivePlanState state(String symbol,String side){MarketProfile p="SOLUSDT".equals(symbol)?MarketProfile.sol():MarketProfile.eth();double e="SOLUSDT".equals(symbol)?70:1900;double tp="LONG".equals(side)?e+2:e-2,sl="LONG".equals(side)?e-1:e+1;return ActivePlanState.builder().formatVersion(2).market(p).status("ACTIVE").side(side).family("LEGACY").reasonCode("X").reasonText("x").score(90).quantity(1).prices(e,tp,sl).risk(2,1).times(1,1,1).notification("sig",1).lastMarket(e,e-.01,e+.01,.35).lastP01ConfirmedAt(0).movement("",false,0,0,0).replayRisk("","").p01(0,0,0,0,0).sizingDiagnostic("").unitRisk(p.resultRoundTripCostReference,0,14.55,2).build();}
}
