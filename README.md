# Kronos — Zero-GC Time Series Storage Engine

[![Java 22](https://img.shields.io/badge/Java-22+-blue.svg)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build Status](https://img.shields.io/badge/Build-Phase%202-orange)](docs/)

> **Kronos**는 Java 22의 **FFM API(Project Panama)** 만으로 GC 영향 없는 시계열 스토리지 엔진을 구현하는 장기 오픈소스 프로젝트입니다.  
> `sun.misc.Unsafe` 의존 없이 **Modern Java 표준 API로 Unsafe 수준의 성능**을 낼 수 있는지 검증합니다.

---

## 🎯 핵심 명제

```
"Modern Java 표준 API(FFM)로 Unsafe 수준의 성능을 낼 수 있는가"
```

모든 설계 결정은 이 명제를 증명하는 방향입니다. **측정 우선(Benchmark-Driven Design)** 과 **블로그 병행(Documentation-as-Code)** 원칙으로 진행됩니다.

---

## 📊 성능 데이터 (최신 벤치마크)

### Phase 1 — LSM-Tree 코어 성능

| 측정 경로 | 처리량 | 설명 |
|-----------|--------|------|
| **MemTable Only** | **9.47 M ops/s** | 인메모리 ConcurrentSkipListMap 삽입 |
| **End-to-End Flush** | **3.27 M ops/s** | 10만 건 put → flush → SSTable 쓰기 (CRC32 + fsync) |
| **WAL + MemTable** | **389 ops/s** | WAL 각 append마다 DSYNC 강제 (물리 상한) |

**핵심 통찰**: LSM-Tree의 fsync amortization이 **8,400× throughput 이득** 달성.  
[자세한 분석 →](docs/benchmarks/phase1-2026-04-17.md)

### Phase 2 — 시계열 특화 (Scan 성능)

조건 기반 판정식으로 다음 기술 방향 결정:

| 조건 | 임계값 | 측정값 | 결과 | 의미 |
|------|--------|--------|------|------|
| p99(4파일, 최근분포, 1% 선택성) | < 5ms | **6.7ms** ❌ | 인덱스 필요 |
| p99(1파일, 최근분포, 1% 선택성) | < 5ms | **21.1ms** ❌ | 인덱스 필요 |
| 선택성별 p99 비율 | > 20× | **1.02×** ❌ | 파일 내부 O(n) 전체 스캔 |

**결정**: 다음 Phase는 **SSTable 내부 sparse index 도입** → 예상 4~5× 개선  
[자세한 분석 →](docs/benchmarks/phase2-2026-04-17.md)

---

## 🏗️ 아키텍처 (LSM-Tree + FFM API)

```
┌─────────────────────────────────────────────────────────────┐
│                        API Layer                             │
│  (HTTP 쓰기/읽기 + Prometheus Remote Write 수신)              │
└────────────┬────────────────────────────┬────────────────────┘
             │                            │
       ┌─────▼──────┐              ┌──────▼──────┐
       │  Write Path │              │  Read Path  │
       └─────┬──────┘              └──────┬──────┘
             │                            │
       ┌─────▼────────────────────────────▼────────┐
       │          LSM-Tree Engine Core              │
       │  ┌──────────┐      ┌──────────┐           │
       │  │ MemTable │ ◄─┐  │  SSTable │           │
       │  │(InMemory)│   │  │ (Disk)   │           │
       │  └──────────┘   │  └──────────┘           │
       │       ▲         │       ▲                  │
       │       │      ┌──▼──┐   │                  │
       │       └──────┤ WAL ├───┘                  │
       │              └─────┘                      │
       │        (Write-Ahead Log)                  │
       │                                           │
       │  ┌──────────────────────────────────┐    │
       │  │      Compaction Engine           │    │
       │  │ (Size-Tiered N-to-1 Strategy)    │    │
       │  └──────────────────────────────────┘    │
       └──────────────┬──────────────────────────┘
                      │
       ┌──────────────▼──────────────┐
       │    Off-Heap Memory Layer     │
       │    (FFM API / MemorySegment) │
       └──────────────┬──────────────┘
                      │
       ┌──────────────▼──────────────┐
       │  File System (mmap + fsync) │
       └─────────────────────────────┘
```

[Excalidraw 다이어그램으로 보기](https://excalidraw.com) (상위 다이어그램을 excalidraw로 재작성)

### 각 계층의 역할

| 계층 | 책임 | 핵심 기술 |
|------|------|----------|
| **MemTable** | 인메모리 쓰기 버퍼 | ConcurrentSkipListMap + 오프힙 저장소 |
| **WAL** | 내구성 보장 | Write-Ahead Log with DSYNC flush |
| **SSTable** | 정렬된 범위 저장 | Immutable disk files + mmap |
| **Compaction** | 읽기 성능 최적화 | Size-Tiered N-to-1 Merge |
| **Off-Heap** | GC 영향 차단 | FFM API MemorySegment + Arena |

---

## 🔬 개발 로드맵 (Phase 기반)

### ✅ Phase 0 — FFM API 기초 (완료)
**목표**: `long[]`의 오프힙 버전 구현 + GC 영향 없음 확인

```
[x] OffHeapLongArray: get/set/close (MemorySegment 기반)
[x] JMH: 인힙 long[] vs 오프힙 MemorySegment 성능 비교
[x] 블로그: "Java 22 FFM API로 오프힙 배열 만들기"
```

**벤치마크 결과**: [Phase 0 결과 보고서](docs/benchmarks/phase0-2026-04-09.md)

---

### ✅ Phase 1 — LSM-Tree 코어 (완료)
**목표**: 동작하는 LSM-Tree 엔진 구현

```
[x] MemTable (ConcurrentSkipListMap 기반)
[x] SSTable flush (오프힙 → 파일)
[x] WAL (Write-Ahead Log with DSYNC)
[x] SSTable 읽기 + 병합 (MergingIterator)
[x] Basic Compaction (Size-Tiered)
[x] 100,000 엔트리 정합성 테스트 통과
[x] JMH 벤치마크 (9.47 M ops/s 달성)
[x] 블로그 5편 시리즈 게시
```

**완료 기준**: ✅ 100,000 건 write → flush → read 정합성 + 벤치마크 검증  
**관련 파일**:
- 벤치마크: [Phase 1 결과](docs/benchmarks/phase1-2026-04-17.md)
- ADR: [ADR-002](docs/ADR/ADR-002-memtable-heap-index.md), [ADR-003](docs/ADR/ADR-003-wal-implementation.md), [ADR-004](docs/ADR/ADR-004-basic-compaction.md)
- 블로그: [Phase1 시리즈](docs/blog/)

---

### 🚀 Phase 2 — 시계열 특화 (진행 중)
**목표**: TSDB라고 부를 수 있는 수준

```
[x] Scan bench baseline (1M 엔트리, 16 조합)
[ ] SSTable 내부 sparse index (ADR-005) ← 현재 작업 중
[ ] Gorilla XOR 압축 구현 (Meta 논문)
[ ] Delta-of-Delta 인코딩
[ ] 인덱스 적용 후 scan bench 재측정 (목표: 5× 개선)
[ ] 블로그: "Phase 2 벤치 설계 + 인덱스 구현기"
```

**완료 기준**: p99 < 5ms or 5× 개선 달성 + 압축률 75% 이상

**현 상태**: 판정식 3개 조건 모두 FAIL → 인덱스 필요성 확정  
**벤치마크 데이터**:
- [Phase 2 상세 분석](docs/benchmarks/phase2-2026-04-17.md)
- [설계 스펙](docs/superpowers/specs/2026-04-17-phase2-scan-bench-design.md)

---

### 📋 Phase 3 — 연동 레이어 (예정)
**목표**: 실제로 쓸 수 있는 수준

```
[ ] HTTP API (쓰기/범위 읽기)
[ ] Prometheus Remote Write 수신
[ ] Grafana 데이터소스 연동
[ ] 최종 벤치마크 (vs QuestDB 비교)
[ ] GitHub 0.1.0 릴리즈
```

---

## 📦 프로젝트 구조

```
kronos/
├── README.md                           ← 이 파일
├── CLAUDE.md                           ← 개발 규칙 + Phase 체크리스트
├── build.gradle                        ← Gradle 설정 (JMH 플러그인 포함)
├── settings.gradle
│
├── .claude/                            ← Claude Code 자동화
│   ├── settings.json                   ← 훅 + 권한 설정
│   ├── hooks/
│   │   ├── check-java-version.sh       ← Java 22 체크
│   │   └── pre-stop-checklist.sh       ← 세션 종료 전 검증
│   ├── skills/
│   │   ├── ffm-api/SKILL.md            ← FFM API 작업 시 자동 발동
│   │   └── lsm-tree/SKILL.md           ← LSM-Tree 작업 시 자동 발동
│   └── commands/
│       ├── bench.md                    ← /project:bench
│       ├── phase-check.md              ← /project:phase-check
│       └── blog-draft.md               ← /project:blog-draft
│
├── docs/
│   ├── README.md                       ← 기술 문서 인덱스
│   ├── TIL.md                          ← 배운 점 누적
│   ├── ADR/                            ← 아키텍처 결정 기록
│   │   ├── ADR-002-memtable-heap-index.md
│   │   ├── ADR-003-wal-implementation.md
│   │   ├── ADR-004-basic-compaction.md
│   │   └── ADR-005-sstable-internal-index.md (작성 예정)
│   ├── benchmarks/                     ← JMH 결과 + 분석
│   │   ├── phase0-2026-04-09.md
│   │   ├── phase1-2026-04-17.md
│   │   └── phase2-2026-04-17.md
│   ├── blog/                           ← Velog 포스팅 내용
│   │   ├── phase1-01-memtable.md
│   │   ├── phase1-02-wal.md
│   │   ├── phase1-03-sstable.md
│   │   ├── phase1-04-merge-and-bench.md
│   │   └── phase1-05-compaction.md
│   └── superpowers/                    ← 설계 스펙 + 구현 계획
│       ├── specs/
│       └── plans/
│
└── kronos-core/
    ├── build.gradle
    ├── src/
    │   ├── main/java/io/kronos/
    │   │   ├── memory/                 ← FFM API 오프힙 추상화
    │   │   │   ├── OffHeapLongArray.java
    │   │   │   └── MemoryUtils.java
    │   │   ├── lsm/                    ← LSM-Tree 구현
    │   │   │   ├── MemTable.java
    │   │   │   ├── SSTable*.java       ← Writer, Reader, Meta
    │   │   │   ├── WalWriter.java
    │   │   │   ├── LsmReadView.java    ← 읽기 경로
    │   │   │   ├── MergingIterator.java
    │   │   │   ├── Compactor.java
    │   │   │   └── LsmEngine.java      ← 통합 API
    │   │   ├── compression/            ← 데이터 압축 (구현 예정)
    │   │   │   ├── GorillaXor.java
    │   │   │   └── DeltaOfDelta.java
    │   │   ├── api/                    ← HTTP 레이어 (구현 예정)
    │   │   └── ingestion/              ← Prometheus 연동 (구현 예정)
    │   │
    │   └── test/java/io/kronos/
    │       ├── **/*Test.java           ← 정합성 테스트
    │       └── bench/
    │           ├── LsmWriteBench.java
    │           ├── LsmReadBench.java   (구현 예정)
    │           ├── OffHeapArrayBench.java
    │           ├── LsmScanBench.java
    │           └── *Fixture.java       ← 벤치 데이터 생성
    │
    └── build/
        └── results/jmh/                ← JMH 원본 로그
```

---

## 🛠️ 기술 스택

| 영역 | 선택 | 버전 | 이유 |
|------|------|------|------|
| **언어** | Java | 22+ | FFM API 정식 스펙 |
| **빌드** | Gradle | 8.x | Kotlin DSL + 멀티모듈 |
| **벤치마크** | JMH | 1.37 | JVM 표준 + 정확도 |
| **테스트** | JUnit 5 | 5.x | Modern + 파라미터화 |
| **검증** | AssertJ | 3.x | 가독성 좋은 assertion |
| **메모리** | FFM API | Java 22 | `sun.misc.Unsafe` 대체 |
| **HTTP** | (구현 예정) | - | GC 최소화 경량 서버 |

### 의존성 규칙 (엄격함)

- ✅ **FFM API 필수** — 이 프로젝트의 존재 이유
- ❌ **`sun.misc.Unsafe` 절대 금지** — FFM으로 대체
- ❌ **JNI 사용 금지** — FFM API로 해결
- ❌ **Spring Boot 금지** (Phase 3) — 경량 서버만 (GC 압력 최소화)

---

## 🚀 빠른 시작

### 필수 요구사항

```bash
# Java 22 이상 확인
java --version
# openjdk 22.0.1 2024-08-06 (또는 Temurin 22.x.x)
```

### 빌드

```bash
# 전체 빌드
./gradlew build

# 테스트만 실행
./gradlew test

# 특정 테스트만
./gradlew test --tests "*CompactorTest"
```

### JMH 벤치마크 실행

```bash
# 모든 벤치마크 (시간 소요: ~10분)
./gradlew :kronos-core:jmh

# 특정 벤치마크만 (예: LsmWriteBench)
./gradlew :kronos-core:jmh --tests "*.LsmWriteBench"

# 결과는 자동으로 저장
# → docs/benchmarks/phase{N}-{날짜}.md
```

### 개발 워크플로우 (Claude Code 권장)

```bash
# 세션 시작
# 1. Phase 확인
/project:phase-check

# 2. 현재 Task 선택해서 작업
# (FFM API 또는 LSM-Tree 작업 → 자동으로 SKILL 발동)

# 3. 구현 완료 후
./gradlew test                 # 테스트 통과 확인
./gradlew :kronos-core:jmh    # 벤치마크 실행

# 4. 결과 정리
/project:blog-draft            # Velog 초안 생성
```

---

## 📈 성능 비교 (시각화)

### Throughput (ops/s) — Phase 1

```
MemTable Only         ████████████████████████████ 9.47M ops/s
End-to-End Flush      ██████████ 3.27M ops/s
WAL + MemTable        ░ 389 ops/s (물리 상한)
```

**통찰**: WAL의 DSYNC 비용이 전체 throughput의 지배적 인수.  
LSM-Tree 설계로 fsync를 배치(amortize)하면 **8,400× 이득** 달성 가능.

### Latency (μs) — Phase 2 Scan

```
1 파일, RECENT, 0.01% 선택성:
  p50:  10.2 ms
  p99:  21.1 ms  ❌ 목표: < 5ms

4 파일, 동일 조건:
  p50:   2.3 ms
  p99:   6.7 ms  ⚠️ 거의 만족, 하지만 선택성 미활용
```

**결론**: 파일 단위 프루닝(overlaps)은 잘 작동하나,  
**파일 내부 인덱스 필요** → 예상 4~5× 개선 가능.

---

## 🧪 검증 전략

### 1. 정합성 검증 (Correctness)

모든 operation이 정렬된 상태를 유지하는지 테스트:
- [CompactorTest](kronos-core/src/test/java/io/kronos/lsm/CompactorTest.java) — 100,000 엔트리 compaction + read 검증

```java
@Test
void hundred_thousand_entries_survive_compaction() {
    // 10만 개 엔트리 put → flush → compact → read
    // 모든 엔트리가 올바른 순서로 검색되는지 확인
}
```

### 2. 성능 검증 (Benchmark)

JMH로 매 Phase마다 측정:
- 절대 값: "이 구간은 X ms/op인가?"
- 상대 값: "이전 Phase 대비 몇 배?"
- 비교군: 인메모리 baseline과 비교해서 오버헤드 정량화

### 3. GC 검증 (Zero-GC 목표)

```bash
# Young/Old GC 카운트가 0이어야 함
./gradlew test -Xmx4g -XX:+PrintGCDetails
```

---

## 📚 학습 자료

### 프로젝트 문서

| 문서 | 목적 | 대상 |
|------|------|------|
| [CLAUDE.md](CLAUDE.md) | 개발 규칙 + Phase 체크리스트 | 개발자 (이 프로젝트) |
| [docs/ADR/](docs/ADR/) | 아키텍처 결정 기록 | 설계자, 코드리뷰어 |
| [docs/benchmarks/](docs/benchmarks/) | 성능 분석 + 통찰 | 성능 엔지니어, 의사결정자 |
| [docs/blog/](docs/blog/) | Velog 블로그 원본 | 기술 커뮤니티 |

### 외부 참고 자료

- **[JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)** — FFM API 공식 스펙
- **[Gorilla: Fast, Scalable In-Memory Time Series DB (VLDB 2015)](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf)** — 압축 알고리즘 원본
- **[LSM-Tree 논문 (O'Neil et al., 1996)](https://www.cs.umb.edu/~poneil/lsmtree.pdf)** — 기초 이론
- **[QuestDB 소스코드](https://github.com/questdb/questdb)** — Unsafe 기반 구현 (대조군)

---

## 🤝 기여 (현재 단인 프로젝트)

> 이 프로젝트는 **단일 개발자의 장기 학습 프로젝트**입니다.  
> 아이디어나 피드백은 GitHub Issues에 환영합니다.

---

## 📝 라이선스

MIT License — [LICENSE](LICENSE) 참고

---

## 🎓 개발 기록

### Phase 1 완료 (2026-04-17)

- ✅ LSM-Tree 코어 구현 (MemTable, SSTable, WAL, Compaction)
- ✅ 100,000 엔트리 정합성 검증
- ✅ 벤치마크: 9.47 M ops/s (인메모리), 3.27 M ops/s (end-to-end)
- ✅ 블로그 5편 시리즈 게시

### Phase 2 진행 중 (2026-04-22 ~)

- 🔄 Scan 성능 벤치마크 (1M 엔트리, 16 조합)
- 🔄 판정식 기반 기술 방향 결정 → **sparse index 도입 확정**
- ⏳ ADR-005 작성 (SSTable 내부 인덱스)
- ⏳ 인덱스 구현 + 재벤치 (목표: 5× 개선)

### 최신 TIL

[docs/TIL.md](docs/TIL.md) 참고

---

**마지막 업데이트**: 2026-04-23  
**프로젝트 상태**: Phase 2 진행 중 (인덱스 설계)  
**다음 Milestone**: ADR-005 작성 + sparse index 구현

