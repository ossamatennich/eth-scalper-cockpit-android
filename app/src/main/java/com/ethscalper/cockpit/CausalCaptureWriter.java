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
    public static final long MAX_BATCH_LATENCY_MS=75L;
    private final CausalCaptureQueue queue;
    private final CausalCaptureStore store;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean flushRequested=new AtomicBoolean();
    private final AtomicLong written=new AtomicLong(),failed=new AtomicLong();
    private final Object progress=new Object();
    private Thread thread;

    public CausalCaptureWriter(CausalCaptureQueue queue,CausalCaptureStore store){
        if(queue==null||store==null)throw new IllegalArgumentException("capture writer");
        this.queue=queue;this.store=store;this.queue.setActivityListener(this::onQueueActivity);
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
            queue.rejected(),queue.highWaterMark(),queue.droppedByKind(),written.get(),failed.get(),
            store.combinedBytes(),store.segmentCount(),store.evictedSegments(),running.get());}

    public void shutdown(long timeoutMs){
        running.set(false);synchronized(progress){progress.notifyAll();}
        Thread worker; synchronized(this){worker=thread;}
        if(worker!=null)try{worker.join(Math.max(1L,timeoutMs));}
        catch(InterruptedException error){Thread.currentThread().interrupt();}
    }

    @Override public void close(){shutdown(2_000L);}

    private void onQueueActivity(int size){if(size==1||size>=highWaterThreshold())
        synchronized(progress){progress.notifyAll();}}
    private int highWaterThreshold(){return Math.min(MAX_BATCH,Math.max(32,queue.capacity()/4));}

    private void run(){
        while(running.get()||queue.size()>0){
            if(queue.size()==0){synchronized(progress){try{progress.wait();}
                catch(InterruptedException error){if(!running.get())break;}}continue;}
            if(running.get()&&!flushRequested.get()&&queue.size()<highWaterThreshold()){
                long deadline=System.nanoTime()+MAX_BATCH_LATENCY_MS*1_000_000L;
                synchronized(progress){while(running.get()&&!flushRequested.get()
                        &&queue.size()<highWaterThreshold()){long remaining=deadline-System.nanoTime();
                    if(remaining<=0)break;try{progress.wait(Math.max(1L,remaining/1_000_000L));}
                    catch(InterruptedException error){if(!running.get())Thread.currentThread().interrupt();break;}}}}
            List<CausalMarketRecord> batch=queue.drain(MAX_BATCH);if(batch.isEmpty())continue;
            try{store.appendBatch(batch);written.addAndGet(batch.size());}
            catch(Exception error){failed.addAndGet(batch.size());}
            if(written.get()+failed.get()>=queue.accepted())flushRequested.set(false);
            synchronized(progress){progress.notifyAll();}}
        synchronized(progress){progress.notifyAll();}
    }

    public static final class Stats {
        public final int queueCapacity,queueSize;public final long accepted,rejected,queueHighWaterMark,written,failed;
        public final java.util.Map<String,Long> droppedByKind;
        public final long bytes,evictedSegments;public final int segments;public final boolean running;
        Stats(int queueCapacity,int queueSize,long accepted,long rejected,long queueHighWaterMark,
              java.util.Map<String,Long> droppedByKind,long written,long failed,long bytes,int segments,
              long evictedSegments,boolean running){this.queueCapacity=queueCapacity;
            this.queueSize=queueSize;this.accepted=accepted;this.rejected=rejected;
            this.queueHighWaterMark=queueHighWaterMark;this.droppedByKind=droppedByKind;
            this.written=written;this.failed=failed;this.bytes=bytes;this.segments=segments;
            this.evictedSegments=evictedSegments;this.running=running;}
        public java.util.Map<String,Object> toMap(){java.util.LinkedHashMap<String,Object> out=
                new java.util.LinkedHashMap<>();out.put("schema",MicrostructureMarketRecord.SCHEMA);
            out.put("queueCapacity",queueCapacity);out.put("queueSize",queueSize);
            out.put("accepted",accepted);out.put("rejected",rejected);
            out.put("queueHighWaterMark",queueHighWaterMark);out.put("droppedByKind",droppedByKind);
            out.put("written",written);
            out.put("failed",failed);out.put("bytes",bytes);out.put("segments",segments);
            out.put("evictedSegments",evictedSegments);out.put("running",running);
            return java.util.Collections.unmodifiableMap(out);}
    }
}
