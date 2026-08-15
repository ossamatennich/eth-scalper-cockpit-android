package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.Assert.*;

public class V4UniverseAndSafetyTest {
    @Test public void exactCanonicalUniverse(){assertEquals(53,V4Universe.ASSETS.size());assertEquals(53,new HashSet<>(V4Universe.ASSETS).size());}
    @Test public void prohibitedAssetsAbsent(){assertFalse(V4Universe.ASSETS.contains("COTI"));assertFalse(V4Universe.ASSETS.contains("S&P500"));assertFalse(V4Universe.ASSETS.contains("XYZ100"));assertFalse(V4Universe.ASSETS.contains("MATIC"));}
    @Test public void everyAssetHasExactlyOneLeverage(){assertEquals(new HashSet<>(V4Universe.ASSETS),V4Universe.leverageTable().keySet());}
    @Test public void exactLeverageExamples(){assertEquals(10,V4Universe.leverage("BTC"));assertEquals(5,V4Universe.leverage("ETH"));assertEquals(3,V4Universe.leverage("HYPE"));assertEquals(2,V4Universe.leverage("WIF"));}
    @Test public void exactSymbolOnly(){assertEquals("POLUSDT",V4Universe.binanceSymbol("POL"));assertThrows(IllegalArgumentException.class,()->V4Universe.binanceSymbol("MATIC"));}
    @Test public void lightweightFeedHasAllAssetsOnce(){String u=V4MarketDataClient.bookTickerUrl();assertTrue(u.startsWith("wss://fstream.binance.com/public/stream?streams="));
        String streams=u.substring(u.indexOf('=')+1);java.util.List<String> tokens=java.util.Arrays.asList(streams.split("/"));for(String a:V4Universe.ASSETS)assertEquals(1,java.util.Collections.frequency(tokens,a.toLowerCase()+"usdt@bookTicker"));assertFalse(u.contains("depth"));assertFalse(u.contains("aggTrade"));assertFalse(u.contains("forceOrder"));}
    @Test public void cvCoreCannotPublishNewPlan(){assertFalse(V4PublicationPolicy.mayPublishNewPlan(CvCorePolicy.ENGINE_ID));assertTrue(V4PublicationPolicy.mayPublishNewPlan(V4Universe.ENGINE_ID));}
    @Test public void realTradingRemainsFalse(){assertFalse(SignalSafetyPolicies.realTradingAllowed());}
    private static int count(String s,String x){int n=0,p=0;while((p=s.indexOf(x,p))>=0){n++;p+=x.length();}return n;}
}
