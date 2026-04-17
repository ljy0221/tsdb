package io.kronos.lsm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;

/**
 * 읽기 경로 통합 뷰 — 하나의 (frozen) MemTable과 여러 SSTable에 걸친
 * 시점 조회 및 범위 스캔을 제공한다.
 *
 * <h2>우선순위</h2>
 * <p>생성자 인자 순서가 곧 우선순위다:
 * <pre>
 *   [memTable, sstable_newest, ..., sstable_oldest]
 * </pre>
 * 동일 타임스탬프에 대해서는 앞쪽 소스가 승리한다.
 *
 * <h2>소유권</h2>
 * <p>이 뷰는 전달받은 {@link MemTable}/{@link SSTableReader}를 <b>닫지 않는다</b>.
 * 수명은 호출자가 관리한다. 뷰 자체는 가벼운 상태만 유지한다.
 */
public final class LsmReadView {

    private final MemTable memTable;           // nullable — 읽기 전용 엔진에서는 없을 수 있다
    private final List<SSTableReader> sstables; // newest first

    /**
     * @param memTable frozen MemTable, 없으면 null
     * @param sstables newest first 순서
     */
    public LsmReadView(MemTable memTable, List<SSTableReader> sstables) {
        if (memTable != null && !memTable.isFrozen()) {
            throw new IllegalStateException("MemTable must be frozen to be part of a read view");
        }
        this.memTable = memTable;
        this.sstables = List.copyOf(sstables);
    }

    /**
     * 정확한 타임스탬프 조회.
     *
     * <p>MemTable → newest SSTable → ... → oldest SSTable 순으로 탐색하며,
     * 처음 발견된 값을 반환한다. SSTable은 푸터의 minTs/maxTs로 먼저 거부된다.
     */
    public OptionalDouble get(long timestamp) {
        if (memTable != null) {
            OptionalDouble v = memTable.get(timestamp);
            if (v.isPresent()) return v;
        }
        for (SSTableReader r : sstables) {
            SSTableMeta m = r.meta();
            if (timestamp < m.minTimestamp() || timestamp > m.maxTimestamp()) continue;
            OptionalDouble v = r.get(timestamp);
            if (v.isPresent()) return v;
        }
        return OptionalDouble.empty();
    }

    /**
     * {@code [startTs, endTs]} 범위 스캔. 타임스탬프 오름차순으로 consumer가 호출된다.
     *
     * <p>각 소스는 자신의 iterator를 통해 전체 엔트리를 방출하지만,
     * merge 소비자 레벨에서 범위 필터를 적용한다.
     * minTs/maxTs로 겹치지 않는 SSTable은 iterator 자체를 생성하지 않는다.
     */
    public void scan(long startTs, long endTs, BiConsumer<Long, Double> consumer) {
        List<Iterator<TimestampValuePair>> sources = new ArrayList<>(sstables.size() + 1);

        if (memTable != null && memTable.size() > 0) {
            sources.add(memTable.iterator());
        }
        for (SSTableReader r : sstables) {
            SSTableMeta m = r.meta();
            if (m.maxTimestamp() < startTs || m.minTimestamp() > endTs) continue;
            sources.add(r.iterator());
        }

        if (sources.isEmpty()) return;

        MergingIterator merged = new MergingIterator(sources);
        while (merged.hasNext()) {
            TimestampValuePair p = merged.next();
            if (p.timestamp() < startTs) continue;
            if (p.timestamp() > endTs) break;
            consumer.accept(p.timestamp(), p.value());
        }
    }
}
