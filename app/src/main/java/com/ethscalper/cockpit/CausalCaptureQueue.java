package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking bounded hand-off between market ingestion and a diagnostic writer. */
public final class CausalCaptureQueue implements CausalMarketCapture.Sink {
    public static final int DEFAULT_CAPACITY=16_384;
    private final ArrayBlockingQueue<CausalMarketRecord> queue;
    private final AtomicLong accepted=new AtomicLong(),rejected=new AtomicLong();

    public CausalCaptureQueue(){this(DEFAULT_CAPACITY);}
    public CausalCaptureQueue(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");
        queue=new ArrayBlockingQueue<>(capacity);}

    /** Never blocks the caller. */
    @Override public boolean offer(CausalMarketRecord record) {
        if(record==null){rejected.incrementAndGet();return false;}
        boolean result=queue.offer(record);if(result)accepted.incrementAndGet();
        else rejected.incrementAndGet();return result;
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
    public int clear(){int removed=queue.size();queue.clear();return removed;}

    /** Resets queue and accounting only while its writer is stopped. */
    public void reset(){queue.clear();accepted.set(0);rejected.set(0);}
}
