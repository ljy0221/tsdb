---
title: "LSM-Tree 직접 구현기 (1) — MemTable: 힙 인덱스 + 오프힙 데이터 버퍼"
tags: [Java, FFM API, TSDB, LSM-Tree, 오픈소스]
---

> 이 글은 Java 22 FFM API 기반 시계열 DB **Kronos**를 만들면서 겪은 설계 결정을 기록한다.
> Phase 1 목표: 동작하는 LSM-Tree 엔진. 첫 번째 컴포넌트는 MemTable이다.

---

## MemTable이란

LSM-Tree(Log-Structured Merge-Tree)의 **쓰기 버퍼**다.
모든 쓰기는 디스크 대신 MemTable에 먼저 쌓인다.
일정 크기를 넘으면 SSTable로 flush되고, 새 MemTable이 열린다.

```
write(ts, val)
  → WAL append (내구성 확보)
  → MemTable insert
  → if MemTable full: flush → SSTable
```

MemTable의 핵심 요구사항은 두 가지다.

1. **쓰기 빠를 것** — 타임스탬프 기준 삽입이 O(log N) 이하
2. **정렬된 순회** — flush 시 SSTable에 timestamp 오름차순으로 내려야 함

---

## 설계 결정: 왜 "힙 인덱스 + 오프힙 데이터"인가

가장 먼저 든 질문: 인덱스와 데이터를 둘 다 오프힙에 놓을까, 힙에 놓을까?

### 옵션 A: 전부 힙 (일반적인 선택)

```java
// 가장 단순한 형태
ConcurrentSkipListMap<Long, Double> data;
```

- 장점: 구현 단순. 정렬 내장.
- 단점: 대량 시계열 데이터가 GC Heap을 압박. GC pause 리스크.

### 옵션 B: 전부 오프힙 (이상적이지만 복잡)

포인터 기반 SkipList를 `MemorySegment`로 직접 구현해야 한다.
링크드 노드를 오프힙 포인터(long 주소)로 연결하는 코드 — Phase 1 범위에서 YAGNI다.

### 옵션 C: 인덱스는 힙, 데이터는 오프힙 ← 채택

```
index: TreeMap<Long, Long>    ← timestamp → 오프힙 오프셋 (힙)
buffer: MemorySegment         ← [ts(8) | value(8)] × N (오프힙)
```

**핵심 관찰**: MemTable의 인덱스는 *단명 구조체*다.
flush 완료 후 MemTable이 `close()`되면 TreeMap은 GC에 즉시 수거된다.
GC 영향이 *지속적*이지 않으므로 큰 문제가 없다.

반면 **데이터**는 오프힙에 두면 세 가지 이점이 생긴다.
- 수십만 건의 시계열 데이터가 GC Young/Old Gen을 압박하지 않음
- Phase 2의 Gorilla XOR 압축을 `MemorySegment`에서 직접 비트 연산으로 적용 가능
- `Arena.ofConfined()`의 명시적 `close()`로 해제 — GC 관여 없음

---

## 왜 ConcurrentSkipListMap이 아닌 TreeMap인가

쓰기는 **단일 스레드 전용**이다.
WAL → MemTable 경로가 순차적이므로 lock-free 자료구조의 오버헤드가 불필요하다.
`TreeMap.entrySet()`은 정렬된 순회를 O(N)으로 제공한다 — flush 경로에 딱 맞다.

```java
// flush 시 timestamp 오름차순 순회
for (var entry : index.entrySet()) {
    long ts     = entry.getKey();
    long offset = entry.getValue();
    double val  = buffer.get(ValueLayout.JAVA_DOUBLE, offset + 8);
    // SSTable에 기록
}
```

---

## 핵심 구현

### 오프힙 버퍼 사전 할당

```java
public SkipListMemTable(long flushThresholdBytes) {
    // 임계값의 1.1배를 사전 할당 —
    // isFull() 검사와 put() 사이의 미세 경쟁에서도 오버플로우 방지
    long bufferBytes = (long) (flushThresholdBytes * 1.1);
    this.arena  = Arena.ofConfined();
    this.buffer = arena.allocate(bufferBytes, ValueLayout.JAVA_LONG.byteAlignment());
}
```

`Arena.ofConfined()`를 선택한 이유: 단일 스레드 소유권이 명확하고,
`Arena.ofAuto()`처럼 GC에 해제를 위임하지 않는다 — 이 프로젝트의 핵심 원칙.

### put(): 신규 vs 덮어쓰기 분기

```java
public void put(long timestamp, double value) {
    if (frozen) throw new IllegalStateException("frozen");

    Long existingOffset = index.get(timestamp);
    if (existingOffset != null) {
        // 동일 타임스탬프: 오프힙 오프셋은 그대로, value만 갱신
        buffer.set(ValueLayout.JAVA_DOUBLE, existingOffset + 8, value);
        return;  // writePosition, sizeBytes 변화 없음
    }

    long offset = writePosition;
    writePosition += ENTRY_BYTES;

    buffer.set(ValueLayout.JAVA_LONG,   offset,     timestamp);
    buffer.set(ValueLayout.JAVA_DOUBLE, offset + 8, value);
    index.put(timestamp, offset);
    sizeBytes += ENTRY_BYTES;
}
```

덮어쓰기 시 새 오프셋을 할당하지 않는 것이 포인트다.
기존 슬롯을 재사용하므로 `sizeBytes`가 늘지 않고, `isFull()` 판정이 정확하다.

### 생명주기: Active → Frozen → Closed

```
put() 가능     → freeze() →     forEachInOrder() 가능     → close()
   Active                             Frozen                 (해제)
```

`freeze()`는 "이 MemTable에 더 이상 쓰지 않겠다"는 선언이다.
flush 전담 스레드가 `forEachInOrder()`를 호출하기 전에 반드시 호출해야 한다.

---

## 트레이드오프 요약

| 항목 | 비용 | 완화 |
|------|------|------|
| Long boxing (인덱스) | 엔트리당 Long 객체 2개 | flush 후 즉시 GC — 지속적 압박 없음 |
| TreeMap 노드 힙 사용 | 4MB 기준 ~12MB 추가 힙 | 허용 가능 수준 |
| 단일 스레드 쓰기 | 병렬 put() 불가 | WAL → MemTable 순차 경로이므로 현재 요구사항과 일치 |

---

## Phase 1에서 이 설계가 충분한 이유

완료 기준은 "10만 건 write → flush → read 정합성"이다.
10만 건 × 16바이트 = 1.6MB — 4MB 임계값 내에서 동작한다.

GC pause가 실제로 문제가 되는 시점(Phase 2 JMH 측정)에서
오프힙 인덱스로의 전환 여부를 데이터로 판단한다.
지금은 측정 없이 복잡도를 늘리지 않는다.

---

> 다음 글: **LSM-Tree 직접 구현기 (2) — WAL: FFM API로 write-ahead log 만들기**
