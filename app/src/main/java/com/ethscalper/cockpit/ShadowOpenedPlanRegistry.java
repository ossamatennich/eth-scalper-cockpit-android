package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single bounded FIFO registry for active and terminal shadow research plans. */
public final class ShadowOpenedPlanRegistry {
    public static final int CAPACITY=256;
    private final Deque<String> order=new ArrayDeque<>();
    private final LinkedHashMap<String,Record> byPlanId=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> planIdBySignature=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> planIdByMovement=new LinkedHashMap<>();
    private long evicted;

    public synchronized Record registerOpen(ShadowPlanState plan,String movementKey,String family,
                                              boolean higherPriorityOverlap){
        if(plan==null||empty(plan.shadowPlanId)||empty(plan.candidateSignature)||empty(movementKey))return null;
        Record record=new Record(plan.shadowPlanId,plan.candidateSignature,movementKey,plan.symbol,
                plan.side,family,plan.component,plan.openedAt,higherPriorityOverlap);
        removePlan(plan.shadowPlanId);byPlanId.put(record.shadowPlanId,record);
        planIdBySignature.put(record.candidateSignature,record.shadowPlanId);
        planIdByMovement.put(record.movementKey,record.shadowPlanId);order.addLast(record.shadowPlanId);
        while(order.size()>CAPACITY){String oldest=order.removeFirst();removePlan(oldest);evicted++;}
        return record.copy();
    }

    public synchronized Record markTerminal(String planId,long at,String status){
        Record r=byPlanId.get(planId);if(r==null)return null;
        if(r.terminalAt<=0){r.terminalAt=at;r.terminalStatus=status==null?"":status;}
        return r.copy();
    }

    public synchronized Record findOverlap(String signature,String movementKey){
        String id=!empty(signature)?planIdBySignature.get(signature):null;
        if(id==null&&!empty(movementKey))id=planIdByMovement.get(movementKey);
        Record r=id==null?null:byPlanId.get(id);return r==null?null:r.copy();
    }

    /** @return true only for the first public-overlap transition. */
    public synchronized boolean markPublicOverlap(String planId){Record r=byPlanId.get(planId);
        if(r==null||r.publicOverlap)return false;r.publicOverlap=true;return true;}
    public synchronized boolean markHigherPriorityOverlap(String planId){Record r=byPlanId.get(planId);
        if(r==null||r.higherPriorityOverlap)return false;r.higherPriorityOverlap=true;return true;}

    public synchronized void reset(){order.clear();byPlanId.clear();planIdBySignature.clear();
        planIdByMovement.clear();evicted=0;}
    public synchronized int rememberedOpenedRecords(){return byPlanId.size();}
    public synchronized int rememberedMovementKeys(){return planIdByMovement.size();}
    public synchronized int rememberedSignatures(){return planIdBySignature.size();}
    public synchronized long evictedMovementRecords(){return evicted;}
    public synchronized List<Record> records(){List<Record> out=new ArrayList<>();
        for(Record r:byPlanId.values())out.add(r.copy());return out;}
    public synchronized Map<String,Object> snapshotStats(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        int terminals=0;for(Record r:byPlanId.values())if(r.terminalAt>0)terminals++;
        out.put("dedupCapacity",CAPACITY);out.put("dedupCapacityReached",byPlanId.size()>=CAPACITY);
        out.put("evictedMovementRecords",evicted);out.put("rememberedOpenedRecords",byPlanId.size());
        out.put("rememberedMovementKeys",planIdByMovement.size());out.put("rememberedSignatures",planIdBySignature.size());
        out.put("terminalShadowRecords",terminals);out.put("activeShadowPlans",byPlanId.size()-terminals);return out;}

    private void removePlan(String id){Record old=byPlanId.remove(id);if(old==null)return;
        order.remove(id);if(id.equals(planIdBySignature.get(old.candidateSignature)))planIdBySignature.remove(old.candidateSignature);
        if(id.equals(planIdByMovement.get(old.movementKey)))planIdByMovement.remove(old.movementKey);}
    private static boolean empty(String value){return value==null||value.isEmpty();}

    public static final class Record{
        public final String shadowPlanId,candidateSignature,movementKey,symbol,side,family,component;
        public final long openedAt;public long terminalAt;public String terminalStatus="";
        public boolean publicOverlap,higherPriorityOverlap;
        private Record(String id,String signature,String movement,String symbol,String side,String family,
                       String component,long openedAt,boolean higher){shadowPlanId=id;candidateSignature=signature;
            movementKey=movement;this.symbol=symbol;this.side=side;this.family=family==null?"":family;
            this.component=component;this.openedAt=openedAt;higherPriorityOverlap=higher;}
        private Record copy(){Record c=new Record(shadowPlanId,candidateSignature,movementKey,symbol,side,
                family,component,openedAt,higherPriorityOverlap);c.terminalAt=terminalAt;
            c.terminalStatus=terminalStatus;c.publicOverlap=publicOverlap;return c;}
    }
}
