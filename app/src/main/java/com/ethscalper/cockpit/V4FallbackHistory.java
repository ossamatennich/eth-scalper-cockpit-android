package com.ethscalper.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One persisted FALLBACK daily-best observation per completed UTC day. */
public final class V4FallbackHistory {
    public static final double QUALITY_SCORE_QUANTILE=.675d;
    public static final double QUALITY_SPREAD_QUANTILE=.50d;
    public static final int QUALITY_PRIOR_DAYS=90;
    public static final int QUALITY_MIN_PRIOR=45;
    public interface Backend {String load();void save(String json);}
    public static final class MemoryBackend implements Backend {
        private String value;
        public MemoryBackend(){this("[]");}
        public MemoryBackend(String initial){value=initial==null?"[]":initial;}
        @Override public String load(){return value;}
        @Override public void save(String json){value=json;}
        public String value(){return value;}
    }
    public static final class Gate {
        public final int priorCount;public final double threshold30,minPriorSpread;public final boolean ready,accepted;
        Gate(int count,double threshold,double minSpread,boolean ready,boolean accepted){priorCount=count;threshold30=threshold;
            minPriorSpread=minSpread;this.ready=ready;this.accepted=accepted;}
    }
    public static final class QualityGate {
        public final int priorCount;
        public final double scoreThreshold,spreadThreshold;
        public final boolean ready,accepted;

        QualityGate(int priorCount,double scoreThreshold,double spreadThreshold,
                    boolean ready,boolean accepted){
            this.priorCount=priorCount;
            this.scoreThreshold=scoreThreshold;
            this.spreadThreshold=spreadThreshold;
            this.ready=ready;
            this.accepted=accepted;
        }
    }

    private static final long DAY_MS=86_400_000L;
    private final Backend backend;private final TreeMap<Long,double[]> byDay=new TreeMap<>();
    public V4FallbackHistory(Backend backend){this.backend=backend;loadAndMigrate();}
    public synchronized Gate evaluateThenCommit(long cutoffUtc,double best,double spread){long day=day(cutoffUtc);
        List<double[]> prior=prior(day,90);double threshold=percentile(prior,.30,0),minimum=min(prior,1);
        double[] frozenToday=byDay.get(day);double evaluatedBest=frozenToday==null?best:frozenToday[0],evaluatedSpread=frozenToday==null?spread:frozenToday[1];
        boolean ready=prior.size()>=45,accepted=ready&&evaluatedBest>=threshold&&evaluatedSpread>=minimum;
        if(!byDay.containsKey(day)){byDay.put(day,new double[]{best,spread});persist();}
        return new Gate(prior.size(),threshold,minimum,ready,accepted);
    }
    /**
     * Gate du mode OFF.
     *
     * Calibration uniquement sur les meilleurs signaux quotidiens
     * des 90 jours UTC PRECEDENTS.
     *
     * Le jour courant n'est jamais inclus.
     * Cette méthode ne modifie pas l'historique.
     */
    public synchronized QualityGate qualityGate(long cutoffUtc,double score,double spread){
        long day=day(cutoffUtc);
        List<double[]> prior=prior(day,QUALITY_PRIOR_DAYS);

        double scoreThreshold=percentile(prior,QUALITY_SCORE_QUANTILE,0);
        double spreadThreshold=percentile(prior,QUALITY_SPREAD_QUANTILE,1);

        boolean ready=prior.size()>=QUALITY_MIN_PRIOR;
        boolean accepted=ready
                &&Double.isFinite(score)
                &&Double.isFinite(spread)
                &&score>=scoreThreshold
                &&spread>=spreadThreshold;

        return new QualityGate(
                prior.size(),
                scoreThreshold,
                spreadThreshold,
                ready,
                accepted
        );
    }

    public synchronized void observe(long cutoffUtc,double best,double spread){long day=day(cutoffUtc);if(!byDay.containsKey(day)){
        byDay.put(day,new double[]{best,spread});persist();}}
    public synchronized int size(){return byDay.size();}
    public synchronized int count(long cutoffUtc){return byDay.containsKey(day(cutoffUtc))?1:0;}
    public synchronized double best(long cutoffUtc){double[]v=byDay.get(day(cutoffUtc));return v==null?Double.NaN:v[0];}
    private void loadAndMigrate(){try{JSONArray a=new JSONArray(backend.load());for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);
            long d=day(o.optLong("cutoffUtc",o.optLong("date",Long.MIN_VALUE)));double best=o.getDouble("best"),spread=o.getDouble("spread");
            if(d>=0&&Double.isFinite(best)&&Double.isFinite(spread))byDay.putIfAbsent(d,new double[]{best,spread});}}catch(Exception ignored){}
        persist();}
    private void persist(){JSONArray a=new JSONArray();for(Map.Entry<Long,double[]>e:byDay.entrySet()){JSONObject o=new JSONObject();
        try{o.put("cutoffUtc",e.getKey());o.put("best",e.getValue()[0]);o.put("spread",e.getValue()[1]);a.put(o);}catch(Exception ignored){}}
        backend.save(a.toString());}
    private List<double[]> prior(long day,int limit){ArrayList<double[]> out=new ArrayList<>();for(double[]v:byDay.headMap(day,false).descendingMap().values()){
        out.add(v);if(out.size()==limit)break;}return out;}
    private static double percentile(List<double[]>rows,double q,int column){if(rows.isEmpty())return Double.POSITIVE_INFINITY;
        double[]x=rows.stream().mapToDouble(v->v[column]).sorted().toArray();double at=q*(x.length-1);int lo=(int)Math.floor(at),hi=(int)Math.ceil(at);
        return x[lo]+(x[hi]-x[lo])*(at-lo);}
    private static double min(List<double[]>rows,int column){return rows.stream().mapToDouble(v->v[column]).min().orElse(Double.POSITIVE_INFINITY);}
    public static long day(long utcMillis){return Math.floorDiv(utcMillis,DAY_MS)*DAY_MS;}
}
