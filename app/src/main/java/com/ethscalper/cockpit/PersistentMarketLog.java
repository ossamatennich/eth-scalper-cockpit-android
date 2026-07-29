package com.ethscalper.cockpit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded two-generation JSONL persistence used only by diagnostics. */
public final class PersistentMarketLog {
    public static final long MAX_CURRENT_BYTES=64L*1024L*1024L;
    public static final long FRAME_INTERVAL_MS=5_000L;
    private PersistentMarketLog() {}

    public static boolean appendEvent(File current,String eventType,String serialized)throws Exception {
        if(serialized==null||"MARKET_FRAME".equals(eventType))return false;
        append(current,serialized);return true;
    }

    public static void appendFrame(File current,String serialized)throws Exception {
        if(serialized==null)throw new IllegalArgumentException("frame");append(current,serialized);
    }

    public static synchronized void append(File current,String jsonLine)throws Exception {
        append(current,jsonLine,MAX_CURRENT_BYTES);
    }

    static synchronized void append(File current,String jsonLine,long maximumBytes)throws Exception {
        if(current==null||jsonLine==null)throw new IllegalArgumentException("log");
        if(maximumBytes<1)throw new IllegalArgumentException("maximumBytes");
        File parent=current.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())
            throw new IllegalStateException("persistent directory");
        String normalized=jsonLine.endsWith("\n")?jsonLine:jsonLine+"\n";
        byte[] bytes=normalized.getBytes(StandardCharsets.UTF_8);
        if(current.exists()&&current.length()>0&&current.length()+bytes.length>maximumBytes)
            rotate(current);
        try(FileOutputStream output=new FileOutputStream(current,true)){
            output.write(bytes);output.flush();
        }
    }

    static synchronized void rotate(File current)throws Exception {
        File previous=previous(current);
        if(previous.exists()&&!previous.delete())throw new IllegalStateException("delete previous log");
        if(!current.exists())return;
        if(current.renameTo(previous))return;
        copy(current,previous);
        try(FileOutputStream ignored=new FileOutputStream(current,false)){}
    }

    public static synchronized String readChronological(File current) {
        StringBuilder out=new StringBuilder();appendFile(out,previous(current));appendFile(out,current);
        return out.toString();
    }

    public static synchronized long combinedBytes(File current) {
        File previous=previous(current);return (previous.exists()?previous.length():0)
                +(current!=null&&current.exists()?current.length():0);
    }

    public static synchronized void reset(File current) {
        delete(previous(current));delete(current);
    }

    public static File previous(File current) {
        if(current==null)throw new IllegalArgumentException("current");
        return new File(current.getParentFile(),current.getName()+".1");
    }

    private static void appendFile(StringBuilder out,File file) {
        if(file==null||!file.exists())return;
        try(FileInputStream input=new FileInputStream(file);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))>=0)bytes.write(buffer,0,read);
            out.append(bytes.toString(StandardCharsets.UTF_8.name()));
        }catch(Exception ignored){}
    }

    private static void copy(File source,File target)throws Exception {
        try(BufferedInputStream input=new BufferedInputStream(new FileInputStream(source));
            BufferedOutputStream output=new BufferedOutputStream(new FileOutputStream(target,false))){
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))>=0)output.write(buffer,0,read);
            output.flush();
        }
    }

    private static void delete(File file){if(file!=null&&file.exists()&&!file.delete())
        throw new IllegalStateException("delete diagnostic log");}
}
