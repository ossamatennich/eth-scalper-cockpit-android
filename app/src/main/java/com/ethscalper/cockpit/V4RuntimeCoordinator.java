package com.ethscalper.cockpit;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application-scoped V4 runtime. It cannot place an order; all actions are local declarations. */
public final class V4RuntimeCoordinator implements V4MarketDataClient.Listener {
    public static final String ACTION_CHANGED="com.ethscalper.cockpit.V4_CHANGED";
    private static volatile V4RuntimeCoordinator instance;
    public static synchronized V4RuntimeCoordinator start(Context c){if(instance==null)instance=new V4RuntimeCoordinator(c.getApplicationContext());instance.startInternal();return instance;}
    public static V4RuntimeCoordinator get(){return instance;}
    private final Context context;private final V4PlanStore store;private final V4AccountProfile account;private final V4MarketDataClient market;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();private final V4Engine engine;
    private volatile String scannerState="SYNCHRO",lastError="";private volatile long lastAnalysisAt;private volatile int dataEligible;
    private volatile Map<String,V4FeatureEngine.Snapshot> snapshots=new LinkedHashMap<>();private volatile V4FeatureEngine.Candidate pending;
    private volatile V4FeatureEngine.Candidate continuation;private volatile String continuationParent;
    private boolean started,historySeeded;private volatile long lastQuoteTickScheduled;
    private V4RuntimeCoordinator(Context c){context=c;store=new V4PlanStore(c);account=new V4AccountProfile(c);V4ExtraTreesModel m;
        try{m=V4ExtraTreesModel.load(c.getAssets());}catch(Exception e){throw new IllegalStateException("Frozen V4 model unavailable",e);}
        android.content.SharedPreferences hp=c.getSharedPreferences("v4_fallback_daily_history",Context.MODE_PRIVATE);
        V4FallbackHistory history=new V4FallbackHistory(new V4FallbackHistory.Backend(){public String load(){return hp.getString("records","[]");}
            public void save(String json){hp.edit().putString("records",json).commit();}});
        engine=new V4Engine(m,history);market=new V4MarketDataClient(c,this);}
    private synchronized void startInternal(){if(started)return;started=true;ensureChannel();market.start();scheduler.scheduleAtFixedRate(this::tick,2,15,TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(market::refreshDailyAsync,6,6,TimeUnit.HOURS);}
    public synchronized void stop(){market.stop();scheduler.shutdownNow();started=false;instance=null;}
    @Override public void onQuote(String asset,V4MarketDataClient.Quote quote){long now=System.currentTimeMillis();if(now-lastQuoteTickScheduled>1000){lastQuoteTickScheduled=now;scheduler.execute(this::tick);}}
    @Override public void onState(String state){scannerState=state;publishChanged();}
    @Override public void onDailyReady(Map<String,List<V4DailyBar>> panel){scheduler.execute(()->analyse(panel));}
    private void analyse(Map<String,List<V4DailyBar>> panel){try{long cutoff=V4FeatureEngine.latestCutoff(panel);if(!historySeeded){seedHistory(panel,cutoff);historySeeded=true;}
        snapshots=V4FeatureEngine.computeAt(panel,cutoff);dataEligible=snapshots.size();
        pending=engine.select(snapshots);lastAnalysisAt=System.currentTimeMillis();lastError="";tick();}catch(Exception e){lastError=e.getClass().getSimpleName();}}
    private void seedHistory(Map<String,List<V4DailyBar>> panel,long currentCutoff){for(int back=90;back>=1;back--){long day=currentCutoff-back*86_400_000L;
        try{engine.observePrior(V4FeatureEngine.computeAt(panel,day));}catch(Exception ignored){}}}
    private synchronized void tick(){long now=System.currentTimeMillis();for(V4Plan p:store.active()){V4MarketDataClient.Quote q=market.quote(p.symbol);if(q==null)continue;
        V4MarketMetadata meta=market.metadata(p.symbol);if(p.status==V4Plan.Status.DATA_UNAVAILABLE){if(meta==null){if(now>=p.expiresAt){p.status=V4Plan.Status.EXPIRED;p.closedAt=now;p.statusReason="Fenêtre d'entrée terminée";store.save(p);}continue;}
            double risk=Math.min(p.allocatedRiskFraction,V4RiskSizer.remainingRisk(account.equity(),store.active()));
            V4RiskSizer.Result sized=V4RiskSizer.size(p.symbol,account.equity(),p.entry,p.sl,risk,meta);if(!sized.available){V4CreationPolicy.rejectForRiskCap(p,now);store.save(p);continue;}
            p.restoreUncommittedQuantity(sized.quantity);p.status=V4Plan.Status.WAITING;p.statusReason="Métadonnées de marché restaurées";}
        List<V4PlanLifecycle.PricePoint> path=new ArrayList<>();long from=p.lastEvaluatedAt>0?p.lastEvaluatedAt:p.createdAt;
        if(now-from>120_000L)path.addAll(market.fetchMinutePath(p.symbol,from,now));
        path.add(new V4PlanLifecycle.PricePoint(now,q.mid(),q.ask,q.bid,q.mid(),q.bid,q.ask));V4Plan.Status before=p.status;
        V4PlanLifecycle.evaluate(p,path,now,meta);
        if(p.status==V4Plan.Status.OPEN&&now>=p.expiresAt){p.status=V4Plan.Status.CLOSED_OTHER;p.closedAt=now;p.closePrice=q.mid();p.closeReason="Clôture avant reset";p.statusReason="Segment terminé avant reset";
            if(V4ContinuationPolicy.mayCreateSecondSegment(p)){V4FeatureEngine.Snapshot s=snapshots.get(p.symbol);if(s!=null){continuation=new V4FeatureEngine.Candidate(V4Plan.Source.CORE,p.symbol,p.side,s.atr,0,0);continuationParent=p.planId;}}}
        store.save(p);if(p.status!=before){if(p.status==V4Plan.Status.CLOSED_TP||p.status==V4Plan.Status.CLOSED_SL||p.status==V4Plan.Status.CLOSED_OTHER)account.applyClosedPlan(p);notifyTransition(p,before);}}
        if(V4Engine.afterActivation(now)){if(V4ContinuationPolicy.freshWins(pending,continuation)){replaceContinuationParent();continuation=null;continuationParent=null;}
            if(pending!=null&&canCreateFresh(pending)){createPending(pending,null);pending=null;}
            if(continuation!=null&&canCreateContinuation(continuation)){createPending(continuation,continuationParent);continuation=null;continuationParent=null;}}publishChanged();}
    private boolean canCreateFresh(V4FeatureEngine.Candidate c){V4FeatureEngine.Snapshot s=snapshots.get(c.asset);return market.quote(c.asset)!=null&&s!=null
            &&V4CreationPolicy.mayCreateFresh(c,s.cutoff,store.active(),store.all());}
    private boolean canCreateContinuation(V4FeatureEngine.Candidate c){V4Plan parent=store.find(continuationParent);return market.quote(c.asset)!=null
            &&V4CreationPolicy.mayCreateContinuation(c,parent,store.active(),store.all(),pending);}
    private void replaceContinuationParent(){V4Plan parent=store.find(continuationParent);if(parent!=null){parent.status=V4Plan.Status.CLOSED_OTHER;
        parent.statusReason="REPLACED_BY_FRESH_SIGNAL";parent.closeReason="REPLACED_BY_FRESH_SIGNAL";store.save(parent);}}
    private void createPending(V4FeatureEngine.Candidate c,String parent){V4MarketDataClient.Quote q=market.quote(c.asset);V4MarketMetadata meta=market.metadata(c.asset);if(q==null)return;
        double desired=c.source==V4Plan.Source.CORE?V4RiskSizer.CORE_RISK:V4RiskSizer.FALLBACK_RISK;
        desired=Math.min(desired,V4RiskSizer.remainingRisk(account.equity(),store.active()));
        double entry=c.side==V4Plan.Side.LONG?q.ask:q.bid,sl=entry+(c.side==V4Plan.Side.LONG?-1:1)*(c.source==V4Plan.Source.CORE?.4:1)*c.atr;
        V4RiskSizer.Result size=V4RiskSizer.size(c.asset,account.equity(),entry,sl,desired,meta);
        long cutoff=snapshots.get(c.asset).cutoff;V4Plan p=engine.create(c,entry,size.available?size.quantity:0,account.equity(),desired,cutoff,parent);
        if(!size.available){if(meta==null){p.status=V4Plan.Status.DATA_UNAVAILABLE;p.statusReason="Métadonnées de quantité indisponibles";}
            else V4CreationPolicy.rejectForRiskCap(p,System.currentTimeMillis());store.save(p);return;}store.save(p);
        V4PlanLifecycle.evaluate(p,List.of(new V4PlanLifecycle.PricePoint(System.currentTimeMillis(),q.mid(),q.ask,q.bid,q.mid(),q.bid,q.ask)),System.currentTimeMillis(),meta);store.save(p);notifyTransition(p,V4Plan.Status.WAITING);}
    public synchronized void markOrder(String id){V4Plan p=store.find(id);if(p!=null){V4PlanLifecycle.markOrderPlaced(p,System.currentTimeMillis());store.save(p);publishChanged();}}
    public synchronized void markTaken(String id){V4Plan p=store.find(id);if(p!=null){V4PlanLifecycle.markTaken(p,System.currentTimeMillis());store.save(p);publishChanged();}}
    public synchronized void manualClose(String id,double price){V4Plan p=store.find(id);if(p!=null){V4PlanLifecycle.manualClose(p,System.currentTimeMillis(),price);store.save(p);account.applyClosedPlan(p);publishChanged();}}
    public V4PlanStore store(){return store;}public V4AccountProfile account(){return account;}
    public JSONObject status(){try{JSONObject o=new JSONObject();o.put("engineId",V4Universe.ENGINE_ID);o.put("engineVersion",V4Plan.ENGINE_VERSION);o.put("scannerState",scannerState);
        o.put("lastAnalysisAt",lastAnalysisAt);o.put("marketsConfigured",53);o.put("dataEligibleAssets",dataEligible);o.put("lastError",lastError);o.put("realTradingAllowed",false);
        JSONArray active=new JSONArray(),history=new JSONArray();for(V4Plan p:store.all()){if(p.terminal())history.put(p.toJson());else active.put(p.toJson());}o.put("activePlans",active);o.put("history",history);
        o.put("accountMode",account.mode().name());o.put("trackedEquity",account.equity());o.put("evaluationTarget",account.target());return o;}catch(Exception e){throw new IllegalStateException("V4 status",e);}}
    private void publishChanged(){context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));}
    private void ensureChannel(){NotificationManager nm=context.getSystemService(NotificationManager.class);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel("nmc_v4_plans","Plans V4",NotificationManager.IMPORTANCE_HIGH));}
    private void notifyTransition(V4Plan p,V4Plan.Status before){if(p.status==before)return;boolean useful=p.status==V4Plan.Status.EXECUTABLE||p.status==V4Plan.Status.OPEN||p.status==V4Plan.Status.INVALIDATED||p.status==V4Plan.Status.EXPIRED||p.status==V4Plan.Status.CLOSED_TP||p.status==V4Plan.Status.CLOSED_SL;
        if(!useful)return;Intent i=new Intent(context,V4MainActivity.class);PendingIntent pi=PendingIntent.getActivity(context,40,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String title=p.symbol+" "+p.side.name()+" — "+V4Plan.french(p.status),text="Qté "+fmt(p.quantity())+" · Entry "+fmt(p.entry)+" · TP "+fmt(p.tp)+" · SL "+fmt(p.sl);
        context.getSystemService(NotificationManager.class).notify(("v4:"+p.planId+":"+p.status).hashCode(),new NotificationCompat.Builder(context,"nmc_v4_plans").setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true).build());}
    private static String fmt(double v){return new DecimalFormat("0.########").format(v);}
}
