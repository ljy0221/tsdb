package io.kronos.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WalReaderTest {

    @TempDir
    Path tempDir;

    // ── 빈 파일 ────────────────────────────────────────────────────

    @Test
    void readAll_empty_file_returns_empty_list() throws IOException {
        Path wal = tempDir.resolve("wal-empty.log");
        Files.createFile(wal);

        assertThat(WalReader.readAll(wal)).isEmpty();
    }

    // ── 정상 복구 ──────────────────────────────────────────────────

    @Test
    void readAll_single_entry_roundtrip() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        try (var writer = new WalWriter(wal)) {
            writer.append(9_000L, 99.9);
        }

        List<TimestampValuePair> entries = WalReader.readAll(wal);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).timestamp()).isEqualTo(9_000L);
        assertThat(entries.get(0).value()).isEqualTo(99.9);
    }

    @Test
    void readAll_multiple_entries_in_write_order() throws IOException {
        Path wal = tempDir.resolve("wal-0.log");
        try (var writer = new WalWriter(wal)) {
            writer.append(100L, 1.0);
            writer.append(200L, 2.0);
            writer.append(300L, 3.0);
        }

        List<TimestampValuePair> entries = WalReader.readAll(wal);
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(TimestampValuePair::timestamp)
                           .containsExactly(100L, 200L, 300L);
        assertThat(entries).extracting(TimestampValuePair::value)
                           .containsExactly(1.0, 2.0, 3.0);
    }

    // ── 내결함성: 불완전 엔트리 ─────────────────────────────────────

    @Test
    void readAll_ignores_incomplete_trailing_entry() throws IOException {
        Path wal = tempDir.resolve("wal-partial.log");
        // 완전한 엔트리 1개(16바이트) + 불완전 7바이트
        try (var writer = new WalWriter(wal)) {
            writer.append(1_000L, 1.0);
        }
        // 임의 7바이트를 뒤에 덧붙여 불완전 엔트리 시뮬레이션
        byte[] garbage = new byte[7];
        Files.write(wal, garbage, java.nio.file.StandardOpenOption.APPEND);

        assertThat(Files.size(wal)).isEqualTo(23L); // 16 + 7

        List<TimestampValuePair> entries = WalReader.readAll(wal);
        // 완전한 1개만 반환, 7바이트 잔여는 무시
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).timestamp()).isEqualTo(1_000L);
    }

    // ── 대량 엔트리 복구 정합성 ─────────────────────────────────────

    @Test
    void readAll_large_bulk_roundtrip() throws IOException {
        int count = 10_000;
        Path wal = tempDir.resolve("wal-bulk.log");
        try (var writer = new WalWriter(wal)) {
            for (int i = 0; i < count; i++) {
                writer.append((long) i * 1000, i * 0.1);
            }
        }

        List<TimestampValuePair> entries = WalReader.readAll(wal);
        assertThat(entries).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(entries.get(i).timestamp()).isEqualTo((long) i * 1000);
            assertThat(entries.get(i).value()).isCloseTo(i * 0.1, within(1e-9));
        }
    }
}
