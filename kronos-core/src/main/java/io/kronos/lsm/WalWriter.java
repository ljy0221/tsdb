package io.kronos.lsm;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * WAL(Write-Ahead Log) 단일 엔트리 기록기.
 *
 * <h2>내부 구조</h2>
 * <pre>
 * arena: Arena.ofConfined()        ← WAL 인스턴스 필드, close() 시 해제
 * writeBuf: MemorySegment(16B)     ← [ts(8) | value(8)], 1회 할당 후 재사용
 * nioView: ByteBuffer              ← writeBuf.asByteBuffer() 캐시
 * channel: FileChannel             ← append-only, DSYNC
 * </pre>
 *
 * <h2>스레드 안전성</h2>
 * 단일 스레드 전용. {@code append()}는 MemTable.put()과 같은 스레드에서 호출된다.
 *
 * <h2>엔트리 포맷</h2>
 * <pre>[timestamp: 8 bytes][value: 8 bytes] = 16 bytes 고정</pre>
 *
 * <p>상세 설계 근거: docs/ADR/ADR-003-wal-implementation.md
 */
public final class WalWriter implements Closeable {

    /** 엔트리당 바이트: timestamp(8) + value(8) */
    static final long ENTRY_BYTES = 16L;

    private final Arena arena;
    private final MemorySegment writeBuf;
    /** writeBuf.asByteBuffer() 캐시 — append()마다 view 재생성 비용 제거 */
    private final ByteBuffer nioView;
    private final FileChannel channel;

    /**
     * @param walPath WAL 파일 경로. 존재하지 않으면 생성, 존재하면 이어쓴다.
     * @throws IOException 파일 열기 실패 시
     */
    public WalWriter(Path walPath) throws IOException {
        this.arena = Arena.ofConfined();
        this.writeBuf = arena.allocate(ENTRY_BYTES, ValueLayout.JAVA_LONG.byteAlignment());
        this.nioView = writeBuf.asByteBuffer();
        this.channel = FileChannel.open(
            walPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.DSYNC
        );
    }

    /**
     * WAL에 단일 엔트리를 동기 기록한다.
     *
     * <p>write buffer를 덮어쓴 뒤 NIO view를 rewind해 FileChannel에 전달한다.
     * 버퍼는 WAL 인스턴스 생명 동안 재사용되므로 추가 할당이 없다.
     *
     * @param timestamp 타임스탬프 (단조 증가를 권장하나 강제하지 않음)
     * @param value     측정값
     * @throws IOException 디스크 기록 실패 시
     * @throws IllegalStateException {@link #close()} 이후 호출 시 (Arena 닫힘)
     */
    public void append(long timestamp, double value) throws IOException {
        writeBuf.set(ValueLayout.JAVA_LONG,   0, timestamp);
        writeBuf.set(ValueLayout.JAVA_DOUBLE, 8, value);
        nioView.rewind();
        channel.write(nioView);
    }

    /**
     * FileChannel을 닫고 오프힙 write buffer를 해제한다.
     *
     * <p>channel을 먼저 닫아 OS 버퍼를 플러시한 뒤 arena를 해제한다.
     * channel.close()가 실패해도 arena는 finally에서 반드시 해제된다.
     */
    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            arena.close();
        }
    }
}
