package io.kronos.lsm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.*;

class SkipListMemTableTest {

    // ── 기본 put/get ──────────────────────────────────────────────

    @Test
    void put_and_get_single_entry() {
        try (var table = new SkipListMemTable()) {
            table.put(1000L, 3.14);
            assertThat(table.get(1000L)).hasValue(3.14);
        }
    }

    @Test
    void get_missing_key_returns_empty() {
        try (var table = new SkipListMemTable()) {
            assertThat(table.get(9999L)).isEqualTo(OptionalDouble.empty());
        }
    }

    @Test
    void put_overwrites_same_timestamp() {
        try (var table = new SkipListMemTable()) {
            table.put(1000L, 1.0);
            table.put(1000L, 2.0);

            assertThat(table.get(1000L)).hasValue(2.0);
            // 덮어쓰기는 엔트리 수를 늘리지 않는다
            assertThat(table.size()).isEqualTo(1);
        }
    }

    @Test
    void overwrite_does_not_increase_sizeBytes() {
        try (var table = new SkipListMemTable()) {
            table.put(1000L, 1.0);
            long sizeAfterFirst = table.sizeBytes();

            table.put(1000L, 2.0);
            assertThat(table.sizeBytes()).isEqualTo(sizeAfterFirst);
        }
    }

    // ── forEachInOrder ─────────────────────────────────────────────

    @Test
    void forEachInOrder_returns_entries_in_timestamp_order() {
        try (var table = new SkipListMemTable()) {
            table.put(3000L, 3.0);
            table.put(1000L, 1.0);
            table.put(2000L, 2.0);
            table.freeze();

            List<Long> timestamps = new ArrayList<>();
            table.forEachInOrder((ts, val) -> timestamps.add(ts));

            assertThat(timestamps).containsExactly(1000L, 2000L, 3000L);
        }
    }

    @Test
    void forEachInOrder_before_freeze_throws() {
        try (var table = new SkipListMemTable()) {
            table.put(1000L, 1.0);

            assertThatThrownBy(() -> table.forEachInOrder((ts, val) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freeze()");
        }
    }

    // ── freeze ──────────────────────────────────────────────────────

    @Test
    void put_after_freeze_throws() {
        try (var table = new SkipListMemTable()) {
            table.freeze();

            assertThatThrownBy(() -> table.put(1000L, 1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
        }
    }

    @Test
    void get_after_freeze_still_works() {
        try (var table = new SkipListMemTable()) {
            table.put(1000L, 42.0);
            table.freeze();

            assertThat(table.get(1000L)).hasValue(42.0);
        }
    }

    @Test
    void freeze_is_idempotent() {
        try (var table = new SkipListMemTable()) {
            table.freeze();
            assertThatCode(table::freeze).doesNotThrowAnyException();
            assertThat(table.isFrozen()).isTrue();
        }
    }

    // ── isFull ──────────────────────────────────────────────────────

    @Test
    void isFull_triggers_on_sizeBytes_threshold() {
        // 16바이트 임계값 → 1개 엔트리로 가득 참
        long threshold = 16L;
        try (var table = new SkipListMemTable(threshold)) {
            assertThat(table.isFull()).isFalse();
            table.put(1000L, 1.0);
            assertThat(table.isFull()).isTrue();
        }
    }

    @Test
    void isFull_not_triggered_by_overwrite() {
        // 16바이트 임계값, 동일 key 2번 put → 엔트리 1개, sizeBytes=16
        long threshold = 32L; // 엔트리 2개 이상이어야 full
        try (var table = new SkipListMemTable(threshold)) {
            table.put(1000L, 1.0);
            table.put(1000L, 2.0); // 덮어쓰기 — sizeBytes 증가 없음
            assertThat(table.isFull()).isFalse();
        }
    }

    // ── close 후 접근 ────────────────────────────────────────────────

    @Test
    void access_after_close_throws() {
        var table = new SkipListMemTable();
        table.put(1000L, 1.0);
        table.freeze();
        table.close();

        // MemorySegment는 Arena.close() 후 접근 시 IllegalStateException
        assertThatThrownBy(() -> table.get(1000L))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── 대량 쓰기 정합성 ────────────────────────────────────────────

    @Test
    void bulk_write_and_read_consistency() {
        int count = 10_000;
        // 10,000개 엔트리 × 16바이트 = 160KB → 4MB 임계값 미만
        try (var table = new SkipListMemTable()) {
            for (int i = 0; i < count; i++) {
                table.put((long) i * 1000, i * 0.1);
            }

            assertThat(table.size()).isEqualTo(count);

            for (int i = 0; i < count; i++) {
                assertThat(table.get((long) i * 1000))
                    .hasValueCloseTo(i * 0.1, within(1e-9));
            }
        }
    }

    @Test
    void forEachInOrder_covers_all_entries_in_order() {
        int count = 100;
        try (var table = new SkipListMemTable()) {
            // 역순으로 삽입
            for (int i = count - 1; i >= 0; i--) {
                table.put((long) i, i * 1.5);
            }
            table.freeze();

            List<Long> timestamps = new ArrayList<>();
            table.forEachInOrder((ts, val) -> timestamps.add(ts));

            assertThat(timestamps).hasSize(count);
            for (int i = 0; i < count; i++) {
                assertThat(timestamps.get(i)).isEqualTo((long) i);
            }
        }
    }
}
