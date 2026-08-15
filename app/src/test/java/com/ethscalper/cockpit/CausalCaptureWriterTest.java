package com.ethscalper.cockpit;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CausalCaptureWriterTest {
    @Test public void highWaterSignalDrainsNominalBurstWithoutRejects()throws Exception{
        File directory=Files.createTempDirectory("capture-writer-burst").toFile();
        CausalCaptureQueue queue=new CausalCaptureQueue(2_048);CausalCaptureStore store=
                new CausalCaptureStore(directory,"burst",8,256_000);CausalCaptureWriter writer=
                new CausalCaptureWriter(queue,store);writer.start();for(int i=1;i<=1_000;i++)
            assertTrue(queue.offer(CausalMarketRecord.quote("s",i,i,i,"ETHUSDT","WS",i,i,i,
                    100,1,101,1)));assertTrue(writer.flush(10_000));assertEquals(0,queue.rejected());
        assertEquals(1_000,writer.stats().written);assertTrue(writer.stats().queueHighWaterMark>0);
        writer.close();}

    @Test public void storageBatchFailureIsCountedAndWriterRemainsFailOpen()throws Exception{
        File directory=Files.createTempDirectory("capture-writer-failure").toFile();
        CausalCaptureQueue queue=new CausalCaptureQueue(8);CausalCaptureStore store=
                new CausalCaptureStore(directory,"failure",4,64_000);CausalCaptureWriter writer=
                new CausalCaptureWriter(queue,store);writer.start();assertTrue(queue.offer(
                CausalMarketRecord.session("v1",1,1,1,"WS","START")));assertTrue(queue.offer(
                MicrostructureMarketRecord.session("v2",1,2,2,"WS","START")));
        assertTrue(writer.flush(2_000));assertEquals(2,writer.stats().failed);
        assertTrue(writer.stats().running);writer.close();}
    @Test public void writerDrainsAcceptedRecordsAndCheckpointsImmutably()throws Exception{
        File directory=Files.createTempDirectory("capture-writer").toFile();
        CausalCaptureQueue queue=new CausalCaptureQueue(8);
        CausalCaptureStore store=new CausalCaptureStore(directory,"writer",4,64_000);
        CausalCaptureWriter writer=new CausalCaptureWriter(queue,store);writer.start();
        assertTrue(queue.offer(CausalMarketRecord.session("s",1,1_000,1,"FUTURES","START")));
        assertTrue(queue.offer(CausalMarketRecord.quote("s",2,1_001,2,"ETHUSDT","FUTURES",
                900,901,1,1900,1,1900.01,2)));
        assertTrue(writer.flush(2_000));
        assertEquals(2,writer.stats().written);assertEquals(0,writer.stats().failed);
        try(CausalCaptureStore.Snapshot snapshot=writer.checkpoint(2_000)){
            assertEquals(2,CausalCaptureStore.read(snapshot.files,true).records.size());
            assertTrue(queue.offer(CausalMarketRecord.quote("s",3,1_002,3,"ETHUSDT","FUTURES",
                    902,903,2,1900.01,1,1900.02,2)));
            assertTrue(writer.flush(2_000));
            assertEquals(2,CausalCaptureStore.read(snapshot.files,true).records.size());
        }
        writer.close();
    }

    @Test public void boundedQueueRejectsWithoutBlocking()throws Exception{
        CausalCaptureQueue queue=new CausalCaptureQueue(1);
        assertTrue(queue.offer(CausalMarketRecord.session("s",1,1,1,"FUTURES","START")));
        assertFalse(queue.offer(CausalMarketRecord.session("s",2,2,2,"FUTURES","START")));
        assertEquals(1,queue.rejected());
    }

    @Test public void resetStartsFreshAccountingAndCheckpointWithoutOldRecords()throws Exception{
        File directory=Files.createTempDirectory("capture-writer-reset").toFile();
        CausalCaptureQueue queue=new CausalCaptureQueue(8);
        CausalCaptureStore store=new CausalCaptureStore(directory,"writer",4,64_000);
        CausalCaptureWriter writer=new CausalCaptureWriter(queue,store);writer.start();
        assertTrue(queue.offer(CausalMarketRecord.session("old",1,1_000,1,"WS","START")));
        assertTrue(writer.flush(2_000));writer.reset(2_000);
        assertEquals(0,queue.accepted());assertEquals(0,writer.stats().written);
        assertTrue(queue.offer(CausalMarketRecord.session("new",1,2_000,2,"WS","START")));
        assertTrue(queue.offer(CausalMarketRecord.quote("new",2,2_001,3,"ETHUSDT","WS",
                2_000,2_000,2,100,1,101,1)));
        assertTrue(writer.flush(2_000));
        try(CausalCaptureStore.Snapshot snapshot=writer.checkpoint(2_000)){
            java.util.List<CausalMarketRecord> records=
                    CausalCaptureStore.read(snapshot.files,true).records;
            assertEquals(2,records.size());assertEquals("new",records.get(0).sessionId);}
        writer.close();
    }
}
