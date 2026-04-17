package io.kronos.lsm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * {@link TreeMap} 인덱스 + 오프힙 연속 버퍼 기반 MemTable 구현체.
 *
 * <h2>내부 구조</h2>
 * <pre>
 * index: TreeMap&lt;Long, Long&gt;   ← timestamp → 오프힙 오프셋 (힙)
 * buffer: MemorySegment          ← [ts(8) | value(8)] × N (오프힙)
 * </pre>
 *
 * <p>인덱스를 힙에 두는 이유: Phase 1 단순성. flush 후 GC로 즉시 수거된다.
 * 상세 근거는 docs/ADR/ADR-002-memtable-heap-index.md 참조.
 *
 * <h2>스레드 안전성</h2>
 * put()은 단일 스레드 전용이다. freeze() 이후 forEachInOrder()는
 * flush 전담 스레드가 단독으로 호출한다.
 */
public final class SkipListMemTable implements MemTable {

    /** 엔트리당 오프힙 바이트: timestamp(8) + value(8) */
    private static final long ENTRY_BYTES = 16L;

    /** 기본 flush 임계값: 4MB (오프힙 데이터 기준) */
    public static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 4L * 1024 * 1024;

    /** timestamp → 오프힙 오프셋 (힙 인덱스) */
    private final TreeMap<Long, Long> index = new TreeMap<>();

    /** 오프힙 연속 데이터 버퍼 */
    private final Arena arena;
    private final MemorySegment buffer;

    /** 다음 신규 엔트리를 기록할 오프힙 오프셋 */
    private long writePosition = 0L;

    /** 오프힙 물리 사용 바이트 (덮어쓰기는 추가 계산 없음) */
    private long sizeBytes = 0L;

    private final long flushThresholdBytes;
    private final long entryCapacity;

    private boolean frozen = false;

    /**
     * @param flushThresholdBytes flush를 트리거할 오프힙 사용 바이트 임계값
     */
    public SkipListMemTable(long flushThresholdBytes) {
        if (flushThresholdBytes <= 0) {
            throw new IllegalArgumentException("flushThresholdBytes must be positive");
        }
        this.flushThresholdBytes = flushThresholdBytes;
        this.entryCapacity = flushThresholdBytes / ENTRY_BYTES;

        // 임계값의 1.1배를 사전 할당 — isFull() 검사 직후 put() 사이의
        // 미세 경쟁 조건에서도 버퍼 오버플로우가 발생하지 않도록 여유를 둔다.
        long bufferBytes = (long) (flushThresholdBytes * 1.1);
        this.arena = Arena.ofConfined();
        this.buffer = arena.allocate(bufferBytes, ValueLayout.JAVA_LONG.byteAlignment());
    }

    public SkipListMemTable() {
        this(DEFAULT_FLUSH_THRESHOLD_BYTES);
    }

    @Override
    public void put(long timestamp, double value) {
        if (frozen) {
            throw new IllegalStateException("MemTable is frozen — cannot write after freeze()");
        }

        Long existingOffset = index.get(timestamp);
        if (existingOffset != null) {
            // 동일 타임스탬프 덮어쓰기: 기존 오프셋에 value만 갱신.
            // writePosition, sizeBytes, index 모두 변경 없음.
            buffer.set(ValueLayout.JAVA_DOUBLE, existingOffset + 8, value);
            return;
        }

        if (writePosition + ENTRY_BYTES > buffer.byteSize()) {
            throw new IllegalStateException(
                "OffHeap buffer overflow — writePosition=" + writePosition
                + ", bufferSize=" + buffer.byteSize()
            );
        }

        long offset = writePosition;
        writePosition += ENTRY_BYTES;

        buffer.set(ValueLayout.JAVA_LONG, offset, timestamp);
        buffer.set(ValueLayout.JAVA_DOUBLE, offset + 8, value);
        index.put(timestamp, offset);
        sizeBytes += ENTRY_BYTES;
    }

    @Override
    public OptionalDouble get(long timestamp) {
        Long offset = index.get(timestamp);
        if (offset == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(buffer.get(ValueLayout.JAVA_DOUBLE, offset + 8));
    }

    @Override
    public void forEachInOrder(BiConsumer<Long, Double> consumer) {
        if (!frozen) {
            throw new IllegalStateException("Must call freeze() before forEachInOrder()");
        }
        // TreeMap은 key(=timestamp) 오름차순으로 entrySet()을 반환하므로
        // 별도 정렬 없이 시계열 순서가 보장된다.
        for (var entry : index.entrySet()) {
            long ts = entry.getKey();
            long offset = entry.getValue();
            double val = buffer.get(ValueLayout.JAVA_DOUBLE, offset + 8);
            consumer.accept(ts, val);
        }
    }

    @Override
    public Iterator<TimestampValuePair> iterator() {
        if (!frozen) {
            throw new IllegalStateException("Must call freeze() before iterator()");
        }
        Iterator<Map.Entry<Long, Long>> inner = index.entrySet().iterator();
        return new Iterator<>() {
            @Override public boolean hasNext() { return inner.hasNext(); }

            @Override
            public TimestampValuePair next() {
                if (!inner.hasNext()) throw new NoSuchElementException();
                Map.Entry<Long, Long> e = inner.next();
                long ts = e.getKey();
                long offset = e.getValue();
                double v = buffer.get(ValueLayout.JAVA_DOUBLE, offset + 8);
                return new TimestampValuePair(ts, v);
            }
        };
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public boolean isFull() {
        // 오프힙 사용량(바이트)과 논리적 엔트리 수 두 가지를 모두 확인한다.
        // 덮어쓰기가 많으면 sizeBytes 증가 없이 entryCapacity에 먼저 도달하고,
        // 단조 증가 키라면 sizeBytes가 먼저 임계값에 도달한다.
        return sizeBytes >= flushThresholdBytes || index.size() >= entryCapacity;
    }

    @Override
    public void freeze() {
        this.frozen = true;
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    @Override
    public void close() {
        arena.close();
    }
}
