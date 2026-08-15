package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent transport/capture health; it has no effect on public feed authority. */
public final class MicrostructureCaptureHealth {
    public static final String HEALTHY="MICRO_CAPTURE_HEALTHY";
    public static final String DEGRADED="MICRO_CAPTURE_DEGRADED";
    public static final String STALE="MICRO_CAPTURE_STALE";
    public static final String PUBLIC_WS="PUBLIC_WS",MARKET_WS="MARKET_WS",
            INCREMENTAL_DEPTH_WS="INCREMENTAL_DEPTH_WS";
    public static final long WARMUP_MS=10_000L,TOP_STALE_MS=5_000L,DEPTH_STALE_MS=5_000L,
            AGG_STALE_MS=15_000L;
    public static final int MAX_DISCONNECT_EVENTS=64;
    private static final String[] SYMBOLS={"ETHUSDT","SOLUSDT","BTCUSDT"};
    private final LinkedHashMap<String,MutableSymbol> symbols=new LinkedHashMap<>();
    private final SocketState publicSocket=new SocketState(PUBLIC_WS),marketSocket=new SocketState(MARKET_WS),
            incrementalDepthSocket=new SocketState(INCREMENTAL_DEPTH_WS);
    private final ArrayDeque<Map<String,Object>> disconnectEvents=new ArrayDeque<>();
    private long startedAt,malformedMessages,disconnectEventsEvicted;

    public MicrostructureCaptureHealth(){for(String symbol:SYMBOLS)symbols.put(symbol,new MutableSymbol());}

    public synchronized void reset(long now){startedAt=now;malformedMessages=disconnectEventsEvicted=0;
        disconnectEvents.clear();for(MutableSymbol value:symbols.values())value.clear();
        publicSocket.resetCounters();marketSocket.resetCounters();incrementalDepthSocket.resetCounters();}

    public synchronized void wsBook(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.book++;observed.lastBookAt=Math.max(observed.lastBookAt,at);
        symbols.get(symbol).lastBookAt=Math.max(symbols.get(symbol).lastBookAt,at);}
    public synchronized void wsAgg(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.agg++;observed.lastAggAt=Math.max(observed.lastAggAt,at);}
    public synchronized void marketAggAccepted(String symbol,long at){MutableSymbol value=symbols.get(symbol);
        if(value==null)throw new IllegalArgumentException("symbol");value.lastMarketAggAcceptedAt=
                Math.max(value.lastMarketAggAcceptedAt,at);Source observed=source(symbol,MARKET_WS);
        observed.wsAggAccepted++;observed.lastAggAcceptedAt=Math.max(observed.lastAggAcceptedAt,at);}
    public synchronized void wsKline(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.kline++;observed.lastKlineAt=Math.max(observed.lastKlineAt,at);}
    public synchronized void wsDepth(String symbol,String source,long at){wsDepth(symbol,source,at,true);}
    public synchronized void wsDepth(String symbol,String source,long at,boolean accepted){Source observed=
        source(symbol,source);observed.depth++;if(accepted){observed.lastDepthAt=Math.max(
                observed.lastDepthAt,at);MutableSymbol value=symbols.get(symbol);
            value.lastDepthPublicWsAt=Math.max(value.lastDepthPublicWsAt,at);}}
    /** Naturally sparse research-only stream; deliberately never participates in health gates. */
    public synchronized void wsForceOrder(String symbol,String source,long at){Source observed=
        source(symbol,source);observed.forceOrder++;observed.lastForceOrderAt=Math.max(
                observed.lastForceOrderAt,at);}
    public synchronized void restRowsSeen(String symbol,long count){if(count>0)
        source(symbol,"REST").restSeen+=count;}
    public synchronized void restRowsAccepted(String symbol,long count,long at){if(count>0){
        Source observed=source(symbol,"REST");observed.restAccepted+=count;
        observed.lastRestAggAt=Math.max(observed.lastRestAggAt,at);symbols.get(symbol).lastRestAggAt=
                Math.max(symbols.get(symbol).lastRestAggAt,at);}}
    public synchronized void malformed(){malformedMessages++;}

    public synchronized void socketConnected(String type,String endpoint,long at,int httpStatus){SocketState value=
        socket(type);value.connects++;value.connected=true;value.endpoint=bounded(endpoint,300);
        value.lastConnectAt=Math.max(value.lastConnectAt,at);value.lastHttpStatus=httpStatus;}
    public synchronized void socketMessage(String type,long at){SocketState value=socket(type);
        value.lastMessageAt=Math.max(value.lastMessageAt,at);}
    public synchronized void socketReconnect(String type,long at,int attempt){SocketState value=socket(type);
        value.reconnects++;value.lastReconnectAt=Math.max(value.lastReconnectAt,at);
        value.lastReconnectAttempt=Math.max(0,attempt);}
    public synchronized void socketFailure(String type,String endpoint,long at,int httpStatus,
            String exceptionClass,String exceptionMessage,int reconnectAttempt,long sinceLastMessageMs){
        SocketState value=socket(type);value.failures++;value.connected=false;
        value.lastFailureAt=Math.max(value.lastFailureAt,at);value.endpoint=bounded(endpoint,300);
        rememberDisconnect(value,at,-1,"",httpStatus,exceptionClass,exceptionMessage,
                reconnectAttempt,sinceLastMessageMs);}
    public synchronized void socketClosed(String type,String endpoint,long at,int closeCode,
            String closeReason,int reconnectAttempt,long sinceLastMessageMs){SocketState value=socket(type);
        value.connected=false;value.lastClosedAt=Math.max(value.lastClosedAt,at);
        value.endpoint=bounded(endpoint,300);rememberDisconnect(value,at,closeCode,closeReason,-1,
                "","",reconnectAttempt,sinceLastMessageMs);}

    /** MARKET_WS only: REST is deliberately excluded from research-flow health. */
    public synchronized boolean aggStale(String symbol,long now){MutableSymbol value=symbols.get(symbol);
        return value==null||value.lastMarketAggAcceptedAt<=0
                ||now-value.lastMarketAggAcceptedAt>AGG_STALE_MS;}

    public synchronized Map<String,Object> snapshot(long now,MicrostructureCaptureV2.Stats capture,
            CausalCaptureWriter.Stats writer){LinkedHashMap<String,Object> root=new LinkedHashMap<>();
        root.put("schema",MicrostructureMarketRecord.SCHEMA);
        root.put("formatVersion",MicrostructureMarketRecord.FORMAT_VERSION);
        root.put("observedAt",now);root.put("warmupMs",WARMUP_MS);
        root.put("liquidationStreamConfigured",true);
        root.put("liquidationStreamNaturallySparse",true);
        root.put("malformedMessages",malformedMessages);appendSocketCounters(root);
        root.put("publicWs",publicSocket.toMap());root.put("marketWs",marketSocket.toMap());
        root.put("incrementalDepthWs",incrementalDepthSocket.toMap());
        root.put("socketDisconnectEvents",new ArrayList<>(disconnectEvents));
        root.put("socketDisconnectEventsEvicted",disconnectEventsEvicted);
        LinkedHashMap<String,Object> perSymbol=new LinkedHashMap<>();boolean topStale=false;
        boolean depthStale=false,marketAggStale=false,flowMissing=false;
        boolean captureValid=capture!=null&&!capture.sessionId.isEmpty()&&capture.sequence>0;
        for(String symbol:SYMBOLS){MutableSymbol transport=symbols.get(symbol);
            MicrostructureCaptureV2.SymbolStats causal=capture==null?null:capture.symbols.get(symbol);
            long topAge=age(now,transport.lastBookAt),depthAge=age(now,transport.lastDepthPublicWsAt);
            long marketAggAge=age(now,transport.lastMarketAggAcceptedAt);
            long restAggAge=age(now,transport.lastRestAggAt);
            boolean symbolTopStale=topAge<0||topAge>TOP_STALE_MS;
            boolean causalDepthReady=causal!=null&&causal.depthSamples>0;
            boolean causalFlowReady=causal!=null&&causal.flowBuckets>0;
            boolean symbolDepthStale=depthAge<0||depthAge>DEPTH_STALE_MS||!causalDepthReady;
            boolean symbolMarketAggStale=marketAggAge<0||marketAggAge>AGG_STALE_MS;
            topStale|=symbolTopStale;depthStale|=symbolDepthStale;
            marketAggStale|=symbolMarketAggStale;flowMissing|=!causalFlowReady;
            LinkedHashMap<String,Object> item=new LinkedHashMap<>();item.put("sources",transport.sourcesMap());
            item.put("topBookAgeMs",topAge);item.put("depth20PublicWsAgeMs",depthAge);
            item.put("aggTradeMarketWsAgeMs",marketAggAge);item.put("aggTradeRestAgeMs",restAggAge);
            item.put("topBookStale",symbolTopStale);item.put("depth20Stale",symbolDepthStale);
            item.put("aggTradeMarketWsStale",symbolMarketAggStale);
            item.put("flow100msReady",causalFlowReady);item.put("lowActivityException",false);
            if(causal!=null)item.put("causal",causal.toMap());perSymbol.put(symbol,item);}
        boolean writerUnavailable=writer==null||!writer.running||writer.failed>0;
        boolean saturated=writer!=null&&(writer.queueSize*4>=writer.queueCapacity*3
                ||writer.accepted>0&&writer.rejected*1_000L>writer.accepted);
        boolean criticalGapUnresolved=!publicSocket.connected||!marketSocket.connected;
        boolean warming=startedAt<=0||now-startedAt<WARMUP_MS;
        boolean usable=!warming&&captureValid&&!topStale&&!depthStale&&!marketAggStale&&!flowMissing
                &&!writerUnavailable&&!saturated&&!criticalGapUnresolved;
        String state=topStale?STALE:(usable?HEALTHY:DEGRADED);
        root.put("state",state);root.put("warmingUp",warming);
        root.put("captureV2Valid",captureValid);root.put("writerAvailable",!writerUnavailable);
        root.put("queueSaturated",saturated);root.put("depth20WsReady",!depthStale);
        root.put("marketWsAggTradeReady",!marketAggStale);root.put("flowWsReady",!flowMissing);
        root.put("criticalGapUnresolved",criticalGapUnresolved);
        root.put("usableForMicrostructureResearch",usable);root.put("symbols",perSymbol);
        if(writer!=null)root.put("writer",writer.toMap());return Collections.unmodifiableMap(root);}

    private void appendSocketCounters(Map<String,Object> root){root.put("publicWsConnects",publicSocket.connects);
        root.put("publicWsReconnects",publicSocket.reconnects);root.put("publicWsFailures",publicSocket.failures);
        root.put("marketWsConnects",marketSocket.connects);root.put("marketWsReconnects",marketSocket.reconnects);
        root.put("marketWsFailures",marketSocket.failures);root.put("lastPublicWsConnectAt",
                publicSocket.lastConnectAt);root.put("lastPublicWsMessageAt",publicSocket.lastMessageAt);
        root.put("lastPublicWsFailureAt",publicSocket.lastFailureAt);root.put("lastPublicWsClosedAt",
                publicSocket.lastClosedAt);root.put("lastMarketWsConnectAt",marketSocket.lastConnectAt);
        root.put("lastMarketWsMessageAt",marketSocket.lastMessageAt);root.put("lastMarketWsFailureAt",
                marketSocket.lastFailureAt);root.put("lastMarketWsClosedAt",marketSocket.lastClosedAt);
        root.put("incrementalDepthWsConnects",incrementalDepthSocket.connects);
        root.put("incrementalDepthWsReconnects",incrementalDepthSocket.reconnects);
        root.put("incrementalDepthWsFailures",incrementalDepthSocket.failures);
        root.put("lastIncrementalDepthWsConnectAt",incrementalDepthSocket.lastConnectAt);
        root.put("lastIncrementalDepthWsMessageAt",incrementalDepthSocket.lastMessageAt);}
    private void rememberDisconnect(SocketState socket,long at,int closeCode,String closeReason,
            int httpStatus,String exceptionClass,String exceptionMessage,int attempt,long sinceLastMessageMs){
        LinkedHashMap<String,Object> event=new LinkedHashMap<>();event.put("socketType",socket.type);
        event.put("endpoint",socket.endpoint);event.put("timestamp",at);
        event.put("closeCode",closeCode<0?null:closeCode);event.put("closeReason",bounded(closeReason,180));
        event.put("httpStatus",httpStatus<0?null:httpStatus);event.put("exceptionClass",
                bounded(exceptionClass,120));event.put("exceptionMessage",bounded(exceptionMessage,180));
        event.put("reconnectAttempt",Math.max(0,attempt));event.put("msSinceLastValidMessage",
                Math.max(-1,sinceLastMessageMs));disconnectEvents.addLast(Collections.unmodifiableMap(event));
        while(disconnectEvents.size()>MAX_DISCONNECT_EVENTS){disconnectEvents.removeFirst();
            disconnectEventsEvicted++;}}
    private SocketState socket(String type){if(PUBLIC_WS.equals(type))return publicSocket;
        if(MARKET_WS.equals(type))return marketSocket;if(INCREMENTAL_DEPTH_WS.equals(type))
            return incrementalDepthSocket;throw new IllegalArgumentException("socketType");}
    private Source source(String symbol,String source){MutableSymbol value=symbols.get(symbol);
        if(value==null)throw new IllegalArgumentException("symbol");String key=source==null?"UNKNOWN":source;
        return value.sources.computeIfAbsent(key,ignored->new Source());}
    private static long age(long now,long at){return at<=0?-1:Math.max(0,now-at);}
    private static String bounded(String value,int maximum){String out=value==null?"":value.replace('\n',' ')
            .replace('\r',' ').trim();return out.length()<=maximum?out:out.substring(0,maximum);}

    private static final class MutableSymbol {final LinkedHashMap<String,Source> sources=new LinkedHashMap<>();
        long lastBookAt,lastDepthPublicWsAt,lastMarketAggAcceptedAt,lastRestAggAt;void clear(){sources.clear();
            lastBookAt=lastDepthPublicWsAt=lastMarketAggAcceptedAt=lastRestAggAt=0;}
        Map<String,Object> sourcesMap(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            for(Map.Entry<String,Source> entry:sources.entrySet())out.put(entry.getKey(),entry.getValue().map());
            return out;}}
    private static final class Source {long book,agg,kline,depth,forceOrder,restSeen,restAccepted,
        wsAggAccepted,lastBookAt,lastAggAt,lastAggAcceptedAt,lastKlineAt,lastDepthAt,
        lastForceOrderAt,lastRestAggAt;
        Map<String,Object> map(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("wsBookTickerMessages",book);out.put("wsAggTradeMessages",agg);
            out.put("wsKlineMessages",kline);out.put("wsDepth20Messages",depth);
            out.put("forceOrderWsMessages",forceOrder);
            out.put("causalAggTradesAcceptedFromWs",wsAggAccepted);
            out.put("restAggTradeRowsSeen",restSeen);out.put("restAggTradeRowsAcceptedForResearch",restAccepted);
            out.put("lastSuccessfulBookTickerAt",lastBookAt);out.put("lastSuccessfulAggTradeAt",lastAggAt);
            out.put("lastAcceptedWsAggTradeAt",lastAggAcceptedAt);out.put("lastSuccessfulKlineAt",lastKlineAt);
            out.put("lastSuccessfulDepth20At",lastDepthAt);
            out.put("lastLiquidationReceivedAt",lastForceOrderAt<=0?null:lastForceOrderAt);
            out.put("lastSuccessfulRestAggTradeAt",lastRestAggAt);return out;}}
    private static final class SocketState {final String type;boolean connected;String endpoint="";
        long connects,reconnects,failures,lastConnectAt,lastMessageAt,lastFailureAt,lastClosedAt,
                lastReconnectAt;int lastHttpStatus=-1,lastReconnectAttempt;
        SocketState(String type){this.type=type;}void resetCounters(){connects=reconnects=failures=
            lastConnectAt=lastMessageAt=lastFailureAt=lastClosedAt=lastReconnectAt=0;
            lastHttpStatus=-1;lastReconnectAttempt=0;}
        Map<String,Object> toMap(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("socketType",type);out.put("connected",connected);out.put("endpoint",endpoint);
            out.put("connects",connects);out.put("reconnects",reconnects);out.put("failures",failures);
            out.put("lastConnectAt",lastConnectAt);out.put("lastMessageAt",lastMessageAt);
            out.put("lastFailureAt",lastFailureAt);out.put("lastClosedAt",lastClosedAt);
            out.put("lastReconnectAt",lastReconnectAt);out.put("lastHttpStatus",
                    lastHttpStatus<0?null:lastHttpStatus);out.put("lastReconnectAttempt",lastReconnectAttempt);
            return out;}}
}
