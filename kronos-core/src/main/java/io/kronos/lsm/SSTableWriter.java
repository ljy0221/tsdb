package io.kronos.lsm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * 동결된(frozen) {@link MemTable}을 SSTable 파일로 직렬화한다.
 *
 * <h2>파일 포맷</h2>
 * <pre>
 * [Header — 16 bytes]
 *   magic  : 4 bytes  (0x4B524F4E = "KRON")
 *   version: 4 bytes  (1)
 *   count  : 8 bytes  (long, 엔트리 수)
 *
 * [Data Block — count × 16 bytes]
 *   timestamp: 8 bytes (long)
 *   value    : 8 bytes (double)
 *
 * [Footer — 20 bytes]
 *   min_timestamp: 8 bytes  (long)
 *   max_timestamp: 8 bytes  (long)
 *   checksum     : 4 bytes  (CRC32 of data block)
 * </pre>
 *
 * <h2>쓰기 전략</h2>
 * <p>전체 파일 크기를 사전 계산하여 오프힙에 단일 버퍼를 할당한다.
 * 데이터를 모두 채운 뒤 {@link FileChannel#write}를 한 번만 호출하므로
 * 시스템 콜 횟수를 최소화한다.
 */
public final class SSTableWriter {

    /** 파일 식별 매직 넘버: 'K','R','O','N' */
    static final int  MAGIC        = 0x4B524F4E;
    static final int  VERSION      = 1;
    /** Header = magic(4) + version(4) + count(8) */
    static final long HEADER_BYTES = 16L;
    /** Data entry = timestamp(8) + value(8) */
    static final long ENTRY_BYTES  = 16L;
    /** Footer = min_ts(8) + max_ts(8) + checksum(4) */
    static final long FOOTER_BYTES = 20L;

    private SSTableWriter() {}

    /**
     * {@code memTable}의 모든 엔트리를 {@code sstPath}에 직렬화한다.
     *
     * @param memTable 반드시 {@link MemTable#freeze()} 상태여야 한다.
     * @param sstPath  출력 파일 경로. 이미 존재하면 {@link IOException}을 던진다.
     * @return 기록된 SSTable의 메타데이터
     * @throws IllegalStateException    MemTable이 frozen 상태가 아닌 경우
     * @throws IllegalArgumentException 엔트리가 없는 경우
     * @throws IOException              파일 I/O 오류
     */
    public static SSTableMeta write(MemTable memTable, Path sstPath) throws IOException {
        if (!memTable.isFrozen()) {
            throw new IllegalStateException("MemTable must be frozen before flush");
        }
        int count = memTable.size();
        if (count == 0) {
            throw new IllegalArgumentException("Cannot flush an empty MemTable");
        }

        long fileSize = HEADER_BYTES + (long) count * ENTRY_BYTES + FOOTER_BYTES;

        // minTs/maxTs: 람다 안에서 수정되므로 Arena 블록 밖에 선언
        long[] minTs = {Long.MAX_VALUE};
        long[] maxTs = {Long.MIN_VALUE};

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(fileSize, ValueLayout.JAVA_LONG.byteAlignment());

            // ── 1. Header ──────────────────────────────────────────────────
            buf.set(ValueLayout.JAVA_INT,  0L, MAGIC);
            buf.set(ValueLayout.JAVA_INT,  4L, VERSION);
            buf.set(ValueLayout.JAVA_LONG, 8L, (long) count);

            // ── 2. Data Block ──────────────────────────────────────────────
            long[] pos = {HEADER_BYTES};
            memTable.forEachInOrder((ts, val) -> {
                buf.set(ValueLayout.JAVA_LONG,   pos[0],     ts);
                buf.set(ValueLayout.JAVA_DOUBLE, pos[0] + 8, val);
                pos[0] += ENTRY_BYTES;
                if (ts < minTs[0]) minTs[0] = ts;
                if (ts > maxTs[0]) maxTs[0] = ts;
            });

            // ── 3. CRC32 (data block 전체, heap 복사 없이 ByteBuffer 뷰 사용) ──
            CRC32 crc32 = new CRC32();
            crc32.update(buf.asSlice(HEADER_BYTES, (long) count * ENTRY_BYTES).asByteBuffer());
            int checksum = (int) crc32.getValue();

            // ── 4. Footer ──────────────────────────────────────────────────
            long fo = HEADER_BYTES + (long) count * ENTRY_BYTES;
            buf.set(ValueLayout.JAVA_LONG, fo,        minTs[0]);
            buf.set(ValueLayout.JAVA_LONG, fo + 8L,   maxTs[0]);
            buf.set(ValueLayout.JAVA_INT,  fo + 16L,  checksum);

            // ── 5. 파일 기록 (단일 write + fsync) ─────────────────────────
            try (FileChannel ch = FileChannel.open(sstPath,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer nio = buf.asByteBuffer();
                while (nio.hasRemaining()) {
                    ch.write(nio);
                }
                ch.force(true);
            }
        }

        return new SSTableMeta(sstPath, minTs[0], maxTs[0], count);
    }
}
