package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure causal daily feature and setup selection used by runtime and JVM fixtures. */
public final class V4FeatureEngine {
    public static final class Snapshot {public final String asset;public final long cutoff;public final double close,atr;
        public final double[] fallbackFeatures;public final double resMom14,zRes14,qv30Rank;
        Snapshot(String a,long c,double close,double atr,double[] f,double r,double z,double q){asset=a;cutoff=c;this.close=close;this.atr=atr;fallbackFeatures=f;resMom14=r;zRes14=z;qv30Rank=q;}}
    public static final class Candidate {public final V4Plan.Source source;public final String asset;public final V4Plan.Side side;
        public final double atr,longScore,shortScore;Candidate(V4Plan.Source s,String a,V4Plan.Side d,double atr,double l,double sh){source=s;asset=a;side=d;this.atr=atr;longScore=l;shortScore=sh;}}
    private V4FeatureEngine(){}
    public static Map<String,Snapshot> compute(Map<String,List<V4DailyBar>> panel){
        return computeAt(panel,latestCutoff(panel));}
    public static Map<String,Snapshot> computeAt(Map<String,List<V4DailyBar>> panel,long cutoff){
        return computeAligned(alignAt(panel,cutoff),cutoff);}
    public static long latestCutoff(Map<String,List<V4DailyBar>> panel){long latest=Long.MIN_VALUE;for(List<V4DailyBar>b:panel.values())if(b!=null&&!b.isEmpty())latest=Math.max(latest,b.get(b.size()-1).openTime);
        if(latest==Long.MIN_VALUE)throw new IllegalArgumentException("empty panel");return latest;}
    public static Map<String,List<V4DailyBar>> alignAt(Map<String,List<V4DailyBar>> panel,long cutoff){LinkedHashMap<String,List<V4DailyBar>> out=new LinkedHashMap<>();
        for(String asset:V4Universe.ASSETS){List<V4DailyBar>b=panel.get(asset);if(b==null)continue;int index=find(b,cutoff);if(index>=0)out.put(asset,new ArrayList<>(b.subList(0,index+1)));}return out;}
    public static long sharedCutoff(Map<String,Snapshot> snapshots){long cutoff=Long.MIN_VALUE;for(Snapshot s:snapshots.values()){
        if(cutoff==Long.MIN_VALUE)cutoff=s.cutoff;else if(cutoff!=s.cutoff)throw new IllegalArgumentException("mixed UTC cutoff");}
        if(cutoff==Long.MIN_VALUE)throw new IllegalArgumentException("empty snapshots");return cutoff;}
    private static Map<String,Snapshot> computeAligned(Map<String,List<V4DailyBar>> panel,long cutoff){
        LinkedHashMap<String,double[]> raw=new LinkedHashMap<>();Map<String,Double> ret1=new HashMap<>(),resmom=new HashMap<>(),qv30=new HashMap<>();
        for(String a:V4Universe.ASSETS){List<V4DailyBar>b=panel.get(a);if(b==null||b.size()<90||!contiguous(b,b.size()-1,90))continue;int n=b.size()-1;cutoff=Math.max(cutoff,b.get(n).openTime);
            if(b.get(n).openTime!=cutoff)continue;
            double r=b.get(n).close/b.get(n-1).close-1;ret1.put(a,r);qv30.put(a,meanQuote(b,n,30));}
        double market=ret1.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        for(String a:ret1.keySet()){List<V4DailyBar>b=panel.get(a);int n=b.size()-1;double sum=0;
            for(int k=0;k<14;k++){Map<String,Double> day=new HashMap<>();long at=b.get(n-k).openTime;
                for(String p:ret1.keySet()){List<V4DailyBar>pb=panel.get(p);int j=find(pb,at);if(j>0)day.put(p,pb.get(j).close/pb.get(j-1).close-1);}
                double m=day.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);sum+=day.getOrDefault(a,0d)-m;}
            resmom.put(a,sum);}
        Map<String,Double> zres=zscore(resmom),qrank=percentRank(qv30);
        for(String a:ret1.keySet()){List<V4DailyBar>b=panel.get(a);int n=b.size()-1;double atr=atr(b,n,14);
            double[] f={ret(b,n,1),ret(b,n,3),ret(b,n,5),ret(b,n,7),ret(b,n,14),ret(b,n,21),
                    sumOf(b,n,3),sumOf(b,n,7),buyRatio(b.get(n)),qvz(b,n,7),qvz(b,n,30),atr/b.get(n).close,loc(b,n,14),loc(b,n,21)};
            raw.put(a,f);}
        standardizeColumns(raw);LinkedHashMap<String,Snapshot> out=new LinkedHashMap<>();
        for(String a:raw.keySet()){List<V4DailyBar>b=panel.get(a);int n=b.size()-1;out.put(a,new Snapshot(a,b.get(n).openTime,b.get(n).close,
                atr(b,n,14),raw.get(a),resmom.get(a),zres.get(a),qrank.get(a)));}return out;}
    public static Candidate selectCore(Map<String,Snapshot>s){Snapshot best=null;for(String a:V4Universe.CORE_ASSETS){Snapshot x=s.get(a);
        if(x!=null&&Math.abs(x.zRes14)>=.50&&(best==null||Math.abs(x.zRes14)>Math.abs(best.zRes14)))best=x;}
        return best==null?null:new Candidate(V4Plan.Source.CORE,best.asset,best.zRes14>0?V4Plan.Side.LONG:V4Plan.Side.SHORT,best.atr,0,0);}
    public static Candidate selectFallback(Map<String,Snapshot>s,V4ExtraTreesModel model){Candidate best=null;double score=-Double.MAX_VALUE;
        for(Snapshot x:s.values()){if(x.qv30Rank<.40)continue;double l=model.predictLong(x.fallbackFeatures),sh=model.predictShort(x.fallbackFeatures),v=Math.max(l,sh);
            if(v>score){score=v;best=new Candidate(V4Plan.Source.FALLBACK,x.asset,l>=sh?V4Plan.Side.LONG:V4Plan.Side.SHORT,x.atr,l,sh);}}return best;}
    private static double ret(List<V4DailyBar>b,int n,int k){return b.get(n).close/b.get(n-k).close-1;}
    private static double of(V4DailyBar b){double e=1e-12*Math.max(1,b.quoteVolume),sell=Math.max(0,b.quoteVolume-b.takerBuyQuote);return Math.log(b.takerBuyQuote+e)-Math.log(sell+e);}
    private static double sumOf(List<V4DailyBar>b,int n,int k){double v=0;for(int i=0;i<k;i++)v+=of(b.get(n-i));return v;}
    private static double buyRatio(V4DailyBar b){return b.takerBuyQuote/Math.max(b.quoteVolume,1e-12);}
    private static double meanQuote(List<V4DailyBar>b,int n,int k){double v=0;for(int i=0;i<k;i++)v+=b.get(n-i).quoteVolume;return v/k;}
    private static double qvz(List<V4DailyBar>b,int n,int k){double m=0;for(int i=0;i<k;i++)m+=Math.log(Math.max(1e-12,b.get(n-i).quoteVolume));m/=k;double v=0;
        for(int i=0;i<k;i++){double d=Math.log(Math.max(1e-12,b.get(n-i).quoteVolume))-m;v+=d*d;}return v==0?0:(Math.log(Math.max(1e-12,b.get(n).quoteVolume))-m)/Math.sqrt(v/k);}
    private static double loc(List<V4DailyBar>b,int n,int k){double lo=Double.MAX_VALUE,hi=-Double.MAX_VALUE;for(int i=0;i<k;i++){lo=Math.min(lo,b.get(n-i).low);hi=Math.max(hi,b.get(n-i).high);}return hi==lo?.5:(b.get(n).close-lo)/(hi-lo);}
    private static double atr(List<V4DailyBar>b,int n,int k){double sum=0;for(int i=0;i<k;i++){int j=n-i;V4DailyBar x=b.get(j);double prev=b.get(j-1).close;
        sum+=Math.max(x.high-x.low,Math.max(Math.abs(x.high-prev),Math.abs(x.low-prev)));}return sum/k;}
    private static int find(List<V4DailyBar>b,long at){int lo=0,hi=b.size()-1;while(lo<=hi){int m=(lo+hi)>>>1;long v=b.get(m).openTime;if(v==at)return m;if(v<at)lo=m+1;else hi=m-1;}return -1;}
    private static boolean contiguous(List<V4DailyBar>b,int n,int count){for(int i=n-count+2;i<=n;i++)if(b.get(i).openTime-b.get(i-1).openTime!=86_400_000L)return false;return true;}
    private static Map<String,Double> zscore(Map<String,Double> x){double m=x.values().stream().mapToDouble(Double::doubleValue).average().orElse(0),v=0;for(double d:x.values())v+=(d-m)*(d-m);double sd=Math.sqrt(v/Math.max(1,x.size()));Map<String,Double>o=new HashMap<>();for(var e:x.entrySet())o.put(e.getKey(),sd==0?0:(e.getValue()-m)/sd);return o;}
    private static Map<String,Double> percentRank(Map<String,Double>x){List<Map.Entry<String,Double>>q=new ArrayList<>(x.entrySet());q.sort(Map.Entry.comparingByValue());Map<String,Double>o=new HashMap<>();
        for(int i=0;i<q.size();i++)o.put(q.get(i).getKey(),(i+1d)/q.size());return o;}
    private static void standardizeColumns(Map<String,double[]>x){if(x.isEmpty())return;int p=x.values().iterator().next().length;for(int j=0;j<p;j++){double m=0;for(double[]v:x.values())m+=v[j];m/=x.size();double s=0;for(double[]v:x.values())s+=(v[j]-m)*(v[j]-m);s=Math.sqrt(s/x.size());for(double[]v:x.values())v[j]=s==0?0:(v[j]-m)/s;}}
}
