package io.kronos.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * FFM API 기반 오프힙 long 배열.
 * GC 영향 없이 대용량 long 데이터를 저장한다.
 *
 * <p>반드시 try-with-resources 또는 명시적 close()로 사용할 것.
 * close() 후 접근 시 IllegalStateException 발생.
 */
public final class OffHeapLongArray implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final long length;

    public OffHeapLongArray(long length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        this.length = length;
        this.arena = Arena.ofConfined();
        this.segment = arena.allocate(
            ValueLayout.JAVA_LONG.byteSize() * length,
            ValueLayout.JAVA_LONG.byteAlignment()
        );
    }

    public long get(long index) {
        checkIndex(index);
        return segment.get(ValueLayout.JAVA_LONG, index * Long.BYTES);
    }

    public void set(long index, long value) {
        checkIndex(index);
        segment.set(ValueLayout.JAVA_LONG, index * Long.BYTES, value);
    }

    public long length() {
        return length;
    }

    @Override
    public void close() {
        arena.close();
    }

    private void checkIndex(long index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index " + index + " out of bounds for length " + length);
        }
    }
}
