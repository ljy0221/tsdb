---
title: "LSM-Tree 직접 구현기 (5) — Basic Compaction: size-tiered N-to-1, 왜 수동 트리거였나"
tags: [Java, FFM API, TSDB, LSM-Tree, Compaction, 오픈소스]
---

> Phase 1의 마지막 조각. SSTable이 무한정 쌓이는 문제를 compaction으로 푼다.
> 그런데 "size-tiered + **수동** 트리거 + 뷰 스냅샷"이라는, 교과서에서는 잘 안 나오는 조합을 택했다.
> 이유는 전부 **이 프로젝트의 지금까지 결정들** 때문이었다.

---

## 문제 — SSTable은 읽을수록 늘어난다

4편 마지막에서 `endToEndFlush` 3.27M ops/s를 얻었다. flush 한 번에 10만 건이 나가니까 부하를 밀어 넣을수록 `.sst` 파일이 선형으로 늘어난다. 이게 **읽기 amplification** 문제다.

```
write가 많아질수록…
┌──────────────────────┐
│ sst-0-t9 (newest)    │
│ sst-0-t8             │
│ sst-0-t7             │
│ sst-0-t6             │
│ sst-0-t5             │
│ sst-0-t4             │
│ sst-0-t3             │
│ sst-0-t2             │
│ sst-0-t1             │
│ sst-0-t0 (oldest)    │
└──────────────────────┘
   ↑ get()이 최악의 경우 10개를 다 뒤진다
```

`LsmReadView.get()`이 min/max로 fast-reject를 하긴 한다. 하지만 시계열이어도 지연 도착이 섞이거나 같은 ts가 여러 번 들어오면 겹침이 생긴다. 파일 수를 **상수 이하로 유지하는 장치**가 필요하다.

해법의 이름은 compaction — 여러 SSTable을 읽어 하나로 병합해 쓰고, 옛 파일들은 지운다.

---

## 핵심 제약 — 4편 벤치가 남긴 "하지 말 것" 목록

설계를 시작하기 전에 [4편 Phase 1 JMH 결과](./phase1-04-merge-and-bench.md)를 다시 본다. 수치 하나가 compaction 설계의 골격을 반쯤 정해놓고 있었다.

| 경로              | ops/s (env: WSL2 ext4) |
|-------------------|-----------------------:|
| memTableOnly      |              ~9.47 M   |
| endToEndFlush     |              ~3.27 M   |
| memTablePlusWal   |                 ~389   |

`memTablePlusWal ≈ 389 ops/s` — WAL fsync(DSYNC) 한 번에 약 2.57ms. **이게 durable 쓰기의 물리 상한**이다. 코드로는 못 넘는다.

여기서 compaction에 걸리는 제약이 나온다.

- 쓰기 경로에 **fsync를 추가하면 안 된다.** 추가되는 순간 ops/s가 곧바로 반토막.
- 쓰기 경로에 **동기 대기를 걸면 안 된다.** WAL 2.57ms 뒤에 compaction을 기다리게 하면 지연이 두 배가 된다.
- 즉 compaction은 **쓰기와 독립적**이어야 한다.

교과서적인 답은 "백그라운드 스레드에서 돌려라"다. 이 프로젝트에서는 그게 **지금은 불가능**했다. 왜 그런지 보는 게 이 글의 본론이다.

---

## Arena.ofConfined()가 만든 벽

ADR-001의 결정 한 줄로 거슬러 올라간다.

> Arena는 `Arena.ofConfined()`를 기본으로 쓴다.

`ofConfined()` Arena는 **생성한 스레드에서만** 접근 가능하다. 다른 스레드가 이 Arena에서 나온 `MemorySegment`에 접근하면 `WrongThreadException`이 **확정적으로** 발생한다. JEP 454의 명시적 계약이다.

문제는 `SSTableReader`가 `Arena.ofConfined()`로 mmap을 쥐고 있다는 점이다.

```java
// SSTableReader (요약)
this.arena   = Arena.ofConfined();
this.segment = arena.allocate(size);  // 또는 mmap segment
```

백그라운드 compaction 스레드가 "옛 SSTable 3개를 읽어서 새 파일로 쓴다"를 하려면 기존 reader의 `MemorySegment`를 읽어야 한다. 그 접근은 쓰기 스레드가 만든 Arena의 영역이다. 두 스레드가 같은 Arena를 터치하는 순간 `WrongThreadException`.

선택지는 두 개뿐이었다.

1. **`Arena.ofShared()`로 전환** — 멀티스레드 안전, 대신 ADR-001 · ADR-002 · ADR-003을 전면 수정. Shared Arena의 동기화 오버헤드가 오프힙 경로 전반에 깔린다. "Unsafe 수준의 성능"이라는 핵심 명제를 재측정해야 한다.
2. **Compaction 스레드가 자기만의 reader를 새로 연다** — 같은 파일을 두 번 mmap. 커널이 페이지 캐시로 중복을 회수한다 해도, 관리 복잡도가 Phase 1 스코프를 넘어간다.

둘 다 **Phase 1에서 할 일이 아니다.** Phase 1의 기준은 "10만 건 write → flush → read 정합성"이지 "백그라운드 compaction"이 아니다.

따라서 Phase 1의 compaction은 **쓰기 스레드와 같은 스레드에서 수동으로 돌아가야** 했다. 이건 피해 간 것이 아니라 **현재 아키텍처의 유일한 합법적 선택**이다. ADR-004에 이 근거를 그대로 박아놓았다.

---

## 정책 — Size-tiered, N-to-1

두 번째 결정. 정책은 무엇으로 할까.

교과서에 나오는 후보는 두 가지다.

- **Leveled** — Level별로 용량 상한을 두고 넘치면 다음 level로. LevelDB/RocksDB의 기본. 읽기 amp가 작고 쓰기 amp가 크다.
- **Size-tiered** — 비슷한 크기의 SSTable이 N개 쌓이면 묶어서 하나로. Cassandra의 기본. 쓰기 amp가 작고 읽기 amp가 크다.

Phase 1에서는 **size-tiered N-to-1**을 택했다. 이유는 세 가지.

1. **"Level 개념이 지금 죽은 코드다."** 현재 `LsmReadView`는 `List<SSTableReader>` 하나만 쥔다. Level 메타데이터를 도입하면 단지 compaction을 위해 전체 읽기 경로를 재배치해야 한다.
2. **시계열 워크로드는 `[minTs, maxTs]`가 잘 안 겹친다.** 타임스탬프가 단조 증가하는 경향이 강해서, 범위 쿼리에서 대부분의 SSTable이 footer min/max로 즉시 거부된다. size-tiered의 약점(중복 키 누적)이 이 도메인에서는 완화된다.
3. **N=4는 실측 없이 "의미 있는 기본값"이다.** RocksDB/LevelDB의 default L0 file number trigger와 같은 값. Phase 2에서 "SSTable 스택 깊이 vs 읽기 p99"를 재측정해 조정한다.

핵심 로직은 딱 이 정도다.

```java
public Optional<CompactionResult> maybeCompact(List<SSTableReader> current)
        throws IOException {
    if (current.size() < trigger) return Optional.empty();

    int newGen = 0;
    long totalCount = 0;
    for (SSTableReader r : current) {
        int g = generationOf(r.meta().path());
        if (g > newGen) newGen = g;
        totalCount += r.meta().entryCount();
    }
    newGen += 1;

    Path tmp = dir.resolve("sst-" + newGen + "-" + System.nanoTime() + ".tmp");

    List<Iterator<TimestampValuePair>> sources = new ArrayList<>(current.size());
    for (SSTableReader r : current) sources.add(r.iterator());

    MergingIterator merged = new MergingIterator(sources);
    SSTableWriter.writeFromIterator(merged, tmp, totalCount);

    Path finalPath = dir.resolve(
        tmp.getFileName().toString().replace(".tmp", ".sst"));
    Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE);

    return Optional.of(new CompactionResult(finalPath, List.copyOf(current)));
}
```

짧다. 짧은 이유가 중요하다. 4편에서 만든 `MergingIterator`가 "여러 소스를 newest-wins로 한 스트림처럼 읽는" 일을 이미 다 한다. Compactor는 그저 그 스트림을 새 파일에 쓴다. **기존 읽기 경로와 compaction이 같은 primitive를 공유**한다.

### 왜 호출자 순서를 newest-first로 유지했나

한 줄만 짚고 가자. 호출자는 `LsmReadView`와 똑같이 `current`를 **newest-first**로 넘긴다. `MergingIterator`의 비교자는 2차 키로 소스 index(=priority)를 쓰는데, index 0이 newest면 동일 ts에서 **newest가 이긴다**. 읽기와 compaction이 **같은 newest-wins 규칙**을 **같은 한 줄의 비교자**로 공유한다. 정책을 두 번 구현하지 않는다.

---

## count 상한 — "정확한 수"는 못 준다

한 가지 트레이드오프가 있었다. `SSTableWriter`는 쓸 엔트리 수를 미리 받는다(오프힙 버퍼를 그 크기로 할당한다). 그런데 `MergingIterator`가 중복을 drain하므로 **실제 결과 엔트리 수는 입력 총합보다 작을 수 있다**.

선택지는 두 가지였다.

- **2패스** — 한 번 순회해 실제 count를 잰 뒤, 두 번째 순회로 write. 읽기 비용 2배.
- **상한 할당 + footer 갱신** — `totalCount`를 상한으로 잡고 버퍼를 할당. 실제 write가 끝나면 footer의 entryCount만 실제 값으로 갱신.

후자를 채택했다. `SSTableWriter.writeFromIterator`가 그 계약을 그대로 받는다. 4편의 `endToEndFlush = 3.27M ops/s`가 말해주듯 **파일 write는 fsync 1회**로 amortize된다 — 상한 버퍼가 약간 크더라도 실제 bytes write만 정확하면 OCI 영향이 없다.

---

## 소유권 — "뷰는 스냅샷이다"

세 번째 결정이 가장 까다롭다. Compaction이 끝나면 옛 SSTable 파일은 언제 지워야 하나?

순진하게 "즉시 지운다"가 성립하지 않는다. Compaction이 일어난 **바로 그 순간**에 **옛 reader를 참조하는 읽기 쿼리**가 있을 수 있기 때문이다. mmap된 파일을 삭제해도 이미 맵핑된 페이지는 유효하지만, `SSTableReader.close()`로 Arena를 닫는 순간 거기서 나온 `MemorySegment` 접근은 `IllegalStateException`을 만난다. 최악의 경우 OS 레벨에서 SIGBUS도 가능하다.

그래서 모델을 이렇게 잡았다.

> **뷰는 스냅샷이다.** `LsmReadView`는 생성 시점의 reader 집합을 잡고, 이후 SSTable 집합이 바뀌어도 **자기가 받은 리스트만 본다**. Compaction은 옛 뷰를 건드리지 않고, 새 reader 집합으로 **새 뷰를 만들** 뿐이다.

그리고 **파일 삭제는 호출자가 명시적으로** 한다. `CompactionResult.retireObsolete()`가 그 API다.

```java
public record CompactionResult(
        Path newSSTable,
        List<SSTableReader> obsoleteReaders
) {
    /**
     * 호출 전제: 이 obsoleteReaders를 참조하는 뷰가 더 이상 없어야 한다.
     * 이 조건이 깨지면 진행 중 쿼리가 SIGBUS 또는 IllegalStateException을 만날 수 있다.
     */
    public void retireObsolete() throws IOException { /* close + delete */ }
}
```

테스트에서 이 계약이 어떻게 보이는지가 명확하다.

```java
// 1) compact — 옛 reader는 아직 살아 있음
Optional<CompactionResult> result = compactor.maybeCompact(List.of(newest, mid, oldest));

// 2) 새 뷰 생성 (새 reader만 포함)
try (SSTableReader newReader = new SSTableReader(result.get().newSSTable())) {
    LsmReadView view = new LsmReadView(null, List.of(newReader));
    // ... scan/get 검증
}

// 3) 호출자가 "옛 뷰는 더 이상 없다"를 선언
result.get().retireObsolete();   // 이제야 close + delete
```

순서가 포인트다. **새 뷰가 완전히 쓰고 끝난 뒤에** `retireObsolete()`가 호출된다. 뷰의 수명과 파일 삭제 사이에 **호출자가 명시적으로 일치시킨다**.

### 왜 Cleaner/Finalizer를 쓰지 않았나

"뷰가 GC되면 자동으로 옛 reader를 해제한다" 같은 `Cleaner` 기반 설계가 가능은 하다. 단 한 줄로 쳐냈다.

**ADR-001이 `Arena.ofAuto()`를 금지한다.** 이 프로젝트의 존재 이유는 "GC에 의존하지 않는 메모리 관리"다. Cleaner는 본질적으로 같은 자리에 서 있다 — GC 시점에 의존하는 자원 해제. 원칙을 외부적으로만 지키고 내부적으로 깨는 건 의미가 없다.

"누수 위험이 있어도 명시적 해제를 택한다." 이 한 줄이 이 프로젝트의 규율이다.

---

## 파일 명명 규칙 — `.tmp` → `.sst` atomic rename

짧게 짚고 넘어간다.

```
sst-{generation}-{nanotime}.sst   ← 정상 파일
sst-{generation}-{nanotime}.tmp   ← 중간 파일 (compaction 중)
```

- `generation`: 단조 증가 정수. Flush는 0세대, compaction 결과는 입력 중 최대 세대 + 1.
- `.tmp`로 write → `Files.move(tmp, final, ATOMIC_MOVE)`. POSIX에서 rename은 원자적이다.
- `.sst`만 로드되게 설계하면, 크래시로 남은 `.tmp`는 고아로 무시된다.
- `SSTableReader` 생성 시 magic + CRC32 검증 — footer가 없는 파일은 열리지 않는다.

이 규칙은 Phase 2의 디렉토리 스캐너/기동 복구를 위한 **사전 작업**이다. Phase 1에서는 테스트마다 tmpdir을 새로 만들기 때문에 고아 문제가 드러나지 않는다.

> Windows `ATOMIC_MOVE` 호환은 보장하지 않는다. 이 프로젝트의 타깃은 Linux/macOS.

---

## 검증 — Phase 1 완료 기준을 compaction이 통과하게 만들기

Phase 1 체크리스트의 마지막 줄.

> 10만 건 write → flush → read가 정합성 있게 동작한다.

Compaction이 이 경로를 통과해도 깨지지 않아야 한다. 테스트 하나로 닫는다.

```java
@Test
void hundred_thousand_entries_survive_compaction() throws IOException {
    // 10만 건을 3개 SSTable로 나눠 flush (키 범위 비중첩)
    List<SSTableReader> readers = /* ... */;

    Compactor compactor = new Compactor(tempDir, 3);
    Optional<CompactionResult> result = compactor.maybeCompact(readers);

    assertThat(result).isPresent();
    try (SSTableReader merged = new SSTableReader(result.get().newSSTable())) {
        assertThat(merged.meta().entryCount()).isEqualTo(100_000);
        // ts 단조 증가 + 값이 기대치와 일치
    }

    result.get().retireObsolete();
}
```

`CompactorTest.hundred_thousand_entries_survive_compaction`이 초록이 되는 순간 Phase 1이 닫힌다. newest-wins는 별도 테스트 `compacts_sstables_and_preserves_entries_with_newest_wins`로 따로 잡는다.

> 전체 구현과 테스트: [Compactor.java](../../kronos-core/src/main/java/io/kronos/lsm/Compactor.java) · [CompactionResult.java](../../kronos-core/src/main/java/io/kronos/lsm/CompactionResult.java) · [CompactorTest.java](../../kronos-core/src/test/java/io/kronos/lsm/CompactorTest.java) · [ADR-004](../ADR/ADR-004-basic-compaction.md)

---

## 명시적으로 Phase 2로 미룬 것들

이 글이 끝나는 지점이 Phase 1의 끝점이다. 그래서 "아직 안 한 것"을 기록하는 게 의무다.

- **자동 트리거** — Phase 2에서 엔진 상위 클래스가 생기면 flush 직후 `maybeCompact()`를 같은 스레드에서 synchronous로 호출.
- **백그라운드 compaction** — Arena 모델 재설계 + 멀티스레드 쓰기 도입과 한 번에.
- **Manifest + 기동 복구** — 프로세스 재시작 후 디렉토리에서 SSTable 리로드.
- **Leveled compaction** — SSTable 스택 깊이가 읽기 p99에 의미 있는 영향을 줄 때 검토.
- **Tombstone/delete 마커** — 시계열이 append-only에 가깝지만 재평가 필요.
- **WAL truncate** — compaction 완료 후 어느 WAL 세그먼트까지 버릴 것인가.

각 항목은 ADR-004의 "재검토 시점" 절에 **계기 조건**과 함께 적혀 있다. "나중에 생각하자"가 아니라 "이런 신호가 보이면 다시 연다".

---

## 마무리 — Phase 1이 닫힌다

5편을 끝으로 Phase 1이 닫힌다. 되짚어 보면 이렇다.

1. [**MemTable**](./phase1-01-memtable.md) — 힙 인덱스 + 오프힙 데이터 버퍼.
2. [**WAL**](./phase1-02-wal.md) — DSYNC + CRC32로 내구성.
3. [**SSTable**](./phase1-03-sstable.md) — FFM mmap으로 flush/read.
4. [**MergingIterator + JMH**](./phase1-04-merge-and-bench.md) — k-way merge, 쓰기 처리량 측정.
5. **Basic Compaction** (이 글) — size-tiered N-to-1, 수동 트리거, 뷰 스냅샷.

패턴이 보인다. **매번 "교과서적인 답"이 아니라 "이 프로젝트의 이전 결정들과 정합하는 답"을 택했다.** WAL은 DSYNC(이유: 4편 벤치로 물리 상한을 알고 있었다). Merge는 Iterator(이유: push 콜백으로는 교차 진행이 안 된다). Compaction은 수동 트리거(이유: `Arena.ofConfined()`로 여기까지 왔다).

한 프로젝트에서 결정들이 서로를 참조하는 구조가 되면, 새 결정의 탐색 공간은 빠르게 좁아진다. 자유도가 줄어드는 게 아니라 **생각할 것이 줄어든다**.

Phase 2는 "TSDB라고 부를 수 있는 시점"이다. 타임스탬프 범위 쿼리, Gorilla XOR, Delta-of-Delta. 여기서부터는 "저장 엔진"이 아니라 "시계열 엔진"의 이야기다.
