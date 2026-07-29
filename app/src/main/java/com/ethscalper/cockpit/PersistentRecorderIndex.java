package com.ethscalper.cockpit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small incremental index for the persistent recorder. JSONL scans are startup-only. */
public final class PersistentRecorderIndex {
    public static final String MODE = "PERSISTENT_INCREMENTAL_INDEX";
    private static final int FORMAT_VERSION = 1;
    private static final Pattern STRING_FIELD = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile("\\\"(eventAt|at|createdAt)\\\"\\s*:\\s*(\\d+)");

    private long eventCount, frameCount, firstAt, lastAt, eventBytes, frameBytes;
    private long confirmedTrades, restoredActivePlans, tp, sl, candidates, rejectedAdmissions;
    private long staleFreshTransitions, diskReads;
    private final LinkedHashMap<String,Long> bySymbol=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> byEventType=new LinkedHashMap<>();

    public synchronized void recordEvent(Map<String,?> event,long currentEventBytes,long currentFrameBytes) {
        if(event==null||"MARKET_FRAME".equals(string(event.get("eventType"))))return;
        eventCount++;recordCommon(event);String type=string(event.get("eventType"));
        increment(byEventType,type);if("PLAN_CONFIRMED".equals(type))confirmedTrades++;
        if("PLAN_RESTORED".equals(type))restoredActivePlans++;
        if("TP_TOUCHED".equals(type))tp++;if("SL_TOUCHED".equals(type))sl++;
        if(type.contains("CANDIDATE")||type.contains("P01")||type.contains("P02"))candidates++;
        if("ADMISSION_REJECTED".equals(type))rejectedAdmissions++;
        if(type.contains("FEED_")||type.contains("SOURCE_TRANSITION"))staleFreshTransitions++;
        eventBytes=Math.max(0,currentEventBytes);frameBytes=Math.max(0,currentFrameBytes);
    }

    public synchronized void recordFrame(Map<String,?> frame,long currentEventBytes,long currentFrameBytes) {
        if(frame==null)return;frameCount++;recordCommon(frame);
        eventBytes=Math.max(0,currentEventBytes);frameBytes=Math.max(0,currentFrameBytes);
    }

    private void recordCommon(Map<String,?> value) {
        long at=number(value.get("eventAt"),number(value.get("at"),number(value.get("createdAt"),0)));
        if(at>0){if(firstAt==0||at<firstAt)firstAt=at;if(at>lastAt)lastAt=at;}
        increment(bySymbol,string(value.get("symbol")));
    }

    public synchronized Map<String,Object> snapshot() {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();out.put("mode",MODE);
        out.put("formatVersion",FORMAT_VERSION);out.put("eventCount",eventCount);
        out.put("frameCount",frameCount);out.put("observationEvents",eventCount);
        out.put("marketFrames",frameCount);out.put("firstAt",firstAt);out.put("lastAt",lastAt);
        out.put("oldestAt",firstAt);out.put("newestAt",lastAt);
        out.put("durationSec",firstAt>0&&lastAt>firstAt?(lastAt-firstAt)/1000:0);
        out.put("eventFileBytes",eventBytes);out.put("frameFileBytes",frameBytes);
        out.put("observationFileBytes",eventBytes);out.put("marketFileBytes",frameBytes);
        out.put("confirmedTrades",confirmedTrades);out.put("restoredActivePlans",restoredActivePlans);
        out.put("tp",tp);out.put("sl",sl);out.put("candidates",candidates);
        out.put("rejectedAdmissions",rejectedAdmissions);
        out.put("staleFreshTransitions",staleFreshTransitions);
        out.put("bySymbol",new LinkedHashMap<>(bySymbol));out.put("byEventType",new LinkedHashMap<>(byEventType));
        out.put("startupJsonlDiskReads",diskReads);out.put("hotPathJsonlDiskReads",0);
        return Collections.unmodifiableMap(out);
    }

    public synchronized void reset() {
        eventCount=frameCount=firstAt=lastAt=eventBytes=frameBytes=0;
        confirmedTrades=restoredActivePlans=tp=sl=candidates=rejectedAdmissions=0;
        staleFreshTransitions=0;bySymbol.clear();byEventType.clear();
    }

    public synchronized void saveAtomic(File target) throws Exception {
        if(target==null)throw new IllegalArgumentException("target");File parent=target.getParentFile();
        if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("index directory");
        Properties p=new Properties();p.setProperty("formatVersion",String.valueOf(FORMAT_VERSION));
        put(p,"eventCount",eventCount);put(p,"frameCount",frameCount);put(p,"firstAt",firstAt);put(p,"lastAt",lastAt);
        put(p,"eventBytes",eventBytes);put(p,"frameBytes",frameBytes);put(p,"confirmedTrades",confirmedTrades);
        put(p,"restoredActivePlans",restoredActivePlans);put(p,"tp",tp);put(p,"sl",sl);
        put(p,"candidates",candidates);put(p,"rejectedAdmissions",rejectedAdmissions);
        put(p,"staleFreshTransitions",staleFreshTransitions);
        for(Map.Entry<String,Long> e:bySymbol.entrySet())put(p,"symbol."+e.getKey(),e.getValue());
        for(Map.Entry<String,Long> e:byEventType.entrySet())put(p,"type."+e.getKey(),e.getValue());
        File temporary=new File(target.getParentFile(),target.getName()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temporary,false)){p.store(output,"NMC recorder index");output.getFD().sync();}
        try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception ignored){Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }

    public static PersistentRecorderIndex loadOrRebuild(File index,File events,File frames) {
        PersistentRecorderIndex value=new PersistentRecorderIndex();
        if(value.load(index)){value.eventBytes=PersistentMarketLog.combinedBytes(events);
            value.frameBytes=PersistentMarketLog.combinedBytes(frames);return value;}
        value.scan(events,false);value.scan(frames,true);value.eventBytes=PersistentMarketLog.combinedBytes(events);
        value.frameBytes=PersistentMarketLog.combinedBytes(frames);try{value.saveAtomic(index);}catch(Exception ignored){}
        return value;
    }

    /**
     * Startup-safe loader: never scans JSONL. Existing journals remain untouched and are still
     * exported in full. If the small index is absent or corrupt, recording resumes with a fresh
     * summary rather than delaying the Android foreground-service deadline.
     */
    public static PersistentRecorderIndex loadFast(File index,File events,File frames) {
        PersistentRecorderIndex value=new PersistentRecorderIndex();
        value.load(index);
        value.eventBytes=PersistentMarketLog.combinedBytes(events);
        value.frameBytes=PersistentMarketLog.combinedBytes(frames);
        return value;
    }

    /**
     * Network-startup loader. It deliberately does not open the index or either JSONL file.
     * File lengths are metadata calls only, so a legacy/corrupt recorder can never delay the
     * first WebSocket and REST requests. Existing journals remain available to the exporter.
     */
    public static PersistentRecorderIndex metadataOnly(File events,File frames) {
        PersistentRecorderIndex value=new PersistentRecorderIndex();
        value.eventBytes=PersistentMarketLog.combinedBytes(events);
        value.frameBytes=PersistentMarketLog.combinedBytes(frames);
        return value;
    }

    private boolean load(File file) {
        if(file==null||!file.exists())return false;Properties p=new Properties();
        try(FileInputStream input=new FileInputStream(file)){p.load(input);
            if(Integer.parseInt(p.getProperty("formatVersion","0"))!=FORMAT_VERSION)return false;
            eventCount=get(p,"eventCount");frameCount=get(p,"frameCount");firstAt=get(p,"firstAt");lastAt=get(p,"lastAt");
            eventBytes=get(p,"eventBytes");frameBytes=get(p,"frameBytes");confirmedTrades=get(p,"confirmedTrades");
            restoredActivePlans=get(p,"restoredActivePlans");tp=get(p,"tp");sl=get(p,"sl");candidates=get(p,"candidates");
            rejectedAdmissions=get(p,"rejectedAdmissions");staleFreshTransitions=get(p,"staleFreshTransitions");
            for(String name:p.stringPropertyNames()){if(name.startsWith("symbol."))bySymbol.put(name.substring(7),get(p,name));
                if(name.startsWith("type."))byEventType.put(name.substring(5),get(p,name));}return true;
        }catch(Exception invalid){reset();return false;}
    }

    private void scan(File current,boolean frame) {
        scanFile(PersistentMarketLog.previous(current),frame);scanFile(current,frame);
    }
    private void scanFile(File file,boolean frame) {
        if(file==null||!file.exists())return;diskReads++;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){Map<String,Object> parsed=parse(line);
                if(frame)recordFrame(parsed,eventBytes,frameBytes);else recordEvent(parsed,eventBytes,frameBytes);}
        }catch(Exception ignored){}
    }
    private static Map<String,Object> parse(String line){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        Matcher strings=STRING_FIELD.matcher(line);while(strings.find())out.put(strings.group(1),strings.group(2));
        Matcher numbers=NUMBER_FIELD.matcher(line);while(numbers.find())out.put(numbers.group(1),Long.parseLong(numbers.group(2)));return out;}
    private static void increment(Map<String,Long> map,String key){if(key==null||key.isEmpty())key="UNKNOWN";map.put(key,map.getOrDefault(key,0L)+1);}
    private static long number(Object value,long fallback){return value instanceof Number?((Number)value).longValue():fallback;}
    private static String string(Object value){return value==null?"":String.valueOf(value);}
    private static void put(Properties p,String key,long value){p.setProperty(key,String.valueOf(value));}
    private static long get(Properties p,String key){return Long.parseLong(p.getProperty(key,"0"));}
}
