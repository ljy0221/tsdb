package io.kronos.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class LsmReadViewTest {

    @TempDir
    Path tempDir;

    /** MemTable에 엔트리를 기록한 뒤 SSTable로 flush하고 Reader를 연다. */
    private SSTableReader flushToSstable(String name, long[] ts, double[] vals) throws IOException {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            for (int i = 0; i < ts.length; i++) mt.put(ts[i], vals[i]);
            mt.freeze();
            Path path = tempDir.resolve(name);
            SSTableWriter.write(mt, path);
            return new SSTableReader(path);
        }
    }

    // ── get: 소스 우선순위 ────────────────────────────────────────────

    @Test
    void get_prefers_memtable_over_sstables_for_same_timestamp() throws IOException {
        SSTableReader older = flushToSstable("older.sst",
            new long[]{100, 200}, new double[]{1.0, 2.0});
        try (older) {
            try (SkipListMemTable mt = new SkipListMemTable()) {
                mt.put(100L, 999.0); // 같은 ts를 덮어쓸 값
                mt.freeze();

                var view = new LsmReadView(mt, List.of(older));
                assertThat(view.get(100L)).hasValue(999.0); // MemTable 승
                assertThat(view.get(200L)).hasValue(2.0);   // SSTable에서
            }
        }
    }

    @Test
    void get_prefers_newer_sstable_over_older_for_same_timestamp() throws IOException {
        SSTableReader newer = flushToSstable("newer.sst",
            new long[]{50, 100}, new double[]{5.5, 10.5});
        SSTableReader older = flushToSstable("older.sst",
            new long[]{50, 150}, new double[]{0.0, 15.0});

        try (newer; older) {
            var view = new LsmReadView(null, List.of(newer, older));
            assertThat(view.get(50L)).hasValue(5.5);   // newer 승
            assertThat(view.get(100L)).hasValue(10.5); // newer only
            assertThat(view.get(150L)).hasValue(15.0); // older only
            assertThat(view.get(999L)).isEmpty();
        }
    }

    @Test
    void get_returns_empty_when_timestamp_missing_in_all_sources() throws IOException {
        SSTableReader sst = flushToSstable("only.sst",
            new long[]{10, 20, 30}, new double[]{1, 2, 3});
        try (sst) {
            var view = new LsmReadView(null, List.of(sst));
            assertThat(view.get(25L)).isEmpty();
            assertThat(view.get(1000L)).isEmpty();
        }
    }

    // ── scan: 병합 결과 ──────────────────────────────────────────────

    @Test
    void scan_merges_memtable_and_sstables_in_timestamp_order() throws IOException {
        SSTableReader sstOld = flushToSstable("old.sst",
            new long[]{10, 40, 70}, new double[]{1, 4, 7});
        SSTableReader sstNew = flushToSstable("new.sst",
            new long[]{20, 50, 80}, new double[]{2, 5, 8});

        try (sstNew; sstOld) {
            try (SkipListMemTable mt = new SkipListMemTable()) {
                mt.put(30L, 3);
                mt.put(60L, 6);
                mt.put(90L, 9);
                mt.freeze();

                var view = new LsmReadView(mt, List.of(sstNew, sstOld));
                List<Long> tsOut = new ArrayList<>();
                List<Double> valOut = new ArrayList<>();
                view.scan(Long.MIN_VALUE, Long.MAX_VALUE, (ts, v) -> {
                    tsOut.add(ts); valOut.add(v);
                });

                assertThat(tsOut).containsExactly(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L);
                assertThat(valOut).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
            }
        }
    }

    @Test
    void scan_applies_range_filter() throws IOException {
        SSTableReader sst = flushToSstable("sst.sst",
            new long[]{10, 20, 30, 40, 50}, new double[]{1, 2, 3, 4, 5});
        try (sst) {
            var view = new LsmReadView(null, List.of(sst));
            List<Long> out = new ArrayList<>();
            view.scan(20L, 40L, (ts, v) -> out.add(ts));
            assertThat(out).containsExactly(20L, 30L, 40L);
        }
    }

    @Test
    void scan_deduplicates_newest_wins_across_sources() throws IOException {
        SSTableReader sstOld = flushToSstable("old.sst",
            new long[]{100, 200}, new double[]{0.0, 2.0});
        SSTableReader sstNew = flushToSstable("new.sst",
            new long[]{100, 150}, new double[]{1.0, 1.5});

        try (sstNew; sstOld) {
            try (SkipListMemTable mt = new SkipListMemTable()) {
                mt.put(100L, 99.0); // 최종 승자
                mt.freeze();

                var view = new LsmReadView(mt, List.of(sstNew, sstOld));
                List<TimestampValuePair> out = new ArrayList<>();
                view.scan(Long.MIN_VALUE, Long.MAX_VALUE,
                    (ts, v) -> out.add(new TimestampValuePair(ts, v)));

                assertThat(out).containsExactly(
                    new TimestampValuePair(100L, 99.0),  // mem 승
                    new TimestampValuePair(150L, 1.5),
                    new TimestampValuePair(200L, 2.0)
                );
            }
        }
    }

    @Test
    void scan_skips_sstable_outside_range_without_iterating() throws IOException {
        // 범위와 겹치지 않는 SSTable은 iterator 생성도 하지 말아야 한다.
        SSTableReader farOld = flushToSstable("far-old.sst",
            new long[]{1000, 2000}, new double[]{1, 2});
        SSTableReader target = flushToSstable("target.sst",
            new long[]{10, 20}, new double[]{10, 20});

        try (farOld; target) {
            var view = new LsmReadView(null, List.of(target, farOld));
            List<Long> out = new ArrayList<>();
            view.scan(0L, 100L, (ts, v) -> out.add(ts));
            assertThat(out).containsExactly(10L, 20L);
        }
    }

    @Test
    void scan_on_empty_view_produces_nothing() {
        var view = new LsmReadView(null, List.of());
        List<Long> out = new ArrayList<>();
        view.scan(0L, 1000L, (ts, v) -> out.add(ts));
        assertThat(out).isEmpty();
    }

    // ── 생성 검증 ────────────────────────────────────────────────────

    @Test
    void constructor_rejects_non_frozen_memtable() {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            mt.put(1L, 1.0);
            assertThatThrownBy(() -> new LsmReadView(mt, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
        }
    }
}
