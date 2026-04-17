package io.kronos.lsm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Compaction 실행 결과 — 새로 쓰인 SSTable과 폐기 대상 옛 reader들.
 *
 * <h2>소유권</h2>
 * <p>{@link #obsoleteReaders}는 아직 열려 있다. {@link Compactor}는 이것들을
 * close하지 않는다. 호출자가 새 {@link LsmReadView}를 구성하고, 옛 뷰가 모두
 * 사용 완료된 뒤에만 {@link #retireObsolete()}를 호출해 해제·삭제한다.
 *
 * <p>Cleaner/Finalizer 기반 자동 해제는 <b>쓰지 않는다</b> — ADR-001의
 * "GC 관여 없는 메모리 관리" 원칙과 충돌하기 때문.
 *
 * @param newSSTable       compaction 결과로 생성된 새 SSTable 파일 경로
 * @param obsoleteReaders  compaction에 입력된 옛 reader들 (아직 열려 있음)
 */
public record CompactionResult(
        Path newSSTable,
        List<SSTableReader> obsoleteReaders
) {

    /**
     * 옛 reader들을 {@link SSTableReader#close()}한 뒤 해당 파일을 삭제한다.
     *
     * <p>호출 전제: 이 {@code obsoleteReaders}를 참조하는 뷰가 더 이상 없어야 한다.
     * 이 조건이 깨지면 진행 중 쿼리가 SIGBUS 또는 {@link IllegalStateException}을
     * 만날 수 있다.
     *
     * <p>일부 reader의 close/삭제가 실패하더라도 나머지를 계속 시도한다.
     * 첫 예외만 기록하고 마지막에 던진다.
     */
    public void retireObsolete() throws IOException {
        IOException first = null;
        for (SSTableReader r : obsoleteReaders) {
            Path p = r.meta().path();
            try {
                r.close();
                Files.deleteIfExists(p);
            } catch (IOException e) {
                if (first == null) first = e;
            } catch (RuntimeException e) {
                // Arena.close() 등이 던지는 비-IOException을 IOException으로 래핑
                if (first == null) first = new IOException(e);
            }
        }
        if (first != null) throw first;
    }
}
