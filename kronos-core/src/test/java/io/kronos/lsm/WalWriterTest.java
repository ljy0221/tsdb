package io.kronos.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class WalWriterTest {

    @TempDir
    Path tempDir;

    // ── append / 파일 크기 ─────────────────────────────────────────

    @Test
    void append_single_entry_writes_16_bytes() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        try (var writer = new WalWriter(wal)) {
            writer.append(1000L, 3.14);
        }
        assertThat(Files.size(wal)).isEqualTo(16L);
    }

    @Test
    void append_n_entries_writes_n_times_16_bytes() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        int n = 100;
        try (var writer = new WalWriter(wal)) {
            for (int i = 0; i < n; i++) {
                writer.append((long) i * 1000, i * 0.5);
            }
        }
        assertThat(Files.size(wal)).isEqualTo(16L * n);
    }

    // ── 내구성: close 후 파일에 데이터 잔존 ─────────────────────────

    @Test
    void appended_entries_survive_close_and_reopen() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        try (var writer = new WalWriter(wal)) {
            writer.append(1_000L, 1.1);
            writer.append(2_000L, 2.2);
            writer.append(3_000L, 3.3);
        }

        // WalReader로 복구
        var entries = WalReader.readAll(wal);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).timestamp()).isEqualTo(1_000L);
        assertThat(entries.get(0).value()).isCloseTo(1.1, within(1e-9));
        assertThat(entries.get(1).timestamp()).isEqualTo(2_000L);
        assertThat(entries.get(1).value()).isCloseTo(2.2, within(1e-9));
        assertThat(entries.get(2).timestamp()).isEqualTo(3_000L);
        assertThat(entries.get(2).value()).isCloseTo(3.3, within(1e-9));
    }

    // ── append 순서 보존 ────────────────────────────────────────────

    @Test
    void entries_are_written_in_append_order() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        // 역순 타임스탬프로 기록 → WAL은 정렬하지 않고 순서대로 저장
        try (var writer = new WalWriter(wal)) {
            writer.append(3_000L, 3.0);
            writer.append(1_000L, 1.0);
            writer.append(2_000L, 2.0);
        }

        var entries = WalReader.readAll(wal);
        assertThat(entries).extracting(TimestampValuePair::timestamp)
                           .containsExactly(3_000L, 1_000L, 2_000L);
    }

    // ── double precision 보존 ────────────────────────────────────────

    @Test
    void append_preserves_double_precision() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        double precise = Math.PI;
        try (var writer = new WalWriter(wal)) {
            writer.append(42L, precise);
        }

        var entries = WalReader.readAll(wal);
        assertThat(entries.get(0).value()).isEqualTo(precise); // 정확히 일치해야 함
    }

    // ── 기존 파일에 이어쓰기 (APPEND 모드) ───────────────────────────

    @Test
    void reopening_writer_appends_to_existing_file() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");

        try (var writer = new WalWriter(wal)) {
            writer.append(1_000L, 1.0);
        }
        try (var writer = new WalWriter(wal)) {
            writer.append(2_000L, 2.0);
        }

        var entries = WalReader.readAll(wal);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).timestamp()).isEqualTo(1_000L);
        assertThat(entries.get(1).timestamp()).isEqualTo(2_000L);
    }

    // ── close 후 오프힙 버퍼 해제 ────────────────────────────────────

    @Test
    void append_after_close_throws() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        var writer = new WalWriter(wal);
        writer.close();

        // Arena.ofConfined()가 닫힌 후 MemorySegment 접근 → IllegalStateException
        assertThatThrownBy(() -> writer.append(1_000L, 1.0))
            .isInstanceOf(IllegalStateException.class);
    }
}
