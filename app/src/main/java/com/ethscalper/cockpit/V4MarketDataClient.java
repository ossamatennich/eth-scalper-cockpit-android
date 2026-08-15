package com.ethscalper.cockpit;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Lightweight V4 transport: cached daily bars, exchange metadata, and bookTicker only. */
public final class V4MarketDataClient {
    public interface Listener {void onQuote(String asset,Quote quote);void onDailyReady(Map<String,List<V4DailyBar>> panel);void onState(String state);}
    public static final class Quote {public final double bid,ask;public final long receivedAt;Quote(double b,double a,long t){bid=b;ask=a;receivedAt=t;}public double mid(){return (bid+ask)/2;}}
    private static final String REST="https://fapi.binance.com";
    private final Context context;private final Listener listener;private final OkHttpClient http;private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Map<String,List<V4DailyBar>> panel=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String,V4MarketMetadata> metadata=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String,Quote> quotes=Collections.synchronizedMap(new LinkedHashMap<>());private volatile WebSocket socket;
    private volatile boolean socketConnected,dailySyncInProgress=true,quoteConfirmed,stopped;
    private volatile long lastQuoteAt,lastSocketFailureAt;
    public V4MarketDataClient(Context c,Listener l){context=c.getApplicationContext();listener=l;http=new OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS)
            .readTimeout(25,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS).retryOnConnectionFailure(true).build();loadCache();}
    public void start(){stopped=false;connectQuotes();io.execute(()->{refreshMetadata();refreshDaily();});}
    public void refreshDailyAsync(){if(!io.isShutdown())io.execute(this::refreshDaily);}
    public void stop(){stopped=true;socketConnected=false;if(socket!=null)socket.close(1000,"stop");io.shutdownNow();}
    public Map<String,List<V4DailyBar>> panelSnapshot(){synchronized(panel){return new LinkedHashMap<>(panel);}}
    public V4MarketMetadata metadata(String asset){return metadata.get(asset);}
    public Quote quote(String asset){return quotes.get(asset);}
    public boolean socketConnected(){return socketConnected;}
    public boolean dailySyncInProgress(){return dailySyncInProgress;}
    public long lastQuoteAt(){return lastQuoteAt;}
    public long lastSocketFailureAt(){return lastSocketFailureAt;}
    public static String bookTickerUrl(){StringBuilder b=new StringBuilder("wss://fstream.binance.com/public/stream?streams=");
        for(String a:V4Universe.ASSETS){if(b.charAt(b.length()-1)!='=')b.append('/');b.append(a.toLowerCase(Locale.ROOT)).append("usdt@bookTicker");}return b.toString();}
    private void connectQuotes(){socketConnected=false;quoteConfirmed=false;listener.onState("SYNCHRO");socket=http.newWebSocket(new Request.Builder().url(bookTickerUrl()).build(),new WebSocketListener(){
        @Override public void onOpen(WebSocket w,Response r){socketConnected=true;listener.onState("SYNCHRO");}
        @Override public void onMessage(WebSocket w,String text){try{JSONObject root=new JSONObject(text),d=root.optJSONObject("data");if(d==null)return;
            String symbol=d.optString("s"),asset=symbol.endsWith("USDT")?symbol.substring(0,symbol.length()-4):"";if(!V4Universe.supports(asset))return;
            double bid=d.getDouble("b"),ask=d.getDouble("a");if(!(bid>0&&ask>=bid))return;long now=System.currentTimeMillis();Quote q=new Quote(bid,ask,now);quotes.put(asset,q);lastQuoteAt=now;
            if(!quoteConfirmed){quoteConfirmed=true;listener.onState("ACTIF");}listener.onQuote(asset,q);
        }catch(Exception ignored){}}
        @Override public void onClosed(WebSocket w,int code,String reason){socketConnected=false;quoteConfirmed=false;if(!stopped)listener.onState("HORS LIGNE");}
        @Override public void onFailure(WebSocket w,Throwable t,Response r){socketConnected=false;quoteConfirmed=false;lastSocketFailureAt=System.currentTimeMillis();listener.onState("HORS LIGNE");io.execute(()->{try{Thread.sleep(5000);}catch(InterruptedException ignored){}if(!io.isShutdown())connectQuotes();});}});}
    private void loadCache(){File dir=new File(context.getFilesDir(),"v4_daily");for(String asset:V4Universe.ASSETS){File f=new File(dir,asset+".json");if(!f.isFile())continue;
        try(FileInputStream in=new FileInputStream(f)){JSONArray a=new JSONArray(new String(readAll(in),StandardCharsets.UTF_8));ArrayList<V4DailyBar> bars=new ArrayList<>();for(int i=0;i<a.length();i++)bars.add(V4DailyBar.fromBinance(a.getJSONArray(i)));panel.put(asset,bars);}catch(Exception ignored){}}}
    private void refreshDaily(){dailySyncInProgress=true;listener.onState("SYNCHRO");long now=System.currentTimeMillis(),start=1672531200000L;
        try{for(String asset:V4Universe.ASSETS){if(Thread.currentThread().isInterrupted())return;
            try{String url=REST+"/fapi/v1/klines?symbol="+V4Universe.binanceSymbol(asset)+"&interval=1d&startTime="+start+"&limit=1500";
                try(Response response=http.newCall(new Request.Builder().url(url).build()).execute()){if(!response.isSuccessful()||response.body()==null)continue;JSONArray raw=new JSONArray(response.body().string());
                    ArrayList<V4DailyBar> bars=new ArrayList<>();long previous=-1;for(int i=0;i<raw.length();i++){JSONArray row=raw.getJSONArray(i);long at=row.getLong(0);if(at<=previous||at+86_400_000L>now)continue;
                        V4DailyBar bar=V4DailyBar.fromBinance(row);bars.add(bar);previous=at;}if(!bars.isEmpty()){panel.put(asset,bars);writeCache(asset,raw,now);}}}catch(Exception ignored){}}}
        finally{dailySyncInProgress=false;listener.onDailyReady(panelSnapshot());}}
    private void writeCache(String asset,JSONArray raw,long now)throws Exception{JSONArray complete=new JSONArray();for(int i=0;i<raw.length();i++)if(raw.getJSONArray(i).getLong(0)+86_400_000L<=now)complete.put(raw.getJSONArray(i));
        File dir=new File(context.getFilesDir(),"v4_daily");if(!dir.exists()&&!dir.mkdirs())return;try(FileOutputStream out=new FileOutputStream(new File(dir,asset+".json"))){out.write(complete.toString().getBytes(StandardCharsets.UTF_8));}}
    private void refreshMetadata(){try(Response response=http.newCall(new Request.Builder().url(REST+"/fapi/v1/exchangeInfo").build()).execute()){
        if(!response.isSuccessful()||response.body()==null)return;JSONArray symbols=new JSONObject(response.body().string()).getJSONArray("symbols");for(int i=0;i<symbols.length();i++){JSONObject s=symbols.getJSONObject(i);
            String raw=s.getString("symbol"),asset=raw.endsWith("USDT")?raw.substring(0,raw.length()-4):"";if(!V4Universe.supports(asset)||!"TRADING".equals(s.optString("status")))continue;
            double tick=0,step=0,minQty=0,minNotional=0;JSONArray fs=s.getJSONArray("filters");for(int j=0;j<fs.length();j++){JSONObject f=fs.getJSONObject(j);switch(f.getString("filterType")){
                case "PRICE_FILTER"->tick=f.optDouble("tickSize");case "LOT_SIZE"->{step=f.optDouble("stepSize");minQty=f.optDouble("minQty");}
                case "MIN_NOTIONAL"->minNotional=f.optDouble("notional");}}
            if(tick>0&&step>0)metadata.put(asset,new V4MarketMetadata(tick,step,minQty,minNotional));}}
        catch(Exception ignored){}}
    public List<V4PlanLifecycle.PricePoint> fetchMinutePath(String asset,long from,long to){ArrayList<V4PlanLifecycle.PricePoint> out=new ArrayList<>();long cursor=from;
        while(cursor<to&&out.size()<2000){String url=REST+"/fapi/v1/klines?symbol="+V4Universe.binanceSymbol(asset)+"&interval=1m&startTime="+cursor+"&endTime="+to+"&limit=1000";
            try(Response r=http.newCall(new Request.Builder().url(url).build()).execute()){if(!r.isSuccessful()||r.body()==null)break;JSONArray a=new JSONArray(r.body().string());if(a.length()==0)break;
                for(int i=0;i<a.length();i++){JSONArray b=a.getJSONArray(i);out.add(new V4PlanLifecycle.PricePoint(b.getLong(0),b.getDouble(1),b.getDouble(2),b.getDouble(3),b.getDouble(4),0,0));}
                cursor=a.getJSONArray(a.length()-1).getLong(0)+60_000L;}catch(Exception e){break;}}return out;}
    private static byte[] readAll(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);return out.toByteArray();}
}
