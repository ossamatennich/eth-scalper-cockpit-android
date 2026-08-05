package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnostics-only monitor for legacy plans that would previously have been public. */
public final class LegacyPublicComparator {
    private final LinkedHashMap<String,Entry> active=new LinkedHashMap<>();
    private long opened,tp,sl,skipped;
    public synchronized boolean open(ActivePlanState plan,long now){return openResult(plan,now).opened;}
    public synchronized OpenResult openResult(ActivePlanState plan,long now){
        if(plan==null)return new OpenResult(false,"LEGACY_COMPARATOR_INVALID_PLAN");
        if(active.containsKey(plan.symbol)){skipped++;return new OpenResult(false,"LEGACY_COMPARATOR_SKIPPED_ACTIVE");}
        active.put(plan.symbol,new Entry(plan,now));opened++;return new OpenResult(true,"");}
    public synchronized Terminal observe(String symbol,long now,double bid,double ask,boolean fresh){Entry e=active.get(symbol);
        if(e==null||!fresh||!valid(bid,ask))return null;double q="LONG".equals(e.plan.side)?bid:ask;
        boolean stop="LONG".equals(e.plan.side)?q<=e.plan.stopLoss:q>=e.plan.stopLoss;
        boolean target="LONG".equals(e.plan.side)?q>=e.plan.takeProfit:q<=e.plan.takeProfit;
        if(!stop&&!target)return null;String status=stop?ScalpActionPlan.SL:ScalpActionPlan.TP;active.remove(symbol);if(stop)sl++;else tp++;
        double distance=stop?-e.plan.stopDistance:e.plan.targetMove;double fees=e.plan.quantity*Math.max(0,e.plan.resultCostPerUnit);
        return new Terminal(e.plan,status,now,q,e.plan.quantity*distance-fees);}
    public synchronized Map<String,Object> snapshot(){Map<String,Object> m=new LinkedHashMap<>();m.put("opened",opened);m.put("tp",tp);m.put("sl",sl);m.put("skippedActive",skipped);m.put("active",active.size());return m;}
    public synchronized void reset(){active.clear();opened=tp=sl=skipped=0;}
    private static boolean valid(double b,double a){return Double.isFinite(b)&&b>0&&Double.isFinite(a)&&a>0&&a>=b;}
    private static final class Entry{final ActivePlanState plan;final long openedAt;Entry(ActivePlanState p,long at){plan=p;openedAt=at;}}
    public static final class OpenResult{public final boolean opened;public final String reasonCode;
        OpenResult(boolean opened,String reasonCode){this.opened=opened;this.reasonCode=reasonCode;}}
    public static final class Terminal{public final ActivePlanState plan;public final String status;public final long at;public final double touchQuote,netResultUsdt;
        Terminal(ActivePlanState p,String s,long a,double q,double n){plan=p;status=s;at=a;touchQuote=q;netResultUsdt=n;}}
}
