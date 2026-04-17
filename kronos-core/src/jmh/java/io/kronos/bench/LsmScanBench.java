package io.kronos.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 범위 스캔 성능 측정. Phase 2 다음 ADR(내부 인덱스 vs Gorilla 압축) 방향을
 * spec의 판정식으로 결정하기 위한 베이스라인.
 *
 * <p>축: selectivity × distribution × fileCount = 4 × 2 × 2 = 16 조합.
 * 각 조합에서 p50/p99 latency 측정 (SampleTime + 10 iterations × 3s × 2 forks).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 3)
@Fork(2)
public class LsmScanBench {

    /** Spec §3 기준 규모. 1M × 16 B = 16 MB → WSL2 page cache에 안착. */
    private static final int TOTAL_ENTRIES = 1_000_000;

    @Param({"0.01", "0.1", "0.5", "1.0"})
    public double selectivity;

    @Param({"UNIFORM", "RECENT"})
    public ScanDistribution distribution;

    @Param({"1", "4"})
    public int fileCount;

    private Path tmpDir;
    private ScanBenchFixture fixture;

    private Random rng;

    private long currentStart;
    private long currentEnd;

    @Setup(Level.Trial)
    public void setUpTrial() throws IOException {
        tmpDir = Files.createTempDirectory("lsm-scan-bench-");
        fixture = ScanBenchFixture.build(tmpDir, TOTAL_ENTRIES, fileCount);
        long seed = 0xC0FFEEL ^ ((long) fileCount << 32) ^ distribution.ordinal();
        rng = new Random(seed);
    }

    @Setup(Level.Invocation)
    public void pickRange() {
        long[] r = distribution.range(fixture.dataMin(), fixture.dataMax(), selectivity, rng);
        currentStart = r[0];
        currentEnd = r[1];
    }

    @Benchmark
    public void scanCount(Blackhole bh) {
        long n = fixture.readView().scanCount(currentStart, currentEnd);
        bh.consume(n);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        if (fixture != null) fixture.close();
        if (tmpDir != null) {
            try (Stream<Path> walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }
}
