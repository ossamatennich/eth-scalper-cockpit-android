package com.ethscalper.cockpit;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Collector-only contracts for raw USD-M Futures incremental depth V4. */
public final class V23454IncrementalDepthCaptureTest {
    @Test public void thirdSocketContainsExactlyThreeDiffStreamsAndNothingElse(){String url=
            MarketFeedEndpointPool.incrementalDepthCombinedStreamUrl(0,MarketRegistry.production());
        assertTrue(url.startsWith("wss://fstream.binance.com/public/stream?streams="));
        for(String symbol:new String[]{"ethusdt","solusdt","btcusdt"})assertEquals(1,
                count(url,symbol+"@depth"));for(String forbidden:new String[]{"@aggTrade",
                "@kline_1m","@forceOrder","@bookTicker","@depth20"})assertFalse(url, url.contains(forbidden));
        assertEquals(3,count(url,"@depth"));assertFalse(url.contains("@depth@100ms"));}

    @Test public void existingSocketsAreUnchangedAndContainNoDiffDepth(){String publicUrl=
            MarketFeedEndpointPool.publicCombinedStreamUrl(0,MarketRegistry.production());String marketUrl=
            MarketFeedEndpointPool.marketCombinedStreamUrl(0,MarketRegistry.production());
        for(String symbol:new String[]{"ethusdt","solusdt","btcusdt"}){assertEquals(1,count(publicUrl,
                symbol+"@bookTicker"));assertEquals(1,count(publicUrl,symbol+"@depth20@100ms"));
            assertEquals(1,count(marketUrl,symbol+"@aggTrade"));assertEquals(1,count(marketUrl,
                    symbol+"@kline_1m"));assertEquals(1,count(marketUrl,symbol+"@forceOrder"));}
        assertFalse(publicUrl.contains("@depth@100ms"));assertFalse(marketUrl.contains("@depth@100ms"));}

    @Test public void routerAcceptsDiffOnlyOnThirdSocketForAllSymbols()throws Exception{
        BinanceCombinedStreamRouter router=new BinanceCombinedStreamRouter();for(String symbol:new String[]{
                "ETHUSDT","SOLUSDT","BTCUSDT"}){BinanceCombinedStreamRouter.Event event=router.parse(
                fixture(symbol,100,101,99,"1.0","0"));assertNotNull(event);assertEquals(symbol,event.symbol);
            assertEquals(BinanceCombinedStreamRouter.Type.DEPTH_DIFF,event.type);assertTrue(
                    BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType
                            .INCREMENTAL_DEPTH_WS,event.type));assertFalse(BinanceCombinedStreamRouter.accepts(
                    BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,event.type));assertFalse(
                    BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.MARKET_WS,
                            event.type));}}

    @Test public void thirdSocketRejectsEveryExistingStreamType(){for(BinanceCombinedStreamRouter.Type type:
            new BinanceCombinedStreamRouter.Type[]{BinanceCombinedStreamRouter.Type.BOOK_TICKER,
                    BinanceCombinedStreamRouter.Type.DEPTH20,BinanceCombinedStreamRouter.Type.AGG_TRADE,
                    BinanceCombinedStreamRouter.Type.KLINE_1M,
                    BinanceCombinedStreamRouter.Type.LIQUIDATION_SNAPSHOT})assertFalse(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType
                    .INCREMENTAL_DEPTH_WS,type));}

    @Test public void strictParserPreservesZeroRemovalAndRejectsMalformedInputs()throws Exception{
        BinanceDepthDiff.ParseResult valid=parse(fixture("ETHUSDT",100,101,99,"1900.1","0"),"ETHUSDT");
        assertTrue(valid.accepted());assertEquals(0,valid.value.bids[0][1],0);assertEquals(99,
                valid.value.previousFinalUpdateId);for(String bad:new String[]{"NaN","Infinity","-1"})
            assertFalse(parse(fixture("ETHUSDT",100,101,99,bad,"1"),"ETHUSDT").accepted());
        assertFalse(parse(fixture("ETHUSDT",102,101,99,"1","1"),"ETHUSDT").accepted());
        assertFalse(parse(fixture("BNBUSDT",100,101,99,"1","1"),"BNBUSDT").accepted());
        assertNull(new BinanceCombinedStreamRouter().parse(fixture("ETHUSDT",100,101,99,"1","1")
                .replace("\"s\":\"ETHUSDT\"","\"s\":\"SOLUSDT\"")));}

    @Test public void v4RecordsRoundTripAllRawFieldsAndCrc()throws Exception{double[][] bids={{1900,0},
                {1899.5,2}},asks={{1901,3}};MicrostructureMarketRecord bootstrap=bootstrap("s",2,
                1_100,100,bids,asks);MicrostructureMarketRecord diff=MicrostructureMarketRecord.depthDiff(
                "s",3,1_200,3,"ETHUSDT","BINANCE_FUTURES_INCREMENTAL_DEPTH_WS",1_190,1_191,
                100,101,99,bids,asks);File dir=Files.createTempDirectory("depth-v4").toFile();
        CausalCaptureStore store=new CausalCaptureStore(dir,"v4",4,128_000);store.appendBatch(List.of(
                MicrostructureMarketRecord.session("s",1,1_000,1,"WS","START"),bootstrap,diff));
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){CausalCaptureReplay.Validation replay=
                CausalCaptureReplay.replay(snapshot.files,null);assertTrue(replay.complete);assertEquals(1,
                replay.depthDiffs);assertEquals(1,replay.depthBootstraps);List<CausalMarketRecord> restored=
                CausalCaptureStore.read(snapshot.files,true).records;assertEquals(4,((MicrostructureMarketRecord)
                restored.get(2)).recordFormatVersion);assertEquals(0,((MicrostructureMarketRecord)
                restored.get(2)).bids[0][1],0);}}

    @Test public void v1V2V3V4BlocksRemainReadableTogether()throws Exception{File dir=
            Files.createTempDirectory("mixed-v1234").toFile();CausalCaptureStore store=new CausalCaptureStore(
            dir,"mixed",8,128_000);store.append(CausalMarketRecord.session("v1",1,1,1,"V1","S"));
        store.append(MicrostructureMarketRecord.sessionForVersion(2,"v2",1,2,2,"V2","S"));
        store.append(MicrostructureMarketRecord.sessionForVersion(3,"v3",1,3,3,"V3","S"));
        store.appendBatch(List.of(MicrostructureMarketRecord.session("v4",1,4,4,"V4","S"),
                bootstrap("v4",2,50,10,levels(2,100,false),levels(2,101,true))));
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){List<CausalMarketRecord> values=
                CausalCaptureStore.read(snapshot.files,true).records;assertEquals(5,values.size());
            assertEquals(1,values.get(0).toMap().get("formatVersion"));assertEquals(2,
                    values.get(1).toMap().get("formatVersion"));assertEquals(3,
                    values.get(2).toMap().get("formatVersion"));assertEquals(4,
                    values.get(3).toMap().get("formatVersion"));}}

    @Test public void continuityAnchorsChainsBreaksAndRecoversIndependently(){IncrementalDepthContinuity c=
            new IncrementalDepthContinuity();c.socketConnected(true);c.bootstrapAttempt("ETHUSDT");
        c.bootstrapSuccess("ETHUSDT",100,1_000);assertTrue(c.observe("ETHUSDT",99,101,98,1_100).applied);
        assertTrue(c.observe("ETHUSDT",102,103,101,1_200).applied);assertTrue(c.observe("ETHUSDT",
                105,106,104,1_300).needsBootstrap);assertFalse(Boolean.TRUE.equals(c.snapshot(1_300,true,
                false).get("usableForIncrementalDepthResearch")));c.bootstrapSuccess("ETHUSDT",200,1_400);
        assertTrue(c.observe("ETHUSDT",199,201,198,1_500).applied);Map<String,Object> root=c.snapshot(
                1_500,true,false);@SuppressWarnings("unchecked") Map<String,Object> symbols=(Map<String,Object>)
                root.get("incrementalDepthReadyBySymbol");assertFalse(Boolean.TRUE.equals(((Map<?,?>)
                symbols.get("SOLUSDT")).get("ready")));}

    @Test public void dropInvalidatesReconstructionAndBecomesExplicitDropSummary()throws Exception{List<CausalMarketRecord>
            records=new ArrayList<>();MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(record->{
                if(record.kind==CausalMarketRecord.Kind.DEPTH_DIFF)return false;records.add(record);return true;});
        capture.startSession("s","WS",1_000,1);BinanceDepthDiff diff=parse(fixture("ETHUSDT",100,101,99,
                "1900","0"),"ETHUSDT").value;assertFalse(capture.observeDepthDiff(diff,"WS",1_100,2));
        capture.health(1_200,3,"WS","WAKE");assertTrue(records.stream().anyMatch(record->record.kind==
                CausalMarketRecord.Kind.DROP_SUMMARY&&((MicrostructureMarketRecord)record).droppedByKind
                .containsKey("DEPTH_DIFF")));IncrementalDepthContinuity c=new IncrementalDepthContinuity();
        c.bootstrapSuccess("ETHUSDT",99,1_000);c.observe("ETHUSDT",99,101,98,1_100);c.dropped(
                "ETHUSDT",1_200);assertFalse(Boolean.TRUE.equals(c.snapshot(1_200,true,false).get(
                "usableForIncrementalDepthResearch")));}

    @Test public void incrementalHealthIsIndependentOfValidatedV3Health(){IncrementalDepthContinuity c=
            new IncrementalDepthContinuity();assertFalse(Boolean.TRUE.equals(c.snapshot(10_000,true,false)
                    .get("usableForIncrementalDepthResearch")));c.socketConnected(true);for(String symbol:
            new String[]{"ETHUSDT","SOLUSDT","BTCUSDT"}){c.bootstrapSuccess(symbol,100,9_000);
            c.observe(symbol,99,101,98,9_500);}assertTrue(Boolean.TRUE.equals(c.snapshot(10_000,true,false)
                    .get("usableForIncrementalDepthResearch")));assertFalse(Boolean.TRUE.equals(c.snapshot(
                    10_000,false,false).get("usableForIncrementalDepthResearch")));assertFalse(
                    Boolean.TRUE.equals(c.snapshot(10_000,true,true).get("usableForIncrementalDepthResearch")));}

    @Test public void nominalRawDiffBurstDrainsWithoutQueueRejectionOrCorruption()throws Exception{
        File dir=Files.createTempDirectory("depth-burst").toFile();CausalCaptureQueue queue=
                new CausalCaptureQueue(1024);CausalCaptureStore store=new CausalCaptureStore(dir,"burst",8,
                512_000);CausalCaptureWriter writer=new CausalCaptureWriter(queue,store);writer.start();
        MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(queue);capture.startSession("s","WS",
                1_000,1);BinanceDepthDiff diff=parse(fixture("ETHUSDT",100,101,99,"1900","0"),
                "ETHUSDT").value;for(int i=0;i<300;i++)assertTrue(capture.observeDepthDiff(diff,"WS",
                1_100+i,2+i));assertTrue(writer.flush(5_000));CausalCaptureWriter.Stats stats=writer.stats();
        assertEquals(0,stats.rejected);assertEquals(301,stats.written);assertEquals(0,stats.failed);
        writer.shutdown(2_000);try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            assertEquals(300,CausalCaptureReplay.replay(snapshot.files,null).depthDiffs);}}

    @Test public void restBootstrapIsPublicUnsignedAndSafetyFrozen(){MarketFeedEndpointPool.RestEndpoint endpoint=
            MarketFeedEndpointPool.depthSnapshotEndpoints("ETHUSDT",500).get(0);assertEquals(
            "https://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=500",endpoint.url);
        assertFalse(endpoint.spotFallback);assertEquals("NMC_SCALP_CV_CORE_V1",CvCorePolicy.ENGINE_ID);
        assertEquals(4,CvCorePolicy.DUAL_EXHAUSTION_SHORT.targetMultiple,0);assertEquals(14.55,
                CvCorePolicy.DUAL_EXHAUSTION_SHORT.riskBudgetUsdt,0);assertEquals(7.275,
                CvCorePolicy.P02_BALANCED_SHORT.riskBudgetUsdt,0);assertEquals(1.43,
                CvCorePolicy.RESULT_COST_PER_UNIT,0);assertFalse(SignalSafetyPolicies.realTradingAllowed());}

    private static BinanceDepthDiff.ParseResult parse(String fixture,String symbol)throws Exception{return
            BinanceDepthDiff.parse(symbol,new JSONObject(fixture).getJSONObject("data"));}
    private static String fixture(String symbol,long first,long last,long previous,String price,String qty){return
            "{\"stream\":\""+symbol.toLowerCase()+"@depth\",\"data\":{\"e\":\"depthUpdate\","
            +"\"E\":1000,\"T\":999,\"s\":\""+symbol+"\",\"U\":"+first+",\"u\":"+last+
            ",\"pu\":"+previous+",\"b\":[[\""+price+"\",\""+qty+"\"]],\"a\":[[\"1901\",\"2\"]]}}";}
    private static MicrostructureMarketRecord bootstrap(String session,long sequence,long at,long update,
            double[][] bids,double[][] asks){return MicrostructureMarketRecord.depthBootstrap(session,
            sequence,at-10,at,sequence,"ETHUSDT","BINANCE_FUTURES_PUBLIC_REST",update,1000,bids,asks);}
    private static double[][] levels(int count,double start,boolean ascending){double[][] out=new double[count][2];
        for(int i=0;i<count;i++){out[i][0]=start+(ascending?i:-i)*.01;out[i][1]=1;}return out;}
    private static int count(String value,String token){int out=0,from=0;while((from=value.indexOf(token,from))>=0)
        {out++;from+=token.length();}return out;}
}
