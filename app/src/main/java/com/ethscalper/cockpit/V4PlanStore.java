package com.ethscalper.cockpit;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class V4PlanStore {
    private final SharedPreferences prefs;
    public V4PlanStore(Context c){prefs=c.getSharedPreferences("v4_plan_store",Context.MODE_PRIVATE);}
    public synchronized List<V4Plan> all(){ArrayList<V4Plan> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("plans","[]"));
        for(int i=0;i<a.length();i++)out.add(V4Plan.fromJson(a.getJSONObject(i)));}catch(Exception ignored){}out.sort(Comparator.comparingLong((V4Plan p)->p.createdAt).reversed());return out;}
    public synchronized List<V4Plan> active(){ArrayList<V4Plan> out=new ArrayList<>();for(V4Plan p:all())if(!p.terminal())out.add(p);return out;}
    public synchronized void save(V4Plan plan){List<V4Plan> plans=all();boolean replaced=false;for(int i=0;i<plans.size();i++)if(plans.get(i).planId.equals(plan.planId)){plans.set(i,plan);replaced=true;break;}
        if(!replaced)plans.add(0,plan);while(plans.size()>500)plans.remove(plans.size()-1);write(plans);}
    private void write(List<V4Plan> plans){JSONArray a=new JSONArray();for(V4Plan p:plans)a.put(p.toJson());prefs.edit().putString("plans",a.toString()).commit();}
    public synchronized V4Plan find(String id){for(V4Plan p:all())if(p.planId.equals(id))return p;return null;}
}
