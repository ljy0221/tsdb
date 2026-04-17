---
title: "LSM-Tree 직접 구현기 (3) — SSTable: 오프힙 데이터를 파일로 flush하기"
tags: [Java, FFM API, TSDB, LSM-Tree, SSTable, 오픈소스]
---

> Kronos Phase 1 세 번째 컴포넌트. MemTable이 가득 차면 SSTable로 내려야 한다.
> 이 글은 "오프힙 `MemorySegment`를 불변 정렬 파일로 바꾸는 경로"를 기록한다.

---

## SSTable이 해야 할 일

지난 글까지 쓰기 경로는 `WAL → MemTable`에서 끝났다.
MemTable은 힙을 계속 점유하므로 일정 크기를 넘으면 디스크로 내려야 한다.
이게 **SSTable flush**다.

```
MemTable(frozen)
  ─ timestamp 오름차순 순회 ─┐
                              ▼
                    ┌──────────────────┐
                    │   SSTable 파일   │
                    │  (불변, 정렬됨)  │
                    └──────────────────┘
```

SSTable의 핵심 성질은 세 가지다.

1. **불변(immutable)** — 한 번 쓰면 고치지 않는다. 수정은 "새 SSTable 쓰기 + 옛것 폐기"로 처리한다.
2. **정렬** — 데이터 블록이 timestamp 오름차순으로 저장된다. 범위 스캔이 이진 탐색 + 선형 주사로 끝난다.
3. **자기 검증** — 파일 끝에 체크섬이 있어 손상을 탐지할 수 있다.

---

## 파일 포맷

Phase 1 SSTable 포맷. 의도적으로 작게 잡았다.

```
┌─────────────── Header (16B) ────────────────┐
│ magic   : 4B  0x4B524F4E ("KRON")           │
│ version : 4B  1                             │
│ count   : 8B  entry 개수 (long)             │
├─────────────── Data Block ──────────────────┤
│ [ts: 8B][value: 8B]  × count                │
├─────────────── Footer (20B) ────────────────┤
│ min_ts   : 8B                               │
│ max_ts   : 8B                               │
│ checksum : 4B  CRC32(Data Block)            │
└─────────────────────────────────────────────┘
```

**데이터 블록 앞에 인덱스가 없다.** 읽기 경로에서 이진 탐색으로 직접 찾는다 — 모든 엔트리가 16B 고정 크기라서 `HEADER + idx*16`으로 오프셋이 바로 계산된다. 가변 길이 엔트리라면 별도 인덱스 블록이 필요하지만 Phase 1 스코프에서는 YAGNI다.

**Footer의 min_ts/max_ts**는 읽기 쿼리가 파일을 열기 전에 **빠른 거부(quick reject)**를 할 수 있게 해준다. 범위가 겹치지 않으면 mmap조차 할 필요 없다.

---

## 쓰기 전략: 단일 write

SSTable 쓰기에서 가장 흔한 실수는 엔트리마다 `channel.write()`를 호출하는 것이다. 시스템 콜이 count만큼 발생한다.

Kronos는 **파일 전체 크기를 미리 계산**해서 오프힙에 단일 버퍼를 잡고, 그 안에 Header / Data / Footer를 순서대로 채운 뒤 `FileChannel.write()`를 **한 번만** 호출한다.

```java
long fileSize = HEADER_BYTES + (long) count * ENTRY_BYTES + FOOTER_BYTES;

try (Arena arena = Arena.ofConfined()) {
    MemorySegment buf = arena.allocate(fileSize, ValueLayout.JAVA_LONG.byteAlignment());

    // 1. Header
    buf.set(ValueLayout.JAVA_INT,  0L, MAGIC);
    buf.set(ValueLayout.JAVA_INT,  4L, VERSION);
    buf.set(ValueLayout.JAVA_LONG, 8L, (long) count);

    // 2. Data Block — MemTable이 timestamp 순서로 넘겨준다
    long[] pos = {HEADER_BYTES};
    long[] minTs = {Long.MAX_VALUE};
    long[] maxTs = {Long.MIN_VALUE};
    memTable.forEachInOrder((ts, val) -> {
        buf.set(ValueLayout.JAVA_LONG,   pos[0],     ts);
        buf.set(ValueLayout.JAVA_DOUBLE, pos[0] + 8, val);
        pos[0] += ENTRY_BYTES;
        if (ts < minTs[0]) minTs[0] = ts;
        if (ts > maxTs[0]) maxTs[0] = ts;
    });

    // 3. CRC32 (data block만)
    CRC32 crc32 = new CRC32();
    crc32.update(buf.asSlice(HEADER_BYTES, (long) count * ENTRY_BYTES).asByteBuffer());
    int checksum = (int) crc32.getValue();

    // 4. Footer
    long fo = HEADER_BYTES + (long) count * ENTRY_BYTES;
    buf.set(ValueLayout.JAVA_LONG, fo,        minTs[0]);
    buf.set(ValueLayout.JAVA_LONG, fo + 8L,   maxTs[0]);
    buf.set(ValueLayout.JAVA_INT,  fo + 16L,  checksum);

    // 5. 단일 write + fsync
    try (FileChannel ch = FileChannel.open(sstPath, CREATE_NEW, WRITE)) {
        ByteBuffer nio = buf.asByteBuffer();
        while (nio.hasRemaining()) ch.write(nio);
        ch.force(true);
    }
}
```

`arena.close()`가 try-with-resources로 호출되면 오프힙 버퍼가 해제된다. `Arena.ofConfined()`가 확실한 수명을 보장하므로 `Cleaner`/GC가 개입할 여지가 없다 — 이 프로젝트의 핵심 원칙.

### 람다 안에서의 `long[] pos`

자바 람다는 외부 지역 변수를 *사실상 final*로만 참조할 수 있다. 그런데 `forEachInOrder`의 콜백은 매 호출마다 `pos`를 증가시켜야 한다. 1-요소 배열(`long[] pos = {HEADER_BYTES}`)은 이 제약을 우회하는 관용구다 — 배열 참조 자체는 final이고, 그 안의 값만 바꾼다. `minTs`/`maxTs`도 같은 이유로 배열이다.

### CRC32는 heap copy 없이

`crc32.update(buf.asSlice(...).asByteBuffer())` — `MemorySegment.asByteBuffer()`는 같은 오프힙 메모리를 가리키는 뷰를 반환한다. `CRC32.update(ByteBuffer)`는 이 뷰를 그대로 읽으므로 heap으로의 복사가 없다. 체크섬 계산이 추가 할당을 만들지 않는다는 건, Phase 2 압축에서도 같은 패턴을 재사용할 수 있다는 뜻이다.

---

## 읽기 전략: mmap + 이진 탐색

`SSTableReader`는 파일을 `FileChannel.map()`으로 mmap한다.

```java
Arena a = Arena.ofConfined();
try (FileChannel ch = FileChannel.open(path, READ)) {
    // FileChannel을 닫아도 mmap은 Arena 수명 동안 살아있다 (JEP 454)
    mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize, a);
}
```

**여기가 FFM API의 진짜 이점이 드러나는 지점이다.** `FileChannel.map(mode, pos, size, arena)`는 Java 22에 추가된 오버로드로, 매핑의 수명이 `Arena`에 묶인다. 그래서 위 코드처럼 `FileChannel.close()`를 먼저 해도 mmap은 살아있다 — 과거 `MappedByteBuffer` 시절에는 불가능했던 깔끔한 분리다.

헤더와 푸터는 mmap 위에서 바로 읽는다.

```java
int magic = mapped.get(ValueLayout.JAVA_INT, 0L);
if (magic != MAGIC) throw new IOException("Invalid SSTable magic");
entryCount = mapped.get(ValueLayout.JAVA_LONG, 8L);

long fo = HEADER_BYTES + entryCount * ENTRY_BYTES;
minTs = mapped.get(ValueLayout.JAVA_LONG, fo);
maxTs = mapped.get(ValueLayout.JAVA_LONG, fo + 8L);
```

### 포인트 조회는 이진 탐색

엔트리가 16B 고정이므로 인덱스 블록 없이도 O(log N) 접근이 가능하다.

```java
public OptionalDouble get(long timestamp) {
    long lo = 0, hi = entryCount - 1;
    while (lo <= hi) {
        long mid = (lo + hi) >>> 1;
        long ts = readTimestamp(mid);
        if (ts == timestamp) return OptionalDouble.of(readValue(mid));
        if (ts < timestamp) lo = mid + 1;
        else hi = mid - 1;
    }
    return OptionalDouble.empty();
}

private long readTimestamp(long idx) {
    return mapped.get(ValueLayout.JAVA_LONG, HEADER_BYTES + idx * ENTRY_BYTES);
}
```

`readTimestamp(mid)`는 mmap된 페이지에서 8바이트를 읽는 것 뿐이다 — 페이지 캐시가 따뜻하면 거의 무료.

### 범위 스캔은 quick reject → 이진 탐색 → 선형

```java
public void scan(long startTs, long endTs, BiConsumer<Long, Double> c) {
    if (maxTs < startTs || minTs > endTs) return;  // ← footer로 즉시 거부

    // startTs 이상인 첫 인덱스를 이진 탐색
    long lo = 0, hi = entryCount - 1, first = entryCount;
    while (lo <= hi) {
        long mid = (lo + hi) >>> 1;
        if (readTimestamp(mid) >= startTs) { first = mid; hi = mid - 1; }
        else lo = mid + 1;
    }
    for (long i = first; i < entryCount; i++) {
        long ts = readTimestamp(i);
        if (ts > endTs) break;
        c.accept(ts, readValue(i));
    }
}
```

Footer를 읽는 비용이 공짜에 가까우니, **쿼리 범위와 SSTable 범위가 겹치지 않으면 mmap 페이지 접근이 0회**로 끝난다. 여러 개의 SSTable이 쌓인 상황에서 의미 있는 최적화다.

---

## 테스트: 체크섬 변조와 매직 넘버 변조는 "다른 경로"다

처음에 테스트 한 개로 "파일이 손상되면 에러"를 퉁치려 했는데 실패했다. 매직 넘버 변조는 **헤더 단계**에서 걸리고, 체크섬 변조는 **CRC32 재계산 단계**에서 걸린다. 두 경로는 서로 커버하지 않는다. 그래서 두 개의 테스트를 따로 썼다.

```java
@Test
void reader_rejects_wrong_magic() throws IOException {
    // 정상 파일 생성 후 첫 4바이트 변조
    try (var raf = new RandomAccessFile(sst.toFile(), "rw")) {
        raf.writeInt(0xDEADBEEF);
    }
    assertThatThrownBy(() -> new SSTableReader(sst))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid SSTable magic");
}

@Test
void reader_rejects_corrupted_data_block() throws IOException {
    // 데이터 블록 한 바이트 flip
    try (var raf = new RandomAccessFile(sst.toFile(), "rw")) {
        raf.seek(HEADER_BYTES);
        int b = raf.read();
        raf.seek(HEADER_BYTES);
        raf.write(b ^ 0x01);
    }
    assertThatThrownBy(() -> new SSTableReader(sst))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("checksum mismatch");
}
```

체크섬 테스트에서는 magic은 그대로라서 헤더 검증을 통과하고, 그 뒤 CRC32 재계산이 원본과 불일치해서 거부된다. 테스트가 어떤 경로를 실제로 타고 있는지 그림으로 그려보면 놓치기 어렵다.

---

## 트레이드오프 요약

| 선택 | 대안 | 결정 이유 |
|------|------|-----------|
| 인덱스 블록 없음 | 별도 sparse index | 엔트리가 16B 고정 → 오프셋 계산으로 O(log N) 달성 |
| Footer에 min/max | 파일마다 메타 파일 별도 보관 | 한 번의 read로 fast reject 가능, 추가 파일 관리 불필요 |
| 데이터 블록 CRC32 | 엔트리별 CRC | 파일 단위 손상 탐지만 Phase 1에서 필요 |
| 전체 사전 버퍼링 | 스트리밍 write | count가 `int.MAX`를 넘지 않는 한 단일 write가 더 싸다 |

---

## Phase 1에서 이 구조가 충분한 이유

Phase 1 완료 기준은 "10만 건 write → flush → read 정합성"이다.

- 10만 건 × 16B = 1.6MB. 단일 버퍼 할당에 전혀 부담 없는 크기.
- 이진 탐색 log₂(100,000) ≈ 17 페이지 접근. mmap 경로에서 체감 불가.
- Footer의 min/max로 "읽기 쿼리가 해당 SSTable을 건드릴 필요 없는지" 판단 가능.

SSTable이 여러 개 쌓이는 상황이 되면 **병합 읽기(k-way merge)**가 필요해진다. 그게 다음 편의 주제다.

---

> 다음 글: **LSM-Tree 직접 구현기 (4) — 여러 SSTable을 하나처럼 읽기: k-way merge와 쓰기 처리량 측정**
