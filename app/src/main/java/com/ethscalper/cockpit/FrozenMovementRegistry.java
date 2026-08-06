package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded structural movement identity registry, independent for each symbol. */
public final class FrozenMovementRegistry {
    private final LinkedHashMap<String,Record> records=new LinkedHashMap<>();
    private long sequence,evicted;

    public synchronized Resolution resolve(MarketProfile profile,SignalDecision signal,long now) {
        String group=FrozenProfitabilityShadowPolicy.familyGroup(signal.family);
        boolean structural=finitePositive(signal.movementOrigin)&&finitePositive(signal.movementExtreme);
        if(!structural)for(Record r:records.values())if(r.symbol.equals(profile.symbol)
                &&r.side.equals(signal.side)&&r.familyGroup.equals(group)
                &&"FALLBACK".equals(r.signatureMode)&&r.firstObservedAt/15_000L==now/15_000L){
            r.lastObservedAt=now;r.duplicates++;return new Resolution(r,true);}
        if(structural)for(Record r:records.values()) {
            if(!r.symbol.equals(profile.symbol)||!r.side.equals(signal.side)||!r.familyGroup.equals(group))continue;
            double originTolerance=Math.max(profile.priceTick*2.0,Math.max(profile.priceTick,Math.abs(r.distance))*.10);
            double extremeTolerance=Math.max(profile.priceTick*5.0,Math.max(profile.priceTick,Math.abs(r.distance))*.25);
            if(Math.abs(r.origin-signal.movementOrigin)<=originTolerance
                    &&Math.abs(r.lastExtreme-signal.movementExtreme)<=extremeTolerance) {
                r.lastExtreme=signal.movementExtreme;r.lastObservedAt=now;r.duplicates++;
                return new Resolution(r,true);
            }
        }
        String mode=structural?"STRUCTURAL":"FALLBACK";
        double origin=structural?profile.roundPriceConservative(signal.movementOrigin,false):Double.NaN;
        double extreme=structural?profile.roundPriceConservative(signal.movementExtreme,false):Double.NaN;
        long fallbackBucket=now/15_000L;
        String key=profile.symbol+"|"+signal.side+"|"+group+"|"+mode+"|"+
                (structural?fmt(origin)+"|"+fmt(extreme)+"|"+fmt(signal.movementDistance):fallbackBucket)+"|"+(++sequence);
        Record r=new Record(key,mode,profile.symbol,signal.side,group,origin,extreme,
                signal.movementDistance,now);
        records.put(key,r);trim(profile.symbol);return new Resolution(r,false);
    }

    private void trim(String symbol){
        while(count(symbol)>FrozenProfitabilityShadowPolicy.MOVEMENT_CAPACITY_PER_SYMBOL){
            Iterator<Map.Entry<String,Record>> it=records.entrySet().iterator();
            while(it.hasNext()){Map.Entry<String,Record> e=it.next();if(e.getValue().symbol.equals(symbol)){it.remove();evicted++;break;}}
        }
    }
    private int count(String symbol){int n=0;for(Record r:records.values())if(r.symbol.equals(symbol))n++;return n;}
    public synchronized void reset(){records.clear();sequence=0;evicted=0;}
    public synchronized int size(){return records.size();}
    public synchronized long evicted(){return evicted;}
    public synchronized List<Record> snapshot(){return new ArrayList<>(records.values());}
    private static boolean finitePositive(double v){return Double.isFinite(v)&&v>0;}
    private static String fmt(double v){return String.format(Locale.US,"%.8f",v);}

    public static final class Resolution {
        public final Record record;public final boolean duplicate;
        Resolution(Record record,boolean duplicate){this.record=record;this.duplicate=duplicate;}
    }
    public static final class Record {
        public final String movementSignature,signatureMode,symbol,side,familyGroup;
        public final double origin,distance;public double lastExtreme;
        public final long firstObservedAt;public long lastObservedAt,duplicates;
        public boolean qualified,opened;public String lastEventState="";
        Record(String signature,String mode,String symbol,String side,String family,double origin,
               double extreme,double distance,long now){this.movementSignature=signature;
            this.signatureMode=mode;this.symbol=symbol;this.side=side;this.familyGroup=family;
            this.origin=origin;this.lastExtreme=extreme;this.distance=distance;
            this.firstObservedAt=now;this.lastObservedAt=now;}
    }
}
