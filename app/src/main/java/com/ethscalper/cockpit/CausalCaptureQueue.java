package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/** Non-blocking bounded hand-off between market ingestion and a diagnostic writer. */
public final class CausalCaptureQueue implements CausalMarketCapture.Sink {
    public static final int DEFAULT_CAPACITY=16_384;
    private final ArrayBlockingQueue<CausalMarketRecord> queue;
    private final AtomicLong accepted=new AtomicLong(),rejected=new AtomicLong();
    private final AtomicLong highWaterMark=new AtomicLong();
    private final ConcurrentHashMap<String,AtomicLong> droppedByKind=new ConcurrentHashMap<>();
    private volatile IntConsumer activityListener;

    public CausalCaptureQueue(){this(DEFAULT_CAPACITY);}
    public CausalCaptureQueue(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");
        queue=new ArrayBlockingQueue<>(capacity);}

    /** Never blocks the caller. */
    @Override public boolean offer(CausalMarketRecord record) {
        if(record==null){rejected.incrementAndGet();dropped("NULL");return false;}
        boolean result=queue.offer(record);if(result){accepted.incrementAndGet();int size=queue.size();
            highWaterMark.accumulateAndGet(size,Math::max);IntConsumer listener=activityListener;
            if(listener!=null)try{listener.accept(size);}catch(RuntimeException ignored){}}
        else{rejected.incrementAndGet();dropped(record.kind.name());}return result;
    }

    public List<CausalMarketRecord> drain(int maximum) {
        if(maximum<1)return Collections.emptyList();ArrayList<CausalMarketRecord> out=
                new ArrayList<>(Math.min(maximum,queue.size()));queue.drainTo(out,maximum);
        return Collections.unmodifiableList(out);
    }

    public CausalMarketRecord poll(){return queue.poll();}
    public int size(){return queue.size();}
    public int remainingCapacity(){return queue.remainingCapacity();}
    public int capacity(){return size()+remainingCapacity();}
    public long accepted(){return accepted.get();}
    public long rejected(){return rejected.get();}
    public long highWaterMark(){return highWaterMark.get();}
    public java.util.Map<String,Long> droppedByKind(){java.util.LinkedHashMap<String,Long> out=
            new java.util.LinkedHashMap<>();java.util.ArrayList<String> keys=new java.util.ArrayList<>(
            droppedByKind.keySet());java.util.Collections.sort(keys);for(String key:keys)
            out.put(key,droppedByKind.get(key).get());return java.util.Collections.unmodifiableMap(out);}
    public void setActivityListener(IntConsumer listener){activityListener=listener;}
    public int clear(){int removed=queue.size();queue.clear();return removed;}

    /** Resets queue and accounting only while its writer is stopped. */
    public void reset(){queue.clear();accepted.set(0);rejected.set(0);highWaterMark.set(0);
        droppedByKind.clear();}
    private void dropped(String kind){droppedByKind.computeIfAbsent(kind,key->new AtomicLong())
            .incrementAndGet();}
}
