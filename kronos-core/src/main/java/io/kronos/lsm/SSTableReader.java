package io.kronos.lsm;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

/**
 * SSTable 파일 리더.
 *
 * <p>{@link FileChannel#map(java.nio.channels.FileChannel.MapMode, long, long, Arena)}로
 * 파일을 mmap하여 랜덤 접근과 범위 스캔을 zero-copy로 수행한다.
 * {@link #close()} 한 번으로 mmap unmap까지 보장된다.
 *
 * <h2>데이터 접근 패턴</h2>
 * <ul>
 *   <li>{@link #get(long)}: 이진 탐색 O(log N)</li>
 *   <li>{@link #scan(long, long, BiConsumer)}: 이진 탐색으로 시작점 찾기 + 선형 스캔</li>
 * </ul>
 */
public final class SSTableReader implements Closeable {

    private static final long HEADER_BYTES = SSTableWriter.HEADER_BYTES;
    private static final long ENTRY_BYTES  = SSTableWriter.ENTRY_BYTES;

    private final Path path;
    // Arena 수명이 mmap 수명과 연동되므로 non-final이어도 close() 이후 접근 시 ISE 보장
    private Arena arena;
    private MemorySegment mapped;
    private long entryCount;
    private long minTs;
    private long maxTs;

    /**
     * SSTable 파일을 mmap으로 열고 헤더/푸터를 검증한다.
     *
     * @param path SSTable 파일 경로
     * @throws IOException 파일 I/O 오류, 매직 넘버 불일치, 체크섬 불일치
     */
    public SSTableReader(Path path) throws IOException {
        this.path = path;
        long fileSize = Files.size(path);
        Arena a = Arena.ofConfined();
        try {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                // FileChannel을 닫아도 mmap은 Arena 수명 동안 유지된다 (JEP 454)
                mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize, a);
            }

            // Header 검증
            int magic = mapped.get(ValueLayout.JAVA_INT, 0L);
            if (magic != SSTableWriter.MAGIC) {
                throw new IOException(
                    "Invalid SSTable magic: 0x" + Integer.toHexString(magic));
            }
            entryCount = mapped.get(ValueLayout.JAVA_LONG, 8L);

            // Footer 읽기
            long fo = HEADER_BYTES + entryCount * ENTRY_BYTES;
            minTs = mapped.get(ValueLayout.JAVA_LONG, fo);
            maxTs = mapped.get(ValueLayout.JAVA_LONG, fo + 8L);

            // 체크섬 검증
            int stored = mapped.get(ValueLayout.JAVA_INT, fo + 16L);
            CRC32 crc32 = new CRC32();
            crc32.update(mapped.asSlice(HEADER_BYTES, entryCount * ENTRY_BYTES).asByteBuffer());
            int computed = (int) crc32.getValue();
            if (computed != stored) {
                throw new IOException(
                    "SSTable checksum mismatch: stored=0x" + Integer.toHexString(stored)
                    + ", computed=0x" + Integer.toHexString(computed));
            }

            this.arena = a;
        } catch (Exception e) {
            a.close();
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException(e);
        }
    }

    /**
     * 정확한 타임스탬프로 값을 조회한다.
     *
     * <p>데이터 블록을 이진 탐색하므로 O(log N).
     */
    public OptionalDouble get(long timestamp) {
        long lo = 0, hi = entryCount - 1;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            long ts = readTimestamp(mid);
            if (ts == timestamp) return OptionalDouble.of(readValue(mid));
            if (ts < timestamp) lo = mid + 1;
            else hi = mid - 1;
        }
        return OptionalDouble.empty();
    }

    /**
     * {@code [startTs, endTs]} 범위의 모든 엔트리를 타임스탬프 오름차순으로 순회한다.
     *
     * <p>minTs/maxTs로 빠른 거부(quick reject) 후,
     * 이진 탐색으로 시작 인덱스를 찾고 선형 스캔한다.
     *
     * @param startTs 범위 시작 (inclusive)
     * @param endTs   범위 끝 (inclusive)
     */
    public void scan(long startTs, long endTs, BiConsumer<Long, Double> consumer) {
        // Quick reject: 범위가 겹치지 않으면 파일 접근 없이 즉시 반환
        if (maxTs < startTs || minTs > endTs) return;

        // 이진 탐색: startTs 이상인 첫 번째 인덱스 찾기
        long lo = 0, hi = entryCount - 1, first = entryCount;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            if (readTimestamp(mid) >= startTs) {
                first = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }

        // 선형 스캔
        for (long i = first; i < entryCount; i++) {
            long ts = readTimestamp(i);
            if (ts > endTs) break;
            consumer.accept(ts, readValue(i));
        }
    }

    /** 이 리더의 메타데이터를 반환한다. */
    public SSTableMeta meta() {
        return new SSTableMeta(path, minTs, maxTs, entryCount);
    }

    /**
     * 파일의 모든 엔트리를 타임스탬프 오름차순으로 한 번 순회하는 이터레이터.
     *
     * <p>데이터 블록은 정렬 상태로 기록되므로 별도 정렬 없이 선형 스캔만 수행한다.
     * k-way merge 경로 전용 — 랜덤 접근이 필요하면 {@link #get(long)}을 사용한다.
     *
     * <p>반환된 이터레이터는 이 리더의 mmap에 의존한다. {@link #close()} 이후
     * 접근하면 {@link IllegalStateException}이 발생한다.
     */
    public Iterator<TimestampValuePair> iterator() {
        return new Iterator<>() {
            private long cursor = 0L;

            @Override
            public boolean hasNext() {
                return cursor < entryCount;
            }

            @Override
            public TimestampValuePair next() {
                if (cursor >= entryCount) {
                    throw new NoSuchElementException();
                }
                long ts  = readTimestamp(cursor);
                double v = readValue(cursor);
                cursor++;
                return new TimestampValuePair(ts, v);
            }
        };
    }

    /** mmap unmap 및 Arena 해제. */
    @Override
    public void close() {
        arena.close();
    }

    // ── 내부 오프셋 접근자 ─────────────────────────────────────────────

    private long readTimestamp(long idx) {
        return mapped.get(ValueLayout.JAVA_LONG, HEADER_BYTES + idx * ENTRY_BYTES);
    }

    private double readValue(long idx) {
        return mapped.get(ValueLayout.JAVA_DOUBLE, HEADER_BYTES + idx * ENTRY_BYTES + 8L);
    }
}
