package com.ethscalper.cockpit;

import org.json.JSONArray;

import java.util.LinkedHashSet;
import java.util.Set;

/** Persistent plan-id + lifecycle-event dedupe. No plan or trading state is stored here. */
public final class V4NotificationLedger {
    public interface Backend {String load();boolean save(String json);}
    private final Backend backend;
    private final Set<String> delivered=new LinkedHashSet<>();

    public V4NotificationLedger(Backend backend) {
        if(backend==null)throw new IllegalArgumentException("backend");
        this.backend=backend;
        try{String stored=backend.load();JSONArray array=new JSONArray(stored==null?"[]":stored);
            for(int i=0;i<array.length();i++){String key=array.optString(i,"");if(!key.isEmpty())delivered.add(key);}}
        catch(Exception ignored){}
    }

    public synchronized boolean claim(String planId,V4NotificationPolicy.Event event) {
        String key=key(planId,event);
        if(delivered.contains(key))return false;
        delivered.add(key);
        if(!persist()){delivered.remove(key);return false;}
        return true;
    }

    public synchronized void release(String planId,V4NotificationPolicy.Event event) {
        if(delivered.remove(key(planId,event)))persist();
    }

    public synchronized boolean contains(String planId,V4NotificationPolicy.Event event) {
        return delivered.contains(key(planId,event));
    }

    public synchronized int size(){return delivered.size();}

    private boolean persist(){return backend.save(new JSONArray(delivered).toString());}
    private static String key(String planId,V4NotificationPolicy.Event event){
        if(planId==null||planId.isBlank()||event==null||event==V4NotificationPolicy.Event.NONE)
            throw new IllegalArgumentException("notification key");
        return planId+":"+event.name();
    }
}
