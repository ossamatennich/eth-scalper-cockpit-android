package com.ethscalper.cockpit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Cold-path streaming ZIP exporter. It never materializes a JSONL file or the ZIP. */
public final class DiagnosticStreamingExporter {
    public static final int COPY_BUFFER_BYTES=8_192;
    public static final List<String> ENTRY_NAMES=Collections.unmodifiableList(List.of(
            "status.json","markets.json","active_plans.json","profiles_manifest.json",
            "market_events.jsonl","market_frames.jsonl","market_candidates.jsonl",
            "market_candidates.csv","market_plans.jsonl","market_plans.csv",
            "market_summary.json","market_summary.txt","feed_health.json",
            "health_check.txt","instructions.txt","export_manifest.json"));
    private static final Pattern TYPE=Pattern.compile("\\\"eventType\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String[] CSV_FIELDS={"eventAt","symbol","asset","profileVersion","eventType","reasonCode",
            "classification","sleeve","side","family","entry","tp","sl","quantity","score","terminalStatus"};

    private DiagnosticStreamingExporter() {}

    public interface Progress { void onProgress(int percent,String stage); }
    public static Result export(OutputStream destination,File events,File frames,
                                Map<String,String> smallEntries,String version,long exportedAt,
                                Progress progress,BooleanSupplier cancelled)throws Exception {
        if(destination==null)throw new IllegalArgumentException("destination");
        LinkedHashMap<String,EntryDigest> manifest=new LinkedHashMap<>();
        try(ZipOutputStream zip=new ZipOutputStream(destination,StandardCharsets.UTF_8)){
            writeSmall(zip,"status.json",small(smallEntries,"status.json","{}"),manifest);
            writeSmall(zip,"markets.json",small(smallEntries,"markets.json","{}"),manifest);
            writeSmall(zip,"active_plans.json",small(smallEntries,"active_plans.json","[]"),manifest);
            writeSmall(zip,"profiles_manifest.json",small(smallEntries,"profiles_manifest.json","{}"),manifest);
            check(cancelled);progress(progress,15,"Journaux");
            writeJsonl(zip,"market_events.jsonl",events,line->!"MARKET_FRAME".equals(type(line)),"market_events",manifest,cancelled);
            writeJsonl(zip,"market_frames.jsonl",frames,line->true,"market_frames",manifest,cancelled);
            check(cancelled);progress(progress,42,"Candidats");
            writeJsonl(zip,"market_candidates.jsonl",events,line->DiagnosticExportContract.isCandidate(type(line)),"market_candidates",manifest,cancelled);
            writeCsv(zip,"market_candidates.csv",events,line->DiagnosticExportContract.isCandidate(type(line)),"candidateRecord",manifest,cancelled);
            writeJsonl(zip,"market_plans.jsonl",events,line->DiagnosticExportContract.isPlan(type(line)),"market_plans",manifest,cancelled);
            writeCsv(zip,"market_plans.csv",events,line->DiagnosticExportContract.isPlan(type(line)),"planRecord",manifest,cancelled);
            check(cancelled);progress(progress,70,"Synthèse");
            writeSmall(zip,"market_summary.json",small(smallEntries,"market_summary.json","{}"),manifest);
            writeSmall(zip,"market_summary.txt",small(smallEntries,"market_summary.txt",""),manifest);
            writeSmall(zip,"feed_health.json",small(smallEntries,"feed_health.json","{}"),manifest);
            writeSmall(zip,"health_check.txt",small(smallEntries,"health_check.txt",""),manifest);
            writeSmall(zip,"instructions.txt",small(smallEntries,"instructions.txt",""),manifest);
            String manifestJson=manifestJson(version,exportedAt,manifest,events,frames,
                    configuredSymbols(small(smallEntries,"profiles_manifest.json","{}")));
            writeSmall(zip,"export_manifest.json",manifestJson,null);progress(progress,100,"Terminé");
        }
        return new Result(manifest,ENTRY_NAMES,COPY_BUFFER_BYTES);
    }

    private static void writeSmall(ZipOutputStream zip,String name,String text,
                                   Map<String,EntryDigest> manifest)throws Exception {
        byte[] value=(text==null?"":text).getBytes(StandardCharsets.UTF_8);MessageDigest digest=sha();
        zip.putNextEntry(new ZipEntry(name));zip.write(value);digest.update(value);zip.closeEntry();
        if(manifest!=null)manifest.put(name,new EntryDigest(value.length,hex(digest.digest())));
    }

    private static void writeJsonl(ZipOutputStream zip,String name,File current,LineFilter filter,String dataset,
                                   Map<String,EntryDigest> manifest,BooleanSupplier cancelled)throws Exception {
        MessageDigest digest=sha();CountingDigestOutput output=new CountingDigestOutput(zip,digest);
        zip.putNextEntry(new ZipEntry(name));if(dataset!=null)output.write(("{\"eventType\":\"EXPORT_STREAM_METADATA\",\"dataset\":\""+dataset+"\",\"symbol\":\"*\",\"asset\":\"*\",\"profileVersion\":\"export\",\"trade\":false}\n").getBytes(StandardCharsets.UTF_8));streamLines(current,filter,(line)->{
            byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);output.write(bytes);},cancelled);
        zip.closeEntry();manifest.put(name,new EntryDigest(output.count,hex(digest.digest())));
    }

    private static void writeCsv(ZipOutputStream zip,String name,File current,LineFilter filter,String recordColumn,
                                 Map<String,EntryDigest> manifest,BooleanSupplier cancelled)throws Exception {
        MessageDigest digest=sha();CountingDigestOutput output=new CountingDigestOutput(zip,digest);
        zip.putNextEntry(new ZipEntry(name));output.write((recordColumn+","+String.join(",",CSV_FIELDS)+"\n").getBytes(StandardCharsets.UTF_8));
        streamLines(current,filter,line->{StringBuilder row=new StringBuilder();for(int i=0;i<CSV_FIELDS.length;i++){
            if(i>0)row.append(',');row.append(csv(extract(line,CSV_FIELDS[i])));}row.insert(0,csv(recordColumn)+",");row.append('\n');
            output.write(row.toString().getBytes(StandardCharsets.UTF_8));},cancelled);
        zip.closeEntry();manifest.put(name,new EntryDigest(output.count,hex(digest.digest())));
    }

    private static void streamLines(File current,LineFilter filter,LineSink sink,
                                    BooleanSupplier cancelled)throws Exception {
        for(File file:List.of(PersistentMarketLog.previous(current),current)){
            if(file==null||!file.exists())continue;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8),COPY_BUFFER_BYTES)){
                String line;while((line=reader.readLine())!=null){check(cancelled);if(!line.isBlank()&&filter.accept(line))sink.write(line);}
            }
        }
    }

    private static String manifestJson(String version,long at,Map<String,EntryDigest> values,
                                       File events,File frames,List<String> symbols){StringBuilder out=new StringBuilder();
        out.append("{\"product\":\"NMC\",\"version\":\"").append(escape(version)).append("\",\"exportedAt\":").append(at)
                .append(",\"sourceRange\":\"rotated .1 then current\",\"symbols\":[");
        for(int i=0;i<symbols.size();i++){if(i>0)out.append(',');out.append('"').append(escape(symbols.get(i))).append('"');}
        out.append("],\"entries\":[");
        boolean first=true;for(Map.Entry<String,EntryDigest> entry:values.entrySet()){if(!first)out.append(',');first=false;
            out.append("{\"name\":\"").append(escape(entry.getKey())).append("\",\"uncompressedBytes\":")
                    .append(entry.getValue().bytes).append(",\"sha256\":\"").append(entry.getValue().sha256).append("\"}");}
        out.append("],\"sourceEventBytes\":").append(PersistentMarketLog.combinedBytes(events))
                .append(",\"sourceFrameBytes\":").append(PersistentMarketLog.combinedBytes(frames))
                .append(",\"manifestSelfDigest\":\"excluded-by-design\"}");return out.toString();}

    private static String extract(String json,String key){Pattern p=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|null|true|false|-?[0-9.]+)");
        Matcher m=p.matcher(json);if(!m.find())return "";String value=m.group(1);return value.startsWith("\"")?value.substring(1,value.length()-1):value;}
    private static String type(String line){Matcher m=TYPE.matcher(line);return m.find()?m.group(1):"";}
    private static String csv(String value){return "\""+(value==null?"":value.replace("\"","\"\""))+"\"";}
    private static String small(Map<String,String> values,String key,String fallback){String value=values==null?null:values.get(key);return value==null?fallback:value;}
    private static List<String> configuredSymbols(String profiles){List<String> out=new ArrayList<>();Matcher matcher=Pattern.compile("\\\"symbol\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(profiles);
        while(matcher.find())if(!out.contains(matcher.group(1)))out.add(matcher.group(1));return out;}
    private static void progress(Progress value,int percent,String stage){if(value!=null)value.onProgress(percent,stage);}
    private static void check(BooleanSupplier cancelled){if(cancelled!=null&&cancelled.getAsBoolean())throw new ExportCancelledException();}
    private static MessageDigest sha()throws Exception{return MessageDigest.getInstance("SHA-256");}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();}
    private static String escape(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}

    private interface LineFilter{boolean accept(String line);}
    private interface LineSink{void write(String line)throws Exception;}
    private static final class CountingDigestOutput{final OutputStream output;final MessageDigest digest;long count;
        CountingDigestOutput(OutputStream output,MessageDigest digest){this.output=output;this.digest=digest;}
        void write(byte[] bytes)throws Exception{output.write(bytes);digest.update(bytes);count+=bytes.length;}}
    public static final class EntryDigest{public final long bytes;public final String sha256;
        EntryDigest(long bytes,String sha256){this.bytes=bytes;this.sha256=sha256;}}
    public static final class Result{public final Map<String,EntryDigest> entries;public final List<String> names;public final int maximumBufferBytes;
        Result(Map<String,EntryDigest> entries,List<String> names,int maximumBufferBytes){this.entries=Collections.unmodifiableMap(new LinkedHashMap<>(entries));this.names=names;this.maximumBufferBytes=maximumBufferBytes;}}
    public static final class ExportCancelledException extends RuntimeException{}
}
