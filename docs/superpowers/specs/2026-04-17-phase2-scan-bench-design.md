# Phase 2 Kickoff — Range Scan Benchmark Design

- **날짜**: 2026-04-17
- **Phase**: 2 (시계열 특화) 첫 태스크
- **목적**: 다음 ADR(내부 sparse index vs Gorilla 압축) 방향을 수치로 결정
- **산출물 단계**: spec → implementation plan → 벤치 코드 → 리포트 → 다음 ADR

---

## 1. 배경 — 왜 "벤치 먼저"인가

Phase 1 종료 시점의 읽기 경로 상태:
- `SSTableMeta.overlaps()` — 파일 단위 프루닝은 **이미 구현**되어 있다.
- `LsmReadView.scan(startTs, endTs)` — 범위 스캔 API 존재. 단, 겹치는 파일은 **iterator로 전체를 선형 스캔**하고 consumer 단에서 `< startTs` 스킵 / `> endTs` break.
- **파일 내부 인덱스/이진 탐색 없음** — 즉, 파일당 O(n).

CLAUDE.md의 Phase 2 체크리스트 순서는 `[타임스탬프 인덱스] → [Gorilla] → [Delta-of-Delta]`. 그러나 두 레버는 상호작용이 크다:
- 인덱스 먼저 얹으면, Gorilla 도입 시 **블록 포맷이 바뀌면서 인덱스 설계를 재작업**해야 한다.
- Gorilla 먼저 얹으면, 블록 경계 위에 인덱스를 **한 번에** 설계할 수 있다.

어느 레버가 먼저인지는 **현재 구현이 얼마나 느린지, 그 느림의 원인이 무엇인지**에 달려 있다. 따라서 이번 세션의 태스크는 "인덱스 구현"이 아니라 **"결정을 가능하게 하는 측정 harness 구축 + 측정 + 판정"** 이다.

이 접근은 프로젝트 원칙과 정합한다:
- CLAUDE.md — "측정 우선, 빠를 것 같다는 증거가 아니다"
- Phase 1 ADR-004 경험 — 벤치 수치(WAL 2.57ms)가 설계 제약(수동 compaction 트리거)을 **강제**했다.

---

## 2. 목적 & 범위

### 목적
현재 `LsmReadView.scan()` 이 범위 쿼리에서 어떤 병목을 보이는지를 JMH 벤치로 수치로 증명하여, Phase 2의 다음 ADR 방향을 데이터 기반으로 결정한다.

### In-scope
- `LsmScanBench` JMH 벤치 신설 (`kronos-core/src/jmh/java/io/kronos/bench/`)
- 축: `selectivity ∈ {0.01, 0.1, 0.5, 1.0}` × `distribution ∈ {UNIFORM, RECENT}` × `fileCount ∈ {1, 4}`
- 데이터 규모: 1M 엔트리 (~16MB, OS page cache 안착)
- 소비 경로: raw primitive 전용 (consumer overhead 제거 목적)
- 결과물: `docs/benchmarks/phase2-{YYYY-MM-DD}.md` + 판정식에 따른 다음 ADR 결정

### Out-of-scope (명시적 제외)
- 인덱스/압축의 실제 구현 (이 벤치 결과가 결정)
- 100M+ 규모 실운영 벤치 (Phase 3)
- 멀티스레드 읽기 경로 (현재 `Arena.ofConfined` 제약 유지)
- `LsmReadView.scan()` public API 변경 (벤치 전용 경로 `scanCount`만 신설)
- 파일 간 시간 구간 겹침 시나리오 (Phase 3 실운영 벤치에서 별도 측정)

---

## 3. 아키텍처 & 컴포넌트

### 파일 구조

```
kronos-core/src/jmh/java/io/kronos/bench/
├── LsmScanBench.java           ← 신규. 메인 JMH 진입점
└── ScanDistribution.java       ← 신규 enum

kronos-core/src/main/java/io/kronos/lsm/
└── LsmReadView.java            ← scanCount() 추가

kronos-core/src/test/java/io/kronos/
├── lsm/LsmReadViewScanCountTest.java    ← 신규
└── bench/
    ├── LsmScanBenchFixtureTest.java     ← 신규
    └── ScanDistributionTest.java        ← 신규
```

신규 파일 5개, 기존 파일 1개(`LsmReadView`) 수정. 기존 LSM 구조에 새 책임 추가 없음.

### 컴포넌트 1 — `ScanDistribution` (enum)

책임(단일): 총 데이터 범위 `[dataMin, dataMax]` 와 `selectivity` 가 주어졌을 때, 쿼리 구간 `[startTs, endTs]` 를 계산.

```java
public enum ScanDistribution {
    UNIFORM,   // 데이터 전체 범위에서 무작위 구간
    RECENT;    // 데이터 말미(tail)에서 selectivity 만큼의 구간

    public long[] range(long dataMin, long dataMax, double selectivity, Random rng);
}
```

의존성: `java.util.Random` 만. 독립 테스트 가능.

### 컴포넌트 2 — `LsmReadView.scanCount(long, long)` (신규 메서드)

책임: 범위에 포함된 엔트리 **개수만** 리턴. value를 consumer로 넘기지 않아 `BiConsumer + Double boxing` 경로를 제거.

```java
public long scanCount(long startTs, long endTs) { ... }
```

- 내부 구현: 기존 `scan()` 과 동일한 `MergingIterator` + `overlaps()` 프루닝, 단 `consumer.accept(...)` 대신 `count++`.
- 기존 `scan()` API는 **변경 없음**. 이는 벤치 전용 측정 경로이지 public API 변경이 아니다.
- 의도된 부수 효과: 향후 `COUNT(*)` 집계 쿼리의 1차 구현으로 재사용 가능.

### 컴포넌트 3 — `LsmScanBench` (JMH 벤치)

책임: 3축 교차의 `scanCount` p99 레이턴시 측정.

```java
@State(Scope.Benchmark)
public class LsmScanBench {
    @Param({"0.01", "0.1", "0.5", "1.0"})  double selectivity;
    @Param({"UNIFORM", "RECENT"})           ScanDistribution distribution;
    @Param({"1", "4"})                      int fileCount;

    @Setup(Level.Trial)      void buildFixture();   // 1M 엔트리, fileCount개 SSTable 생성
    @Setup(Level.Invocation) void pickRange();      // warm-cache 편향 방지: 구간 rotate
    @TearDown(Level.Trial)   void cleanup();

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public long scan(Blackhole bh) {
        long count = readView.scanCount(startTs, endTs);
        bh.consume(count);
        return count;
    }
}
```

### Fixture 생성 전략

`fileCount` 에 따라 분기:
- `fileCount=1`: 1M 엔트리를 한 MemTable → flush → 1 SSTable (compaction 직후 상태 모사)
- `fileCount=4`: 1M 엔트리를 4등분(250K × 4), 각각 flush → 4 SSTables. **시간 구간이 비중첩**하게 생성 (`[t₀..t₀+249999]`, `[t₀+250000..t₀+499999]`, ...)

### 데이터 흐름

```
@Setup(Trial):
  tmpDir → MemTable(s) → SSTableWriter × N → List<SSTableReader>
                                              ↓
                           LsmReadView(memTable=null, sstables)

@Setup(Invocation):
  distribution.range(dataMin, dataMax, selectivity, rng) → (startTs, endTs)
  * rng 시드는 trial 내 고정, invocation마다 다른 구간

@Benchmark:
  readView.scanCount(startTs, endTs) → count
  Blackhole.consume(count)

@TearDown(Trial):
  readers.forEach(SSTableReader::close)
  deleteRecursively(tmpDir)
```

### 에러/경계 처리

- Fixture 빌드 실패 → `RuntimeException` 으로 해당 @Param 조합 trial만 실패 표시.
- 빈 결과 (count=0) → 유효한 결과로 간주. `Blackhole.consume(0L)` 허용.
- `fileCount=4` 경계 엔트리 → 비중첩 생성으로 `UNIFORM` 구간이 자연스럽게 파일 경계를 가로지르는 케이스 포함.

### 테스트 (벤치 자체의 검증)

- `ScanDistributionTest` — `RECENT/0.01` 이 전체 범위의 마지막 1%를 리턴하는지.
- `LsmReadViewScanCountTest` — `scanCount(s,e)` 의 결과가 `scan(s,e,...)` 으로 누적한 건수와 일치하는지 (**새 측정 경로의 정합성**).
- `LsmScanBenchFixtureTest` — `fileCount=4` 설정이 정말 비중첩 4개 SSTable을 생성하는지.

JMH `@Benchmark` 메서드 자체에는 단위 테스트를 붙이지 않는다 — JMH 측정은 수치의 정합성을 위한 것이지 정답 검증이 아님.

---

## 4. 판정식 (Exit Criteria)

세 조건의 **AND** 평가로 다음 ADR 방향이 기계적으로 결정된다.

```
조건 1: p99( files=4, dist=RECENT, sel=0.01 )  <  5 ms
조건 2: p99( files=1, dist=RECENT, sel=0.01 )  <  5 ms
조건 3: ratio = p99(files=1, RECENT, sel=1.0) / p99(files=1, RECENT, sel=0.01)  >  20×

모두 통과       → 다음 ADR: "ADR-005 Gorilla XOR 압축 선행"
하나 이상 실패  → 다음 ADR: "ADR-005 SSTable 내부 sparse index"
```

### 임계값 근거 (사후 합리화 금지를 위해 spec에 박제)

**`5 ms`**:
- Grafana 대시보드 패널 갱신 예산 ~100 ms / 패널당 쿼리 ~20개 → 쿼리당 5 ms
- WSL2 환경에 pin 된 기준. Phase 3 (실기기/리눅스 직접) 에서는 재교정 필요.

**`20×`**:
- selectivity가 100배 줄 때 시간이 20배 이상 줌 = 파일 내부 스캔이 최소한의 선택률 반응성을 가짐.
- 이론 상한은 100× (완벽 비례). consumer overhead 제거 후 상수항만 남은 상태에서 20× 미만은 **O(n) 선형 스캔이 지배적** 이라는 증거.

두 임계값은 이 spec과 함께 박제되어, 벤치 결과를 보고 나서 조정하지 않는다. 조정이 필요하면 별도 ADR로 문서화한다.

### 애매 구간 처리 (R1 대응, 아래 참조)

`p99` 가 `[4.5 ms, 5.5 ms]` 경계 밴드에 있으면 WSL2 편차에 판정이 휩쓸릴 수 있다. 이 경우의 규약:

1. **재실행 규칙**: 해당 설정(`files, dist, sel`)만 2회 추가 재실행 (총 3회). 3회 중 2회 이상의 p99 **중앙값**이 밴드 바깥으로 떨어지면 그 방향으로 확정.
2. **여전히 밴드 안**: 조건 1 또는 2가 애매하지만 **조건 3 `ratio` 는 명확**(>20× 또는 ≤20×)하면, 조건 3의 방향으로 확정 (선형성 지표가 내부 O(n) 지배 여부를 더 직접적으로 드러내므로).
3. **모두 애매**: 보수적으로 "실패 측"으로 판정 = 인덱스 방향으로 ADR 열기. 이 선택의 근거는 "잘못 압축을 먼저 해서 인덱스를 나중에 얹을 때의 재작업 비용 > 잘못 인덱스를 먼저 해서 압축이 나중에 올 때의 재작업 비용"이라는 비대칭(§1 참조).

### 이 판정식이 다음 ADR 완료 기준으로 재사용되는 구조

다음 ADR이 "인덱스" 방향이면, 동일 벤치 하네스를 인덱스 구현 후 재실행해 다음을 검증:
- **절대 기준**: 모든 조건 1/2/3 통과
- **상대 기준**: `p99(files=4, RECENT, sel=0.01)` baseline 대비 **5× 이상 개선**
- **판정**: 절대 / 상대 중 **더 관대한 쪽** 달성 → 완료

이는 인덱스 ADR 작성 시점에 확정할 조항이며, 이 벤치 spec은 baseline 수치만 제공한다.

---

## 5. 결과물 (Deliverables)

| 산출물 | 경로 | 용도 |
|---|---|---|
| 분포 enum | `kronos-core/src/jmh/java/io/kronos/bench/ScanDistribution.java` | 벤치 공통 유틸 |
| 벤치 코드 | `kronos-core/src/jmh/java/io/kronos/bench/LsmScanBench.java` | Phase 2 내내 재사용 |
| 공개 API 추가 | `LsmReadView.scanCount(long, long)` | 벤치 전용 측정 경로 (부수적으로 COUNT 집계 기반) |
| 정합성 테스트 | `LsmReadViewScanCountTest` | `scanCount` ≡ `scan()` 카운트 검증 |
| Fixture 테스트 | `LsmScanBenchFixtureTest` | 4-파일 비중첩 배치 검증 |
| 분포 테스트 | `ScanDistributionTest` | `UNIFORM`/`RECENT` 구간 계산 검증 |
| 벤치 리포트 | `docs/benchmarks/phase2-{date}.md` | 판정식 적용 결과 + 다음 ADR 결정 근거 |
| 다음 ADR | `docs/ADR/ADR-005-{index 또는 gorilla}.md` | 벤치 결과가 결정. 이 spec 단계에선 파일명 미확정 |

---

## 6. 작업 순서 (Dependencies)

```
1. ScanDistribution + tests                 ← 독립
2. LsmReadView.scanCount + tests            ← 독립 (1과 병행 가능)
3. LsmScanBench + fixture test              ← 1, 2에 의존
4. 벤치 실행 및 리포트 작성                 ← 3에 의존
5. 판정식 적용 및 다음 ADR 초안             ← 4에 의존
6. Phase 2 블로그 1편 초안 (벤치 + ADR)     ← 5에 의존
```

1과 2는 독립적이므로 implementation plan 단계에서 병렬 실행 여부를 판단한다.

---

## 7. 리스크 & 대응

### R1 — WSL2 파일시스템 편차 (중간)
- **증상**: 동일 설정 재실행 시 p99가 ±30% 출렁임. 판정 경계(5ms) 근처에서 결론이 뒤집힐 수 있음.
- **대응**:
  - Fork=2, Warmup 5×2s, Measurement 10×3s, `-XX:+AlwaysPreTouch`
  - 애매 구간 규약 (§4) 적용
  - `-prof gc` 로 allocation rate 동시 측정하여 노이즈 원인 식별

### R2 — `@Setup(Level.Invocation)` 자체 오버헤드 (낮음)
- **증상**: invocation마다 range 재계산 비용이 `scanCount` 실행시간에 섞여 측정 노이즈 증가.
- **대응**: `pickRange()` 는 `Random.nextLong()` 2회만 하는 O(1) 연산. `scanCount` 는 μs~ms 단위라 `Level.Invocation` 사용 원칙(측정값 > setup 오버헤드) 충족.

### R3 — `scanCount` 구현 오류 (중간)
- **증상**: 새 측정 경로가 `scan()` 과 다른 결과를 내면 벤치 수치 자체가 의미 없음.
- **대응**: `LsmReadViewScanCountTest` 에서 10개 이상 random 쿼리에 대해 `scanCount(s,e) == scan(s,e,...)` 누적 건수 assert. 정합성 보장 없이 벤치 단계 진입 금지.

### R4 — 판정이 "인덱스 필요"로 나왔지만 구현 후에도 p99 개선 부족 (중간)
- **증상**: 인덱스 구현 후 재측정에서 p99가 여전히 5ms 미달.
- **대응**: 완료 기준을 "절대 5ms" 와 "baseline 대비 5× 개선" **중 더 관대한 쪽**으로 병기 (§4).

### R5 — 4-파일 fixture의 "시간 구간 비중첩" 가정과 실제 워크로드 괴리 (낮음)
- **증상**: 실제 쓰기는 지연 도착 섞여서 파일 간 구간 겹침 존재. fixture는 완벽 비중첩이라 `overlaps()` 프루닝 효과 과대평가 가능.
- **대응**: Phase 2 범위에서는 비중첩 케이스가 baseline이라는 점을 리포트에 명시. 실제 겹침 워크로드는 Phase 3에서 별도 측정. 지금 확장 시 축 추가로 판정식 복잡화 (YAGNI).

---

## 8. 이 spec이 다음 ADR로 연결되는 지점

- 판정식이 **"다음 ADR을 어느 이름으로 열 것인가"** 까지 결정한다.
  - `ADR-005-sstable-internal-index.md` 또는 `ADR-005-gorilla-compression.md`
- 이 spec 단계에서 **둘 중 어느 쪽도 선점하지 않는다** — "측정 우선" 원칙의 구체 실천.
- 인덱스 ADR이 열리는 경우, 이 벤치 하네스가 그대로 **완료 기준 측정 도구**로 재사용된다.

---

## 9. 참고

- Phase 1 JMH 벤치 결과 — [docs/benchmarks/phase1-2026-04-17.md](../../benchmarks/phase1-2026-04-17.md)
- Phase 1 Basic Compaction ADR — [docs/ADR/ADR-004-basic-compaction.md](../../ADR/ADR-004-basic-compaction.md)
- 현재 읽기 경로 — [LsmReadView.java](../../../kronos-core/src/main/java/io/kronos/lsm/LsmReadView.java)
- 현재 SSTable 포맷 — [SSTableWriter.java](../../../kronos-core/src/main/java/io/kronos/lsm/SSTableWriter.java)
