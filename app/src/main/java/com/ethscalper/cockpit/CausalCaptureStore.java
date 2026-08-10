package com.ethscalper.cockpit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Bounded segmented block store for {@link CausalMarketRecord}.
 *
 * <p>Every independently compressed block has lengths, sequence bounds and CRC32. A process
 * death can therefore lose only a partial tail. Checkpoints seal the current segment and pin an
 * immutable set of files for a streaming exporter.</p>
 */
public final class CausalCaptureStore {
    public static final int MAGIC=0x4E4D4331; // NMC1
    public static final int LEGACY_BLOCK_VERSION=1;
    public static final int BLOCK_VERSION=2;
    public static final int HEADER_BYTES=40;
    public static final int MAX_RECORDS_PER_BLOCK=4_096;
    public static final int MAX_RAW_BLOCK_BYTES=4*1024*1024;
    public static final int MAX_MATERIALIZED_READ_RECORDS=100_000;
    public static final int DEFAULT_SEGMENTS=128;
    public static final long DEFAULT_SEGMENT_BYTES=16L*1024L*1024L;
    public static final int MAX_SNAPSHOT_EXTRA_SEGMENTS=4;
    private static final String SUFFIX=".nmc";

    private final File directory;
    private final String prefix;
    private final int maximumSegments;
    private final long maximumSegmentBytes;
    private long nextSegmentId=1;
    private File current;
    private Snapshot activeSnapshot;
    private long appendedBlocks,appendedRecords,evictedSegments;
    private long cachedCombinedBytes;
    private int cachedSegmentCount;

    public CausalCaptureStore(File directory){this(directory,"causal-market",
            DEFAULT_SEGMENTS,DEFAULT_SEGMENT_BYTES);}
    public CausalCaptureStore(File directory,String prefix,int maximumSegments,
                              long maximumSegmentBytes) {
        if(directory==null||prefix==null||prefix.trim().isEmpty()||maximumSegments<1
                ||maximumSegmentBytes<HEADER_BYTES+64)throw new IllegalArgumentException("store");
        this.directory=directory;this.prefix=safePrefix(prefix);
        this.maximumSegments=maximumSegments;this.maximumSegmentBytes=maximumSegmentBytes;
        if(!directory.exists()&&!directory.mkdirs())throw new IllegalStateException("capture directory");
        if(!directory.isDirectory())throw new IllegalStateException("capture directory");
        for(File file:listSegments()){nextSegmentId=Math.max(nextSegmentId,id(file)+1);}
        // A process restart always begins a new segment, so files already on disk stay immutable.
        evictIfNeeded();refreshCachedStats();
    }

    public synchronized void append(CausalMarketRecord record)throws Exception {
        appendBatch(Collections.singletonList(record));
    }

    public synchronized void appendBatch(List<CausalMarketRecord> records)throws Exception {
        if(records==null||records.isEmpty()||records.size()>MAX_RECORDS_PER_BLOCK)
            throw new IllegalArgumentException("records");
        int blockVersion=records.get(0) instanceof MicrostructureMarketRecord
                ?BLOCK_VERSION:LEGACY_BLOCK_VERSION;
        long previous=0;for(CausalMarketRecord record:records){if(record==null)
            throw new IllegalArgumentException("record");if(record.kind==CausalMarketRecord.Kind.SESSION){
                if(record.sequence!=1)throw new IllegalArgumentException("session sequence");
                previous=0;}if(previous>0&&record.sequence<=previous)
            throw new IllegalArgumentException("sequence");if((record instanceof MicrostructureMarketRecord)
                    !=(blockVersion==BLOCK_VERSION))throw new IllegalArgumentException("mixed format");
            previous=record.sequence;}
        byte[] raw=encode(records,blockVersion);if(raw.length>MAX_RAW_BLOCK_BYTES)
            throw new IllegalArgumentException("raw block");byte[] compressed=compress(raw);
        long blockBytes=HEADER_BYTES+(long)compressed.length;if(blockBytes>maximumSegmentBytes)
            throw new IllegalArgumentException("compressed block");
        ensureCurrent(blockBytes);CRC32 crc=new CRC32();crc.update(raw);
        try(FileOutputStream file=new FileOutputStream(current,true);
            DataOutputStream output=new DataOutputStream(file)){
            output.writeInt(MAGIC);output.writeInt(blockVersion);output.writeInt(records.size());
            output.writeInt(raw.length);output.writeInt(compressed.length);
            output.writeInt((int)crc.getValue());output.writeLong(records.get(0).sequence);
            output.writeLong(records.get(records.size()-1).sequence);output.write(compressed);
            output.flush();file.getFD().sync();}
        appendedBlocks++;appendedRecords+=records.size();evictIfNeeded();refreshCachedStats();
    }

    /** Seals the current segment. Exactly one snapshot may be pinned at a time. */
    public synchronized Snapshot checkpoint() {
        if(activeSnapshot!=null&&!activeSnapshot.closed)throw new IllegalStateException("snapshot active");
        current=null;activeSnapshot=new Snapshot(this,listSegments());return activeSnapshot;
    }

    /** Cached counters: safe for a hot status path and never scan the filesystem. */
    public synchronized long combinedBytes(){return cachedCombinedBytes;}
    public synchronized int segmentCount(){return cachedSegmentCount;}
    public synchronized long appendedBlocks(){return appendedBlocks;}
    public synchronized long appendedRecords(){return appendedRecords;}
    public synchronized long evictedSegments(){return evictedSegments;}
    public int maximumSegments(){return maximumSegments;}
    public long maximumSegmentBytes(){return maximumSegmentBytes;}

    /** Deletes only this store's exact, non-recursive segment files. */
    public synchronized void reset() {
        if(activeSnapshot!=null&&!activeSnapshot.closed)throw new IllegalStateException("snapshot active");
        current=null;for(File file:listSegments())if(!file.delete())
            throw new IllegalStateException("delete capture segment");
        appendedBlocks=appendedRecords=evictedSegments=0;nextSegmentId=1;
        cachedCombinedBytes=0;cachedSegmentCount=0;
    }

    public static ReadResult read(List<File> files,boolean strictCrc)throws Exception {
        ArrayList<CausalMarketRecord> records=new ArrayList<>();ScanResult scan=scan(files,strictCrc,
                record->{if(records.size()>=MAX_MATERIALIZED_READ_RECORDS)
                    throw new IllegalStateException("materialized capture read limit");records.add(record);});
        return new ReadResult(records,scan.corruptBlocks,scan.truncatedTails);
    }

    /** Streams records with memory bounded by one compressed block (at most 4 MiB). */
    public static ScanResult scan(List<File> files,boolean strictCrc,
                                  Consumer<CausalMarketRecord> consumer)throws Exception {
        int corrupt=0,truncated=0;long records=0;
        if(files==null)return new ScanResult(0,0,0);
        ArrayList<File> ordered=new ArrayList<>(files);ordered.sort(Comparator.comparing(File::getName));
        for(File file:ordered){if(file==null||!file.exists())continue;
            try(RandomAccessFile input=new RandomAccessFile(file,"r")){while(input.getFilePointer()<input.length()){
                long remaining=input.length()-input.getFilePointer();if(remaining<HEADER_BYTES){truncated++;break;}
                int magic=input.readInt(),version=input.readInt(),count=input.readInt();
                int rawLength=input.readInt(),compressedLength=input.readInt(),expectedCrc=input.readInt();
                long firstSequence=input.readLong(),lastSequence=input.readLong();
                if(magic!=MAGIC||(version!=LEGACY_BLOCK_VERSION&&version!=BLOCK_VERSION)
                        ||count<1||count>MAX_RECORDS_PER_BLOCK
                        ||rawLength<1||rawLength>MAX_RAW_BLOCK_BYTES||compressedLength<1
                        ||compressedLength>MAX_RAW_BLOCK_BYTES||firstSequence<=0
                        ||lastSequence<=0){corrupt++;if(strictCrc)
                    throw new IllegalStateException("invalid capture block");break;}
                if(input.length()-input.getFilePointer()<compressedLength){truncated++;break;}
                byte[] compressed=new byte[compressedLength];input.readFully(compressed);
                List<CausalMarketRecord> decoded=null;
                try{byte[] raw=decompress(compressed,rawLength);CRC32 crc=new CRC32();crc.update(raw);
                    if((int)crc.getValue()!=expectedCrc)throw new IllegalStateException("capture crc");
                    decoded=decode(raw,count,version);
                    if(decoded.get(0).sequence!=firstSequence
                            ||decoded.get(decoded.size()-1).sequence!=lastSequence)
                        throw new IllegalStateException("capture sequence bounds");
                }catch(Exception error){corrupt++;if(strictCrc)
                    throw new IllegalStateException("corrupt capture block",error);}
                if(decoded==null)continue;
                for(CausalMarketRecord record:decoded){if(consumer!=null)consumer.accept(record);records++;}
            }}catch(EOFException tail){truncated++;}}
        return new ScanResult(records,corrupt,truncated);
    }

    private void ensureCurrent(long blockBytes)throws Exception {
        if(current==null||current.exists()&&current.length()>0
                &&current.length()+blockBytes>maximumSegmentBytes){current=new File(directory,
                    String.format(Locale.ROOT,"%s-%016d%s",prefix,nextSegmentId++,SUFFIX));
            if(current.exists())throw new IllegalStateException("segment collision");}
    }
    private List<File> listSegments(){File[] listed=directory.listFiles(file->file.isFile()
            &&isSegmentName(file.getName()));
        ArrayList<File> out=new ArrayList<>();if(listed!=null)Collections.addAll(out,listed);
        out.sort(Comparator.comparingLong(CausalCaptureStore::id));return out;}
    private void evictIfNeeded(){List<File> values=listSegments();int allowance=maximumSegments;
        if(activeSnapshot!=null&&!activeSnapshot.closed)allowance+=MAX_SNAPSHOT_EXTRA_SEGMENTS;
        while(values.size()>allowance){File removable=null;for(File candidate:values){
            boolean pinned=activeSnapshot!=null&&!activeSnapshot.closed
                    &&activeSnapshot.files.contains(candidate);
            if(!pinned&&!candidate.equals(current)){removable=candidate;break;}}
            if(removable==null)break;if(!removable.delete())
                throw new IllegalStateException("evict capture segment");
            values.remove(removable);evictedSegments++;}}
    private synchronized void release(Snapshot snapshot){if(activeSnapshot!=snapshot)return;
        snapshot.closed=true;activeSnapshot=null;evictIfNeeded();refreshCachedStats();}

    private void refreshCachedStats(){long total=0;List<File> files=listSegments();
        for(File file:files)total+=Math.max(0,file.length());cachedCombinedBytes=total;
        cachedSegmentCount=files.size();}

    private static byte[] encode(List<CausalMarketRecord> records,int version)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){
            for(CausalMarketRecord value:records){out.writeByte(value.kind.ordinal());string(out,value.sessionId);
                out.writeLong(value.sequence);out.writeLong(value.receivedAt);out.writeLong(value.monotonicAt);
                string(out,value.symbol);string(out,value.source);
                switch(value.kind){case SESSION:string(out,value.reasonCode);break;
                    case QUOTE:out.writeLong(value.exchangeEventAt);out.writeLong(value.transactionAt);
                        out.writeLong(value.updateId);out.writeDouble(value.bid);
                        out.writeDouble(value.bidQuantity);out.writeDouble(value.ask);
                        out.writeDouble(value.askQuantity);break;
                    case FLOW_1S:out.writeLong(value.bucketStartAt);out.writeLong(value.bucketEndAt);
                        out.writeLong(value.firstTradeId);out.writeLong(value.lastTradeId);
                        out.writeLong(value.firstTradeAt);out.writeLong(value.lastTradeAt);
                        out.writeLong(value.aggregateCount);out.writeLong(value.aggregateIdGaps);
                        out.writeBoolean(value.hasTrades);out.writeDouble(value.open);
                        out.writeDouble(value.high);out.writeDouble(value.low);out.writeDouble(value.close);
                        out.writeDouble(value.buyerBase);out.writeDouble(value.sellerBase);
                        out.writeDouble(value.buyerNotional);out.writeDouble(value.sellerNotional);break;
                    case GAP:out.writeLong(value.gapFromAt);out.writeLong(value.gapToAt);
                        string(out,value.reasonCode);break;
                    case TOP_OF_BOOK_SAMPLE:requireV2(value,version);out.writeLong(value.exchangeEventAt);
                        out.writeLong(value.transactionAt);out.writeLong(value.updateId);
                        out.writeDouble(value.bid);out.writeDouble(value.bidQuantity);
                        out.writeDouble(value.ask);out.writeDouble(value.askQuantity);break;
                    case FLOW_100MS:{MicrostructureMarketRecord v=requireV2(value,version);
                        out.writeLong(value.bucketStartAt);out.writeLong(v.firstReceivedAt);
                        out.writeLong(v.lastReceivedAt);out.writeLong(value.firstTradeId);
                        out.writeLong(value.lastTradeId);out.writeLong(value.firstTradeAt);
                        out.writeLong(value.lastTradeAt);out.writeLong(value.aggregateCount);
                        out.writeLong(value.aggregateIdGaps);out.writeDouble(value.open);
                        out.writeDouble(value.high);out.writeDouble(value.low);out.writeDouble(value.close);
                        out.writeDouble(value.buyerBase);out.writeDouble(value.sellerBase);
                        out.writeDouble(value.buyerNotional);out.writeDouble(value.sellerNotional);break;}
                    case DEPTH20_SAMPLE:{MicrostructureMarketRecord v=requireV2(value,version);
                        out.writeLong(value.exchangeEventAt);out.writeLong(value.transactionAt);
                        out.writeLong(v.firstUpdateId);out.writeLong(v.finalUpdateId);
                        out.writeLong(v.previousFinalUpdateId);levels(out,v.bids);levels(out,v.asks);break;}
                    case DROP_SUMMARY:{MicrostructureMarketRecord v=requireV2(value,version);
                        out.writeLong(value.gapFromAt);out.writeLong(value.gapToAt);
                        string(out,value.reasonCode);out.writeShort(v.droppedByKind.size());
                        for(java.util.Map.Entry<String,Long> entry:v.droppedByKind.entrySet()){
                            string(out,entry.getKey());out.writeLong(entry.getValue());}break;}
                    case HEALTH:requireV2(value,version);string(out,value.reasonCode);break;}}
        }return bytes.toByteArray();}
    private static List<CausalMarketRecord> decode(byte[] raw,int count,int version)throws Exception {
        ArrayList<CausalMarketRecord> out=new ArrayList<>(count);
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(raw))){for(int i=0;i<count;i++){
            int ordinal=in.readUnsignedByte();if(ordinal>=CausalMarketRecord.Kind.values().length)
                throw new IllegalStateException("record kind");CausalMarketRecord.Kind kind=
                    CausalMarketRecord.Kind.values()[ordinal];String session=string(in);
            long sequence=in.readLong(),receivedAt=in.readLong(),monotonicAt=in.readLong();
            String symbol=string(in),source=string(in);CausalMarketRecord value;
            switch(kind){case SESSION:String sessionReason=string(in);value=version==BLOCK_VERSION
                        ?MicrostructureMarketRecord.session(session,sequence,receivedAt,monotonicAt,
                                source,sessionReason)
                        :CausalMarketRecord.session(session,sequence,receivedAt,monotonicAt,source,
                                sessionReason);break;
                case QUOTE:value=CausalMarketRecord.quote(session,sequence,receivedAt,monotonicAt,
                        symbol,source,in.readLong(),in.readLong(),in.readLong(),in.readDouble(),
                        in.readDouble(),in.readDouble(),in.readDouble());break;
                case FLOW_1S:value=CausalMarketRecord.flow(session,sequence,receivedAt,monotonicAt,
                        symbol,source,in.readLong(),in.readLong(),in.readLong(),in.readLong(),
                        in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readBoolean(),
                        in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),
                        in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());break;
                case GAP:{long from=in.readLong(),to=in.readLong();String reason=string(in);
                    value=version==BLOCK_VERSION?MicrostructureMarketRecord.gap(session,sequence,
                            receivedAt,monotonicAt,symbol,source,from,to,reason)
                            :CausalMarketRecord.gap(session,sequence,receivedAt,monotonicAt,symbol,
                                    source,from,to,reason);break;}
                case TOP_OF_BOOK_SAMPLE:requireVersion2(version);value=MicrostructureMarketRecord.topBook(
                        session,sequence,receivedAt,monotonicAt,symbol,source,in.readLong(),in.readLong(),
                        in.readLong(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());break;
                case FLOW_100MS:requireVersion2(version);value=MicrostructureMarketRecord.flow100(
                        session,sequence,receivedAt,monotonicAt,symbol,source,in.readLong(),
                        in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),
                        in.readLong(),in.readLong(),in.readLong(),in.readDouble(),in.readDouble(),
                        in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),
                        in.readDouble());break;
                case DEPTH20_SAMPLE:requireVersion2(version);value=MicrostructureMarketRecord.depth20(
                        session,sequence,receivedAt,monotonicAt,symbol,source,in.readLong(),in.readLong(),
                        in.readLong(),in.readLong(),in.readLong(),levels(in),levels(in));break;
                case DROP_SUMMARY:{requireVersion2(version);long from=in.readLong(),to=in.readLong();
                    String reason=string(in);int size=in.readUnsignedShort();java.util.LinkedHashMap<String,Long>
                            drops=new java.util.LinkedHashMap<>();for(int j=0;j<size;j++)drops.put(string(in),
                            in.readLong());value=MicrostructureMarketRecord.dropSummary(session,sequence,
                            receivedAt,monotonicAt,source,from,to,drops);break;}
                case HEALTH:requireVersion2(version);value=MicrostructureMarketRecord.health(session,
                        sequence,receivedAt,monotonicAt,source,string(in));break;
                default:throw new IllegalStateException("record kind");}out.add(value);}
            if(in.available()!=0)throw new IllegalStateException("capture trailing payload");}
        return out;}
    private static byte[] compress(byte[] raw)throws Exception {ByteArrayOutputStream bytes=
            new ByteArrayOutputStream();Deflater deflater=new Deflater(Deflater.BEST_SPEED);
        try(DeflaterOutputStream out=new DeflaterOutputStream(bytes,deflater,8192)){out.write(raw);}
        finally{deflater.end();}return bytes.toByteArray();}
    private static byte[] decompress(byte[] compressed,int expected)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(expected);
        try(InflaterInputStream in=new InflaterInputStream(new ByteArrayInputStream(compressed))){
            byte[] buffer=new byte[8192];int read,total=0;while((read=in.read(buffer))>=0){total+=read;
                if(total>expected)throw new IllegalStateException("capture inflated length");
                out.write(buffer,0,read);}}byte[] raw=out.toByteArray();if(raw.length!=expected)
            throw new IllegalStateException("capture raw length");return raw;}
    private static void string(DataOutputStream out,String value)throws Exception {byte[] bytes=
            (value==null?"":value).getBytes(StandardCharsets.UTF_8);if(bytes.length>512)
        throw new IllegalArgumentException("string");out.writeShort(bytes.length);out.write(bytes);}
    private static String string(DataInputStream in)throws Exception {int length=in.readUnsignedShort();
        if(length>512)throw new IllegalStateException("string");byte[] bytes=new byte[length];
        in.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);}
    private static MicrostructureMarketRecord requireV2(CausalMarketRecord value,int version){
        if(version!=BLOCK_VERSION||!(value instanceof MicrostructureMarketRecord))
            throw new IllegalArgumentException("v2 record");return (MicrostructureMarketRecord)value;}
    private static void requireVersion2(int version){if(version!=BLOCK_VERSION)
        throw new IllegalStateException("unsupported V1 record kind");}
    private static void levels(DataOutputStream out,double[][] levels)throws Exception{
        out.writeByte(levels.length);for(double[] level:levels){out.writeDouble(level[0]);
            out.writeDouble(level[1]);}}
    private static double[][] levels(DataInputStream in)throws Exception{int count=in.readUnsignedByte();
        if(count!=20)throw new IllegalStateException("depth levels");double[][] out=new double[count][2];
        for(int i=0;i<count;i++){out[i][0]=in.readDouble();out[i][1]=in.readDouble();}return out;}
    private static String safePrefix(String input){StringBuilder out=new StringBuilder();for(char c:input.toCharArray())
        if(Character.isLetterOrDigit(c)||c=='-'||c=='_')out.append(c);if(out.length()==0)
            throw new IllegalArgumentException("prefix");return out.toString();}
    private boolean isSegmentName(String name){String start=prefix+"-";
        if(name==null||!name.startsWith(start)||!name.endsWith(SUFFIX))return false;
        String digits=name.substring(start.length(),name.length()-SUFFIX.length());
        if(digits.length()!=16)return false;for(int i=0;i<digits.length();i++)
            if(!Character.isDigit(digits.charAt(i)))return false;return true;}
    private static long id(File file){String name=file.getName();int dot=name.lastIndexOf('.');
        int dash=name.lastIndexOf('-',dot);if(dash<0||dot<=dash)return 0;try{return Long.parseLong(
                name.substring(dash+1,dot));}catch(NumberFormatException ignored){return 0;}}

    public static final class Snapshot implements AutoCloseable {
        public final List<File> files;private final CausalCaptureStore owner;private volatile boolean closed;
        Snapshot(CausalCaptureStore owner,List<File> files){this.owner=owner;
            this.files=Collections.unmodifiableList(new ArrayList<>(files));}
        public boolean closed(){return closed;}
        @Override public void close(){owner.release(this);}
    }
    public static final class ReadResult {
        public final List<CausalMarketRecord> records;public final int corruptBlocks,truncatedTails;
        ReadResult(List<CausalMarketRecord> records,int corruptBlocks,int truncatedTails){
            this.records=Collections.unmodifiableList(new ArrayList<>(records));
            this.corruptBlocks=corruptBlocks;this.truncatedTails=truncatedTails;}
    }
    public static final class ScanResult {
        public final long records;public final int corruptBlocks,truncatedTails;
        ScanResult(long records,int corruptBlocks,int truncatedTails){this.records=records;
            this.corruptBlocks=corruptBlocks;this.truncatedTails=truncatedTails;}
    }
}
