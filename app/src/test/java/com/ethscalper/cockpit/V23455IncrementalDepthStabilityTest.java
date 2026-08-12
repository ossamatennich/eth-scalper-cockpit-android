package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/** Live-like deterministic tests for the 5.5 bootstrap/resynchronization state machine. */
public final class V23455IncrementalDepthStabilityTest {
    @Test public void healthyTenMinutesUsesOneBootstrapAndNeverResyncs(){IncrementalDepthContinuity c=
            new IncrementalDepthContinuity();c.socketConnected(true);assertTrue(c.tryBeginBootstrap("ETHUSDT",1_000));
        long previous=99;for(int i=0;i<20;i++){long first=100+i*2,last=first+1;
            assertFalse(c.observe("ETHUSDT",first,last,previous,1_010+i*250).gapTransition);previous=last;}
        assertTrue(c.bootstrapSuccess("ETHUSDT",100,2_000).restored);
        for(int i=20;i<2_400;i++){long first=100+i*2,last=first+1;
            assertTrue(c.observe("ETHUSDT",first,last,previous,2_010+(i-20)*250).applied);previous=last;}
        Map<?,?> state=state(c,"ETHUSDT",600_000);assertEquals(1L,state.get("bootstrapAttempts"));
        assertEquals(1L,state.get("bootstrapSuccesses"));assertEquals(0L,state.get("continuityBreaks"));
        assertEquals(0L,state.get("puMismatches"));assertEquals("RECONSTRUCTIBLE",state.get("phase"));}

    @Test public void snapshotAheadOfBufferWaitsAndAnchorsOnLaterCoveringEvent(){IncrementalDepthContinuity c=
            new IncrementalDepthContinuity();assertTrue(c.tryBeginBootstrap("ETHUSDT",1_000));
        c.observe("ETHUSDT",10,11,9,1_010);c.observe("ETHUSDT",12,13,11,1_020);
        IncrementalDepthContinuity.Result bootstrap=c.bootstrapSuccess("ETHUSDT",100,1_030);
        assertFalse(bootstrap.gapTransition);assertFalse(bootstrap.needsBootstrap);
        assertEquals("SYNCING",state(c,"ETHUSDT",1_030).get("phase"));
        assertTrue(c.observe("ETHUSDT",99,101,98,1_040).restored);
        assertEquals("RECONSTRUCTIBLE",state(c,"ETHUSDT",1_040).get("phase"));}

    @Test public void realPuBreakProducesOneInvalidationAndOneControlledResync(){IncrementalDepthContinuity c=
            anchored("ETHUSDT",100,1_000);assertTrue(c.observe("ETHUSDT",102,103,101,1_100).applied);
        IncrementalDepthContinuity.Result broken=c.observe("ETHUSDT",105,106,104,1_200);
        assertTrue(broken.gapTransition);assertTrue(broken.needsBootstrap);
        assertTrue(c.tryBeginBootstrap("ETHUSDT",1_200));
        for(int i=0;i<100;i++){IncrementalDepthContinuity.Result waiting=c.observe("ETHUSDT",107+i*2,
                    108+i*2,106+i*2,1_201+i);assertFalse(waiting.gapTransition);
            assertFalse(waiting.needsBootstrap);}
        c.bootstrapSuccess("ETHUSDT",200,1_400);Map<?,?> state=state(c,"ETHUSDT",1_400);
        assertEquals(1L,state.get("continuityBreaks"));assertEquals(1L,state.get("puMismatches"));
        assertEquals(2L,state.get("bootstrapAttempts"));assertEquals(2L,state.get("bootstrapSuccesses"));}

    @Test public void hundredDiffsWaitingCannotCreateRestOrGapStorm(){IncrementalDepthContinuity c=
            new IncrementalDepthContinuity();assertTrue(c.tryBeginBootstrap("SOLUSDT",10_000));
        for(int i=0;i<100;i++){IncrementalDepthContinuity.Result result=c.observe("SOLUSDT",i*2,
                    i*2+1,i*2-1,10_001+i);assertFalse(result.gapTransition);
            assertFalse(result.needsBootstrap);assertFalse(c.tryBeginBootstrap("SOLUSDT",10_001+i));}
        Map<?,?> state=state(c,"SOLUSDT",10_200);assertEquals(1L,state.get("bootstrapAttempts"));
        assertEquals(0L,state.get("continuityBreaks"));assertEquals(100,state.get("bufferedDiffs"));}

    @Test public void reconnectAndDropInvalidateWithoutCrossSymbolContamination(){IncrementalDepthContinuity c=
            new IncrementalDepthContinuity();c.socketConnected(true);for(String symbol:new String[]{"ETHUSDT",
                    "SOLUSDT","BTCUSDT"}){assertTrue(c.tryBeginBootstrap(symbol,1_000));
                c.bootstrapSuccess(symbol,100,1_010);c.observe(symbol,99,101,98,1_020);}
        IncrementalDepthContinuity.Result dropped=c.dropped("ETHUSDT",1_030);assertTrue(dropped.gapTransition);
        assertEquals("INVALID_WAITING_RESYNC",state(c,"ETHUSDT",1_030).get("phase"));
        assertEquals("RECONSTRUCTIBLE",state(c,"SOLUSDT",1_030).get("phase"));
        c.requireRebootstrapAll();for(String symbol:new String[]{"ETHUSDT","SOLUSDT","BTCUSDT"})
            assertEquals("WAITING_BOOTSTRAP",state(c,symbol,1_040).get("phase"));}

    @Test public void prolongedUnanchoredStorageGrowthIsRawOnlyNotGapOrBootstrapProportional(){
        IncrementalDepthContinuity c=new IncrementalDepthContinuity();assertTrue(c.tryBeginBootstrap("BTCUSDT",1));
        for(int i=0;i<2_314;i++)c.observe("BTCUSDT",i+1,i+1,i,2+i);
        Map<?,?> state=state(c,"BTCUSDT",3_000);assertEquals(1L,state.get("bootstrapAttempts"));
        assertEquals(0L,state.get("continuityBreaks"));assertEquals(2_314L,state.get("incrementalDepthMessages"));
        assertEquals("BUFFERING",state.get("phase"));}

    @Test public void endpointUsesOfficialDefaultCadenceAndFiveHundredLevelSnapshot(){String url=
            MarketFeedEndpointPool.incrementalDepthCombinedStreamUrl(0,MarketRegistry.production());
        assertFalse(url.contains("@depth@100ms"));for(String symbol:new String[]{"ethusdt","solusdt","btcusdt"})
            assertTrue(url.contains(symbol+"@depth"));assertTrue(MarketFeedEndpointPool.depthSnapshotEndpoints(
                    "ETHUSDT",500).get(0).url.endsWith("limit=500"));}

    private static IncrementalDepthContinuity anchored(String symbol,long anchor,long at){
        IncrementalDepthContinuity c=new IncrementalDepthContinuity();c.socketConnected(true);
        assertTrue(c.tryBeginBootstrap(symbol,at));c.bootstrapSuccess(symbol,anchor,at+1);
        assertTrue(c.observe(symbol,anchor-1,anchor+1,anchor-2,at+2).applied);return c;}
    @SuppressWarnings("unchecked") private static Map<?,?> state(IncrementalDepthContinuity c,String symbol,long now){
        return (Map<?,?>)((Map<String,Object>)c.snapshot(now,true,false).get(
                "incrementalDepthReadyBySymbol")).get(symbol);}
}
