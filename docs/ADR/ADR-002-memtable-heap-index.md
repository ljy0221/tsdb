# ADR-002: MemTable 인덱스 전략 — 힙 기반 TreeMap

**상태**: 결정됨
**날짜**: 2026-04-12

---

## 결정

`SkipListMemTable`의 인덱스는 힙 기반 `TreeMap<Long, Long>`을 사용한다.
데이터(timestamp + value)는 오프힙 `MemorySegment`에 저장한다.

```
index: TreeMap<Long, Long>    ← timestamp → 오프힙 오프셋 (힙)
buffer: MemorySegment         ← [ts(8) | value(8)] × N (오프힙)
```

---

## 이유

### 왜 인덱스를 힙에 두는가

MemTable의 인덱스는 **단명 구조체**다. flush 완료 후 MemTable 전체가 `close()`되고
인덱스 TreeMap은 GC에 의해 즉시 수거된다. GC 영향이 지속적이지 않다.

오프힙 정렬 인덱스(오프힙 SkipList 직접 구현)는 Phase 1 범위에서 YAGNI다.
포인터 기반 링크드 구조를 MemorySegment로 구현하면 코드 복잡도가 크게 올라가고,
검증된 자료구조(`TreeMap`)에서 직접 구현으로 교체할 때 버그 위험도 있다.

### 왜 `ConcurrentSkipListMap`이 아닌 `TreeMap`인가

쓰기는 단일 스레드 전용이다. `ConcurrentSkipListMap`의 lock-free 오버헤드가 불필요하다.
`TreeMap`은 정렬된 `entrySet()` 순회가 O(N)으로 더 단순하다.

### 왜 데이터는 오프힙인가

Phase 0 벤치마크에서 FFM API 오버헤드 ≈ 0 임을 확인했다.
데이터를 오프힙에 두면:
- 대규모 시계열 데이터가 GC Young/Old Gen을 압박하지 않는다
- Phase 2 Gorilla XOR 압축 적용 시 `MemorySegment`에서 직접 비트 연산 가능

---

## 알려진 트레이드오프

| 항목 | 비용 | 완화 방법 |
|------|------|-----------|
| Long boxing (timestamp, offset) | 엔트리당 Long 객체 2개 | flush 후 즉시 GC. 지속적 GC 압박 없음 |
| TreeMap 노드당 ~48바이트 힙 | 4MB threshold 기준 약 12MB 추가 힙 | 총 풋프린트 허용 가능 수준 |
| 단일 스레드 쓰기 제약 | 병렬 put() 불가 | WAL → MemTable 순차 경로이므로 현재 요구사항과 일치 |

---

## Arena 선택: `Arena.ofConfined()`

`freeze()` 후 `forEachInOrder()`는 flush 전담 스레드 **단독**으로 호출한다.
쓰기 스레드는 이미 새 MemTable로 전환된 상태이므로 크로스-스레드 MemorySegment 접근이 없다.
따라서 ADR-001의 원칙대로 `Arena.ofConfined()`를 유지한다.

---

## 재검토 시점

Phase 2 JMH 측정에서 GC pause가 문제로 확인될 경우:
- 오프힙 정렬 배열(flush 시 1회 materialize) 방식으로 전환 검토
- 또는 `TreeMap` → 오프힙 B-tree 구현 검토

현재 Phase 1 완료 기준("10만 건 write → flush → read 정합성")에서는 이 설계로 충분하다.
