# Kronos 문서 인덱스

> 이 페이지는 모든 기술 문서의 지도입니다.

## 📍 빠른 네비게이션

### 🏃 처음 시작하는 분들

1. **[프로젝트 README](../README.md)** — 프로젝트 개요, 성능 수치, 빠른 시작
2. **[ARCHITECTURE.md](./ARCHITECTURE.md)** — 시스템 설계, 데이터 흐름, 메모리 관리
3. **[Phase 체크리스트](../CLAUDE.md#개발-로드맵-phase-기반)** — 현재 개발 상태

### 🔬 벤치마크 분석

| Phase | 링크 | 측정 내용 | 주요 수치 |
|-------|------|----------|----------|
| **0** | [phase0-2026-04-09.md](benchmarks/phase0-2026-04-09.md) | 오프힙 배열 | FFM vs JNI |
| **1** | [phase1-2026-04-17.md](benchmarks/phase1-2026-04-17.md) | LSM-Tree | **9.47 M ops/s** |
| **2** | [phase2-2026-04-17.md](benchmarks/phase2-2026-04-17.md) | Scan 성능 | p99: **21.1ms** (개선 필요) |

👉 **빠른 통찰** : [phase2-2026-04-17.md의 해석 섹션](benchmarks/phase2-2026-04-17.md#해석--수치가-말해주는-것)

### 📋 아키텍처 결정 (ADR)

모든 중요 설계 결정은 `ADR/` 디렉토리에 기록됩니다.

| ADR | 제목 | 상태 | 설명 |
|-----|------|------|------|
| [ADR-002](ADR/ADR-002-memtable-heap-index.md) | MemTable Heap Index | ✅ 결정됨 | Skip-List vs Heap (결정: Skip-List) |
| [ADR-003](ADR/ADR-003-wal-implementation.md) | WAL 구현 전략 | ✅ 결정됨 | DSYNC vs SYNC (결정: DSYNC) |
| [ADR-004](ADR/ADR-004-basic-compaction.md) | 기본 Compaction | ✅ 결정됨 | Size-Tiered N-to-1 merge |
| ADR-005 | SSTable 내부 sparse index | ⏳ 작성 중 | Phase 2 벤치 결과 기반 (예정) |

### 📝 블로그 포스팅 (Velog)

Kronos의 기술 여정은 블로그로 기록됩니다. 각 Phase마다 상세한 구현 기술 문서를 작성합니다.

#### Phase 1 시리즈 (5편)

1. **[MemTable 구현](blog/phase1-01-memtable.md)** — ConcurrentSkipListMap + 오프힙 저장소
2. **[WAL 설계](blog/phase1-02-wal.md)** — Write-Ahead Log의 DSYNC 전략
3. **[SSTable 포맷](blog/phase1-03-sstable.md)** — 디스크 저장, mmap, 메타데이터
4. **[병합과 벤치마크](blog/phase1-04-merge-and-bench.md)** — MergingIterator + 성능 검증
5. **[Compaction](blog/phase1-05-compaction.md)** — Size-Tiered 압축 전략

#### Phase 2 시리즈 (준비 중)

- Phase 2 벤치 설계 및 판정식
- SSTable 내부 sparse index 구현기
- Gorilla XOR 압축 실전 가이드

### 📚 설계 스펙과 계획

| 문서 | 목적 |
|------|------|
| [2026-04-17-phase2-scan-bench-design.md](superpowers/specs/) | Phase 2 벤치마크 설계 스펙 |
| [2026-04-17-phase2-scan-bench.md](superpowers/plans/) | Phase 2 구현 계획 |

### 📖 참고: 외부 자료

- **FFM API**
  - [JEP 454 공식 스펙](https://openjdk.org/jeps/454)
  - [MemorySegment API 문서](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/MemorySegment.html)

- **LSM-Tree 이론**
  - [원본 논문 (O'Neil et al., 1996)](https://www.cs.umb.edu/~poneil/lsmtree.pdf)
  - [Meta Gorilla 논문 (VLDB 2015)](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf)

- **오픈소스 참고**
  - [QuestDB (Unsafe 기반 구현)](https://github.com/questdb/questdb)
  - [RocksDB (LSM 표준)](https://github.com/facebook/rocksdb)

---

## 🎯 Phase 별 문서 맵

### Phase 0: FFM API 기초

```
FFM API 학습
    ↓
OffHeapLongArray 구현
    ↓
JMH 벤치마크 (인메모리 vs 오프힙)
    ↓
✅ 완료: docs/benchmarks/phase0-2026-04-09.md
```

### Phase 1: LSM-Tree 코어

```
MemTable (ConcurrentSkipListMap)
    ↓
WAL (Write-Ahead Log)
    ↓
SSTable (flush to disk)
    ↓
Compaction (merge N files → 1 file)
    ↓
JMH 벤치마크 (throughput: MemTable, WAL, end-to-end)
    ↓
✅ 완료: docs/benchmarks/phase1-2026-04-17.md
        docs/blog/phase1-*.md (5편)
```

### Phase 2: 시계열 특화 (진행 중)

```
Scan 성능 벤치마크 설계 (1M entries, 16 combinations)
    ↓
벤치마크 실행 및 분석
    ↓
판정식 기반 기술 방향 결정
    → 결과: 모든 조건 FAIL → sparse index 필요 확정
    ↓
ADR-005: SSTable 내부 sparse index 설계
    ↓
구현 + 재벤치 (목표: 5× 개선, p99 < 5ms)
    ↓
Gorilla XOR 압축 (선택: 메모리 vs 압축률)
    ↓
⏳ 예정: docs/benchmarks/phase2-2026-05-XX.md (재벤치)
        docs/blog/phase2-*.md (2~3편)
```

### Phase 3: 연동 레이어 (예정)

```
HTTP API (쓰기/읽기)
    ↓
Prometheus Remote Write 수신
    ↓
Grafana 데이터소스 연동
    ↓
최종 벤치마크 (QuestDB 비교)
    ↓
📦 0.1.0 릴리즈
```

---

## 📊 벤치마크 성능 한눈에 보기

### Throughput 추이 (Phase 0 → Phase 1)

```
MemTable Only (in-memory):
  ████████████████████████████ 9.47 M ops/s

End-to-End (put + flush to SSTable):
  ██████████ 3.27 M ops/s

WAL + MemTable (with DSYNC):
  ░ 389 ops/s (물리 상한)
```

**통찰**: LSM-Tree의 fsync amortization이 **8,400× throughput 이득** 달성.

### Latency 추이 (Phase 1 → Phase 2)

#### Phase 1: Full table scan
```
1 파일, RECENT, 0.01% 선택성:
  p50:  10 ms
  p99:  21 ms  ❌ 목표: < 5ms
```

#### Phase 2 (목표): Sparse index 적용 후
```
1 파일, RECENT, 0.01% 선택성:
  p50:  2-3 ms  ✅ (예상)
  p99:  < 5 ms  ✅ (목표)
  개선율: 4-5×
```

---

## 🛠️ 사용 가능한 명령어

프로젝트 디렉토리에서:

```bash
# 벤치마크 실행
./gradlew :kronos-core:jmh

# 테스트 실행
./gradlew test

# 특정 테스트만
./gradlew test --tests "*CompactorTest"

# Claude Code 커맨드 (VSCode 확장 또는 CLI)
/project:bench          # JMH 실행
/project:phase-check    # 현재 Phase 상태
/project:blog-draft     # Velog 초안 생성
```

---

## 📅 최신 업데이트

| 날짜 | 항목 | 상태 |
|------|------|------|
| 2026-04-23 | README + ARCHITECTURE 작성 | ✅ |
| 2026-04-22 | Phase 2 벤치 분석 완료 (sparse index 필요 확정) | ✅ |
| 2026-04-17 | Phase 1 완료 (벤치마크: 9.47 M ops/s) | ✅ |
| 2026-04-09 | Phase 0 완료 (FFM API 기초) | ✅ |

---

## 💡 이 문서들을 읽어야 하는 경우

| 역할 | 추천 문서 | 순서 |
|------|----------|------|
| **첫 기여자** | README → ARCHITECTURE → phase1-*.md | 순서대로 |
| **성능 분석** | phase2-*.md (판정식) → ADR-005 (설계) | 병렬 |
| **인덱스 구현** | ARCHITECTURE (메모리 레이아웃) → ADR-005 (스펙) | 순서대로 |
| **벤치마크 개선** | 최신 phase-*.md (벤치마크 섹션) | 선택 |
| **블로그 작성** | blog/phase*-*.md (참고 자료) | 자유 |

---

## 📞 문의

- 이 프로젝트는 **단인 프로젝트**입니다.
- 피드백/이슈: GitHub Issues
- 기술 토론: Pull Requests / Discussions

---

**마지막 업데이트**: 2026-04-23  
**다음 Milestone**: ADR-005 (SSTable sparse index 설계)
