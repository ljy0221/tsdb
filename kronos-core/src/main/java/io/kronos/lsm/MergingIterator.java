package io.kronos.lsm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * 여러 정렬된 소스를 타임스탬프 오름차순으로 병합하는 이터레이터.
 *
 * <h2>의미론</h2>
 * <ul>
 *   <li>각 입력은 타임스탬프 오름차순으로 엔트리를 방출해야 한다.</li>
 *   <li>동일 타임스탬프가 여러 소스에서 나오면, 입력 리스트에서 <b>앞쪽(낮은 index)</b>에
 *       있는 소스의 값이 승리한다. LSM-Tree의 newest-wins 규칙을 그대로 반영한다:
 *       {@code [memTable, sst_newest, ..., sst_oldest]} 순으로 넘기면 된다.</li>
 *   <li>중복되는 나머지 엔트리는 버려진다(drain).</li>
 * </ul>
 *
 * <h2>알고리즘</h2>
 * <p>{@link PriorityQueue} 기반 k-way merge. 힙의 크기는 소스 개수 k.
 * 엔트리당 {@code O(log k)} — N개 엔트리, k개 소스에 대해 총 {@code O(N log k)}.
 *
 * <p>이 클래스는 <b>pull-based</b>라 Compaction과 Query 경로에서 공통 재사용된다.
 */
public final class MergingIterator implements Iterator<TimestampValuePair> {

    /** 힙 원소: 한 소스의 현재 head 엔트리 + 소스 priority. */
    private record Node(TimestampValuePair pair, int priority, Iterator<TimestampValuePair> src) {}

    private final PriorityQueue<Node> heap;

    /**
     * @param sources 우선순위 내림차순 — 앞쪽 소스가 newest(승자). 예: [memTable, sst_new, sst_mid, sst_old]
     */
    public MergingIterator(List<Iterator<TimestampValuePair>> sources) {
        // ts 오름차순 → 동일 ts는 priority(앞쪽 index) 작은 것 먼저
        this.heap = new PriorityQueue<>((a, b) -> {
            int c = Long.compare(a.pair.timestamp(), b.pair.timestamp());
            if (c != 0) return c;
            return Integer.compare(a.priority, b.priority);
        });
        for (int i = 0; i < sources.size(); i++) {
            Iterator<TimestampValuePair> it = sources.get(i);
            if (it.hasNext()) {
                heap.offer(new Node(it.next(), i, it));
            }
        }
    }

    /** 가변 파라미터 편의 생성자. */
    @SafeVarargs
    public static MergingIterator of(Iterator<TimestampValuePair>... sources) {
        List<Iterator<TimestampValuePair>> list = new ArrayList<>(sources.length);
        for (var s : sources) list.add(s);
        return new MergingIterator(list);
    }

    @Override
    public boolean hasNext() {
        return !heap.isEmpty();
    }

    @Override
    public TimestampValuePair next() {
        if (heap.isEmpty()) throw new NoSuchElementException();

        Node winner = heap.poll();
        advance(winner);

        // 동일 ts를 가진 나머지 소스들은 drain — newest-wins.
        while (!heap.isEmpty() && heap.peek().pair.timestamp() == winner.pair.timestamp()) {
            Node dup = heap.poll();
            advance(dup);
        }

        return winner.pair;
    }

    private void advance(Node n) {
        if (n.src.hasNext()) {
            heap.offer(new Node(n.src.next(), n.priority, n.src));
        }
    }
}
