package com.ethscalper.cockpit;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.media.AudioManager;
import android.net.Uri;
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
    private final V4NotificationLedger notificationLedger;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();private final V4Engine engine;
    private volatile String transportState="SYNCHRO",lastError="";private volatile long lastAnalysisAt,lastErrorAt;private volatile int dataEligible;
    private volatile Map<String,V4FeatureEngine.Snapshot> snapshots=new LinkedHashMap<>();private volatile V4FeatureEngine.Candidate pending;
    private volatile V4FeatureEngine.Candidate continuation;private volatile String continuationParent;
    private boolean started,historySeeded;private volatile long lastQuoteTickScheduled;
    private V4RuntimeCoordinator(Context c){context=c;store=new V4PlanStore(c);account=new V4AccountProfile(c);V4ExtraTreesModel m;
        try{m=V4ExtraTreesModel.load(c.getAssets());}catch(Exception e){throw new IllegalStateException("Frozen V4 model unavailable",e);}
        android.content.SharedPreferences hp=c.getSharedPreferences("v4_fallback_daily_history",Context.MODE_PRIVATE);
        V4FallbackHistory history=new V4FallbackHistory(new V4FallbackHistory.Backend(){public String load(){return hp.getString("records","[]");}
            public void save(String json){hp.edit().putString("records",json).commit();}});
        android.content.SharedPreferences np=c.getSharedPreferences("v4_loud_notification_events",Context.MODE_PRIVATE);
        notificationLedger=new V4NotificationLedger(new V4NotificationLedger.Backend(){public String load(){return np.getString("events","[]");}
            public boolean save(String json){return np.edit().putString("events",json).commit();}});
        if(!np.getBoolean("legacy_statuses_seeded",false)){for(V4Plan p:store.all())seedExistingNotificationState(p);np.edit().putBoolean("legacy_statuses_seeded",true).commit();}
        engine=new V4Engine(m,history);market=new V4MarketDataClient(c,this);}
    private synchronized void startInternal(){if(started)return;started=true;V4NotificationChannels.ensure(context);market.start();scheduler.scheduleAtFixedRate(this::tick,2,15,TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(market::refreshDailyAsync,6,6,TimeUnit.HOURS);}
    public synchronized void stop(){market.stop();scheduler.shutdownNow();started=false;instance=null;}
    @Override public void onQuote(String asset,V4MarketDataClient.Quote quote){long now=System.currentTimeMillis();if(now-lastQuoteTickScheduled>1000){lastQuoteTickScheduled=now;scheduler.execute(this::tick);}}
    @Override public void onState(String state){transportState=state;publishChanged();}
    @Override public void onDailyReady(Map<String,List<V4DailyBar>> panel){scheduler.execute(()->analyse(panel));}
    private void analyse(Map<String,List<V4DailyBar>> panel){try{long cutoff=V4FeatureEngine.latestCutoff(panel);if(!historySeeded){seedHistory(panel,cutoff);historySeeded=true;}
        snapshots=V4FeatureEngine.computeAt(panel,cutoff);dataEligible=snapshots.size();
        pending=engine.select(snapshots);lastAnalysisAt=System.currentTimeMillis();lastError="";lastErrorAt=0;tick();}catch(Exception e){lastError=e.getClass().getSimpleName();lastErrorAt=System.currentTimeMillis();publishChanged();}}
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
        store.save(p);if(p.status!=before){if(p.status==V4Plan.Status.CLOSED_TP||p.status==V4Plan.Status.CLOSED_SL||p.status==V4Plan.Status.CLOSED_OTHER)account.applyClosedPlan(p);notifyTransition(p,before);}
        else notifyUndeliveredActiveState(p);}for(V4Plan p:store.all())notifyUndeliveredTerminalState(p);
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
    public JSONObject status(){try{long now=System.currentTimeMillis();boolean network=networkAvailable();V4RuntimeStatusPolicy.State state=V4RuntimeStatusPolicy.evaluate(
            new V4RuntimeStatusPolicy.Input(now,network,market.socketConnected(),transportState,market.lastQuoteAt(),market.dailySyncInProgress(),
                    lastAnalysisAt,dataEligible,lastError,lastErrorAt));JSONObject o=new JSONObject();o.put("engineId",V4Universe.ENGINE_ID);o.put("engineVersion",V4Plan.ENGINE_VERSION);o.put("scannerState",state.name().replace('_',' '));
        o.put("networkAvailable",network);o.put("priceSocketConnected",market.socketConnected());o.put("lastBookTickerAt",market.lastQuoteAt());
        o.put("lastBookTickerAgeMs",V4RuntimeStatusPolicy.quoteAgeMs(now,market.lastQuoteAt()));o.put("dailySyncInProgress",market.dailySyncInProgress());
        o.put("analysisHealthy",lastAnalysisAt>0&&lastError.isEmpty()&&dataEligible>0);o.put("lastAnalysisAt",lastAnalysisAt);o.put("marketsConfigured",53);o.put("dataEligibleAssets",dataEligible);
        o.put("lastError",lastError);o.put("lastErrorAt",lastErrorAt);o.put("lastSocketFailureAt",market.lastSocketFailureAt());o.put("realTradingAllowed",false);
        JSONArray active=new JSONArray(),history=new JSONArray();for(V4Plan p:store.all()){if(p.terminal())history.put(p.toJson());else active.put(p.toJson());}o.put("activePlans",active);o.put("history",history);
        o.put("accountMode",account.mode().name());o.put("trackedEquity",account.equity());o.put("evaluationTarget",account.target());return o;}catch(Exception e){throw new IllegalStateException("V4 status",e);}}
    private boolean networkAvailable(){try{ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager==null)return false;Network active=manager.getActiveNetwork();
        NetworkCapabilities capabilities=active==null?null:manager.getNetworkCapabilities(active);return capabilities!=null
                &&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                &&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
        catch(Exception ignored){return false;}}
    private void publishChanged(){context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));}
    private void seedExistingNotificationState(V4Plan p){V4NotificationPolicy.Event event=currentActiveNotificationEvent(p);
        if(event!=V4NotificationPolicy.Event.NONE)notificationLedger.claim(p.planId,event);
        if(p.status==V4Plan.Status.CLOSED_TP)notificationLedger.claim(p.planId,V4NotificationPolicy.Event.TP);
        if(p.status==V4Plan.Status.CLOSED_SL)notificationLedger.claim(p.planId,V4NotificationPolicy.Event.SL);}
    private void notifyUndeliveredActiveState(V4Plan p){V4NotificationPolicy.Event event=currentActiveNotificationEvent(p);if(event!=V4NotificationPolicy.Event.NONE)notifyEvent(p,event);}
    private void notifyUndeliveredTerminalState(V4Plan p){if(p.status==V4Plan.Status.CLOSED_TP)notifyEvent(p,V4NotificationPolicy.Event.TP);
        else if(p.status==V4Plan.Status.CLOSED_SL)notifyEvent(p,V4NotificationPolicy.Event.SL);}
    private static V4NotificationPolicy.Event currentActiveNotificationEvent(V4Plan p){if(p.status==V4Plan.Status.LIMIT_ORDER_POSSIBLE||p.status==V4Plan.Status.EXECUTABLE)return V4NotificationPolicy.Event.ACTIONABLE;
        if(p.status==V4Plan.Status.OPEN&&"ORDER_PLACED".equals(p.userFollowState))return V4NotificationPolicy.Event.ENTRY_FILLED;return V4NotificationPolicy.Event.NONE;}
    private void notifyTransition(V4Plan p,V4Plan.Status before){
        V4NotificationPolicy.Event event=V4NotificationPolicy.event(before,p.status);if(event==V4NotificationPolicy.Event.NONE)return;
        notifyEvent(p,event);}
    private void notifyEvent(V4Plan p,V4NotificationPolicy.Event event){
        V4NotificationChannels.ensure(context);NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null||!manager.areNotificationsEnabled()||(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED))return;
        if(!notificationLedger.claim(p.planId,event))return;
        try{
            Intent i=new Intent(context,V4MainActivity.class).putExtra("v4_plan_id",p.planId).putExtra("v4_open_plans",true);
            PendingIntent pi=PendingIntent.getActivity(context,p.planId.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            V4NotificationPolicy.Message message=V4NotificationPolicy.message(p,event);
            Uri sound=Uri.parse("android.resource://"+context.getPackageName()+"/"+R.raw.eth_alert_loud);
            Notification notification=new NotificationCompat.Builder(context,V4NotificationChannels.LOUD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_nmc).setContentTitle(message.title).setContentText(message.body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message.body)).setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setSound(sound,AudioManager.STREAM_ALARM)
                    .setVibrate(V4NotificationChannels.ALERT_VIBRATION).setAutoCancel(true).setOnlyAlertOnce(false).build();
            manager.notify(("v4:"+p.planId+":"+event.name()).hashCode(),notification);
        }catch(RuntimeException error){notificationLedger.release(p.planId,event);}}
    private static String fmt(double v){return new DecimalFormat("0.########").format(v);}
}
