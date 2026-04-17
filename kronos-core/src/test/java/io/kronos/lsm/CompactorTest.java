package io.kronos.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactorTest {

    @TempDir
    Path tempDir;

    /** generation 접두사를 포함한 이름으로 SSTable을 flush하고 Reader를 연다. */
    private SSTableReader flush(int generation, int seq, long[] ts, double[] vals) throws IOException {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            for (int i = 0; i < ts.length; i++) mt.put(ts[i], vals[i]);
            mt.freeze();
            // nanotime 충돌 방지를 위해 seq를 끼워넣는다
            Path path = tempDir.resolve("sst-" + generation + "-" + (System.nanoTime() + seq) + ".sst");
            SSTableWriter.write(mt, path);
            return new SSTableReader(path);
        }
    }

    // ── 트리거 조건 ─────────────────────────────────────────────────────

    @Test
    void below_trigger_returns_empty() throws IOException {
        SSTableReader r1 = flush(0, 1, new long[]{10}, new double[]{1.0});
        SSTableReader r2 = flush(0, 2, new long[]{20}, new double[]{2.0});
        try (r1; r2) {
            Compactor compactor = new Compactor(tempDir, 3);
            assertThat(compactor.maybeCompact(List.of(r1, r2))).isEmpty();
        }
    }

    @Test
    void constructor_rejects_trigger_less_than_two() {
        assertThatThrownBy(() -> new Compactor(tempDir, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trigger must be >= 2");
    }

    // ── Phase 1 완료 기준 시나리오 ──────────────────────────────────────

    /**
     * ADR-004 테스트 시나리오: N개 flush → maybeCompact → 새 뷰에서 정합성 확인.
     * size-tiered N-to-1이 newest-wins를 지키면서 모든 엔트리를 보존하는지 검증한다.
     */
    @Test
    void compacts_sstables_and_preserves_entries_with_newest_wins() throws IOException {
        // newest (idx 0)  : ts 10, 20  — ts=20 값은 새것이 승자
        // mid             : ts 20, 30  — ts=20은 newest에 의해 가려짐
        // oldest (idx 2)  : ts 40, 50
        SSTableReader newest = flush(0, 1, new long[]{10, 20}, new double[]{10.0, TS20_NEW});
        SSTableReader mid    = flush(0, 2, new long[]{20, 30}, new double[]{TS20_OLD, 30.0});
        SSTableReader oldest = flush(0, 3, new long[]{40, 50}, new double[]{40.0, 50.0});

        Compactor compactor = new Compactor(tempDir, 2);
        Optional<CompactionResult> result =
            compactor.maybeCompact(List.of(newest, mid, oldest));

        assertThat(result).isPresent();
        Path newPath = result.get().newSSTable();
        assertThat(Files.exists(newPath)).isTrue();
        // generation이 1 증가했는지
        assertThat(newPath.getFileName().toString()).startsWith("sst-1-");

        // 새 SSTable 하나만 들어간 뷰를 만들고 전체 스캔 정합성 확인
        try (SSTableReader newReader = new SSTableReader(newPath)) {
            LsmReadView view = new LsmReadView(null, List.of(newReader));

            assertThat(view.get(10L)).hasValue(10.0);
            assertThat(view.get(20L)).hasValue(TS20_NEW);   // newest-wins
            assertThat(view.get(30L)).hasValue(30.0);
            assertThat(view.get(40L)).hasValue(40.0);
            assertThat(view.get(50L)).hasValue(50.0);

            // 범위 스캔: ts 오름차순 + 중복 drain 확인
            TreeMap<Long, Double> scanned = new TreeMap<>();
            view.scan(Long.MIN_VALUE, Long.MAX_VALUE, scanned::put);
            assertThat(scanned).containsExactly(
                java.util.Map.entry(10L, 10.0),
                java.util.Map.entry(20L, TS20_NEW),
                java.util.Map.entry(30L, 30.0),
                java.util.Map.entry(40L, 40.0),
                java.util.Map.entry(50L, 50.0)
            );
        }

        // retireObsolete 전: 옛 파일은 아직 존재
        for (SSTableReader r : result.get().obsoleteReaders()) {
            assertThat(Files.exists(r.meta().path())).isTrue();
        }

        // 호출자가 옛 뷰 사용을 끝냈다고 선언 → 해제 + 삭제
        result.get().retireObsolete();
        for (SSTableReader r : result.get().obsoleteReaders()) {
            assertThat(Files.exists(r.meta().path())).isFalse();
        }
    }

    // ── 파일명 규약 ─────────────────────────────────────────────────────

    @Test
    void new_generation_is_max_input_plus_one() throws IOException {
        SSTableReader gen0 = flush(0, 1, new long[]{1}, new double[]{1.0});
        SSTableReader gen3 = flush(3, 2, new long[]{2}, new double[]{2.0});

        Compactor compactor = new Compactor(tempDir, 2);
        Optional<CompactionResult> result = compactor.maybeCompact(List.of(gen0, gen3));

        try {
            assertThat(result).isPresent();
            assertThat(Compactor.generationOf(result.get().newSSTable())).isEqualTo(4);
        } finally {
            result.ifPresent(r -> {
                try { r.retireObsolete(); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void generationOf_returns_zero_for_nonmatching_name() {
        assertThat(Compactor.generationOf(Path.of("/tmp/arbitrary.sst"))).isZero();
        assertThat(Compactor.generationOf(Path.of("/tmp/sst-1-abc.sst"))).isZero();
    }

    // ── 10만 건 정합성 (Phase 1 완료 기준) ──────────────────────────────

    /**
     * Phase 1 완료 기준: "10만 건 write → flush → read 정합성".
     * compaction을 중간에 끼워넣어도 모든 엔트리가 보존되는지 확인한다.
     */
    @Test
    void hundred_thousand_entries_survive_compaction() throws IOException {
        // 10만 건을 3개 SSTable로 나눠 flush — 키 범위가 서로 겹치지 않도록 구성
        List<SSTableReader> readers = new ArrayList<>();
        int totalEntries = 100_000;
        int chunkSize = totalEntries / 3;
        int createdSoFar = 0;
        for (int chunk = 0; chunk < 3; chunk++) {
            int size = (chunk == 2) ? totalEntries - createdSoFar : chunkSize;
            long[] ts = new long[size];
            double[] vs = new double[size];
            for (int i = 0; i < size; i++) {
                long t = (long) createdSoFar + i + 1;
                ts[i] = t;
                vs[i] = t * 0.5;
            }
            readers.add(flush(0, chunk + 1, ts, vs));
            createdSoFar += size;
        }

        Compactor compactor = new Compactor(tempDir, 3);
        Optional<CompactionResult> result = compactor.maybeCompact(readers);

        assertThat(result).isPresent();
        try (SSTableReader merged = new SSTableReader(result.get().newSSTable())) {
            assertThat(merged.meta().entryCount()).isEqualTo(totalEntries);
            assertThat(merged.meta().minTimestamp()).isEqualTo(1L);
            assertThat(merged.meta().maxTimestamp()).isEqualTo(totalEntries);

            // 전체 스캔 후 엔트리 개수 + 단조 증가 확인
            long[] count = {0};
            long[] prev = {Long.MIN_VALUE};
            merged.scan(Long.MIN_VALUE, Long.MAX_VALUE, (t, v) -> {
                assertThat(t).isGreaterThan(prev[0]);
                assertThat(v).isEqualTo(t * 0.5);
                prev[0] = t;
                count[0]++;
            });
            assertThat(count[0]).isEqualTo(totalEntries);
        }

        result.get().retireObsolete();
    }

    // ── 상수 (가독성) ───────────────────────────────────────────────────

    /** newest-wins 검증용 상수 — 같은 ts에서 newer SSTable이 이 값을 쓴다 */
    private static final double TS20_NEW = 222.0;
    /** 같은 ts에서 older SSTable이 이 값을 쓴다. 결과엔 나오면 안 됨 */
    private static final double TS20_OLD = 111.0;
}
