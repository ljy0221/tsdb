package io.kronos.bench;

import io.kronos.lsm.LsmReadView;
import io.kronos.lsm.SSTableReader;
import io.kronos.lsm.SSTableWriter;
import io.kronos.lsm.SkipListMemTable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 벤치 고정(fixture). {@code totalEntries}개의 타임스탬프(0..total-1)를
 * {@code fileCount}개의 비중첩 SSTable로 분할 기록하고, 열린 리더 리스트를
 * 보유한다. 매 trial 시작 시 build(), 종료 시 close() 한다.
 */
public final class ScanBenchFixture implements AutoCloseable {

    private final List<SSTableReader> readers;
    private final LsmReadView readView;
    private final long dataMin;
    private final long dataMax;
    private boolean closed = false;

    private ScanBenchFixture(List<SSTableReader> readers, LsmReadView view,
                             long dataMin, long dataMax) {
        this.readers = readers;
        this.readView = view;
        this.dataMin = dataMin;
        this.dataMax = dataMax;
    }

    public List<SSTableReader> readers() { return readers; }
    public LsmReadView readView()        { return readView; }
    public long dataMin()                { return dataMin; }
    public long dataMax()                { return dataMax; }

    public static ScanBenchFixture build(Path dir, int totalEntries, int fileCount)
            throws IOException {
        if (fileCount <= 0) {
            throw new IllegalArgumentException("fileCount must be > 0");
        }
        if (totalEntries % fileCount != 0) {
            throw new IllegalArgumentException(
                    "totalEntries (" + totalEntries + ") must be divisible by fileCount ("
                    + fileCount + ")");
        }
        int perFile = totalEntries / fileCount;
        long bufferBytes = Math.max((long) perFile * 32L, 1024L);

        List<SSTableReader> readers = new ArrayList<>(fileCount);
        for (int f = 0; f < fileCount; f++) {
            SkipListMemTable mt = new SkipListMemTable(bufferBytes);
            for (int i = 0; i < perFile; i++) {
                long ts = (long) f * perFile + i;
                mt.put(ts, ts * 1.5);
            }
            mt.freeze();
            Path path = dir.resolve("sst-" + f + ".sst");
            SSTableWriter.write(mt, path);
            mt.close();
            readers.add(new SSTableReader(path));
        }
        Collections.reverse(readers);

        long dataMin = 0L;
        long dataMax = (long) totalEntries - 1L;
        LsmReadView view = new LsmReadView(null, readers);
        return new ScanBenchFixture(readers, view, dataMin, dataMax);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (SSTableReader r : readers) {
            try { r.close(); } catch (Exception ignored) {}
        }
    }
}
