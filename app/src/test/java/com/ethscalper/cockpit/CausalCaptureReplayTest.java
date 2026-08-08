package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public final class CausalCaptureReplayTest {
    @Test public void validationAcceptsStrictCausalStreamAndCountsSequenceLoss() {
        List<CausalMarketRecord> values=new ArrayList<>();
        values.add(CausalMarketRecord.session("s",1,0,0,"WS","SESSION_STARTED"));
        values.add(quote(3,1_000,100,101));
        CausalCaptureReplay.Validation result=CausalCaptureReplay.validate(values);
        assertEquals(2,result.records);assertEquals(1,result.sessions);
        assertEquals(1,result.quotes);assertEquals(1,result.missingSequences);
    }

    @Test public void futureOrReorderedObservationIsRejected() {
        List<CausalMarketRecord> values=List.of(
                CausalMarketRecord.session("s",1,2_000,2_000,"WS","SESSION_STARTED"),
                quote(2,1_000,100,101));
        try{CausalCaptureReplay.validate(values);fail("future reorder");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("past"));}
    }

    @Test public void completedFlowCannotBeObservedBeforeBucketEnd() {
        List<CausalMarketRecord> values=List.of(
                CausalMarketRecord.session("s",1,0,0,"WS","SESSION_STARTED"),
                CausalMarketRecord.flow("s",2,1_500,1_500,"ETHUSDT","WS",1_000,2_000,
                        -1,-1,0,0,0,0,false,0,0,0,0,0,0,0,0));
        try{CausalCaptureReplay.validate(values);fail("future flow");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("future flow"));}
    }

    @Test public void entryUsesFirstQuoteStrictlyAfterObservation() {
        List<CausalMarketRecord> values=List.of(
                CausalMarketRecord.session("s",1,900,900,"WS","START"),
                quote(2,1_000,100,101),quote(3,1_001,102,103));
        assertEquals(3,CausalCaptureReplay.firstQuoteAfter(values,"ETHUSDT",1_000).sequence);
        assertNull(CausalCaptureReplay.firstQuoteAfter(values,"SOLUSDT",0));
    }

    @Test public void entryNeverCrossesGapOrSessionBoundary(){
        List<CausalMarketRecord> gap=List.of(
                CausalMarketRecord.session("s",1,900,900,"WS","START"),
                quote(2,1_000,100,101),
                CausalMarketRecord.gap("s",3,1_100,1_100,"*","WS",1_050,1_100,"DROP"),
                quote(4,1_200,102,103));
        assertNull(CausalCaptureReplay.firstQuoteAfter(gap,"ETHUSDT",1_000));
        List<CausalMarketRecord> session=List.of(
                CausalMarketRecord.session("s",1,900,900,"WS","START"),quote(2,1_000,100,101),
                CausalMarketRecord.session("next",1,1_100,1_100,"WS","START"),
                CausalMarketRecord.quote("next",2,1_200,1_200,"ETHUSDT","WS",1_200,1_200,2,
                        102,1,103,1));
        assertNull(CausalCaptureReplay.firstQuoteAfter(session,"ETHUSDT",1_000));
    }

    @Test public void terminalUsesExecutableSideAndPlannedFill() {
        CausalMarketRecord longQuote=quote(1,1_000,120,121);
        CausalCaptureReplay.Terminal longTp=CausalCaptureReplay.terminal("LONG",110,90,longQuote);
        assertEquals("TP_TOUCHED",longTp.status);assertEquals(120,longTp.touchQuote,0);
        assertEquals(110,longTp.fillPrice,0);
        CausalMarketRecord shortQuote=quote(2,2_000,79,80);
        CausalCaptureReplay.Terminal shortTp=CausalCaptureReplay.terminal("SHORT",90,110,shortQuote);
        assertEquals("TP_TOUCHED",shortTp.status);assertEquals(80,shortTp.touchQuote,0);
        assertEquals(90,shortTp.fillPrice,0);
    }

    @Test public void conservativeSlWinsImpossibleAmbiguousFixture() {
        CausalCaptureReplay.Terminal terminal=CausalCaptureReplay.terminal("LONG",90,110,
                quote(1,1_000,100,101));assertEquals("SL_TOUCHED",terminal.status);
        assertEquals(110,terminal.fillPrice,0);
    }

    @Test public void continuousFlowRequiresEveryBucketAndNoExplicitGap() {
        List<CausalMarketRecord> complete=new ArrayList<>();complete.add(emptyFlow(1,0));
        complete.add(emptyFlow(2,1_000));assertTrue(CausalCaptureReplay.continuousFlow(
                complete,"ETHUSDT",0,2_000));complete.add(CausalMarketRecord.gap("s",3,
                2_000,2_000,"ETHUSDT","WS",500,1_500,"DROP"));
        assertFalse(CausalCaptureReplay.continuousFlow(complete,"ETHUSDT",0,2_000));
    }

    @Test public void storeReplayStreamsCompleteCaptureAndRejectsSequenceHole()throws Exception {
        File directory=Files.createTempDirectory("nmc-replay-").toFile();
        CausalCaptureStore store=new CausalCaptureStore(directory,"replay",2,4096);
        store.appendBatch(List.of(CausalMarketRecord.session("s",1,0,0,"WS","START"),
                quote(2,1_000,100,101)));try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            List<Long> sequences=new ArrayList<>();CausalCaptureReplay.Validation validation=
                    CausalCaptureReplay.replay(snapshot.files,r->sequences.add(r.sequence));
            assertTrue(validation.complete);assertEquals(List.of(1L,2L),sequences);}
        CausalCaptureStore holeStore=new CausalCaptureStore(Files.createTempDirectory(
                "nmc-replay-hole-").toFile(),"hole",2,4096);
        holeStore.appendBatch(List.of(CausalMarketRecord.session("h",1,0,0,"WS","START"),
                CausalMarketRecord.quote("h",3,1_000,1_000,"ETHUSDT","WS",1_000,
                        1_000,3,100,1,101,1)));
        try(CausalCaptureStore.Snapshot snapshot=holeStore.checkpoint()){
            try{CausalCaptureReplay.replay(snapshot.files,null);fail("sequence hole");}
            catch(Exception expected){assertTrue(expected.getMessage().contains("capture"));}}
    }

    private static CausalMarketRecord quote(long sequence,long at,double bid,double ask){return
            CausalMarketRecord.quote("s",sequence,at,at,"ETHUSDT","WS",at,at,sequence,
                    bid,1,ask,1);}
    private static CausalMarketRecord emptyFlow(long sequence,long at){return CausalMarketRecord.flow(
            "s",sequence,at+1_000,at+1_000,"ETHUSDT","WS",at,at+1_000,-1,-1,
            0,0,0,0,false,0,0,0,0,0,0,0,0);}
}
