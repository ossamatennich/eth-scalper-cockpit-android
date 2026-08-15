package com.ethscalper.cockpit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

public final class V4Engine {
    private final V4ExtraTreesModel model;private final V4FallbackHistory history;
    public V4Engine(V4ExtraTreesModel model){this(model,new V4FallbackHistory(new V4FallbackHistory.MemoryBackend()));}
    public V4Engine(V4ExtraTreesModel model,V4FallbackHistory history){this.model=model;this.history=history;}
    public V4FeatureEngine.Candidate select(Map<String,V4FeatureEngine.Snapshot> snapshots){
        V4FeatureEngine.Candidate core=V4FeatureEngine.selectCore(snapshots);
        V4FeatureEngine.Candidate fallback=V4FeatureEngine.selectFallback(snapshots,model);if(fallback==null)return core;
        double best=Math.max(fallback.longScore,fallback.shortScore),spread=Math.abs(fallback.longScore-fallback.shortScore);
        V4FallbackHistory.Gate gate=history.evaluateThenCommit(V4FeatureEngine.sharedCutoff(snapshots),best,spread);if(core!=null)return core;
        return gate.accepted?fallback:null;
    }
    public void observePrior(Map<String,V4FeatureEngine.Snapshot> snapshots){V4FeatureEngine.Candidate f=V4FeatureEngine.selectFallback(snapshots,model);if(f!=null){
        history.observe(V4FeatureEngine.sharedCutoff(snapshots),Math.max(f.longScore,f.shortScore),Math.abs(f.longScore-f.shortScore));}}
    public V4Plan create(V4FeatureEngine.Candidate c,double entry,double quantity,double equity,double allocatedRisk,long cutoff,String parent){
        long now=System.currentTimeMillis(),expires=nextExpiry(now);double tp,sl;
        if(c.side==V4Plan.Side.LONG){tp=entry+(c.source==V4Plan.Source.CORE?3:2)*c.atr;sl=entry-(c.source==V4Plan.Source.CORE?.4:1)*c.atr;}
        else{tp=entry-(c.source==V4Plan.Source.CORE?3:2)*c.atr;sl=entry+(c.source==V4Plan.Source.CORE?.4:1)*c.atr;}
        return new V4Plan(null,parent,c.source,c.asset,c.side,quantity,entry,tp,sl,c.atr,now,now,expires,
                V4Plan.Status.WAITING,"Activation en cours",equity,allocatedRisk,cutoff,
                c.source==V4Plan.Source.FALLBACK?model.modelSha256:null);
    }
    public static boolean afterActivation(long now){ZonedDateTime z=Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC);return z.toLocalTime().compareTo(java.time.LocalTime.of(0,35))>=0;}
    public static long nextExpiry(long now){ZonedDateTime z=Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC);ZonedDateTime e=z.toLocalDate().plusDays(1).atTime(0,25).atZone(ZoneOffset.UTC);
        if(z.toLocalTime().isBefore(java.time.LocalTime.of(0,25)))e=z.toLocalDate().atTime(0,25).atZone(ZoneOffset.UTC);return e.toInstant().toEpochMilli();}
}
