# Kronos — CLAUDE.md

> Panama-based Zero-GC Time Series Storage Engine
> Java 22 FFM API + LSM-Tree | 혼자 꾸준히 개발하는 장기 오픈소스 프로젝트

---

## 프로젝트 컨텍스트

### 한 줄 목표
`sun.misc.Unsafe` 없이 Java 22 FFM API(Project Panama)만으로 GC 영향 없는 시계열 스토리지 엔진을 만든다.

### 핵심 명제 (항상 기억)
> "Modern Java 표준 API(FFM)로 Unsafe 수준의 성능을 낼 수 있는가"
> 모든 설계 결정은 이 명제를 증명하는 방향이어야 한다.

### 개발 원칙
- **Phase 단위 완성**: 각 Phase는 독립적으로 동작해야 한다. 중간에 멈춰도 결과물이 있어야 한다.
- **측정 우선**: 구현 후 반드시 JMH 벤치마크를 돌린다. "빠를 것 같다"는 증거가 아니다.
- **블로그 병행**: 구현 → 측정 → Velog 포스팅. 글로 남기지 않은 구현은 없는 것과 같다.

---

## 프로젝트 구조

```
kronos/
├── CLAUDE.md                       ← 이 파일
├── .claude/
│   ├── hooks/
│   │   ├── settings.json           ← 훅 등록
│   │   ├── check-java-version.sh   ← Java 22 미만이면 차단
│   │   └── pre-stop-checklist.sh   ← 세션 종료 전 체크
│   ├── skills/
│   │   ├── ffm-api/SKILL.md        ← FFM API 작업 시 자동 발동
│   │   └── lsm-tree/SKILL.md       ← LSM-Tree 작업 시 자동 발동
│   └── commands/
│       ├── bench.md                ← /project:bench — JMH 실행
│       ├── phase-check.md          ← /project:phase-check — 현재 Phase 상태 확인
│       └── blog-draft.md           ← /project:blog-draft — Velog 초안 생성
├── build.gradle
├── settings.gradle
├── docs/
│   ├── ADR/                        ← 아키텍처 결정 기록
│   └── benchmarks/                 ← JMH 결과 저장
└── kronos-core/
    └── src/
        ├── main/java/io/kronos/
        │   ├── memory/             ← FFM API 오프힙 관리
        │   ├── lsm/                ← LSM-Tree (MemTable, SSTable, WAL)
        │   ├── compression/        ← Gorilla XOR, Delta-of-Delta
        │   ├── api/                ← HTTP 쓰기/읽기 API
        │   └── ingestion/          ← Prometheus Remote Write 수신
        └── test/java/io/kronos/
            └── bench/              ← JMH 벤치마크
```

---

## 개발 로드맵 (Phase 기반)

### Phase 0 — FFM API 기초 (현재)
**목표**: `long[]`의 오프힙 버전 구현. GC 영향 없음 확인.
```
[ ] OffHeapLongArray: get/set/close (MemorySegment 기반)
[ ] JMH: 인힙 long[] vs 오프힙 MemorySegment 성능 비교
[ ] 블로그: "Java 22 FFM API로 오프힙 배열 만들기"
```
**완료 기준**: JMH 벤치마크 결과가 docs/benchmarks/phase0.md에 저장되어 있다.

### Phase 1 — LSM-Tree 코어
**목표**: 동작하는 LSM-Tree 엔진.
```
[x] MemTable (ConcurrentSkipListMap 기반)           — ADR-002
[x] SSTable flush (오프힙 → 파일)                   — SSTableWriter/Reader, FFM mmap
[x] WAL (Write-Ahead Log)                           — ADR-003, DSYNC
[x] SSTable 읽기 + 병합                             — MergingIterator + LsmReadView
[x] Basic Compaction                                — ADR-004, size-tiered N-to-1
[x] JMH: 쓰기 처리량 측정                           — docs/benchmarks/phase1-2026-04-17.md
[x] 블로그: "LSM-Tree를 Java로 직접 구현하기" (5편) — docs/blog/phase1-*.md
```
**완료 기준**: 10만 건 write → flush → read가 정합성 있게 동작한다.
`CompactorTest.hundred_thousand_entries_survive_compaction`으로 검증 완료.

### Phase 2 — 시계열 특화
**목표**: TSDB라고 부를 수 있는 시점.
```
[x] Scan bench baseline — docs/benchmarks/phase2-2026-04-17.md
    → 판정식 3개 조건 전부 FAIL → 다음은 인덱스 방향으로 확정
[ ] SSTable 내부 sparse index (ADR-005)  — 인덱스 ADR 초안 후 구현
[ ] Gorilla XOR 압축 구현 (Meta 논문)    — 인덱스 이후 재평가
[ ] Delta-of-Delta 인코딩
[ ] JMH: 인덱스 적용 후 scan bench 재측정 (완료 기준: p99<5ms 또는 5× 개선)
[ ] 블로그: "Phase 2 벤치 설계 + 인덱스 구현기"
```
**완료 기준**: 압축률 75% 이상 달성, 벤치마크 리포트 존재.

### Phase 3 — 연동 레이어
**목표**: 실제로 써볼 수 있는 수준.
```
[ ] HTTP API (쓰기/범위 읽기)
[ ] Prometheus Remote Write 수신
[ ] Grafana 데이터소스 연동
[ ] 최종 벤치마크: vs QuestDB (Unsafe) 비교
[ ] GitHub 0.1.0 릴리즈
```
**완료 기준**: Grafana에서 실제 메트릭이 보인다.

---

## 기술 스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어 | Java 22 | FFM API 정식 스펙 |
| 빌드 | Gradle (Kotlin DSL) | 멀티모듈 확장 고려 |
| 벤치마크 | JMH | JVM 벤치마크 표준 |
| HTTP | Netty / Vert.x | GC 최소화 목적과 일치 |
| 테스트 | JUnit 5 + AssertJ | - |
| CI | GitHub Actions | - |

### 의존성 주의사항
- `sun.misc.Unsafe` **절대 사용 금지** — 이 프로젝트의 존재 이유를 부정하는 행위
- JNI **사용 금지** — FFM API로 대체 가능한 경우
- Spring Boot **사용 금지** (Phase 3 HTTP API) — 경량 서버로 대체. GC 압력 최소화.

---

## 코딩 컨벤션

### FFM API 패턴
```java
// 항상 try-with-resources 또는 명시적 close 사용
// MemorySession은 절대 누수되면 안 됨
try (Arena arena = Arena.ofConfined()) {
    MemorySegment segment = arena.allocate(SIZE);
    // 작업
} // arena.close() 자동 호출 → 오프힙 메모리 해제
```

### 금지 패턴
```java
// ❌ 절대 금지
sun.misc.Unsafe unsafe = ...;

// ❌ 금지: Arena 없이 raw MemorySegment 생성
MemorySegment seg = MemorySegment.ofAddress(ptr); // 수명 관리 불명확

// ✅ 올바른 패턴
try (Arena arena = Arena.ofConfined()) {
    MemorySegment seg = arena.allocate(layout);
}
```

### 패키지 구조 원칙
- `io.kronos.memory` — 오프힙 메모리 추상화. 다른 패키지에 FFM API 직접 노출 금지.
- `io.kronos.lsm` — LSM-Tree 구현. memory 패키지에만 의존.
- `io.kronos.compression` — 순수 비트 연산. 외부 의존성 없음.

---

## JMH 벤치마크 규칙

모든 벤치마크는 `src/test/java/io/kronos/bench/` 아래에 작성.

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class OffHeapArrayBench {
    // 반드시 인힙 대조군과 비교
}
```

결과는 반드시 `docs/benchmarks/phase{N}-{날짜}.md`에 저장:
```markdown
## Phase 0 벤치마크 결과 — 2026-04-09
환경: M2 MacBook / Java 22.0.1 / JMH 1.37
...결과 표...
```

---

## 아키텍처 결정 기록 (ADR)

`docs/ADR/` 디렉토리에 중요한 결정을 남긴다.

파일명 형식: `ADR-001-오프힙-메모리-전략.md`

```markdown
## ADR-001: 오프힙 메모리 관리 전략

**상태**: 결정됨
**날짜**: 2026-04-09

### 결정
Arena.ofConfined()를 기본 Arena로 사용.

### 이유
- ofShared()는 thread-safe하지만 성능 오버헤드 존재
- 현재 단일 스레드 쓰기 경로이므로 ofConfined()가 적합
- 멀티스레드 필요 시 재검토

### 대안
- ofShared(): 멀티스레드 안전, 오버헤드 있음
- ofAuto(): GC 연동, 이 프로젝트 목적에 반함 ← 절대 사용 금지
```

---

## 세션 시작 시 루틴

Claude Code 세션을 시작할 때 항상 이 순서로:

1. **현재 Phase 확인**: 어느 Phase의 어느 태스크를 하고 있었는지
2. **마지막 벤치마크 결과 확인**: `docs/benchmarks/` 최신 파일
3. **미완성 테스트 확인**: `./gradlew test` 실행 후 상태 파악
4. **오늘 목표 1개 설정**: Phase 체크리스트에서 하나만 골라 완료

> 한 세션에 하나만 완성하는 것이 목표. 욕심 부리지 말 것.

---

## 세션 종료 시 루틴

1. `./gradlew test` — 모든 테스트 통과 확인
2. `git commit` — 의미 있는 단위로 커밋
3. Phase 체크리스트 업데이트
4. 오늘 배운 것 한 줄 요약 → `docs/TIL.md`에 추가
5. 벤치마크 결과가 있으면 → `docs/benchmarks/`에 저장

---

## Skills 발동 조건 (Claude Code 자동 참조)

- **FFM API 관련 작업** → `.claude/skills/ffm-api/SKILL.md` 자동 로드
- **LSM-Tree / MemTable / SSTable / Compaction 관련** → `.claude/skills/lsm-tree/SKILL.md` 자동 로드

---

## 자주 쓰는 명령어

```bash
# 빌드
./gradlew build

# 테스트
./gradlew test

# JMH 벤치마크 실행
./gradlew :kronos-core:jmh

# 특정 벤치마크만
./gradlew :kronos-core:jmh --tests "*.OffHeapArrayBench"

# Java 버전 확인 (22 이상이어야 함)
java --version
```

---

## 참고 자료

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454) — FFM API 공식 스펙
- [Gorilla: A Fast, Scalable, In-Memory Time Series Database (Meta, 2015)](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf) — 압축 알고리즘 원본 논문
- [LSM-Tree 원본 논문 (O'Neil et al., 1996)](https://www.cs.umb.edu/~poneil/lsmtree.pdf)
- [QuestDB 소스코드](https://github.com/questdb/questdb) — Unsafe 기반 구현 참고 (대조군)
- [JMH 공식 샘플](https://github.com/openjdk/jmh/tree/master/jmh-samples)
