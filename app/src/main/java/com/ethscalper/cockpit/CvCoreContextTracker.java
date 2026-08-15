package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded causal quote history and exact CV Core directional metrics. */
public final class CvCoreContextTracker {
    public static final int CAPACITY=1200;
    private final Map<String,Deque<Point>> points=new LinkedHashMap<>();
    public CvCoreContextTracker(){points.put(MarketProfile.ETH_SYMBOL,new ArrayDeque<>());
        points.put(MarketProfile.SOL_SYMBOL,new ArrayDeque<>());points.put(MarketProfile.BTC_SYMBOL,new ArrayDeque<>());}
    public synchronized boolean observe(String symbol,double bid,double ask,long now){Deque<Point> q=points.get(symbol);
        if(q==null||now<=0||!validQuote(bid,ask))return false;Point p=new Point(now,bid,ask,(bid+ask)/2d);
        if(!q.isEmpty()&&q.peekLast().at/1000L==now/1000L)q.removeLast();q.addLast(p);
        while(q.size()>CAPACITY)q.removeFirst();return true;}
    public synchronized Point sampleAtOrBefore(String symbol,long targetAt,long maximumAgeMs){Deque<Point> q=points.get(symbol);
        if(q==null||maximumAgeMs<0)return null;Point found=null;for(Point p:q){if(p.at>targetAt)break;found=p;}
        return found!=null&&targetAt-found.at>=0&&targetAt-found.at<=maximumAgeMs?found:null;}
    public synchronized Metrics metrics(long observedAt,String side,double btcMove8,double btcMove3){
        int sign="SHORT".equals(side)?-1:1;Directional eth60=directional(MarketProfile.ETH_SYMBOL,observedAt,60_000L,sign);
        Directional sol60=directional(MarketProfile.SOL_SYMBOL,observedAt,60_000L,sign);
        Directional sol30=directional(MarketProfile.SOL_SYMBOL,observedAt,30_000L,sign);
        return new Metrics(eth60.returnValue,eth60.returnValid,sol60.returnValue,sol60.returnValid,
                eth60.efficiency,eth60.efficiencyValid,sol30.efficiency,sol30.efficiencyValid,
                sign*btcMove8,sign*btcMove3,eth60.coverage,sol60.coverage,sol30.coverage,
                eth60.anchorAgeMs,sol60.anchorAgeMs,sol30.anchorAgeMs,eth60.pathPoints,
                sol60.pathPoints,sol30.pathPoints,eth60.pathDistance,sol60.pathDistance,sol30.pathDistance);
    }
    public synchronized boolean fresh(String symbol,long at,long maxAge){return sampleAtOrBefore(symbol,at,maxAge)!=null;}
    public synchronized int size(String symbol){Deque<Point> q=points.get(symbol);return q==null?0:q.size();}
    public synchronized void reset(){for(Deque<Point> q:points.values())q.clear();}

    private Directional directional(String symbol,long observedAt,long window,int sign){
        Point current=sampleAtOrBefore(symbol,observedAt,5_000L),anchor=sampleAtOrBefore(symbol,observedAt-window,5_000L);
        if(current==null||anchor==null)return Directional.invalid();
        double ret=sign*(current.mid-anchor.mid)/anchor.mid;TreeMap<Long,Point> path=new TreeMap<>();path.put(anchor.at,anchor);
        Deque<Point> q=points.get(symbol);if(q!=null)for(Point p:q)if(p.at>=anchor.at&&p.at<=current.at)path.put(p.at,p);path.put(current.at,current);
        List<Point> ordered=new ArrayList<>(path.values());double distance=0;for(int i=1;i<ordered.size();i++)distance+=Math.abs(ordered.get(i).mid-ordered.get(i-1).mid);
        double efficiency=distance>0?sign*(current.mid-anchor.mid)/distance:Double.NaN;
        double coverage=Math.min(1d,ordered.size()/(window/1000d+1d));
        return new Directional(ret,Double.isFinite(ret),efficiency,Double.isFinite(efficiency),coverage,
                observedAt-window-anchor.at,ordered.size(),distance);
    }
    private static boolean validQuote(double b,double a){return Double.isFinite(b)&&b>0&&Double.isFinite(a)&&a>0&&a>=b&&Double.isFinite((b+a)/2d);}
    public static final class Point{public final long at;public final double bid,ask,mid;Point(long at,double b,double a,double m){this.at=at;bid=b;ask=a;mid=m;}}
    private static final class Directional{final double returnValue,efficiency,coverage,pathDistance;final boolean returnValid,efficiencyValid;final long anchorAgeMs;final int pathPoints;
        Directional(double r,boolean rv,double e,boolean ev,double c,long age,int count,double distance){returnValue=r;returnValid=rv;efficiency=e;efficiencyValid=ev;coverage=c;anchorAgeMs=age;pathPoints=count;pathDistance=distance;}
        static Directional invalid(){return new Directional(Double.NaN,false,Double.NaN,false,0,-1,0,Double.NaN);}}
    public static final class Metrics{
        public final double directionalEthReturn60,directionalSolReturn60,directionalEthEfficiency60,directionalSolEfficiency30;
        public final boolean directionalEthReturn60Valid,directionalSolReturn60Valid,directionalEthEfficiency60Valid,directionalSolEfficiency30Valid;
        public final double directionalBtcMove8,directionalBtcMove3,ethCoverage60,solCoverage60,solCoverage30;
        public final long ethAnchorAge60Ms,solAnchorAge60Ms,solAnchorAge30Ms;public final int ethPathPoints60,solPathPoints60,solPathPoints30;
        public final double ethPathDistance60,solPathDistance60,solPathDistance30;
        public Metrics(double er,boolean erv,double sr,boolean srv,double ee,boolean eev,double se,boolean sev,double b8,double b3,
                       double ec,double sc60,double sc30,long ea,long sa60,long sa30,int ep,int sp60,int sp30,double ed,double sd60,double sd30){
            directionalEthReturn60=er;directionalEthReturn60Valid=erv;directionalSolReturn60=sr;directionalSolReturn60Valid=srv;
            directionalEthEfficiency60=ee;directionalEthEfficiency60Valid=eev;directionalSolEfficiency30=se;directionalSolEfficiency30Valid=sev;
            directionalBtcMove8=b8;directionalBtcMove3=b3;ethCoverage60=ec;solCoverage60=sc60;solCoverage30=sc30;
            ethAnchorAge60Ms=ea;solAnchorAge60Ms=sa60;solAnchorAge30Ms=sa30;ethPathPoints60=ep;solPathPoints60=sp60;solPathPoints30=sp30;
            ethPathDistance60=ed;solPathDistance60=sd60;solPathDistance30=sd30;}
    }
}
