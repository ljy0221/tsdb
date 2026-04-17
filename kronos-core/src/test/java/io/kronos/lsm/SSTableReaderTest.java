package io.kronos.lsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link SSTableReader} 전용 테스트.
 *
 * <p>Writer-Reader 왕복(get/scan/meta/invalid-magic)은 {@link SSTableWriterTest}에서
 * 이미 검증된다. 여기서는 Reader 단독 의미론 — 체크섬 변조, mmap 수명, 순차 iterator —
 * 에 초점을 맞춘다.
 */
class SSTableReaderTest {

    @TempDir
    Path tempDir;

    private SkipListMemTable buildFrozen(int count) {
        SkipListMemTable mt = new SkipListMemTable();
        for (int i = 0; i < count; i++) {
            mt.put((long) i * 1000L, i * 0.5);
        }
        mt.freeze();
        return mt;
    }

    // ── 체크섬 무결성 ─────────────────────────────────────────────────

    @Test
    void throws_on_checksum_mismatch_when_data_block_corrupted() throws IOException {
        Path sst = tempDir.resolve("corrupt.sst");
        try (SkipListMemTable mt = buildFrozen(10)) {
            SSTableWriter.write(mt, sst);
        }

        // 데이터 블록 첫 바이트를 뒤집어 CRC를 깬다 (헤더/푸터는 그대로).
        byte[] bytes = Files.readAllBytes(sst);
        bytes[(int) SSTableWriter.HEADER_BYTES] ^= (byte) 0xFF;
        Files.write(sst, bytes);

        assertThatThrownBy(() -> new SSTableReader(sst))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void throws_on_checksum_mismatch_when_footer_crc_corrupted() throws IOException {
        Path sst = tempDir.resolve("corrupt.sst");
        try (SkipListMemTable mt = buildFrozen(5)) {
            SSTableWriter.write(mt, sst);
        }

        // 푸터의 CRC32 필드(마지막 4바이트)를 뒤집는다.
        byte[] bytes = Files.readAllBytes(sst);
        int crcOffset = bytes.length - 4;
        for (int i = 0; i < 4; i++) bytes[crcOffset + i] ^= (byte) 0xFF;
        Files.write(sst, bytes);

        assertThatThrownBy(() -> new SSTableReader(sst))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("checksum");
    }

    // ── mmap 수명 / close ─────────────────────────────────────────────

    @Test
    void get_after_close_throws_illegal_state() throws IOException {
        Path sst = tempDir.resolve("lifecycle.sst");
        try (SkipListMemTable mt = buildFrozen(4)) {
            SSTableWriter.write(mt, sst);
        }

        SSTableReader reader = new SSTableReader(sst);
        reader.close();

        // Arena 종료 후 mapped 접근 → ISE (FFM 스펙 보장)
        assertThatThrownBy(() -> reader.get(0L))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── 순차 iterator (merge 경로 전용) ───────────────────────────────

    @Test
    void iterator_yields_all_entries_in_timestamp_order() throws IOException {
        int n = 20;
        try (SkipListMemTable mt = buildFrozen(n)) {
            Path sst = tempDir.resolve("iter.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                List<Long> seen = new ArrayList<>();
                var it = reader.iterator();
                while (it.hasNext()) {
                    seen.add(it.next().timestamp());
                }
                assertThat(seen).hasSize(n);
                // 오름차순 보장
                for (int i = 1; i < seen.size(); i++) {
                    assertThat(seen.get(i)).isGreaterThan(seen.get(i - 1));
                }
            }
        }
    }

    @Test
    void iterator_preserves_values() throws IOException {
        try (SkipListMemTable mt = new SkipListMemTable()) {
            mt.put(100L, 1.5);
            mt.put(200L, 2.5);
            mt.put(300L, 3.5);
            mt.freeze();

            Path sst = tempDir.resolve("iter-values.sst");
            SSTableWriter.write(mt, sst);

            try (SSTableReader reader = new SSTableReader(sst)) {
                var it = reader.iterator();
                assertThat(it.next()).isEqualTo(new TimestampValuePair(100L, 1.5));
                assertThat(it.next()).isEqualTo(new TimestampValuePair(200L, 2.5));
                assertThat(it.next()).isEqualTo(new TimestampValuePair(300L, 3.5));
                assertThat(it.hasNext()).isFalse();
            }
        }
    }
}
