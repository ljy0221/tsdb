package io.kronos.lsm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * WAL 파일을 읽어 크래시 복구용 엔트리 목록을 반환한다.
 *
 * <h2>읽기 전략</h2>
 * {@link FileChannel#map(FileChannel.MapMode, long, long, Arena)}로
 * 파일 전체를 오프힙 {@link MemorySegment}에 매핑한 뒤 오프셋 직접 계산으로 순회한다.
 * seek·복사 없이 OS 페이지 캐시를 직접 참조한다.
 *
 * <h2>내결함성</h2>
 * 파일 끝이 잘려 불완전한 엔트리가 남아 있을 경우(프로세스 크래시),
 * 해당 엔트리는 조용히 무시된다. 완전한 16바이트 엔트리만 반환한다.
 *
 * <h2>엔트리 포맷</h2>
 * <pre>[timestamp: 8 bytes][value: 8 bytes] = 16 bytes 고정</pre>
 *
 * <p>상세 설계 근거: docs/ADR/ADR-003-wal-implementation.md
 */
public final class WalReader {

    private WalReader() {}

    /**
     * WAL 파일의 모든 완전한 엔트리를 기록 순서대로 반환한다.
     *
     * @param walPath WAL 파일 경로
     * @return 기록 순서대로 정렬된 엔트리 목록 (빈 파일이면 빈 리스트)
     * @throws IOException 파일 읽기 실패 시
     */
    public static List<TimestampValuePair> readAll(Path walPath) throws IOException {
        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(walPath, StandardOpenOption.READ)) {

            long fileSize = channel.size();
            if (fileSize == 0) {
                return List.of();
            }

            // 파일 전체를 오프힙 MemorySegment에 매핑.
            // arena가 닫히면 매핑도 해제된다.
            MemorySegment mapped = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, fileSize, arena
            );

            long entryCount = fileSize / WalWriter.ENTRY_BYTES;
            List<TimestampValuePair> entries = new ArrayList<>((int) entryCount);

            long offset = 0;
            while (offset + WalWriter.ENTRY_BYTES <= fileSize) {
                long timestamp = mapped.get(ValueLayout.JAVA_LONG,   offset);
                double value   = mapped.get(ValueLayout.JAVA_DOUBLE, offset + 8);
                entries.add(new TimestampValuePair(timestamp, value));
                offset += WalWriter.ENTRY_BYTES;
            }
            // offset + ENTRY_BYTES > fileSize 인 나머지 바이트는 불완전 엔트리 → 무시

            return entries;
        }
    }
}
