package com.ethscalper.cockpit;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed episodes observed before any CV route threshold is evaluated. */
public final class CvCoreMovementRegistry {
    public static final int CAPACITY=200;
    private final LinkedHashMap<String,Episode> byId=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> latestBySide=new LinkedHashMap<>();
    private long sequence;

    public synchronized Episode observe(String symbol,String side,long observedAt){
        if(symbol==null||side==null||observedAt<=0)return null;
        String key=symbol+"|"+side;Episode current=byId.get(latestBySide.get(key));
        if(current==null||observedAt-current.lastSeenAt>180_000L){
            current=new Episode(symbol+"-"+side+"-"+observedAt+"-"+(++sequence),key,symbol,side,observedAt);
            byId.put(current.episodeId,current);latestBySide.put(key,current.episodeId);trim();
        }else{current.lastSeenAt=Math.max(current.lastSeenAt,observedAt);current.duplicateCount++;}
        return current;
    }
    public synchronized boolean markOpened(String id,String route){Episode e=byId.get(id);
        if(e==null||e.opened)return false;e.opened=true;e.winningRoute=route==null?"":route;return true;}
    public synchronized Episode find(String id){return byId.get(id);}
    public synchronized int size(){return byId.size();}
    public synchronized void reset(){byId.clear();latestBySide.clear();sequence=0;}
    public synchronized Episode restoreOpened(String id,String symbol,String side,long at,String route){
        if(id==null||id.isEmpty())return null;Episode e=new Episode(id,symbol+"|"+side,symbol,side,at);
        e.opened=true;e.winningRoute=route==null?"":route;byId.put(id,e);latestBySide.put(e.key,id);trim();return e;}
    public synchronized Map<String,Object> snapshot(){Map<String,Object> out=new LinkedHashMap<>();
        out.put("capacity",CAPACITY);out.put("size",byId.size());return out;}
    private void trim(){while(byId.size()>CAPACITY){Iterator<Map.Entry<String,Episode>> it=byId.entrySet().iterator();
        Map.Entry<String,Episode> oldest=it.next();it.remove();
        if(oldest.getKey().equals(latestBySide.get(oldest.getValue().key)))latestBySide.remove(oldest.getValue().key);}}

    public static final class Episode{
        public final String episodeId,key,symbol,side;public final long firstSeenAt;
        public long lastSeenAt,duplicateCount;public boolean opened;public String winningRoute="";
        Episode(String id,String key,String symbol,String side,long at){episodeId=id;this.key=key;
            this.symbol=symbol;this.side=side;firstSeenAt=at;lastSeenAt=at;}
    }
}
