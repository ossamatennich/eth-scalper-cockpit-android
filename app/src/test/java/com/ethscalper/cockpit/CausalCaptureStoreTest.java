package com.ethscalper.cockpit;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class CausalCaptureStoreTest {
    @Test public void v2DepthAndFlowRoundTripWithCrcAndV1RemainsReadable()throws Exception{
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"v2",4,64_000);
        double[][] bids=levels(100,false),asks=levels(101,true);List<CausalMarketRecord> values=List.of(
                MicrostructureMarketRecord.session("v2",1,1_000,1,"WS","START"),
                MicrostructureMarketRecord.flow100("v2",2,1_150,2,"ETHUSDT","WS",1_100,
                        1_110,1_150,1,2,900,901,2,0,100,101,100,101,1,1,100,101),
                MicrostructureMarketRecord.depth20("v2",3,1_200,3,"ETHUSDT","WS",1_190,
                        1_195,1,2,0,bids,asks));store.appendBatch(values);
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){List<CausalMarketRecord> read=
                CausalCaptureStore.read(snapshot.files,true).records;assertEquals(3,read.size());
            assertTrue(read.get(1) instanceof MicrostructureMarketRecord);assertEquals(20,
                    ((MicrostructureMarketRecord)read.get(2)).bids.length);
            assertEquals(0,CausalCaptureReplay.replay(snapshot.files,null).corruptBlocks);}
        CausalCaptureStore legacy=new CausalCaptureStore(Files.createTempDirectory("legacy-v1").toFile(),
                "v1",2,4096);legacy.appendBatch(records());try(CausalCaptureStore.Snapshot snapshot=
                legacy.checkpoint()){assertEquals(4,CausalCaptureStore.read(snapshot.files,true).records.size());}}
    @Test public void compressedCrcStoreRoundTripsEveryRecordKind()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"test",4,4096);
        List<CausalMarketRecord> input=records();store.appendBatch(input);
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            CausalCaptureStore.ReadResult read=CausalCaptureStore.read(snapshot.files,true);
            assertEquals(input.size(),read.records.size());assertEquals(0,read.corruptBlocks);
            assertEquals(CausalMarketRecord.Kind.SESSION,read.records.get(0).kind);
            assertEquals(101,read.records.get(1).ask,0);
            assertEquals(2,read.records.get(2).aggregateCount);}
    }

    @Test public void truncatedTailDoesNotDestroyPreviousBlock()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"tail",4,4096);
        store.appendBatch(records());CausalCaptureStore.Snapshot snapshot=store.checkpoint();
        File file=snapshot.files.get(0);try(FileOutputStream out=new FileOutputStream(file,true)){
            out.write(new byte[]{1,2,3});}
        CausalCaptureStore.ReadResult read=CausalCaptureStore.read(snapshot.files,true);
        assertEquals(records().size(),read.records.size());assertEquals(1,read.truncatedTails);
        CausalCaptureReplay.Validation replay=CausalCaptureReplay.replay(snapshot.files,null);
        assertFalse(replay.complete);assertEquals(1,replay.truncatedTails);
        snapshot.close();
    }

    @Test public void crcCorruptionIsDetectedAndCanBeSkipped()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"crc",4,4096);
        store.appendBatch(records());CausalCaptureStore.Snapshot snapshot=store.checkpoint();
        try(RandomAccessFile file=new RandomAccessFile(snapshot.files.get(0),"rw")){
            file.seek(CausalCaptureStore.HEADER_BYTES+2);int value=file.read();
            file.seek(CausalCaptureStore.HEADER_BYTES+2);file.write(value^0x7f);}
        try{CausalCaptureStore.read(snapshot.files,true);fail("CRC must fail");}
        catch(Exception expected){assertTrue(expected.getMessage().contains("capture"));}
        CausalCaptureStore.ReadResult lenient=CausalCaptureStore.read(snapshot.files,false);
        assertEquals(1,lenient.corruptBlocks);assertTrue(lenient.records.isEmpty());snapshot.close();
    }

    @Test public void rotationAndEvictionAreFifoAndBounded()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"fifo",2,220);
        for(int i=1;i<=12;i++)store.append(quote(i,i));
        assertTrue(store.segmentCount()<=2);assertTrue(store.evictedSegments()>0);
        assertTrue(store.combinedBytes()<=440);
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            List<CausalMarketRecord> read=CausalCaptureStore.read(snapshot.files,true).records;
            assertFalse(read.isEmpty());assertEquals(12,read.get(read.size()-1).sequence);}
    }

    @Test public void checkpointPinsImmutableSegmentsUntilClose()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"pin",1,220);
        store.append(quote(1,1));CausalCaptureStore.Snapshot snapshot=store.checkpoint();
        File pinned=snapshot.files.get(0);for(int i=2;i<=8;i++)store.append(quote(i,i));
        assertTrue(pinned.exists());assertTrue(store.segmentCount()
                <=1+CausalCaptureStore.MAX_SNAPSHOT_EXTRA_SEGMENTS);
        snapshot.close();assertTrue(store.segmentCount()<=1);
    }

    @Test public void resetDeletesOnlyOwnedSegments()throws Exception {
        File dir=temp();File unrelated=new File(dir,"keep.txt");
        Files.write(unrelated.toPath(),"x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File lookalike=new File(dir,"owned-not-a-segment.nmc");
        Files.write(lookalike.toPath(),"x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CausalCaptureStore store=new CausalCaptureStore(dir,"owned",2,4096);
        store.appendBatch(records());assertTrue(store.segmentCount()>0);store.reset();
        assertEquals(0,store.segmentCount());assertTrue(unrelated.exists());assertTrue(lookalike.exists());
    }

    @Test public void nonMonotonicBatchIsRejectedBeforeWrite()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"order",2,4096);
        try{store.appendBatch(List.of(quote(2,2),quote(1,1)));fail("order");}
        catch(IllegalArgumentException expected){assertEquals(0,store.segmentCount());}
    }

    @Test public void aBlockMayContainASequenceResetAtNewSession()throws Exception {
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"sessions",2,4096);
        List<CausalMarketRecord> values=List.of(
                CausalMarketRecord.session("a",1,0,0,"WS","SESSION_STARTED"),
                CausalMarketRecord.quote("a",2,1,1,"ETHUSDT","WS",0,0,1,100,1,101,1),
                CausalMarketRecord.session("b",1,2,2,"WS","SESSION_STARTED"));
        store.appendBatch(values);try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            List<CausalMarketRecord> restored=CausalCaptureStore.read(snapshot.files,true).records;
            assertEquals(3,restored.size());assertEquals(2,
                    CausalCaptureReplay.validate(restored).sessions);}
    }

    @Test public void consumerFailureIsNeverMisreportedOrSwallowedAsCorruption()throws Exception{
        File dir=temp();CausalCaptureStore store=new CausalCaptureStore(dir,"consumer",2,4096);
        store.appendBatch(records());try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            for(boolean strict:new boolean[]{true,false})try{
                CausalCaptureStore.scan(snapshot.files,strict,record->{throw new IllegalArgumentException("consumer");});
                fail("consumer failure");
            }catch(IllegalArgumentException expected){assertEquals("consumer",expected.getMessage());}}
    }

    private static List<CausalMarketRecord> records(){List<CausalMarketRecord> out=new ArrayList<>();
        out.add(CausalMarketRecord.session("s",1,1_000,1,"WS","SESSION_STARTED"));
        out.add(CausalMarketRecord.quote("s",2,1_100,2,"ETHUSDT","WS",1_000,1_000,
                1,100,2,101,3));
        out.add(CausalMarketRecord.flow("s",3,2_000,3,"ETHUSDT","WS",1_000,2_000,
                1,2,1_100,1_900,2,0,true,100,101,100,101,2,3,200,303));
        out.add(CausalMarketRecord.gap("s",4,3_000,4,"*","WS",2_000,3_000,"RECONNECT"));
        return out;}
    private static CausalMarketRecord quote(long sequence,long at){return CausalMarketRecord.quote(
            "s",sequence,at,at,"ETHUSDT","WS",at,at,sequence,100,1,101,1);}
    private static File temp()throws Exception{return Files.createTempDirectory("nmc-capture-").toFile();}
    private static double[][] levels(double start,boolean ascending){double[][] out=new double[20][2];
        for(int i=0;i<20;i++){out[i][0]=start+(ascending?i:-i)*.01;out[i][1]=i+1;}return out;}
}
