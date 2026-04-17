---
title: "LSM-Tree 직접 구현기 (4) — 여러 SSTable을 하나처럼 읽기: k-way merge와 쓰기 처리량 측정"
tags: [Java, FFM API, TSDB, LSM-Tree, JMH, 벤치마크, 오픈소스]
---

> Phase 1의 마지막 글. SSTable이 여러 개 쌓이면 하나의 논리적 타임라인으로 읽을 수 있어야 한다.
> 그 경로를 `MergingIterator`로 해결하고, Phase 1 전체 쓰기 처리량을 JMH로 측정한다.

---

## 문제 — SSTable은 점점 늘어난다

지난 글까지 만든 것들의 구조를 보면 이렇다.

```
                       ┌──────────────┐
write path   ──▶       │   MemTable   │ (frozen)
                       └──────┬───────┘
                              │ flush
                              ▼
                  ┌──────────────────────┐
                  │ sst-3  (newest)      │
                  │ sst-2                │
                  │ sst-1                │
                  │ sst-0  (oldest)      │
                  └──────────────────────┘
```

읽기 쿼리는 "이 타임스탬프/범위에 해당하는 값을 주세요"이지, "sst-2에서 주세요"가 아니다. **여러 소스를 하나의 정렬된 스트림처럼** 보여줘야 한다.

동일 타임스탬프가 여러 소스에서 나올 수도 있다. 이때 규칙은 **newest-wins** — MemTable → sst_new → ... → sst_old 순서에서 앞쪽이 이긴다. 이게 LSM-Tree의 기본 의미론이다.

---

## 처음 시도: push 스타일 콜백

MemTable은 이미 `forEachInOrder(BiConsumer<Long, Double>)`를 갖고 있었다. 자연스럽게 SSTable에도 똑같은 API를 주고, 두 소스를 그냥 동시에 돌리면 되지 않을까?

```java
// ❌ 이렇게 merge할 수가 없다
memTable.forEachInOrder((ts, val) -> { /* ... */ });
sstable.forEachInOrder((ts, val) -> { /* ... */ });
```

**여기서 막혔다.** push 스타일 콜백은 제어 흐름을 자신이 쥔다. 소스를 시작하는 순간 끝까지 혼자 달린다. merge는 각 소스를 "한 스텝만 전진"시키면서 다른 소스와 비교해야 하는데, 콜백은 이 교차 진행을 허락하지 않는다.

### 교훈: merge가 필요하면 Iterator

일반 규칙으로 남겨둘 만한 이야기다.

- **push (콜백)** — 소스 1개, 소비자가 모든 걸 받으면 되는 경우. 예: flush.
- **pull (Iterator)** — 소스 ≥ 2개를 교차 진행해야 하는 경우. 예: merge.

`MemTable`과 `SSTableReader`에 각각 `Iterator<TimestampValuePair> iterator()`를 추가한 이유다. flush 경로는 `forEachInOrder`를 그대로 쓰고, merge 경로는 `iterator()`를 쓴다 — 두 API를 공존시킨다.

---

## k-way merge 알고리즘

소스 k개에서 "가장 작은 timestamp의 엔트리를 반복적으로 꺼내는 것"이 k-way merge다. 각 소스가 이미 정렬돼 있으므로, 각 소스의 **현재 head**만 보면 된다. 이것들을 min-heap에 넣으면 poll이 곧 다음 엔트리.

```java
public final class MergingIterator implements Iterator<TimestampValuePair> {

    private record Node(
        TimestampValuePair pair,
        int priority,
        Iterator<TimestampValuePair> src
    ) {}

    private final PriorityQueue<Node> heap;

    public MergingIterator(List<Iterator<TimestampValuePair>> sources) {
        this.heap = new PriorityQueue<>((a, b) -> {
            int c = Long.compare(a.pair.timestamp(), b.pair.timestamp());
            if (c != 0) return c;
            return Integer.compare(a.priority, b.priority);  // ← newest-wins
        });
        for (int i = 0; i < sources.size(); i++) {
            Iterator<TimestampValuePair> it = sources.get(i);
            if (it.hasNext()) heap.offer(new Node(it.next(), i, it));
        }
    }
    // ...
}
```

**여기가 이 구현의 핵심이다.** 비교자가 2차 키로 `priority`를 쓴다. 호출자가 `[memTable, sst_new, ..., sst_old]` 순서로 넘겨주면 `priority`가 그대로 소스의 "신선도 역순"이 된다. 동일 timestamp에서 priority가 작은(앞쪽) 소스가 힙에서 먼저 나온다 — **LSM의 newest-wins 규칙이 비교자 한 줄에 압축된다**.

### next(): winner + 중복 drain

```java
@Override
public TimestampValuePair next() {
    if (heap.isEmpty()) throw new NoSuchElementException();

    Node winner = heap.poll();
    advance(winner);  // 해당 소스의 다음 엔트리를 힙에 다시 넣기

    // 동일 ts를 가진 나머지 소스들은 drain — newest-wins로 결정됨
    while (!heap.isEmpty()
           && heap.peek().pair.timestamp() == winner.pair.timestamp()) {
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
```

winner 바로 뒤에 같은 timestamp를 가진 노드들이 힙 peek에 올라올 수 있다. 이들은 "이미 진 쪽"이므로 값을 읽지 않고 버리고(advance로 다음 엔트리만 꺼내 힙에 다시 넣음), 넘어간다.

복잡도: 엔트리당 O(log k). N개 엔트리, k개 소스 → 전체 **O(N log k)**.

---

## 상위 API: LsmReadView

`MergingIterator`는 저수준 재료다. 이걸 쓰기 편한 "읽기 뷰"로 감싼다.

```java
public final class LsmReadView {
    private final MemTable memTable;
    private final List<SSTableReader> sstables;  // newest first

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
```

포인트 두 가지.

1. **`get()`은 merge하지 않는다.** MemTable과 각 SSTable에 순서대로 물어보고 처음 나온 값을 반환한다. 병합 순회 비용이 필요 없다. 각 SSTable 접근 전에 footer의 min/max로 fast-reject.

2. **`scan()`만 `MergingIterator`를 쓴다.** 범위에 겹치는 소스만 iterator를 생성해 힙에 넣는다. 범위 밖 SSTable은 아예 iterator조차 만들지 않는다.

### 소유권: 뷰는 닫지 않는다

`LsmReadView`는 전달받은 `MemTable`과 `SSTableReader`를 **close하지 않는다**. 뷰가 소스의 생명주기까지 소유하면, 하나의 `SSTableReader`를 여러 뷰에서 재사용할 때 중복 해제가 생긴다. "뷰는 가볍고, 닫기는 소유자의 책임"이라는 규칙.

---

## Phase 1 쓰기 처리량 — JMH 측정

코드가 돌아간다는 건 증명됐다. 이제 **얼마나 빠른가**를 알아야 한다. 이 수치가 없으면 Compaction 설계 결정을 뒷받침할 근거가 없다.

JMH로 세 경로를 측정했다. 소스: `LsmWriteBench.java`.

- **memTableOnly** — `MemTable.put` × 10,000 (오프힙 16B set + TreeMap 삽입)
- **memTablePlusWal** — WAL DSYNC append + MemTable put × 10,000 (실제 durable 쓰기)
- **endToEndFlush** — put × 100,000 → freeze → SSTableWriter.write (CRC32 + 1× fsync)

### 환경
- Linux 6.6 (WSL2, Ubuntu) / ext4 / SSD
- JDK: Temurin 22.0.2+9
- JMH: 1.37, Warmup 3×2s, Measurement 5×3s, Fork 1, Thread 1

### 결과

| Benchmark         | Score (ops/ms) | Error (±99.9%) |
|-------------------|---------------:|---------------:|
| memTableOnly      |     9,465.502  |      1,969.371 |
| endToEndFlush     |     3,269.182  |        414.252 |
| memTablePlusWal   |         0.389  |          0.145 |

환산하면: memTableOnly ≈ **9.47M ops/s**, endToEndFlush ≈ **3.27M ops/s**, memTablePlusWal ≈ **389 ops/s**.

---

## 수치 읽기

### 1. WAL DSYNC가 쓰기 경로의 물리 상한을 결정한다

`memTableOnly : memTablePlusWal = 9,465,502 : 389 ≈ 24,300:1`. MemTable put 자체는 수십 나노초 수준이지만, WAL append마다 DSYNC로 fsync가 강제되어 한 번에 **~2.57ms** (1000/389)가 걸린다. WSL2 ext4 + SSD에서 합리적 수치다.

즉 **이 환경에서 durable 쓰기의 물리 상한은 초당 약 400건**이며, 코드 최적화로는 못 넘는다. "쓰기가 느리다"는 말을 들으면 먼저 의심할 곳은 CPU가 아니라 디스크의 sync 특성이다.

### 2. LSM의 존재 이유가 수치로 드러난다

`endToEndFlush`가 `memTableOnly`의 **35%**를 유지한다는 게 핵심이다. 10만 건 put + freeze + SSTable 직렬화 + fsync 1회가 함께 들어있는 경로인데도 처리량이 살아남는 이유: **fsync가 10만 건당 1회**로 amortize되기 때문.

`memTablePlusWal`과 비교하면 차이가 더 명확하다 — 389 ops/s vs 3,270,000 ops/s, 약 **8,400배**.

이게 LSM이 풀고 있는 문제의 본질이다. "디스크에 쓰는 빈도"가 아니라 **"sync하는 빈도"**를 줄이는 것. WAL은 내구성을 위한 최소한의 durable point이고, 진짜 데이터 레이아웃 작업은 flush로 모아서 한다.

### 3. Compaction 설계에 대한 제약

이 수치는 compaction 설계에 직접적 제약을 걸어준다. 쓰기 경로의 지배 비용이 WAL fsync(2.5ms)이므로:

- ❌ Compaction이 쓰기 경로와 동기화되면 ops/s가 반토막.
- ✅ Compaction은 **쓰기와 독립적인 백그라운드 작업**이어야 한다.
- ✅ 목표는 "쓰기 빠르게 하기"가 아니라 **"읽기의 SSTable fan-out을 줄여 read amplification 잡기"**.

수치로부터 설계 제약이 도출되는 이런 흐름이, 벤치마크를 "숙제"가 아니라 "실험"으로 만드는 방법이다.

---

## 힙 할당 우려 — 아직 안 풀린 퍼즐

`MergingIterator`의 `Node`는 record다. 엔트리 하나 poll할 때마다 `new Node(...)`가 일어난다. 대량 병합에서는 이게 GC 압력이 될 수 있다.

지금 당장 고치지는 않는다. 이유:

1. **정확성이 먼저다.** mutable node 풀링은 소유권 규칙이 복잡해진다.
2. **측정이 아직 없다.** 읽기 벤치(`LsmReadBench`)를 `-prof gc`로 돌려 실제 할당률을 수치로 잡은 뒤 결정한다.
3. **Phase 1 완료 기준에 포함돼 있지 않다.** 지금은 "동작하는 LSM-Tree"를 증명하는 단계.

Phase 2에서 이 퍼즐을 닫는다.

---

## Phase 1 회고

| 컴포넌트 | 상태 | 한 줄 |
|---------|------|------|
| MemTable | ✅ | 힙 인덱스 + 오프힙 데이터. flush 후 GC |
| WAL | ✅ | 16B write buffer 재사용, DSYNC append |
| SSTable Writer/Reader | ✅ | 단일 버퍼 write, mmap + 이진 탐색, footer로 fast reject |
| MergingIterator / LsmReadView | ✅ | k-way merge, 비교자로 newest-wins 인코딩 |
| JMH 쓰기 처리량 | ✅ | 3경로 측정, compaction 설계 제약 도출 |
| Basic Compaction | ⏳ | Phase 1 다음 세션 |

Phase 1 완료 기준 "10만 건 write → flush → read 정합성"은 코드와 측정 양쪽에서 충족됐다. 남은 Compaction은 이번 벤치 결과를 제약으로 삼아 설계한다.

---

> Phase 2에서는 Gorilla XOR 압축과 Delta-of-Delta 인코딩을 다룬다.
> 그 전에 Compaction을 마무리하는 게 다음 글이 될 가능성이 크다.
