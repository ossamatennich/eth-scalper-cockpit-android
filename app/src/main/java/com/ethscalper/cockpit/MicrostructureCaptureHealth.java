package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent transport/capture health; it has no effect on public feed authority. */
public final class MicrostructureCaptureHealth {
    public static final String HEALTHY="MICRO_CAPTURE_HEALTHY";
    public static final String DEGRADED="MICRO_CAPTURE_DEGRADED";
    public static final String STALE="MICRO_CAPTURE_STALE";
    public static final long WARMUP_MS=10_000L,TOP_STALE_MS=5_000L,DEPTH_STALE_MS=5_000L,
            AGG_STALE_MS=15_000L;
    private static final String[] SYMBOLS={"ETHUSDT","SOLUSDT","BTCUSDT"};
    private final LinkedHashMap<String,MutableSymbol> symbols=new LinkedHashMap<>();
    private long startedAt,malformedMessages,researchReconnects;
    public MicrostructureCaptureHealth(){for(String symbol:SYMBOLS)symbols.put(symbol,new MutableSymbol());}

    public synchronized void reset(long now){startedAt=now;malformedMessages=researchReconnects=0;
        for(MutableSymbol value:symbols.values())value.clear();}
    public synchronized void wsBook(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.book++;observed.lastBookAt=Math.max(observed.lastBookAt,at);
        symbols.get(symbol).lastBookAt=Math.max(symbols.get(symbol).lastBookAt,at);}
    public synchronized void wsAgg(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.agg++;observed.lastAggAt=Math.max(observed.lastAggAt,at);
        symbols.get(symbol).lastAggWsAt=Math.max(symbols.get(symbol).lastAggWsAt,at);}
    public synchronized void wsKline(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.kline++;observed.lastKlineAt=Math.max(observed.lastKlineAt,at);
        symbols.get(symbol).lastKlineAt=Math.max(symbols.get(symbol).lastKlineAt,at);}
    public synchronized void wsDepth(String symbol,String source,long at){Source observed=source(symbol,source);
        observed.depth++;observed.lastDepthAt=Math.max(observed.lastDepthAt,at);
        symbols.get(symbol).lastDepthWsAt=Math.max(symbols.get(symbol).lastDepthWsAt,at);}
    public synchronized void restRowsSeen(String symbol,long count){if(count>0)
        source(symbol,"REST").restSeen+=count;}
    public synchronized void restRowsAccepted(String symbol,long count,long at){if(count>0){
        Source observed=source(symbol,"REST");observed.restAccepted+=count;
        observed.lastRestAggAt=Math.max(observed.lastRestAggAt,at);symbols.get(symbol).lastRestAggAt=
                Math.max(symbols.get(symbol).lastRestAggAt,at);}}
    public synchronized void malformed(){malformedMessages++;}
    public synchronized void reconnect(){researchReconnects++;}

    public synchronized boolean aggStale(String symbol,long now){MutableSymbol value=symbols.get(symbol);
        if(value==null)return true;long at=Math.max(value.lastAggWsAt,value.lastRestAggAt);
        return at<=0||now-at>AGG_STALE_MS;}

    public synchronized Map<String,Object> snapshot(long now,MicrostructureCaptureV2.Stats capture,
            CausalCaptureWriter.Stats writer){LinkedHashMap<String,Object> root=new LinkedHashMap<>();
        root.put("schema",MicrostructureMarketRecord.SCHEMA);root.put("formatVersion",2);
        root.put("observedAt",now);root.put("warmupMs",WARMUP_MS);
        root.put("malformedMessages",malformedMessages);root.put("researchReconnects",researchReconnects);
        LinkedHashMap<String,Object> perSymbol=new LinkedHashMap<>();boolean topStale=false,degraded=false;
        for(String symbol:SYMBOLS){MutableSymbol transport=symbols.get(symbol);
            MicrostructureCaptureV2.SymbolStats causal=capture==null?null:capture.symbols.get(symbol);
            long topAt=causal==null?transport.lastBookAt:Math.max(transport.lastBookAt,causal.lastTopAt);
            long depthAt=causal==null?transport.lastDepthWsAt:Math.max(transport.lastDepthWsAt,causal.lastDepthAt);
            long aggAt=causal==null?Math.max(transport.lastAggWsAt,transport.lastRestAggAt)
                    :Math.max(Math.max(transport.lastAggWsAt,transport.lastRestAggAt),causal.lastAggTradeAt);
            long topAge=age(now,topAt),depthAge=age(now,depthAt),aggAge=age(now,aggAt);
            boolean symbolTopStale=topAge<0||topAge>TOP_STALE_MS;
            boolean symbolDepthStale=depthAge<0||depthAge>DEPTH_STALE_MS;
            boolean symbolAggStale=aggAge<0||aggAge>AGG_STALE_MS;
            topStale|=symbolTopStale;degraded|=symbolDepthStale||symbolAggStale;
            LinkedHashMap<String,Object> item=new LinkedHashMap<>();item.put("sources",transport.sourcesMap());
            item.put("topBookAgeMs",topAge);item.put("aggTradeAgeMs",aggAge);
            item.put("depth20AgeMs",depthAge);item.put("topBookStale",symbolTopStale);
            item.put("aggTradeStale",symbolAggStale);item.put("depth20Stale",symbolDepthStale);
            item.put("lowActivityException",false);if(causal!=null)item.put("causal",causal.toMap());
            perSymbol.put(symbol,item);}
        boolean writerUnavailable=writer==null||!writer.running||writer.failed>0;
        boolean saturated=writer!=null&&(writer.queueSize*4>=writer.queueCapacity*3
                ||writer.accepted>0&&writer.rejected*1_000L>writer.accepted);
        boolean warming=startedAt<=0||now-startedAt<WARMUP_MS;String state=topStale?STALE
                :(warming||degraded||writerUnavailable||saturated?DEGRADED:HEALTHY);
        root.put("state",state);root.put("warmingUp",warming);root.put("writerAvailable",!writerUnavailable);
        root.put("queueSaturated",saturated);root.put("symbols",perSymbol);
        if(writer!=null)root.put("writer",writer.toMap());return Collections.unmodifiableMap(root);}

    private Source source(String symbol,String source){MutableSymbol value=symbols.get(symbol);
        if(value==null)throw new IllegalArgumentException("symbol");String key=source==null?"UNKNOWN":source;
        return value.sources.computeIfAbsent(key,ignored->new Source());}
    private static long age(long now,long at){return at<=0?-1:Math.max(0,now-at);}
    private static final class MutableSymbol {final LinkedHashMap<String,Source> sources=new LinkedHashMap<>();
        long lastBookAt,lastAggWsAt,lastKlineAt,lastDepthWsAt,lastRestAggAt;void clear(){sources.clear();
            lastBookAt=lastAggWsAt=lastKlineAt=lastDepthWsAt=lastRestAggAt=0;}
        Map<String,Object> sourcesMap(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            for(Map.Entry<String,Source> entry:sources.entrySet())out.put(entry.getKey(),entry.getValue().map());
            return out;}}
    private static final class Source {long book,agg,kline,depth,restSeen,restAccepted,lastBookAt,
        lastAggAt,lastKlineAt,lastDepthAt,lastRestAggAt;
        Map<String,Object> map(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("wsBookTickerMessages",book);out.put("wsAggTradeMessages",agg);
            out.put("wsKlineMessages",kline);out.put("wsDepth20Messages",depth);
            out.put("restAggTradeRowsSeen",restSeen);
            out.put("restAggTradeRowsAcceptedForResearch",restAccepted);
            out.put("lastSuccessfulBookTickerAt",lastBookAt);
            out.put("lastSuccessfulAggTradeAt",lastAggAt);
            out.put("lastSuccessfulKlineAt",lastKlineAt);
            out.put("lastSuccessfulDepth20At",lastDepthAt);
            out.put("lastSuccessfulRestAggTradeAt",lastRestAggAt);return out;}}
}
