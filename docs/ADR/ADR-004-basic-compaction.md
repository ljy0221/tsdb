# ADR-004: Basic Compaction — Size-tiered + 수동 트리거 + 뷰 스냅샷

**상태**: 결정됨
**날짜**: 2026-04-17

---

## 결정

Phase 1 Basic Compaction을 다음 세 결정의 조합으로 구현한다.

1. **정책**: Size-tiered, N-to-1. SSTable 수가 `N` (기본값 4) 이상이면 전체를 단일 SSTable로 병합.
2. **실행 모델**: 수동 트리거. 엔진 상위 계층(Phase 1에서는 테스트)이 `maybeCompact()`를 호출.
3. **원자성 + 소유권**: "뷰는 스냅샷". `LsmReadView`는 생성 시 reader 리스트를 받고, compaction은 새 reader 집합을 만든다. 옛 reader는 **호출자가** 옛 뷰가 모두 close된 시점에 명시적으로 `close() + Files.delete()` 한다.

---

## 논쟁 요약 (tech-decision-debate)

### 제안자의 근거

- **1-A**: Level 메타데이터는 Phase 1에서 죽은 코드가 된다. 현재 `List<SSTableReader>` 모델과 정확히 호환.
- **2-C**: `Arena.ofConfined()` + 단일 스레드 쓰기 ADR을 깨지 않는 유일한 선택. 백그라운드는 Phase 2 엔진 클래스와 함께.
- **3-C**: `LsmReadView`의 기존 계약("reader close는 소유자 책임")과 변경 없이 맞물린다.

### 비판자의 타당한 지적과 반영

| 비판자 질문 | 강도 | 대응 |
|------------|------|------|
| 백그라운드 스레드는 `Arena.ofConfined()`와 충돌 (`WrongThreadException` 확정) | 결정적 | 2-B를 **기술적으로** 배제. 2-C의 근거로 편입. |
| N의 구체적 값과 근거 | 중간 | 기본값 `N=4`로 확정, **실험 기반 재조정**을 재검토 조건에 추가. |
| 수동 트리거 시 "테스트가 호출을 잊으면" | 중간 | Phase 1 범위에서는 허용. 블로그·ADR에 **의도적으로 수동**임을 명시. |
| 뷰 스냅샷에서 "실제 삭제 시점" | 결정적 | `CompactionResult.retireObsolete()` API 제공. 호출 시점은 호출자 책임. Cleaner/Finalizer 금지 (ADR-001 정신). |
| 파일명 충돌 / 고아 파일 / 크래시 중간 파일 | 결정적 | 파일명 규칙과 tmp→rename 규약을 이 ADR에 **명시**. |
| Footer 미작성 파일 | 중간 | `SSTableReader` 생성자가 magic + CRC32 검증으로 이미 거부. tmp 파일은 `.sst` 확장자가 아니므로 디렉토리 스캔에서 제외. |
| 파일 포맷 version | 약함 | 이미 SSTable 포맷에 `version: 4B` 필드 있음(ADR 암묵). |
| WAL truncate 시점 | 이연 | Phase 1 범위 밖. **Phase 2로 명시 이연**. |
| 시계열 단조 증가 → 읽기 증폭 | 잘못된 전제 | 단조 증가이면 각 SSTable의 `[minTs, maxTs]`가 겹치지 않아 footer fast-reject로 대부분 기각. size-tiered가 오히려 유리. |

### 기각된 비판자 질문

- "Level fanout / write amplification / tombstone" → Phase 1 스코프 밖. Leveled는 Phase 2 이후 측정 후 재결정.
- "Windows `Files.move(ATOMIC_MOVE)` 호환성" → 이 프로젝트는 Linux/macOS 전용 (CLAUDE.md 기준 WSL2). 크로스 플랫폼 보장 안 함.

---

## 결정 1 상세: Size-tiered N-to-1

### 설계
```
입력: List<SSTableReader> current   (뷰의 현재 reader 집합)
조건: current.size() >= N           (기본 N=4)
실행: 전부를 MergingIterator로 합쳐 하나의 새 SSTable로 write
결과: CompactionResult { newSSTablePath, obsoleteReaders }
```

### N의 근거

`N=4`는 RocksDB/LevelDB의 default L0 file number triger(4)와 같은 값이다.
Phase 2 진입 후 "SSTable 스택 깊이 vs 읽기 p99"를 실측해서 재조정한다.
Phase 1 완료 기준(10만 건)에서는 flush 1~3회로 끝나는 게 일반적이므로
자동 트리거가 거의 발동하지 않는다 — **테스트에서 N을 2로 낮춰 경로를 검증**한다.

### 시계열 단조 증가와 read amplification

시계열 워크로드는 타임스탬프가 단조 증가하는 경향이 강하다. 따라서
각 SSTable의 `[minTs, maxTs]`가 **겹치지 않는다**. 범위 쿼리에서
`SSTableMeta.overlaps(startTs, endTs)`로 대부분의 파일이 즉시 거부되므로
size-tiered의 약점(같은 키 중복으로 인한 읽기 증폭)이 이 도메인에서는 완화된다.

단, 지연 도착(late arrivals)이 생기면 이 가정이 깨진다.
Phase 2에서 지연 도착 정책이 결정되면 읽기 amplification을 재측정한다.

---

## 결정 2 상세: 수동 트리거

### 설계
- `Compactor.maybeCompact(List<SSTableReader> current)` 호출만이 compaction을 시작시킨다.
- 쓰기 경로(MemTable put, WAL append, flush)는 compaction을 **절대** 호출하지 않는다.
- 벤치마크 결과의 제약("쓰기 경로에 fsync 추가 금지")이 그대로 보장된다.

### 백그라운드 스레드가 지금 불가능한 이유

`SSTableReader`는 `Arena.ofConfined()`로 mmap을 열었다. 이 Arena는 **생성 스레드에서만** 접근 가능하다 (JEP 454). 백그라운드 compaction 스레드가 기존 reader의 mmap을 읽으려 하면 `WrongThreadException`이 확정적으로 발생한다.

해결책은 두 가지뿐이다.
1. `Arena.ofShared()`로 전환 → ADR-001, ADR-002, ADR-003 전면 수정.
2. compaction 스레드가 자기만의 reader를 새로 열어 사용 → 중복 mmap.

둘 다 Phase 1 스코프를 넘는다. 수동 트리거가 **현재 아키텍처의 유일한 합법적 선택**이다.

### 의도적으로 수동임을 명시

블로그 4편과 본 ADR에 "Phase 1은 의도적으로 수동"이라고 적는다. Phase 2에서 엔진 클래스를 도입할 때 `maybeCompact()`를 flush 뒤에 이어서 호출하는 식으로 자동화가 들어간다. 그때도 백그라운드 스레드가 아닌 **같은 스레드의 synchronous call**로 구현한다 — Arena 제약이 그대로다.

---

## 결정 3 상세: 뷰 스냅샷

### API

```java
public record CompactionResult(
    Path newSSTable,
    List<SSTableReader> obsoleteReaders
) {
    /**
     * 호출자가 "이 obsolete reader들을 가진 뷰가 더 이상 없다"를 보장할 때만
     * 호출한다. close() 후 파일 삭제까지 수행한다.
     */
    public void retireObsolete() throws IOException;
}
```

### 소유권 규칙

- `Compactor.maybeCompact(current)`는 **새 SSTable을 쓰고** `CompactionResult`를 반환한다. 옛 reader는 **그대로** 반환된다 (close 안 함).
- 호출자는 이 결과로 새 reader 집합을 구성해 **새 `LsmReadView`를 만든다**. 기존 뷰는 건드리지 않는다.
- 기존 뷰가 모두 사용 완료된 것을 호출자가 확신하면 `retireObsolete()`를 호출해 옛 파일을 지운다.
- Phase 1 테스트는 뷰를 try-with-resources 수명으로 짧게 쓰므로, `maybeCompact` → 새 뷰 생성 → 옛 뷰 close 직후 `retireObsolete()`가 안전하다.

### Cleaner / Finalizer 금지

"뷰가 GC되면 자동 삭제" 같은 `Cleaner` 기반 구현은 **하지 않는다**. 이 프로젝트의 ADR-001은 `Arena.ofAuto()`를 금지한다 — Cleaner를 쓰면 사실상 같은 원칙 위반이다. 누수 위험이 있어도 "명시적 해제" 쪽을 택한다.

---

## 파일 명명 규칙 (Phase 1 확정)

```
{dir}/sst-{generation}-{nanotime}.sst        ← 정상 파일
{dir}/sst-{generation}-{nanotime}.tmp        ← compaction 중간 결과 (미완성)
```

- `generation`: 단조 증가 정수. `MemTable flush`는 0세대, compaction 결과는 입력 중 최대 세대 + 1.
- `nanotime`: 동일 세대 내 충돌 방지용. Compaction 결과는 세대가 바뀌므로 충돌 불가.
- `.tmp` 확장자: write 중인 파일. 완료 후 `Files.move(..., ATOMIC_MOVE)`로 `.sst`로 rename.
- 디렉토리 스캐너(Phase 2)는 `.sst`만 로드하고 `.tmp`는 고아로 간주해 삭제한다.

### 크래시 중간 파일

Compaction 중 프로세스가 죽으면 `.tmp` 파일이 남을 수 있다. 세 가지 안전 장치:
1. `.tmp` 확장자 → 리로드 시 제외 (Phase 2 디렉토리 스캐너에서 구현).
2. `.sst` 파일은 `SSTableReader` 생성 시 magic + CRC32 검증 → footer가 없는 파일은 열리지 않음.
3. `ATOMIC_MOVE` → rename은 POSIX에서 원자적이므로 중간 상태 파일이 `.sst`로 보이는 일이 없음.

Phase 1에서는 테스트 단위로 매번 tmpdir을 새로 만들기 때문에 고아 파일 문제가 드러나지 않는다. Phase 2의 기동 복구와 함께 정비.

---

## 구현 스케치

```java
public final class Compactor {
    private final Path dir;
    private final int trigger;  // 기본 4

    public Compactor(Path dir, int trigger) { ... }

    /**
     * 조건을 만족하면 compaction을 실행한다. 만족하지 않으면 empty.
     * 이 메서드는 쓰기 경로와 같은 스레드에서 호출되어야 한다 (Arena.ofConfined 제약).
     */
    public Optional<CompactionResult> maybeCompact(List<SSTableReader> current)
            throws IOException {
        if (current.size() < trigger) return Optional.empty();

        int maxGen = current.stream()
            .mapToInt(r -> generationOf(r.meta().path()))
            .max().orElse(0);
        int newGen = maxGen + 1;

        Path tmp = dir.resolve("sst-" + newGen + "-" + System.nanoTime() + ".tmp");

        List<Iterator<TimestampValuePair>> sources = new ArrayList<>(current.size());
        // newest-first 순서는 호출자가 보장 (LsmReadView와 동일 규약)
        for (var r : current) sources.add(r.iterator());

        MergingIterator merged = new MergingIterator(sources);
        SSTableWriter.writeFromIterator(merged, tmp, /*count hint*/ sumCount(current));

        Path finalPath = Path.of(tmp.toString().replace(".tmp", ".sst"));
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE);

        return Optional.of(new CompactionResult(finalPath, List.copyOf(current)));
    }
}
```

`SSTableWriter.writeFromIterator(Iterator, Path, long count)`는 기존 `write(MemTable, Path)`의 내부 로직을 공용 헬퍼로 추출해 재사용한다.

> **트레이드오프**: count가 미리 필요하다. MergingIterator는 중복을 drain하므로 입력 총 엔트리 수보다 적게 나올 수 있다. 해결책은 2패스(1차 순회로 count 측정, 2차 순회로 write) 또는 "입력 총합을 상한으로 잡고 실제 count로 footer 갱신". 전자는 읽기 2배, 후자는 파일 끝 패치. **후자를 채택** — 오프힙 버퍼를 잡을 때 "상한 크기"로 할당하고, 실제 write position으로 header count를 갱신한 뒤 실제 크기만큼만 `ch.write()`.

---

## 알려진 트레이드오프

| 항목 | 비용 | 완화 |
|------|------|------|
| 수동 트리거 | 호출자가 잊으면 SSTable 무제한 누적 | Phase 2 엔진 클래스가 flush 직후 자동 호출 |
| N=4 임의 값 | 벤치 기반 아님 | Phase 2 재측정 후 조정 |
| 뷰 스냅샷의 삭제 시점 위임 | 호출자가 잘못 판단하면 SIGBUS 또는 파일 누적 | Phase 1 테스트는 뷰 수명이 짧아 안전 |
| `Files.move(ATOMIC_MOVE)` POSIX 의존 | Windows 지원 없음 | 프로젝트 타깃은 Linux/macOS |

---

## Phase 2로 명시적으로 미루는 것

- 백그라운드 compaction 스레드 (Arena 모델 재설계 필요)
- Manifest 파일과 크래시 복구 시 SSTable 리로딩
- Leveled compaction / compaction score
- SSTable 디렉토리 스캐너 / 기동 시 자동 로드
- 참조 카운트 기반 reader lifecycle
- Tombstone/delete 마커 (시계열은 append-only가 기본이나 재평가 필요)
- WAL truncate 정책 (compaction 완료 후 어느 WAL 세그먼트까지 버릴 것인가)
- Late arrival이 만드는 `[minTs, maxTs]` 겹침과 그로 인한 읽기 증폭 재측정

---

## 재검토 시점

- **Phase 2에서 엔진 상위 클래스가 생길 때**: 수동 트리거 → 자동 트리거(flush after hook) 전환.
- **JMH 읽기 벤치에서 SSTable 스택 깊이가 p99에 영향을 줄 때**: N 조정 또는 Leveled 전환 검토.
- **멀티스레드 쓰기 도입 시**: Arena 모델 재설계 + 백그라운드 스레드 + manifest 동시 도입.

---

## Phase 1 완료 기준 충족 계획

**테스트 시나리오** (JUnit):
1. MemTable 3번 flush → SSTable 3개 (N=2로 낮춰 조건 충족)
2. `maybeCompact()` 호출 → 새 SSTable 1개 생성
3. 호출자가 새 `LsmReadView` 생성 (새 reader만 포함)
4. 옛 뷰 close → `retireObsolete()` → 옛 파일 3개 삭제 확인
5. 새 뷰에서 전체 범위 scan → 모든 원본 엔트리가 타임스탬프 오름차순으로 나오는지 검증

이 테스트 하나로 Phase 1 체크리스트 "Basic Compaction"이 닫힌다.
