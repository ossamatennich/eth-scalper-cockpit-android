package com.ethscalper.cockpit;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** FIFO, side-aware action episode registry. Terminal state adds no cooldown. */
public final class ScalpActionMovementRegistry {
    public static final int CAPACITY=160;
    private final LinkedHashMap<String,Episode> episodes=new LinkedHashMap<>();
    private long sequence;

    public synchronized Episode observe(String symbol,String side,long observedAt){
        String key=symbol+"|"+side;Episode latest=null;
        for(Episode e:episodes.values())if(key.equals(e.key))latest=e;
        if(latest==null||observedAt-latest.lastSeenAt>180_000L){
            String id=symbol+"-"+side+"-"+observedAt+"-"+(++sequence);
            latest=new Episode(id,key,symbol,side,observedAt);episodes.put(id,latest);
            while(episodes.size()>CAPACITY){Iterator<String> it=episodes.keySet().iterator();it.next();it.remove();}
        }else{latest.lastSeenAt=observedAt;latest.duplicateCount++;}
        return latest;
    }
    public synchronized boolean markOpened(String episodeId,String route){Episode e=episodes.get(episodeId);
        if(e==null||e.opened)return false;e.opened=true;e.winningRoute=route==null?"":route;return true;}
    public synchronized Episode find(String id){return episodes.get(id);}
    public synchronized int size(){return episodes.size();}
    public synchronized void reset(){episodes.clear();sequence=0;}

    public static final class Episode {public final String episodeId,key,symbol,side;public final long firstSeenAt;
        public long lastSeenAt;public boolean opened;public String winningRoute="";public long duplicateCount;
        Episode(String id,String key,String symbol,String side,long at){episodeId=id;this.key=key;this.symbol=symbol;this.side=side;firstSeenAt=at;lastSeenAt=at;}}
}
