package io.kronos.bench;

import io.kronos.lsm.MemTable;
import io.kronos.lsm.SSTableWriter;
import io.kronos.lsm.SkipListMemTable;
import io.kronos.lsm.WalWriter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1 쓰기 처리량 벤치마크.
 *
 * <h2>측정 대상</h2>
 * <ul>
 *   <li>{@code memTableOnly}     — 순수 MemTable put (오프힙 쓰기 경로만)</li>
 *   <li>{@code memTablePlusWal}  — MemTable put + WAL DSYNC append (durable write)</li>
 *   <li>{@code endToEndFlush}    — N건 put → freeze → SSTable flush</li>
 * </ul>
 *
 * <h2>노트</h2>
 * <p>WAL은 {@link java.nio.file.StandardOpenOption#DSYNC}로 열린다 —
 * 수치는 디스크 특성(SSD/HDD, WSL2 ext4)에 크게 좌우된다.
 *
 * <p>{@code BATCH}는 단일 invocation당 처리하는 엔트리 수. MemTable flush
 * 임계값을 초과하지 않는 선에서 충분히 커야 루프 바디가 측정 가능하다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class LsmWriteBench {

    /** invocation당 처리할 엔트리 수. 16B × 10_000 = 160KB ≪ 4MB flush 임계값 */
    private static final int BATCH = 10_000;

    /** endToEndFlush에서 쓰는 엔트리 수 — flush 한 번이 의미를 갖도록 충분히 크게 */
    private static final int FLUSH_BATCH = 100_000;

    private Path tmpDir;

    // ── memTableOnly / memTablePlusWal 용 재사용 상태 ──
    private MemTable memTable;
    private WalWriter wal;
    private long tsCursor;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        tmpDir = Files.createTempDirectory("kronos-bench-");
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        // tmp 디렉토리 정리 — 얕은 삭제로 충분 (벤치 중 생긴 WAL/SST만 존재)
        try (var stream = Files.list(tmpDir)) {
            stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
        Files.deleteIfExists(tmpDir);
    }

    /**
     * 매 invocation마다 MemTable / WAL을 새로 만들어 flush 임계값·파일 크기가
     * 누적되지 않게 한다. 할당 비용 자체가 벤치 수치에 섞이지만, BATCH(10k)가
     * 충분히 커서 put 루프 비용이 지배적이다.
     */
    @Setup(Level.Invocation)
    public void setupInvocation() throws IOException {
        // flush 임계값을 넉넉히 → BATCH × 16B에 여유를 둔다
        memTable = new SkipListMemTable(8L * 1024 * 1024);
        wal = new WalWriter(tmpDir.resolve("wal-" + System.nanoTime() + ".log"));
        tsCursor = 1L;
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws IOException {
        wal.close();
        memTable.close();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Benchmarks
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 순수 MemTable put: 오프힙 버퍼에 [ts, value] 16B 기록 + TreeMap 인덱스 갱신.
     * 디스크 I/O 없음 — FFM set 비용과 TreeMap 삽입 비용의 합을 측정한다.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void memTableOnly(Blackhole bh) {
        for (int i = 0; i < BATCH; i++) {
            memTable.put(tsCursor++, 1.0);
        }
        bh.consume(memTable.size());
    }

    /**
     * MemTable + WAL 동기 기록. 각 put마다 16B DSYNC write가 발생하므로
     * 스토리지 fsync 지연이 직접 드러난다 — 실제 durable write 경로의 상한.
     */
    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void memTablePlusWal(Blackhole bh) throws IOException {
        for (int i = 0; i < BATCH; i++) {
            long ts = tsCursor++;
            wal.append(ts, 1.0);
            memTable.put(ts, 1.0);
        }
        bh.consume(memTable.size());
    }

    // ──────────────────────────────────────────────────────────────────────
    // End-to-end: put×N + freeze + SSTable flush
    //
    // setupInvocation의 재사용 상태를 쓰지 않고, 벤치 바디 안에서 새로 생성한다.
    // flush는 10만 건 축적 후 1회만 호출되므로 @OperationsPerInvocation으로
    // "엔트리 기준 throughput"을 얻는다.
    // ──────────────────────────────────────────────────────────────────────

    @Benchmark
    @OperationsPerInvocation(FLUSH_BATCH)
    public void endToEndFlush(Blackhole bh) throws IOException {
        try (MemTable mt = new SkipListMemTable(
                (long) FLUSH_BATCH * 16L * 2)) {  // 여유 2배
            long ts = 1L;
            for (int i = 0; i < FLUSH_BATCH; i++) {
                mt.put(ts++, 1.0);
            }
            mt.freeze();
            Path sst = tmpDir.resolve("sst-" + System.nanoTime() + ".dat");
            var meta = SSTableWriter.write(mt, sst);
            bh.consume(meta);
            Files.deleteIfExists(sst);
        }
    }
}
