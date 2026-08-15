package com.ethscalper.cockpit;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class V4EnginePolicyTest {
    @Test public void coreBarriersExact(){V4Engine e=new V4Engine(null);V4FeatureEngine.Candidate c=new V4FeatureEngine.Candidate(V4Plan.Source.CORE,"ETH",V4Plan.Side.LONG,10,0,0);V4Plan p=e.create(c,100,1,5000,.0225,0,null);assertEquals(130,p.tp,0);assertEquals(96,p.sl,0);}
    @Test public void fallbackBarriersExact()throws Exception{V4Engine e=new V4Engine(V4ExtraTreesModel.loadBytes(Files.readAllBytes(Path.of("src/main/assets/v4_fallback_model.json"))));V4FeatureEngine.Candidate c=new V4FeatureEngine.Candidate(V4Plan.Source.FALLBACK,"SOL",V4Plan.Side.SHORT,5,.1,.2);V4Plan p=e.create(c,100,1,5000,.0075,0,null);assertEquals(90,p.tp,0);assertEquals(105,p.sl,0);}
    @Test public void noThirdCoreContinuation(){V4Plan first=new V4Plan("1",null,V4Plan.Source.CORE,"BTC",V4Plan.Side.LONG,1,100,110,95,5,1,1,2,V4Plan.Status.CLOSED_OTHER,"",5000,.02,0,null);first.closeReason="Clôture avant reset";assertTrue(V4ContinuationPolicy.mayCreateSecondSegment(first));
        V4Plan second=new V4Plan("2","1",V4Plan.Source.CORE,"BTC",V4Plan.Side.LONG,1,100,110,95,5,3,3,4,V4Plan.Status.CLOSED_OTHER,"",5000,.02,0,null);second.closeReason="Clôture avant reset";assertFalse(V4ContinuationPolicy.mayCreateSecondSegment(second));}
}
