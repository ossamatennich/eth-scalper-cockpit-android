package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded one-second causal quote history for the action engine. */
public final class ScalpActionContextTracker {
    static final int CAPACITY = 1200;
    private static final long SLOT_MAX_AGE_MS = 2_000L;
    private final Map<String,Deque<Point>> points = new LinkedHashMap<>();

    public ScalpActionContextTracker() {
        points.put(MarketProfile.ETH_SYMBOL,new ArrayDeque<>());
        points.put(MarketProfile.SOL_SYMBOL,new ArrayDeque<>());
        points.put(MarketProfile.BTC_SYMBOL,new ArrayDeque<>());
    }

    public synchronized boolean observe(String symbol,double bid,double ask,long now) {
        Deque<Point> q=points.get(symbol);
        if(q==null||now<=0||!validQuote(bid,ask))return false;
        Point p=new Point(now,bid,ask,(bid+ask)/2.0);
        if(!q.isEmpty()&&q.peekLast().at/1000L==now/1000L)q.removeLast();
        q.addLast(p);while(q.size()>CAPACITY)q.removeFirst();return true;
    }

    public synchronized Point sampleAtOrBefore(String symbol,long targetAt,long maximumAgeMs) {
        Deque<Point> q=points.get(symbol);if(q==null)return null;
        Point found=null;for(Point p:q){if(p.at>targetAt)break;found=p;}
        return found!=null&&targetAt-found.at<=maximumAgeMs?found:null;
    }

    public synchronized Metrics metrics(long observedAt,String side) {
        Point eth=sampleAtOrBefore(MarketProfile.ETH_SYMBOL,observedAt,5_000L);
        Point anchor=sampleAtOrBefore(MarketProfile.ETH_SYMBOL,observedAt-480_000L,5_000L);
        int sign="SHORT".equals(side)?-1:1;
        double dret=eth==null||anchor==null?Double.NaN:sign*(eth.mid-anchor.mid)/anchor.mid;
        GridStats rv=grid(MarketProfile.SOL_SYMBOL,observedAt,30);
        GridStats cov=grid(MarketProfile.SOL_SYMBOL,observedAt,180);
        boolean rvValid=rv.validSlots>=25&&rv.returns>=24&&rv.validSlots/31.0>=.80;
        return new Metrics(dret,Double.isFinite(dret),rv.rms(),rvValid,
                cov.validSlots/181.0,cov.validSlots>0);
    }

    public synchronized boolean fresh(String symbol,long observedAt,long maxAgeMs) {
        return sampleAtOrBefore(symbol,observedAt,maxAgeMs)!=null;
    }

    public synchronized int size(String symbol){Deque<Point> q=points.get(symbol);return q==null?0:q.size();}
    public synchronized void reset(){for(Deque<Point> q:points.values())q.clear();}

    private GridStats grid(String symbol,long observedAt,int seconds) {
        GridStats out=new GridStats();double previous=Double.NaN;boolean previousValid=false;
        for(int i=seconds;i>=0;i--){long at=observedAt-i*1000L;
            Point p=sampleAtOrBefore(symbol,at,SLOT_MAX_AGE_MS);boolean valid=p!=null;
            if(valid)out.validSlots++;
            if(valid&&previousValid){double r=Math.log(p.mid/previous);if(Double.isFinite(r)){out.sumSquares+=r*r;out.returns++;}}
            previous=valid?p.mid:Double.NaN;previousValid=valid;
        }return out;
    }

    private static boolean validQuote(double bid,double ask){return Double.isFinite(bid)&&bid>0
            &&Double.isFinite(ask)&&ask>0&&ask>=bid&&Double.isFinite((bid+ask)/2.0);}

    public static final class Point {public final long at;public final double bid,ask,mid;
        Point(long at,double bid,double ask,double mid){this.at=at;this.bid=bid;this.ask=ask;this.mid=mid;}}
    public static final class Metrics {
        public final double ethDret480,solRv30,solCov180;
        public final boolean ethDret480Valid,solRv30Valid,solCov180Valid;
        Metrics(double d,boolean dv,double r,boolean rv,double c,boolean cv){ethDret480=d;
            ethDret480Valid=dv;solRv30=r;solRv30Valid=rv;solCov180=c;solCov180Valid=cv;}
    }
    private static final class GridStats {int validSlots,returns;double sumSquares;
        double rms(){return returns==0?Double.NaN:Math.sqrt(sumSquares/returns);}}
}
