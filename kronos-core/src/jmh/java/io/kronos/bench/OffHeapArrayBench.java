package io.kronos.bench;

import io.kronos.memory.OffHeapLongArray;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class OffHeapArrayBench {

    private static final int SIZE = 1_000_000;

    private long[] heapArray;
    private OffHeapLongArray offHeapArray;

    @Setup
    public void setup() {
        heapArray = new long[SIZE];
        offHeapArray = new OffHeapLongArray(SIZE);
    }

    @TearDown
    public void tearDown() {
        offHeapArray.close();
    }

    @Benchmark
    public void heapWrite(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            heapArray[i] = i;
        }
    }

    @Benchmark
    public void offHeapWrite(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            offHeapArray.set(i, i);
        }
    }

    @Benchmark
    public void heapRead(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(heapArray[i]);
        }
    }

    @Benchmark
    public void offHeapRead(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            bh.consume(offHeapArray.get(i));
        }
    }
}
