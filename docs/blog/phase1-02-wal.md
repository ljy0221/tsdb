---
title: "LSM-Tree 직접 구현기 (2) — WAL: FFM API로 write-ahead log 만들기"
tags: [Java, FFM API, TSDB, LSM-Tree, WAL, 오픈소스]
---

> Kronos Phase 1 두 번째 컴포넌트. MemTable은 인메모리 버퍼라 프로세스가 죽으면 날아간다.
> WAL(Write-Ahead Log)이 그 간격을 메운다.

---

## WAL이 필요한 이유

LSM-Tree의 쓰기 경로는 이렇다.

```
write(ts, val)
  → WAL append   ← 여기가 먼저
  → MemTable insert
  → (full이면) flush → SSTable
```

MemTable이 flush되지 않은 상태에서 프로세스가 죽으면 해당 데이터는 영구적으로 사라진다.
WAL은 MemTable보다 먼저 디스크에 기록해두는 "안전망"이다.

재시작 시 WAL을 읽어 MemTable을 재구성하면 데이터 유실이 없다.

---

## 설계 결정: 왜 FFM API인가

처음에 두 가지 옵션을 고려했다.

| | FFM API (MemorySegment) | NIO (ByteBuffer) |
|---|---|---|
| write buffer 할당 | `Arena.ofConfined()` | `ByteBuffer.allocateDirect()` |
| 해제 방식 | `arena.close()` 명시적 | `Cleaner`(GC 연동) |
| 코드베이스 일관성 | MemTable과 동일 추상화 | 이질적 |
| zero-copy | 없음 (FileChannel 경로 동일) | 없음 |

**처음에 내가 틀렸다**: "FFM이 zero-copy라서 빠르다"고 생각했다.
`MemorySegment.asByteBuffer()` 결과를 `FileChannel.write()`에 넘기면
내부 경로가 `ByteBuffer.allocateDirect()`와 동일하다 — zero-copy 이점이 없다.

그러나 FFM API를 선택한 실제 이유는 두 가지다.

**코드베이스 일관성**: MemTable 데이터가 이미 `MemorySegment`에 있다.
WAL이 `ByteBuffer`를 쓰면 오프힙 메모리 모델이 두 개로 나뉜다.
버그 발생 시 어느 추상화의 문제인지 좁히기 어려워진다.

**명시적 생명주기**: `ByteBuffer.allocateDirect()`는 내부적으로 `Cleaner`를 통해 해제된다.
`Cleaner`는 GC가 간접적으로 관여하는 메커니즘이다.
`Arena.ofConfined()`의 `close()`는 GC 없이 즉시 해제 — 이 프로젝트의 핵심 원칙에 부합한다.

---

## 엔트리 포맷

Phase 1 WAL 포맷은 의도적으로 단순하다.

```
[timestamp: 8 bytes][value: 8 bytes]
= 16 bytes 고정
```

sequence 번호, type 필드, CRC32 체크섬을 넣지 않았다.
Phase 1 완료 기준("10만 건 write → flush → read 정합성")에서 필요하지 않다.
Phase 2 이상에서 그룹 커밋, 체크섬 검증이 필요해지면 그때 추가한다.

---

## WalWriter 핵심 구조

```java
public final class WalWriter implements Closeable {

    static final long ENTRY_BYTES = 16L;

    private final Arena arena;
    private final MemorySegment writeBuf;   // 16B, WAL 수명 동안 재사용
    private final ByteBuffer nioView;       // asByteBuffer() 캐시
    private final FileChannel channel;

    public WalWriter(Path walPath) throws IOException {
        this.arena    = Arena.ofConfined();
        this.writeBuf = arena.allocate(ENTRY_BYTES, ValueLayout.JAVA_LONG.byteAlignment());
        this.nioView  = writeBuf.asByteBuffer();  // 한 번만 생성
        this.channel  = FileChannel.open(walPath,
            CREATE, APPEND, DSYNC);
    }
}
```

`nioView`를 생성자에서 한 번만 캐싱하는 것이 포인트다.
`append()` 호출마다 `asByteBuffer()`를 부르면 호출 횟수만큼 view 객체가 생긴다.
write buffer가 16바이트 고정이고 WAL 수명 내내 하나뿐이므로 캐싱이 간단하다.

### append(): 재사용 패턴

```java
public void append(long timestamp, double value) throws IOException {
    writeBuf.set(ValueLayout.JAVA_LONG,   0, timestamp);
    writeBuf.set(ValueLayout.JAVA_DOUBLE, 8, value);
    nioView.rewind();          // ByteBuffer position을 0으로 되돌림
    channel.write(nioView);    // 16바이트 동기 기록
}
```

매 `append()`마다 `rewind()`를 부르는 이유:
`channel.write(nioView)` 후 ByteBuffer의 position이 limit(16)으로 이동한다.
다음 호출에서 position이 limit이면 아무것도 쓰지 않는다.
`rewind()`로 position을 0으로 되돌려야 다음 엔트리를 올바르게 기록한다.

### close(): 순서가 중요하다

```java
@Override
public void close() throws IOException {
    try {
        channel.close();   // OS 버퍼 플러시 + 파일 닫기
    } finally {
        arena.close();     // 오프힙 write buffer 해제
    }
}
```

`channel`을 먼저 닫는다.
`channel.close()`가 실패해도 `finally`에서 `arena.close()`는 반드시 실행된다.
오프힙 메모리 누수를 막기 위한 방어 코드다.

---

## WalReader: 크래시 복구

재시작 시 WAL 파일을 읽어 MemTable을 재구성하는 컴포넌트다.

```java
public static List<TimestampValuePair> readAll(Path walPath) throws IOException {
    try (Arena arena = Arena.ofConfined();
         FileChannel channel = FileChannel.open(walPath, READ)) {

        long fileSize = channel.size();
        if (fileSize == 0) return List.of();

        // 파일 전체를 오프힙 MemorySegment에 매핑
        MemorySegment mapped = channel.map(
            FileChannel.MapMode.READ_ONLY, 0, fileSize, arena
        );

        List<TimestampValuePair> entries = new ArrayList<>();
        long offset = 0;
        while (offset + ENTRY_BYTES <= fileSize) {
            long   ts  = mapped.get(ValueLayout.JAVA_LONG,   offset);
            double val = mapped.get(ValueLayout.JAVA_DOUBLE, offset + 8);
            entries.add(new TimestampValuePair(ts, val));
            offset += ENTRY_BYTES;
        }
        return entries;
    }
}
```

`FileChannel.map(mode, pos, size, arena)` — Java 22에 추가된 FFM API 오버로드다.
매핑된 `MemorySegment`의 수명이 `arena`에 묶인다.
try-with-resources로 `arena`가 닫히면 매핑도 함께 해제된다.

seek 없이 오프셋 직접 계산으로 순회하므로 read syscall이 없다.
OS 페이지 캐시를 바로 참조한다 — 파일이 크더라도 효율적이다.

### 불완전 엔트리 처리

프로세스가 16바이트 기록 도중 죽으면 파일 끝에 불완전한 데이터가 남는다.

```
while (offset + ENTRY_BYTES <= fileSize) { ... }
```

이 조건 하나로 해결된다.
`offset + 16 > fileSize`인 잔여 바이트는 순회에서 자동으로 제외된다.
예외 없이 조용히 무시 — 이것이 LSM-Tree WAL의 표준적인 처리 방식이다.

---

## 테스트: 내결함성 검증

```java
@Test
void readAll_ignores_incomplete_trailing_entry() throws IOException {
    // 완전한 엔트리 1개 기록
    try (var writer = new WalWriter(wal)) {
        writer.append(1_000L, 1.0);
    }
    // 7바이트 garbage를 이어 붙여 크래시 상황 시뮬레이션
    Files.write(wal, new byte[7], StandardOpenOption.APPEND);

    assertThat(Files.size(wal)).isEqualTo(23L); // 16 + 7

    var entries = WalReader.readAll(wal);
    assertThat(entries).hasSize(1);  // 완전한 1개만 반환
}
```

---

## 정리

| 컴포넌트 | 역할 | 핵심 선택 |
|---|---|---|
| `WalWriter` | append-only 동기 기록 | 16B write buffer 1회 할당 후 재사용 |
| `WalReader` | 크래시 복구 | `FileChannel.map()` + 오프힙 매핑 |

WAL이 있어야 MemTable이 "내구성 있는" 쓰기 버퍼가 된다.
이 두 컴포넌트가 합쳐져야 LSM-Tree 쓰기 경로의 첫 번째 절반이 완성된다.

다음은 SSTable flush — MemTable의 오프힙 데이터를 파일로 직렬화하는 과정이다.

---

> 다음 글: **LSM-Tree 직접 구현기 (3) — SSTable: 오프힙 데이터를 파일로 flush하기**
