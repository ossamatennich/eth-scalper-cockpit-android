package com.ethscalper.cockpit;

import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class V4FeatureAndPlanTest {
    @Test public void dailyBarRejectsImpossibleData(){assertThrows(IllegalArgumentException.class,()->new V4DailyBar(0,1,1,.5,2,1,1,.5));assertThrows(IllegalArgumentException.class,()->new V4DailyBar(0,1,2,.5,1,-1,1,.5));assertThrows(IllegalArgumentException.class,()->new V4DailyBar(0,1,2,.5,1,1,1,2));}
    @Test public void broadPanelResidualSelectsOnlyCoreCandidate(){Map<String,List<V4DailyBar>> panel=new LinkedHashMap<>();int ai=0;for(String a:V4Universe.ASSETS){ArrayList<V4DailyBar>b=new ArrayList<>();double price=10+ai++;
        for(int d=0;d<100;d++){double drift=(a.equals("BTC")&&d>=86)?.03:.001;double next=price*(1+drift);b.add(new V4DailyBar(d*86_400_000L,price,next*1.01,price*.99,next,100,1_000_000+d,510_000));price=next;}panel.put(a,b);}
        Map<String,V4FeatureEngine.Snapshot>s=V4FeatureEngine.compute(panel);assertEquals(53,s.size());V4FeatureEngine.Candidate c=V4FeatureEngine.selectCore(s);assertNotNull(c);assertEquals("BTC",c.asset);assertEquals(V4Plan.Side.LONG,c.side);}
    @Test public void planRoundTripKeepsSingleSourceOfTruth(){V4Plan p=new V4Plan("id","parent",V4Plan.Source.FALLBACK,"XRP",V4Plan.Side.SHORT,12.3,.5,.4,.55,.05,100,100,200,V4Plan.Status.ORDER_PLACED,"ordre",5000,.006,50,"hash");p.entryOrderMarkedAt=120;p.userFollowState="ORDER_PLACED";
        V4Plan r=V4Plan.fromJson(p.toJson());assertEquals(p.planId,r.planId);assertEquals(p.parentPlanId,r.parentPlanId);assertEquals(p.quantity(),r.quantity(),0);assertEquals(p.modelManifestHash,r.modelManifestHash);assertEquals(V4Plan.Status.ORDER_PLACED,r.status);}
    @Test public void explicitUtcCutoffNeverMixesStaleOrLateRows(){Map<String,List<V4DailyBar>> panel=new LinkedHashMap<>();ArrayList<V4DailyBar> eth=new ArrayList<>(),btc=new ArrayList<>(),sol=new ArrayList<>();
        for(int d=0;d<100;d++){eth.add(bar(d));if(d<99)btc.add(bar(d));if(d>=90)sol.add(bar(d));}panel.put("ETH",eth);panel.put("BTC",btc);panel.put("SOL",sol);
        long cutoff=99*86_400_000L;Map<String,List<V4DailyBar>> aligned=V4FeatureEngine.alignAt(panel,cutoff);assertTrue(aligned.containsKey("ETH"));assertFalse(aligned.containsKey("BTC"));assertTrue(aligned.containsKey("SOL"));
        Map<String,V4FeatureEngine.Snapshot> snapshots=V4FeatureEngine.computeAt(panel,cutoff);assertTrue(snapshots.containsKey("ETH"));assertFalse(snapshots.containsKey("BTC"));assertFalse(snapshots.containsKey("SOL"));assertEquals(cutoff,V4FeatureEngine.sharedCutoff(snapshots));}
    private static V4DailyBar bar(int d){double p=100+d;return new V4DailyBar(d*86_400_000L,p,p+2,p-1,p+1,10,1000,500);}
    @Test public void frenchStatusLabelsComplete(){for(V4Plan.Status s:V4Plan.Status.values())assertFalse(V4Plan.french(s).isEmpty());}
}
