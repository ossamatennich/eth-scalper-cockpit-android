package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class V4FallbackHistoryTest {
    private static final long DAY=86_400_000L;
    @Test public void oneRecordPerUtcDayAndPriorOnlyDecisionIsStable(){V4FallbackHistory.MemoryBackend backend=new V4FallbackHistory.MemoryBackend();
        V4FallbackHistory history=new V4FallbackHistory(backend);for(int i=1;i<=45;i++)history.observe(i*DAY,.10+i*.001,.02+i*.0001);
        V4FallbackHistory.Gate first=history.evaluateThenCommit(46*DAY,.20,.05);assertEquals(45,first.priorCount);assertEquals(1,history.count(46*DAY));
        for(int i=0;i<9;i++){V4FallbackHistory.Gate repeated=history.evaluateThenCommit(46*DAY,.99,.99);assertEquals(first.accepted,repeated.accepted);assertEquals(45,repeated.priorCount);}
        assertEquals(46,history.size());assertEquals(.20,history.best(46*DAY),0);
        V4FallbackHistory.Gate next=history.evaluateThenCommit(47*DAY,.21,.05);assertEquals(46,next.priorCount);}
    @Test public void restartDoesNotDuplicate(){V4FallbackHistory.MemoryBackend backend=new V4FallbackHistory.MemoryBackend();V4FallbackHistory a=new V4FallbackHistory(backend);
        a.observe(10*DAY,.1,.2);V4FallbackHistory b=new V4FallbackHistory(backend);b.observe(10*DAY,.8,.9);assertEquals(1,b.size());assertEquals(.1,b.best(10*DAY),0);}
    @Test public void legacyDuplicatesMigrateFirstRecordDeterministically(){String legacy="[{\"cutoffUtc\":"+(5*DAY)+",\"best\":0.1,\"spread\":0.2},{\"cutoffUtc\":"+(5*DAY+1234)+",\"best\":0.9,\"spread\":0.8}]";
        V4FallbackHistory h=new V4FallbackHistory(new V4FallbackHistory.MemoryBackend(legacy));assertEquals(1,h.size());assertEquals(.1,h.best(5*DAY),0);}
}
