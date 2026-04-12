package io.kronos.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SSTableWriterTest {

    @TempDir
    Path tempDir;

    // ── 헬퍼 ──────────────────────────────────────────────────────────

    /** timestamp = i * 1000, value = i * 0.5 인 frozen MemTable 생성 */
    private SkipListMemTable buildFrozen(int count) {
        SkipListMemTable mt = new SkipListMemTable();
        for (int i = 0; i < count; i++) {
            mt.put((long) i * 1000L, i * 0.5);
        }
        mt.freeze();
        return mt;
    }

    // ── write: 파일 크기 ──────────────────────────────────────────────

    @Test
    void write_creates_file_with_correct_size() throws IOException {
        int n = 100;
        try (SkipListMemTable mt = buildFrozen(n)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);
            // 16(header) + n*16(data) + 20(footer)
            assertThat(Files.size(sst)).isEqualTo(16L + (long) n * 16 + 20L);
        }
    }

    @Test
    void write_single_entry_produces_52_bytes() throws IOException {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            mt.put(42L, 3.14);
            mt.freeze();
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);
            assertThat(Files.size(sst)).isEqualTo(52L); // 16 + 16 + 20
        }
    }

    // ── write: 예외 케이스 ────────────────────────────────────────────

    @Test
    void write_throws_if_not_frozen() {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            mt.put(1000L, 1.0);
            Path sst = tempDir.resolve("sst-0-0.sst");
            assertThatThrownBy(() -> SSTableWriter.write(mt, sst))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
        }
    }

    @Test
    void write_throws_if_empty() {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            mt.freeze();
            Path sst = tempDir.resolve("sst-0-0.sst");
            assertThatThrownBy(() -> SSTableWriter.write(mt, sst))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── write: 메타데이터 ─────────────────────────────────────────────

    @Test
    void write_returns_meta_with_correct_minmax_and_count() throws IOException {
        try (SkipListMemTable mt = buildFrozen(10)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableMeta meta = SSTableWriter.write(mt, sst);

            assertThat(meta.path()).isEqualTo(sst);
            assertThat(meta.entryCount()).isEqualTo(10);
            assertThat(meta.minTimestamp()).isEqualTo(0L);
            assertThat(meta.maxTimestamp()).isEqualTo(9000L);
        }
    }

    @Test
    void meta_overlaps_returns_true_for_intersecting_range() throws IOException {
        try (SkipListMemTable mt = buildFrozen(10)) { // ts: 0 ~ 9000
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableMeta meta = SSTableWriter.write(mt, sst);

            assertThat(meta.overlaps(5000L, 15000L)).isTrue();
            assertThat(meta.overlaps(0L, 0L)).isTrue();
        }
    }

    @Test
    void meta_overlaps_returns_false_for_non_intersecting_range() throws IOException {
        try (SkipListMemTable mt = buildFrozen(10)) { // ts: 0 ~ 9000
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableMeta meta = SSTableWriter.write(mt, sst);

            assertThat(meta.overlaps(10000L, 20000L)).isFalse();
            assertThat(meta.overlaps(-2000L, -1L)).isFalse();
        }
    }

    // ── reader: 정확한 조회 ───────────────────────────────────────────

    @Test
    void reader_get_returns_all_written_values() throws IOException {
        int n = 50;
        try (SkipListMemTable mt = buildFrozen(n)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                for (int i = 0; i < n; i++) {
                    long ts = (long) i * 1000L;
                    assertThat(reader.get(ts))
                        .as("ts=%d", ts)
                        .hasValue(i * 0.5);
                }
            }
        }
    }

    @Test
    void reader_get_single_entry_roundtrip() throws IOException {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            mt.put(42L, 3.14);
            mt.freeze();
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                assertThat(reader.get(42L)).hasValue(3.14);
                assertThat(reader.meta().minTimestamp()).isEqualTo(42L);
                assertThat(reader.meta().maxTimestamp()).isEqualTo(42L);
            }
        }
    }

    @Test
    void reader_get_returns_empty_for_missing_timestamp() throws IOException {
        try (SkipListMemTable mt = buildFrozen(10)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                assertThat(reader.get(999_999L)).isEmpty();
                assertThat(reader.get(-1L)).isEmpty();
                assertThat(reader.get(500L)).isEmpty(); // 사이 값
            }
        }
    }

    // ── reader: 범위 스캔 ─────────────────────────────────────────────

    @Test
    void reader_scan_returns_entries_in_range() throws IOException {
        // ts: 0, 1000, 2000, ..., 19000
        try (SkipListMemTable mt = buildFrozen(20)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                List<Long> timestamps = new ArrayList<>();
                reader.scan(3000L, 7000L, (ts, val) -> timestamps.add(ts));
                assertThat(timestamps)
                    .containsExactly(3000L, 4000L, 5000L, 6000L, 7000L);
            }
        }
    }

    @Test
    void reader_scan_full_range() throws IOException {
        int n = 10;
        try (SkipListMemTable mt = buildFrozen(n)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                List<Long> timestamps = new ArrayList<>();
                reader.scan(Long.MIN_VALUE, Long.MAX_VALUE, (ts, val) -> timestamps.add(ts));
                assertThat(timestamps).hasSize(n);
                assertThat(timestamps.get(0)).isEqualTo(0L);
                assertThat(timestamps.get(n - 1)).isEqualTo((long) (n - 1) * 1000L);
            }
        }
    }

    @Test
    void reader_scan_returns_nothing_for_non_overlapping_range() throws IOException {
        try (SkipListMemTable mt = buildFrozen(10)) { // ts: 0 ~ 9000
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                List<Long> result = new ArrayList<>();
                reader.scan(100_000L, 200_000L, (ts, val) -> result.add(ts));
                assertThat(result).isEmpty();
            }
        }
    }

    // ── reader: 메타 / 무결성 ─────────────────────────────────────────

    @Test
    void reader_meta_matches_written_data() throws IOException {
        try (SkipListMemTable mt = buildFrozen(10)) {
            Path sst = tempDir.resolve("sst-0-0.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                SSTableMeta meta = reader.meta();
                assertThat(meta.minTimestamp()).isEqualTo(0L);
                assertThat(meta.maxTimestamp()).isEqualTo(9000L);
                assertThat(meta.entryCount()).isEqualTo(10);
                assertThat(meta.path()).isEqualTo(sst);
            }
        }
    }

    @Test
    void reader_throws_on_invalid_magic() throws IOException {
        Path sst = tempDir.resolve("sst-0-0.sst");
        // 올바른 크기지만 매직 넘버가 0인 파일
        Files.write(sst, new byte[52]);
        assertThatThrownBy(() -> new SSTableReader(sst))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("magic");
    }
}
