package io.kronos.lsm;

import java.nio.file.Path;

/**
 * SSTable 파일의 경량 메타데이터.
 *
 * <p>파일을 열지 않고 범위 쿼리 가능성 판단에 사용한다.
 * {@link SSTableWriter#write}의 반환값이자 {@link SSTableReader}로 열기 위한 핸들.
 *
 * @param path         SSTable 파일 경로
 * @param minTimestamp 데이터 블록의 최솟값 타임스탬프
 * @param maxTimestamp 데이터 블록의 최댓값 타임스탬프
 * @param entryCount   총 엔트리 수
 */
public record SSTableMeta(
        Path path,
        long minTimestamp,
        long maxTimestamp,
        long entryCount
) {
    /**
     * 이 SSTable이 주어진 시간 범위와 겹치는지 확인한다.
     * {@code false}이면 파일을 열지 않아도 된다.
     */
    public boolean overlaps(long startTs, long endTs) {
        return minTimestamp <= endTs && maxTimestamp >= startTs;
    }
}
