package com.ethscalper.cockpit;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single bounded writer for the prospective market capture.
 *
 * <p>Market ingestion only calls {@link CausalCaptureQueue#offer}; all compression, fsync and
 * rotation happen on this daemon. A storage failure drops the affected bounded batch and is
 * counted, but it can never block or throw into the market loop.</p>
 */
public final class CausalCaptureWriter implements AutoCloseable {
    public static final int MAX_BATCH=4_096;
    public static final long MAX_BATCH_LATENCY_MS=2_000L;
    private final CausalCaptureQueue queue;
    private final CausalCaptureStore store;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean flushRequested=new AtomicBoolean();
    private final AtomicLong written=new AtomicLong(),failed=new AtomicLong();
    private final Object progress=new Object();
    private Thread thread;

    public CausalCaptureWriter(CausalCaptureQueue queue,CausalCaptureStore store){
        if(queue==null||store==null)throw new IllegalArgumentException("capture writer");
        this.queue=queue;this.store=store;
    }

    public synchronized void start(){
        if(running.get())return;running.set(true);thread=new Thread(this::run,"nmc-causal-capture");
        thread.setDaemon(true);thread.start();
    }

    /** Waits only for records accepted before this call. New ticks may continue to arrive. */
    public boolean flush(long timeoutMs){
        long target=queue.accepted();long deadline=System.nanoTime()
                +Math.max(1L,timeoutMs)*1_000_000L;
        flushRequested.set(true);synchronized(progress){progress.notifyAll();}
        synchronized(progress){while(written.get()+failed.get()<target){long remaining=
                    (deadline-System.nanoTime())/1_000_000L;if(remaining<=0)return false;
                try{progress.wait(Math.min(remaining,50L));}
                catch(InterruptedException error){Thread.currentThread().interrupt();return false;}}}
        flushRequested.set(false);
        return true;
    }

    public CausalCaptureStore.Snapshot checkpoint(long timeoutMs){
        if(!flush(timeoutMs))throw new IllegalStateException("capture flush timeout");
        return store.checkpoint();
    }

    public synchronized void reset(long timeoutMs){
        boolean restart=running.get();
        shutdown(timeoutMs);
        if(thread!=null&&thread.isAlive())throw new IllegalStateException("capture writer reset timeout");
        queue.reset();store.reset();written.set(0);failed.set(0);thread=null;
        if(restart)start();
    }

    public Stats stats(){return new Stats(queue.capacity(),queue.size(),queue.accepted(),
            queue.rejected(),written.get(),failed.get(),store.combinedBytes(),
            store.segmentCount(),store.evictedSegments(),running.get());}

    public void shutdown(long timeoutMs){
        running.set(false);synchronized(progress){progress.notifyAll();}
        Thread worker; synchronized(this){worker=thread;}
        if(worker!=null)try{worker.join(Math.max(1L,timeoutMs));}
        catch(InterruptedException error){Thread.currentThread().interrupt();}
    }

    @Override public void close(){shutdown(2_000L);}

    private void run(){
        while(running.get()||queue.size()>0){
            if(queue.size()==0){synchronized(progress){try{progress.wait(50L);}
                catch(InterruptedException error){if(!running.get())break;}}continue;}
            if(running.get()&&!flushRequested.get())synchronized(progress){try{
                progress.wait(MAX_BATCH_LATENCY_MS);}catch(InterruptedException error){
                if(!running.get())Thread.currentThread().interrupt();}}
            List<CausalMarketRecord> batch=queue.drain(MAX_BATCH);if(batch.isEmpty())continue;
            try{store.appendBatch(batch);written.addAndGet(batch.size());}
            catch(Exception error){failed.addAndGet(batch.size());}
            if(written.get()+failed.get()>=queue.accepted())flushRequested.set(false);
            synchronized(progress){progress.notifyAll();}}
        synchronized(progress){progress.notifyAll();}
    }

    public static final class Stats {
        public final int queueCapacity,queueSize;public final long accepted,rejected,written,failed;
        public final long bytes,evictedSegments;public final int segments;public final boolean running;
        Stats(int queueCapacity,int queueSize,long accepted,long rejected,long written,long failed,
              long bytes,int segments,long evictedSegments,boolean running){this.queueCapacity=queueCapacity;
            this.queueSize=queueSize;this.accepted=accepted;this.rejected=rejected;
            this.written=written;this.failed=failed;this.bytes=bytes;this.segments=segments;
            this.evictedSegments=evictedSegments;this.running=running;}
        public java.util.Map<String,Object> toMap(){java.util.LinkedHashMap<String,Object> out=
                new java.util.LinkedHashMap<>();out.put("schema",CausalMarketRecord.SCHEMA);
            out.put("queueCapacity",queueCapacity);out.put("queueSize",queueSize);
            out.put("accepted",accepted);out.put("rejected",rejected);out.put("written",written);
            out.put("failed",failed);out.put("bytes",bytes);out.put("segments",segments);
            out.put("evictedSegments",evictedSegments);out.put("running",running);
            return java.util.Collections.unmodifiableMap(out);}
    }
}
