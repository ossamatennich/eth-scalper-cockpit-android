package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent frozen portfolio: one economic group per symbol, two simultaneous SOL branches. */
public final class FrozenProfitabilityShadowPortfolio {
    private final LinkedHashMap<String,Group> active=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> lastResolvedAt=new LinkedHashMap<>();

    public synchronized boolean canOpen(String symbol,long now){
        if(active.containsKey(symbol))return false;
        long last=lastResolvedAt.getOrDefault(symbol,0L);
        return last==0||now-last>=FrozenProfitabilityShadowPolicy.COOLDOWN_MS;
    }
    public synchronized long cooldownRemaining(String symbol,long now){
        long last=lastResolvedAt.getOrDefault(symbol,0L);
        return last==0?0:Math.max(0,FrozenProfitabilityShadowPolicy.COOLDOWN_MS-(now-last));
    }
    public synchronized boolean open(Group group){
        if(group==null||group.branches.isEmpty()||active.containsKey(group.symbol))return false;
        active.put(group.symbol,group);return true;
    }
    public synchronized Group active(String symbol){return active.get(symbol);}
    public synchronized int activeGroups(){return active.size();}
    public synchronized int unresolvedBranches(){int n=0;for(Group g:active.values())for(FrozenProfitabilityShadowPlan p:g.branches)if(!p.terminal())n++;return n;}

    public synchronized List<TerminalEvent> observe(String symbol,long now,double bid,double ask,boolean marketFresh){
        Group group=active.get(symbol);List<TerminalEvent> out=new ArrayList<>();if(group==null)return out;
        for(FrozenProfitabilityShadowPlan plan:group.branches){
            FrozenProfitabilityShadowPlan.Terminal terminal=plan.observe(now,bid,ask,marketFresh);
            if(terminal!=null)out.add(new TerminalEvent(group,plan,terminal));
        }
        boolean resolved=true;for(FrozenProfitabilityShadowPlan p:group.branches)if(!p.terminal())resolved=false;
        if(resolved){active.remove(symbol);lastResolvedAt.put(symbol,now);group.resolvedAt=now;}
        return out;
    }
    public synchronized Group markPublicOverlap(String symbol){
        Group group=active.get(symbol);if(group==null||group.publicOverlap)return null;
        group.publicOverlap=true;for(FrozenProfitabilityShadowPlan p:group.branches)p.markPublicOverlap();return group;
    }
    public synchronized List<FrozenProfitabilityShadowPlan> reset(){
        List<FrozenProfitabilityShadowPlan> aborted=new ArrayList<>();
        for(Group g:active.values())for(FrozenProfitabilityShadowPlan p:g.branches)if(!p.terminal())aborted.add(p);
        active.clear();lastResolvedAt.clear();return aborted;
    }
    public synchronized Map<String,Group> activeSnapshot(){return new LinkedHashMap<>(active);}

    public static final class Group {
        public final String opportunityId,movementSignature,symbol;public final long openedAt;
        public final List<FrozenProfitabilityShadowPlan> branches;public boolean publicOverlap;
        public long resolvedAt;
        public Group(String id,String signature,String symbol,long opened,List<FrozenProfitabilityShadowPlan> plans){
            opportunityId=id;movementSignature=signature;this.symbol=symbol;openedAt=opened;
            branches=List.copyOf(plans);
        }
    }
    public static final class TerminalEvent {
        public final Group group;public final FrozenProfitabilityShadowPlan plan;
        public final FrozenProfitabilityShadowPlan.Terminal terminal;
        TerminalEvent(Group g,FrozenProfitabilityShadowPlan p,FrozenProfitabilityShadowPlan.Terminal t){group=g;plan=p;terminal=t;}
    }
}
