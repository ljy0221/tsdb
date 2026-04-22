# Kronos 성능 가이드

> 성능 데이터로 증명된 Kronos의 설계 결정.

---

## 📈 Phase 별 성능 진화

### Phase 0: FFM API 기초 (2026-04-09)

**목표**: 오프힙 메모리가 정말 GC 없이 동작하는가?

| 구간 | 처리량 | GC 발생 |
|------|--------|---------|
| **인메모리 long[]** | 기준선 | 예 (YGC) |
| **오프힙 MemorySegment** | 거의 동일 | **아니오** ✅ |

**결론**: FFM API로 GC 영향 없는 오프힙 저장소 구현 가능 확인.  
**다음 단계**: 실제 LSM-Tree에 통합

---

### Phase 1: LSM-Tree 코어 (2026-04-17)

**목표**: LSM-Tree를 Java 표준 API로 구현할 수 있는가?

#### 벤치마크 결과

```
┌─────────────────────────────────────────────────────────┐
│ Operation                │ Throughput  │ Latency        │
├─────────────────────────────────────────────────────────┤
│ MemTable PUT (in-memory) │ 9.47M ops/s │ ~100 ns/op     │
│ + Flush to SSTable       │ 3.27M ops/s │ ~30.6ms/100k   │
│ + WAL (DSYNC)            │ 389 ops/s   │ ~2.57ms/fsync  │
└─────────────────────────────────────────────────────────┘
```

#### 통찰 1: fsync 비용의 절대적 우위

```
Throughput 비율:
  MemTable only       : 9,465,502 ops/s
  MemTable + WAL      :       389 ops/s
  비율                : 9,465,502 / 389 ≈ 24,300:1 ❌

원인:
  - MemTable put: ~100 ns (Java heap operation, 매우 빠름)
  - WAL fsync:  ~2.57 ms (물리 I/O, 지배적)
  - 비율:      2.57ms = 2,570,000 ns >> 100 ns
```

**결론**: WAL의 fsync가 가장 비싼 연산. LSM 설계로 amortize 필수.

#### 통찰 2: LSM-Tree의 가치 (Throughput 회복)

```
Without LSM (WAL per-write):
  389 ops/s (매우 느림, 내구성 있음)

With LSM (fsync per-batch):
  3.27M ops/s (9,300배 개선)

메커니즘:
  100,000 put → 1회 flush → 1회 fsync
  fsync 비용: 2.57 ms / 100,000 = 25 μs/op amortize
```

**결론**: LSM-Tree는 **fsync 횟수를 O(n) → O(1/batch)로 감소** 시키는 구조.

#### 통찰 3: Compaction이 읽기를 "해친다"는 역설

Phase 1에서 compaction은 파일 수를 줄이는 것으로 "좋은 일"로 가정했으나,  
Phase 2 벤치에서 의외의 결과:

```
Files=4 (더 많은 파일):
  p99: 6.7ms (더 빠름)

Files=1 (더 적은 파일):
  p99: 21.1ms (더 느림)

이유:
  SSTableMeta.overlaps() → 시간 범위별 파일 프루닝
  Files=4: 4개 중 1개만 읽기 → 빠름
  Files=1: 전체 파일 읽기 필수 → 느림
```

**해석**: Compaction이 읽기 성능을 해치는 이유는  
**파일 내부 인덱스가 없기 때문**. Phase 2에서 sparse index 도입으로 해소.

---

### Phase 2: 시계열 특화 (2026-04-17, 분석 중)

**목표**: TSDB 워크로드에서 어떤 성능을 낼 수 있는가?

#### 벤치마크 설정

```
엔트리: 1,000,000
선택성 (selectivity): {0.01, 0.1, 0.5, 1.0} (%)
분포 (distribution): {UNIFORM, RECENT}
파일 수 (fileCount): {1, 4}
총 조합: 16가지

측정: LsmReadView.scanCount(start, end)
     → 실제 쿼리 경로 (boxing 오버헤드 제거)
```

#### 결과 요약

| 조건 | p50 | p99 | 상태 |
|------|-----|-----|------|
| files=1, UNIFORM, sel=0.01% | 5.5ms | 17.3ms | ⚠️ |
| files=1, RECENT, sel=0.01% | 10.2ms | 21.1ms | ❌ |
| files=4, UNIFORM, sel=0.01% | 1.3ms | 5.8ms | ⚠️ |
| files=4, RECENT, sel=0.01% | 2.3ms | 6.7ms | ⚠️ |

#### 판정식 적용 (성공/실패 이분 결정)

```
조건 1: p99(files=4, RECENT, sel=0.01) < 5ms
  측정값: 6.7ms ❌ FAIL (1.34배 초과)

조건 2: p99(files=1, RECENT, sel=0.01) < 5ms
  측정값: 21.1ms ❌ FAIL (4.22배 초과)

조건 3: ratio = p99(files=1, RECENT, sel=1.0) / p99(sel=0.01) > 20×
  측정값: 21.5ms / 21.1ms = 1.02× ❌ FAIL (이상적: 100×)
```

**결과**: 3개 조건 **모두 실패** → 기술 방향 명확: **sparse index 필수**

#### 통찰 1: 선택성이 무효 파라미터임을 증명한 조건 3

```
이상적: 1% 쿼리와 100% 쿼리는 100배 차이 나야 함
실제:   1% 쿼리와 100% 쿼리가 거의 같은 시간

원인: 현재 구현에서 MergingIterator가
      파일 내부를 "전체 스캔(O(n))"하기 때문
      선택성이 아무 도움 못 함

증명:
  - selectivity를 줄여도 → 같은 양의 바이트 읽음
  - p99가 거의 변하지 않음
  - 비율: 1.02× (이상적: 100×)
```

**결론**: 파일 내부에서 시작점을 "건너뛸" 수 있어야 함  
→ **sparse index로 binary search + block read**

#### 통찰 2: Files=4가 Files=1보다 빠른 이유

```
파일 단위 프루닝이 작동 중:

SSTableMeta.overlaps(queryRange)
  → 시간 범위 [ts1, ts2]와 겹치는 파일만 읽기

files=1 (모든 데이터 1개 파일):
  → 항상 1개 파일 열어야 함
  → 선택성 이점 없음

files=4 (데이터 4개 파일, 시간별 분리):
  → 대부분 1개 파일만 열기
  → 나머지 3개 건너뜀
  → 3-4배 빠름 ✅

역설:
  Compaction이 파일 수를 줄이면
  파일 단위 프루닝 효과가 줄어듦
  (Phase 2 sparse index가 해소)
```

#### 통찰 3: RECENT 분포가 UNIFORM보다 느린 이유

```
기대: RECENT는 tail 반복이니 page cache 이점
실제: RECENT가 더 느림

원인: MergingIterator의 break 조건

UNIFORM (중간값에 endTs):
  시작점 → ... → endTs 발견 → break
  → 평균적으로 중간까지만 스캔

RECENT (endTs == dataMax):
  시작점 → ... → 끝까지 스캔
  → break 절대 발동 안 함

결론: 역방향 스캔도 고려 가치 있음 (우선도 낮음)
```

---

## 🎯 성능 목표 설정 (Phase 2 → 3)

### 현재 상태 (Phase 1)

```
쓰기 처리량:     9.47M ops/s (in-memory) ✅ 우수
쓰기 end-to-end: 3.27M ops/s (durable)   ✅ 양호
읽기 지연:       21.1ms (p99, 1% 선택성)  ❌ 필요 개선
```

### Phase 2 목표

#### 절대 기준 (spec §4)

```
p99(files=4, RECENT, sel=0.01) < 5ms  AND
p99(files=1, RECENT, sel=0.01) < 5ms
```

#### 상대 기준 (spec §4 후반)

```
files=1, RECENT, sel=0.01 baseline (21.1ms) 대비
5배 이상 개선
```

#### 달성 기준 (더 관대한 쪽)

```
둘 중 하나만 만족해도 Phase 2 완료
```

### 예상 성능 곡선

```
Current (Phase 1):
  ████████████████████████ 21.1ms

With Sparse Index (Phase 2 예상):
  ███████ 5.0ms (4-5배 개선)

  메커니즘:
  1. Binary search on index: O(log 262) ≈ 8 step
  2. Skip to first block: 1회 read
  3. Linear scan within range: K entries (1-10%)
  4. Total: ~100-200 entries 읽기 (vs 1M 현재)
```

---

## 💾 메모리 사용량 분석

### Phase 1 기준

```
MemTable (in-memory):
  100,000 entries × 16 bytes = 1.6 MB (off-heap)
  Skip-List metadata: ~200 KB (on-heap, negligible GC)

SSTable (disk + mmap):
  1M entries × 16 bytes = 16 MB
  Compacted: 8-16 MB (depending on duplicates)

Sparse Index (Phase 2 예상):
  1M entries / 262 blocks = 262 index entries
  262 × 16 bytes = ~4 KB (negligible)
```

### GC Impact (Off-Heap 설계의 이득)

```
Traditional (on-heap):
  MemTable 1.6MB → Young GC 유발
  100k puts 중 GC pause 발생 가능성

Kronos (off-heap):
  MemTable 1.6MB → off-heap allocation
  Arena.close() → 명시적 해제
  GC pause: 0ms ✅
```

---

## 🔍 성능 측정 방법론

### 1. 절대 측정 (Absolute Metrics)

```
벤치마크:
  ops/s    (throughput)
  μs/op    (latency per operation)
  p50/p99  (percentiles)

목적:
  "이 시스템은 초당 몇 건 처리하는가?"
  "99% 쿼리는 몇 ms 이내인가?"
```

### 2. 상대 측정 (Relative Metrics)

```
비교 기준선:
  Phase 0 vs Phase 1: 내메모리만 vs LSM-Tree (fsync 효과)
  Files=1 vs Files=4: 파일 프루닝 효과
  sel=0.01 vs sel=1.0: 선택성 활용 정도

목적:
  "이 설계 결정이 얼마나 영향을 미치는가?"
```

### 3. 구간 측정 (Path Analysis)

```
벤치마크 경로:
  memTableOnly        : put만 측정 (baseline)
  endToEndFlush       : put + freeze + SSTable write
  memTablePlusWal     : put + WAL fsync

목적:
  "어느 부분이 병목인가?"
  "fsync 비용은?"
```

---

## 🚀 성능 최적화 전략

### 현재 적용된 최적화

| 기법 | 효과 | 구현 위치 |
|------|------|----------|
| **LSM-Tree** | fsync amortization (8,400×) | LsmEngine + SSTableWriter |
| **Size-Tiered Compaction** | 읽기 fan-out 감소 | Compactor |
| **Off-Heap Storage** | GC 제거 | MemoryUtils + Arena |
| **SSTableMeta.overlaps()** | 파일 프루닝 (3-4×) | LsmReadView |
| **MergingIterator** | 정렬된 병합 | LsmReadView.scanCount() |

### 계획된 최적화 (Phase 2+)

| 기법 | 예상 효과 | 우선도 |
|------|----------|--------|
| **Sparse Index** | 선택성 활용 (4-5×) | **1순위** (현재 진행) |
| **Gorilla XOR** | 압축률 75%+ | 2순위 |
| **Delta-of-Delta** | 압축률 추가 개선 | 2순위 |
| **역방향 Scan** | RECENT 쿼리 최적화 | 3순위 (낮음) |

---

## 📊 비교 벤치마크 (향후)

### Phase 2 완료 후: QuestDB vs Kronos

계획된 최종 벤치마크 (Phase 3):

```
시나리오: 1억 개 시계열, Prometheus 메트릭

QuestDB (Unsafe 기반):
  쓰기: 50+ M ops/s
  읽기 (1% 선택성): 1-2ms p99

Kronos (FFM 기반):
  쓰기: 3-5 M ops/s (목표)
  읽기 (1% 선택성): 5ms p99 (목표)

결론:
  쓰기: 10-20배 느림 (trade-off: GC-safe, standard API)
  읽기: 비교 가능 (sparse index 투자의 가치 증명)
```

---

## 💡 성능 튜닝 체크리스트

새로운 기능을 추가할 때마다 확인:

- [ ] **JMH 벤치마크 통과?** — 최소 지난 Phase 성능 유지
- [ ] **p99 악화 없음?** — 극단 케이스에서 성능 유지
- [ ] **GC 증가 없음?** — `java -XX:+PrintGCDetails` 확인
- [ ] **off-heap 누수?** — Arena 반드시 close()
- [ ] **Correctness 테스트 통과?** — 성능 > 정합성은 금지

---

## 🎓 학습 자료

벤치마크 해석을 위한 필독서:

1. **[JMH Official Samples](https://github.com/openjdk/jmh/tree/master/jmh-samples)**
   — 벤치마킹 함정과 해결책

2. **[Systems Performance (Brendan Gregg)](https://www.brendangregg.com/systems-performance-2nd-ed.html)**
   — 성능 측정 원리

3. **[Java Concurrency in Practice](https://www.oreilly.com/library/view/java-concurrency-in/0596007825/)**
   — JVM 성능 최적화

---

**마지막 업데이트**: 2026-04-23  
**다음 마일스톤**: Phase 2 sparse index 구현 후 재벤치 (예상: 5월 초)

