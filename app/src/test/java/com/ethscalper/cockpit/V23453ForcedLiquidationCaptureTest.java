package com.ethscalper.cockpit;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Deterministic contract tests for collector-only forced-liquidation capture V3. */
public final class V23453ForcedLiquidationCaptureTest {
    @Test public void marketHasEachSymbolForceOrderExactlyOnceAndPublicHasNone(){String market=
            MarketFeedEndpointPool.marketCombinedStreamUrl(0,MarketRegistry.production());String publicUrl=
            MarketFeedEndpointPool.publicCombinedStreamUrl(0,MarketRegistry.production());for(String symbol:
            new String[]{"ethusdt","solusdt","btcusdt"}){assertEquals(1,count(market,symbol+
                    "@forceOrder"));assertEquals(1,count(market,symbol+"@aggTrade"));assertEquals(1,
                    count(market,symbol+"@kline_1m"));assertEquals(1,count(publicUrl,symbol+
                    "@bookTicker"));assertEquals(1,count(publicUrl,symbol+"@depth20@100ms"));}
        assertFalse(publicUrl.contains("forceOrder"));assertFalse(market.contains("!forceOrder@arr"));}

    @Test public void routerRecognizesAllSymbolsAndOnlyMarketAcceptsLiquidation()throws Exception{
        BinanceCombinedStreamRouter router=new BinanceCombinedStreamRouter();for(String symbol:new String[]{
                "ETHUSDT","SOLUSDT","BTCUSDT"}){BinanceCombinedStreamRouter.Event event=router.parse(
                fixture(symbol,1,1));assertNotNull(event);assertEquals(symbol,event.symbol);assertEquals(
                BinanceCombinedStreamRouter.Type.LIQUIDATION_SNAPSHOT,event.type);assertTrue(
                BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.MARKET_WS,
                        event.type));assertFalse(BinanceCombinedStreamRouter.accepts(
                        BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,event.type));}}

    @Test public void routerRejectsMissingOrderAndUnsupportedSymbol()throws Exception{
        BinanceCombinedStreamRouter router=new BinanceCombinedStreamRouter();assertNull(router.parse(
                "{\"stream\":\"ethusdt@forceOrder\",\"data\":{\"e\":\"forceOrder\",\"E\":2}}"));
        assertNull(router.parse(fixture("BNBUSDT",1,1)));}

    @Test public void strictParserPreservesRawOrderSemantics()throws Exception{JSONObject root=
            new JSONObject(fixture("ETHUSDT",2.5,2.0));BinanceForceOrderSnapshot.ParseResult result=
            BinanceForceOrderSnapshot.parse("ETHUSDT",root.getJSONObject("data"));assertTrue(
            result.accepted());BinanceForceOrderSnapshot value=result.snapshot;assertEquals("SELL",
            value.orderSide);assertEquals("LIMIT",value.orderType);assertEquals("IOC",value.timeInForce);
        assertEquals(2.5,value.originalQuantity,0);assertEquals(2.0,value.accumulatedFilledQuantity,0);}

    @Test public void strictParserRejectsMissingOrderUnknownSymbolSideTimestampAndNumbers()throws Exception{
        assertRejected("{\"e\":\"forceOrder\",\"E\":2}","ETHUSDT");
        JSONObject unknown=new JSONObject(fixture("BNBUSDT",1,1)).getJSONObject("data");
        assertFalse(BinanceForceOrderSnapshot.parse("BNBUSDT",unknown).accepted());
        JSONObject noSide=data("ETHUSDT");noSide.getJSONObject("o").put("S","");assertRejected(noSide);
        JSONObject badTime=data("ETHUSDT");badTime.getJSONObject("o").put("T",3_000);assertRejected(badTime);
        for(String bad:new String[]{"NaN","Infinity","-1"}){JSONObject invalid=data("ETHUSDT");
            invalid.getJSONObject("o").put("q",bad);assertRejected(invalid);}}

    @Test public void liquidationRecordMapContainsAllV3FieldsAndFiniteValues(){MicrostructureMarketRecord value=
            liquidation("s",2,1_100);Map<String,Object> map=value.toMap();assertEquals(
            "NMC_CAUSAL_MARKET_CAPTURE_V3",map.get("schema"));assertEquals(3,map.get("formatVersion"));
        assertEquals("LIQUIDATION_SNAPSHOT",map.get("kind"));for(String key:new String[]{"sessionId",
                "sequence","receivedAt","monotonicAt","symbol","source","exchangeEventAt","tradeAt",
                "orderSide","orderType","timeInForce","originalQuantity","price","averagePrice",
                "orderStatus","lastFilledQuantity","accumulatedFilledQuantity"})assertTrue(key,
                map.containsKey(key));assertTrue(CausalCaptureReplay.validate(List.of(
                MicrostructureMarketRecord.sessionForVersion(3,"s",1,1_000,1,"WS","START"),value)).complete);}

    @Test public void v3StoreCrcReplayRoundTripsLiquidation()throws Exception{File directory=
            Files.createTempDirectory("liquidation-v3").toFile();CausalCaptureStore store=
            new CausalCaptureStore(directory,"v3",4,64_000);store.appendBatch(List.of(
            MicrostructureMarketRecord.sessionForVersion(3,"s",1,1_000,1,"WS","START"),liquidation("s",2,1_100)));
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){CausalCaptureReplay.Validation replay=
                CausalCaptureReplay.replay(snapshot.files,null);assertTrue(replay.complete);assertEquals(1,
                replay.liquidationSnapshots);MicrostructureMarketRecord restored=(MicrostructureMarketRecord)
                CausalCaptureStore.read(snapshot.files,true).records.get(1);assertEquals("SELL",
                restored.orderSide);assertEquals(1900.25,restored.averagePrice,0);}}

    @Test public void v2AndV1BlocksRemainReadableBesideV3()throws Exception{File directory=
            Files.createTempDirectory("mixed-v123").toFile();CausalCaptureStore store=
            new CausalCaptureStore(directory,"mixed",8,64_000);store.append(CausalMarketRecord.session(
                    "v1",1,1,1,"V1","START"));store.append(MicrostructureMarketRecord.sessionForVersion(
                    2,"v2",1,2,2,"V2","START"));store.appendBatch(List.of(
                    MicrostructureMarketRecord.sessionForVersion(3,"v3",1,3,3,"V3","START"),
                    liquidation("v3",2,4)));try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
                List<CausalMarketRecord> read=CausalCaptureStore.read(snapshot.files,true).records;
                assertEquals(4,read.size());assertEquals(1,read.get(0).toMap().get("formatVersion"));
                assertEquals(2,read.get(1).toMap().get("formatVersion"));assertEquals(3,
                        read.get(2).toMap().get("formatVersion"));}}

    @Test public void sparseLiquidationDoesNotChangeHealthyStateOrMaskStaleAggTrade(){HealthFixture healthy=
            healthyFixture();Map<String,Object> before=healthy.snapshot(13_000);healthy.health.wsForceOrder(
            "ETHUSDT",MicrostructureCaptureHealth.MARKET_WS,13_000);assertEquals(before.get("state"),
            healthy.snapshot(13_000).get("state"));HealthFixture stale=healthyFixture();assertEquals(
            MicrostructureCaptureHealth.HEALTHY,stale.snapshot(13_000).get("state"));stale.health.wsForceOrder(
            "BTCUSDT",MicrostructureCaptureHealth.MARKET_WS,30_000);assertFalse(Boolean.TRUE.equals(
            stale.snapshot(30_000).get("usableForMicrostructureResearch")));}

    @Test public void captureWritesEverySnapshotWithoutHeuristicDedupAndCountsSides(){List<CausalMarketRecord>
            records=new ArrayList<>();MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(record->{
                records.add(record);return true;});capture.startSession("s","WS",1_000,1);for(int i=0;i<2;i++)
            assertTrue(capture.observeLiquidation("ETHUSDT","BINANCE_FUTURES_MARKET_WS",1_100+i,
                    2+i,1_099,1_098,"SELL","LIMIT","IOC",2,1900,1899.5,"FILLED",2,2));
        assertEquals(3,records.size());MicrostructureCaptureV2.SymbolStats stats=capture.stats().symbols.get(
                "ETHUSDT");assertEquals(2,stats.liquidationSnapshotsAccepted);assertEquals(2,
                stats.liquidationSellOrderSide);assertEquals(2L,capture.stats().toMap().get(
                        "totalLiquidationSnapshots"));}

    @Test public void rejectedLiquidationAndDropSummaryUseDedicatedKind(){List<CausalMarketRecord> records=
            new ArrayList<>();final int[] offers={0};MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(
                    record->{offers[0]++;if(record.kind==CausalMarketRecord.Kind.LIQUIDATION_SNAPSHOT)return false;
                        records.add(record);return true;});capture.startSession("s","WS",1_000,1);
        assertFalse(capture.observeLiquidation("ETHUSDT","WS",1_100,2,1_099,1_098,"SELL","LIMIT",
                "IOC",2,1900,1899,"FILLED",2,2));capture.health(1_200,3,"WS","WAKE");
        assertTrue(records.stream().anyMatch(record->record.kind==CausalMarketRecord.Kind.DROP_SUMMARY
                &&((MicrostructureMarketRecord)record).droppedByKind.containsKey(
                        "LIQUIDATION_SNAPSHOT")));}

    @Test public void releaseAndTradingSafetyRemainFrozen(){assertEquals("NMC_SCALP_CV_CORE_V1",
            CvCorePolicy.ENGINE_ID);assertEquals(4,CvCorePolicy.DUAL_EXHAUSTION_SHORT.targetMultiple,0);
        assertEquals(14.55,CvCorePolicy.DUAL_EXHAUSTION_SHORT.riskBudgetUsdt,0);assertEquals(7.275,
                CvCorePolicy.P02_BALANCED_SHORT.riskBudgetUsdt,0);assertEquals(1.43,
                CvCorePolicy.RESULT_COST_PER_UNIT,0);assertFalse(SignalSafetyPolicies.realTradingAllowed());}

    private static JSONObject data(String symbol)throws Exception{return new JSONObject(fixture(symbol,1,1))
            .getJSONObject("data");}
    private static void assertRejected(JSONObject value){assertFalse(BinanceForceOrderSnapshot.parse(
            "ETHUSDT",value).accepted());}
    private static void assertRejected(String value,String symbol)throws Exception{assertFalse(
            BinanceForceOrderSnapshot.parse(symbol,new JSONObject(value)).accepted());}
    private static String fixture(String symbol,double quantity,double accumulated){return "{\"stream\":\""
            +symbol.toLowerCase()+"@forceOrder\",\"data\":{\"e\":\"forceOrder\",\"E\":2000,\"o\":{"
            +"\"s\":\""+symbol+"\",\"S\":\"SELL\",\"o\":\"LIMIT\",\"f\":\"IOC\",\"q\":\""
            +quantity+"\",\"p\":\"1900.0\",\"ap\":\"1900.25\",\"X\":\"FILLED\",\"l\":\""
            +accumulated+"\",\"z\":\""+accumulated+"\",\"T\":1999}}}";}
    private static MicrostructureMarketRecord liquidation(String session,long sequence,long at){return
            MicrostructureMarketRecord.liquidationForVersion(3,session,sequence,at,sequence,"ETHUSDT",
                    "BINANCE_FUTURES_MARKET_WS",at-1,at-2,"SELL","LIMIT","IOC",2,1900,
                    1900.25,"FILLED",2,2);}
    private static int count(String value,String token){int count=0,from=0;while((from=value.indexOf(token,
            from))>=0){count++;from+=token.length();}return count;}
    private static double[][] levels(double start,boolean ascending){double[][] out=new double[20][2];
        for(int i=0;i<20;i++){out[i][0]=start+(ascending?i:-i)*.01;out[i][1]=1;}return out;}
    private static HealthFixture healthyFixture(){MicrostructureCaptureHealth health=
            new MicrostructureCaptureHealth();health.reset(1_000);health.socketConnected(
                    MicrostructureCaptureHealth.PUBLIC_WS,"public",1_001,101);health.socketConnected(
                    MicrostructureCaptureHealth.MARKET_WS,"market",1_001,101);MicrostructureCaptureV2 capture=
            new MicrostructureCaptureV2(record->true);capture.startSession("s","WS",1_000,1);long id=1;
        for(String symbol:new String[]{"ETHUSDT","SOLUSDT","BTCUSDT"}){health.wsBook(symbol,
                MicrostructureCaptureHealth.PUBLIC_WS,12_000);capture.observeTopBook(symbol,"PUBLIC",12_000,
                2,0,0,id,100,1,101,1);boolean depth=capture.observeDepth20(symbol,"PUBLIC",12_000,2,
                0,0,id,id,0,levels(100,false),levels(101,true));health.wsDepth(symbol,
                MicrostructureCaptureHealth.PUBLIC_WS,12_000,depth);health.wsAgg(symbol,
                MicrostructureCaptureHealth.MARKET_WS,12_000);capture.observeAggTrade(symbol,"MARKET",
                12_000,2,id,11_999,100,1,false);health.marketAggAccepted(symbol,12_000);id++;}
        capture.flushThrough(12_500,3);return new HealthFixture(health,capture);}
    private static final class HealthFixture {final MicrostructureCaptureHealth health;
        final MicrostructureCaptureV2 capture;HealthFixture(MicrostructureCaptureHealth health,
                MicrostructureCaptureV2 capture){this.health=health;this.capture=capture;}
        Map<String,Object> snapshot(long now){return health.snapshot(now,capture.stats(),
                new CausalCaptureWriter.Stats(64,0,100,0,16,Map.of(),100,0,1024,1,0,true));}}
}
