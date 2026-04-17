package io.kronos.lsm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Size-tiered N-to-1 Compaction 오케스트레이터.
 *
 * <p>{@code trigger}개 이상의 SSTable이 쌓이면 전부를 하나의 새 SSTable로 병합한다.
 * 실행은 <b>수동 트리거</b>만 지원한다 — 호출자가 {@link #maybeCompact(List)}를 부른다.
 * 쓰기 경로와 같은 스레드에서 호출되어야 한다 ({@link java.lang.foreign.Arena#ofConfined()} 제약).
 *
 * <p>상세 설계 근거: docs/ADR/ADR-004-basic-compaction.md
 *
 * <h2>파일 명명 규칙</h2>
 * <pre>
 *   sst-{generation}-{nanotime}.sst   ← 정상 파일
 *   sst-{generation}-{nanotime}.tmp   ← 중간 파일 (완료 후 rename)
 * </pre>
 *
 * <h2>스레드 안전성</h2>
 * 단일 스레드 전용. 쓰기 스레드에서 호출한다.
 */
public final class Compactor {

    /** RocksDB/LevelDB의 default L0 file number trigger와 동일한 값 */
    public static final int DEFAULT_TRIGGER = 4;

    /** 파일명에서 generation을 추출하는 패턴. sst-{gen}-{nanotime}.sst */
    private static final Pattern SST_NAME = Pattern.compile("sst-(\\d+)-\\d+\\.sst");

    private final Path dir;
    private final int trigger;

    public Compactor(Path dir) {
        this(dir, DEFAULT_TRIGGER);
    }

    public Compactor(Path dir, int trigger) {
        if (trigger < 2) {
            throw new IllegalArgumentException("trigger must be >= 2");
        }
        this.dir = dir;
        this.trigger = trigger;
    }

    /**
     * {@code current.size() >= trigger}이면 전체를 병합해 새 SSTable을 만든다.
     *
     * <p>{@code current}는 newest-first 순서여야 한다 — {@link LsmReadView}와 동일 규약.
     * 옛 reader는 결과에 그대로 담겨 반환되며, <b>이 메서드는 close하지 않는다</b>.
     * 호출자가 새 뷰를 구성하고, 옛 뷰가 모두 사용 완료된 시점에
     * {@link CompactionResult#retireObsolete()}를 호출해 해제·삭제한다.
     *
     * @param current newest-first 순서의 현재 SSTableReader 리스트
     * @return 조건 미달이면 {@link Optional#empty()}, 만족하면 결과
     */
    public Optional<CompactionResult> maybeCompact(List<SSTableReader> current)
            throws IOException {
        if (current.size() < trigger) return Optional.empty();

        // 새 세대: 입력 파일들의 최대 generation + 1
        int newGen = 0;
        long totalCount = 0;
        for (SSTableReader r : current) {
            int g = generationOf(r.meta().path());
            if (g > newGen) newGen = g;
            totalCount += r.meta().entryCount();
        }
        newGen += 1;

        Path tmp = dir.resolve("sst-" + newGen + "-" + System.nanoTime() + ".tmp");

        // newest-first 순서를 그대로 MergingIterator에 넘긴다 → priority가
        // 소스 index(0=newest)로 설정되어 LSM의 newest-wins 규칙이 그대로 적용된다.
        List<Iterator<TimestampValuePair>> sources = new ArrayList<>(current.size());
        for (SSTableReader r : current) sources.add(r.iterator());

        MergingIterator merged = new MergingIterator(sources);
        // 입력 총합을 상한으로 넘긴다. 중복이 drain되면 실제 count는 더 작다.
        SSTableWriter.writeFromIterator(merged, tmp, totalCount);

        Path finalPath = dir.resolve(
            tmp.getFileName().toString().replace(".tmp", ".sst"));
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE);

        return Optional.of(new CompactionResult(finalPath, List.copyOf(current)));
    }

    /** 파일명에서 generation을 추출한다. 규칙 외 이름은 0으로 간주. */
    static int generationOf(Path ssTablePath) {
        Matcher m = SST_NAME.matcher(ssTablePath.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }
}
