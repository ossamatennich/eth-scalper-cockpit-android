package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded research-only telemetry. It owns no plan and no public lifecycle state. */
public final class ShadowTelemetryRegistry {
    public static final int CAPACITY=160;
    private final LinkedHashMap<String,RangeRecord> ranges=new LinkedHashMap<>();
    private final LinkedHashMap<String,BreakoutRecord> breakouts=new LinkedHashMap<>();
    private final Deque<String> rangeOrder=new ArrayDeque<>(),breakoutOrder=new ArrayDeque<>();
    private long rangeEvictions,breakoutEvictions;

    public synchronized RangeUpdate observeRange(String key,SignalDecision signal,
            NormalizedSignalMetrics.Result metrics,MarketSnapshot snapshot,long now,
            double adverse,boolean marketFresh,boolean btcFresh) {
        if(empty(key)||signal==null||metrics==null||!metrics.valid||snapshot==null)return RangeUpdate.none();
        RangeRecord r=ranges.get(key);boolean created=false,changed=false;
        if(r==null){r=new RangeRecord(key,signal,now);ranges.put(key,r);rangeOrder.addLast(key);
            trimRanges();created=true;changed=true;}
        r.observations++;r.feedFresh=marketFresh;r.btcFresh=btcFresh;r.lastObservedAt=now;
        double m1=metrics.m1;
        if(m1>0&&r.firstM1PositiveAt==0){r.firstM1PositiveAt=now;changed=true;}
        if(m1+.000000000001>=.10&&r.firstM1Above010At==0){r.firstM1Above010At=now;changed=true;}
        if(m1+.000000000001>=.20&&r.firstM1Above020At==0){r.firstM1Above020At=now;changed=true;}
        if(m1+.000000000001>=.30&&r.firstM1Above030At==0){r.firstM1Above030At=now;changed=true;}
        double reclaim=metrics.a>0&&Double.isFinite(snapshot.marketLast)
                ?("LONG".equals(signal.side)?snapshot.marketLast-signal.movementOrigin
                :signal.movementOrigin-snapshot.marketLast)/metrics.a:Double.NaN;
        if(Double.isFinite(reclaim)){changed|=r.reclaim(reclaim,adverse,now);}
        boolean terminal=false;
        if(marketFresh&&finitePositive(snapshot.marketBid)&&finitePositive(snapshot.marketAsk)){
            double quote="LONG".equals(signal.side)?snapshot.marketBid:snapshot.marketAsk;
            boolean tp="LONG".equals(signal.side)?quote>=signal.takeProfit:quote<=signal.takeProfit;
            boolean sl="LONG".equals(signal.side)?quote<=signal.stopLoss:quote>=signal.stopLoss;
            if(tp&&r.targetTouchedAt==0){r.targetTouchedAt=now;terminal=true;changed=true;}
            if(sl&&r.stopTouchedAt==0){r.stopTouchedAt=now;terminal=true;changed=true;}
        }
        r.metrics=metrics;r.adverse=Math.max(r.adverse,Math.max(0,adverse));
        return new RangeUpdate(created,changed,terminal,r.details());
    }

    public synchronized BreakoutUpdate observeBreakout(String key,SignalDecision signal,
            MarketSnapshot snapshot,long createdAt,long targetAt,double adverse,double favorable,
            String reason,long firstExecutableAt) {
        if(empty(key)||signal==null)return BreakoutUpdate.none();BreakoutRecord r=breakouts.get(key);
        if(r!=null){r.duplicates++;return new BreakoutUpdate(false,r.duplicates,r.details());}
        r=new BreakoutRecord(key,signal,createdAt,targetAt,adverse,favorable,reason,firstExecutableAt,snapshot);
        breakouts.put(key,r);breakoutOrder.addLast(key);trimBreakouts();return new BreakoutUpdate(true,0,r.details());
    }

    public synchronized void reset(){ranges.clear();breakouts.clear();rangeOrder.clear();breakoutOrder.clear();
        rangeEvictions=breakoutEvictions=0;}
    public synchronized int rememberedRanges(){return ranges.size();}
    public synchronized int rememberedBreakouts(){return breakouts.size();}
    public synchronized long rangeEvictions(){return rangeEvictions;}
    public synchronized long breakoutEvictions(){return breakoutEvictions;}

    private void trimRanges(){while(rangeOrder.size()>CAPACITY){String key=rangeOrder.removeFirst();ranges.remove(key);rangeEvictions++;}}
    private void trimBreakouts(){while(breakoutOrder.size()>CAPACITY){String key=breakoutOrder.removeFirst();breakouts.remove(key);breakoutEvictions++;}}
    private static boolean empty(String v){return v==null||v.isEmpty();}
    private static boolean finitePositive(double v){return Double.isFinite(v)&&v>0;}
    private static Object number(double v){return Double.isFinite(v)?v:null;}

    public static final class RangeUpdate{
        public final boolean created,changed,terminal;public final Map<String,Object> details;
        private RangeUpdate(boolean created,boolean changed,boolean terminal,Map<String,Object>d){this.created=created;this.changed=changed;this.terminal=terminal;details=d;}
        static RangeUpdate none(){return new RangeUpdate(false,false,false,java.util.Collections.emptyMap());}
    }
    public static final class BreakoutUpdate{
        public final boolean created;public final long duplicates;public final Map<String,Object> details;
        private BreakoutUpdate(boolean created,long duplicates,Map<String,Object>d){this.created=created;this.duplicates=duplicates;details=d;}
        static BreakoutUpdate none(){return new BreakoutUpdate(false,0,java.util.Collections.emptyMap());}
    }

    private static final class RangeRecord{
        final String key,signature,side,family;final long createdAt;final double originalEntry,origin,extreme;
        long observations,lastObservedAt,firstM1PositiveAt,firstM1Above010At,firstM1Above020At,
                firstM1Above030At,firstPriceReclaim025AAt,firstPriceReclaim050AAt,
                firstPriceReclaim075AAt,targetTouchedAt,stopTouchedAt;
        double adverse,maxAdverse025,maxAdverse050,maxAdverse075;boolean feedFresh,btcFresh;
        NormalizedSignalMetrics.Result metrics;
        RangeRecord(String key,SignalDecision s,long now){this.key=key;signature="";side=s.side;family=s.family;
            createdAt=now;originalEntry=s.entry;origin=s.movementOrigin;extreme=s.movementExtreme;}
        boolean reclaim(double value,double adverse,long now){boolean changed=false;
            if(value+.000000000001>=.25&&firstPriceReclaim025AAt==0){firstPriceReclaim025AAt=now;maxAdverse025=adverse;changed=true;}
            if(value+.000000000001>=.50&&firstPriceReclaim050AAt==0){firstPriceReclaim050AAt=now;maxAdverse050=adverse;changed=true;}
            if(value+.000000000001>=.75&&firstPriceReclaim075AAt==0){firstPriceReclaim075AAt=now;maxAdverse075=adverse;changed=true;}return changed;}
        Map<String,Object> details(){LinkedHashMap<String,Object>d=new LinkedHashMap<>();d.put("movementKey",key);
            d.put("originalEntry",originalEntry);d.put("movementOrigin",origin);d.put("movementExtreme",extreme);
            d.put("firstM1PositiveAt",nullable(firstM1PositiveAt));d.put("firstM1Above010At",nullable(firstM1Above010At));
            d.put("firstM1Above020At",nullable(firstM1Above020At));d.put("firstM1Above030At",nullable(firstM1Above030At));
            d.put("firstPriceReclaim025AAt",nullable(firstPriceReclaim025AAt));d.put("firstPriceReclaim050AAt",nullable(firstPriceReclaim050AAt));
            d.put("firstPriceReclaim075AAt",nullable(firstPriceReclaim075AAt));d.put("maxAdverseBeforeReclaim025A",number(maxAdverse025));
            d.put("maxAdverseBeforeReclaim050A",number(maxAdverse050));d.put("maxAdverseBeforeReclaim075A",number(maxAdverse075));
            d.put("targetTouchedAt",nullable(targetTouchedAt));d.put("stopTouchedAt",nullable(stopTouchedAt));
            d.put("feedFresh",feedFresh);d.put("btcFresh",btcFresh);d.put("observations",observations);
            if(metrics!=null){d.put("A",number(metrics.a));d.put("room",number(metrics.room));d.put("directionalEdge",number(metrics.directionalEdge));
                d.put("m1",number(metrics.m1));d.put("m3",number(metrics.m3));d.put("m8",number(metrics.m8));
                d.put("f30",number(metrics.f30));d.put("f60",number(metrics.f60));d.put("volumeRatio",number(metrics.volumeRatio));}return d;}
    }

    private static final class BreakoutRecord{
        final String key,signature,symbol,side,family,reason;final int score;final long createdAt,targetAt,firstExecutableAt;
        final double entry,target,stop,adverse,favorable,a,m1,m3,m8,f30,f60,volume,edge,btc1,btc3,btc8;long duplicates;
        BreakoutRecord(String key,SignalDecision s,long created,long targetAt,double adverse,double favorable,
                       String reason,long executable,MarketSnapshot x){this.key=key;signature="";symbol=s.symbol;side=s.side;family=s.family;
            score=s.score;createdAt=created;this.targetAt=targetAt;firstExecutableAt=executable;entry=s.entry;target=s.takeProfit;stop=s.stopLoss;
            this.adverse=adverse;this.favorable=favorable;this.reason=reason==null?"":reason;
            a=x==null?Double.NaN:x.avgRange20;MarketProfile profile=MarketProfile.SOL_SYMBOL.equals(s.symbol)
                    ?MarketProfile.sol():MarketProfile.eth();NormalizedSignalMetrics.Result m=x==null?null:NormalizedSignalMetrics.calculate(
                    profile,s.side,s,x,adverse);
            m1=m==null?Double.NaN:m.m1;m3=m==null?Double.NaN:m.m3;m8=m==null?Double.NaN:m.m8;
            f30=m==null?Double.NaN:m.f30;f60=m==null?Double.NaN:m.f60;volume=m==null?Double.NaN:m.volumeRatio;
            edge=m==null?Double.NaN:m.directionalEdge;btc1=x==null?Double.NaN:x.btcMove1;btc3=x==null?Double.NaN:x.btcMove3;btc8=x==null?Double.NaN:x.btcMove8;}
        Map<String,Object> details(){LinkedHashMap<String,Object>d=new LinkedHashMap<>();d.put("movementKey",key);d.put("symbol",symbol);
            d.put("side",side);d.put("family",family);d.put("score",score);d.put("createdAt",createdAt);
            d.put("firstExecutableAt",nullable(firstExecutableAt));d.put("targetTouchedAt",targetAt);d.put("timeToTargetMs",Math.max(0,targetAt-createdAt));
            d.put("originalEntry",entry);d.put("target",target);d.put("stop",stop);d.put("maxAdverseBeforeTarget",adverse);
            d.put("maxFavorableBeforeTarget",favorable);d.put("adverseInA",a>0?adverse/a:null);d.put("favorableInA",a>0?favorable/a:null);
            d.put("m1",number(m1));d.put("m3",number(m3));d.put("m8",number(m8));d.put("f30",number(f30));d.put("f60",number(f60));
            d.put("volumeRatio",number(volume));d.put("directionalEdge",number(edge));d.put("BTC1",number(btc1));d.put("BTC3",number(btc3));d.put("BTC8",number(btc8));
            d.put("nonConfirmationReason",reason);d.put("duplicatesGrouped",duplicates);return d;}
    }
    private static Object nullable(long v){return v>0?v:null;}
}
