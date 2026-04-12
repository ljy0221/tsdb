# ADR-003: WAL 구현 방식 — FFM API (MemorySegment + FileChannel)

**상태**: 결정됨
**날짜**: 2026-04-12

---

## 결정

WAL(Write-Ahead Log)의 쓰기 버퍼는 `Arena.ofConfined()`로 할당한 `MemorySegment`를 사용한다.
FileChannel 기록 시 `segment.asByteBuffer()`를 통해 NIO FileChannel에 전달한다.
write buffer는 WAL 인스턴스 생성 시 **단 1회** 사전 할당하고 엔트리마다 재사용한다.

```
WAL 내부 구조:
  arena: Arena.ofConfined()        ← WAL 인스턴스 필드, close() 시 해제
  writeBuf: MemorySegment(16B)     ← [ts(8) | value(8)], 1회 할당 후 재사용
  channel: FileChannel             ← append-only, O_SYNC or fsync 제어
```

---

## 논쟁 요약 (tech-decision-debate)

### 에이전트 A (FFM API 제안)의 핵심 근거

1. **코드베이스 일관성**: MemTable 데이터가 `MemorySegment`(오프힙)에 있으므로,
   WAL도 동일 API를 사용해야 `MemTable → WAL` 경로에서 이질적 추상화가 없다.
2. **GC 관여 없는 생명주기**: `ByteBuffer.allocateDirect()`의 해제는 `Cleaner`(GC 연동)에
   의존한다. `Arena.ofConfined()`는 명시적 `close()`로 해제 — 이 프로젝트의 핵심 원칙과 일치한다.
3. **Phase 0 증거**: FFM API 오버헤드 ≈ 0 (인힙 대비 성능 차이 없음). WAL 버퍼 할당에
   FFM을 사용해도 성능 불이익이 없다.

### 에이전트 B (NIO 제안)의 유효한 반론

1. **"zero-copy" 과장**: `MemorySegment.asByteBuffer()`는 결국 `ByteBuffer`를 반환하고
   `FileChannel.write()` 내부 경로는 동일하다. zero-copy 이점은 실질적으로 없다.
2. **`ByteBuffer.allocateDirect()` 재사용 패턴**: WAL 레벨에서 write buffer를 1회 할당해
   재사용하면 NIO로도 GC 이슈가 없다.

### 판정

| B의 반론 | 강도 | 결과 |
|---|---|---|
| zero-copy 과장 | 강함 | A의 근거 1(zero-copy)을 철회. 채택 근거를 코드베이스 일관성으로 교체 |
| allocateDirect 재사용 | 중간 | Cleaner 의존 생명주기 문제는 반박되지 않음 |
| 코드 복잡도 | 약함 | Arena를 인스턴스 필드로 두는 패턴은 SkipListMemTable(ADR-002)과 동일 |

B의 반론 중 타당한 부분("매 엔트리 할당 금지")은 구현 제약으로 흡수.
핵심 결정은 옵션 B(FFM API) 채택.

---

## 이유

### 왜 FFM API를 선택하는가

**코드베이스 내 단일 추상화 레이어**

MemTable(ADR-002)이 `Arena.ofConfined()` + `MemorySegment`를 사용한다.
WAL이 `ByteBuffer`를 사용하면 `MemTable → WAL` 경로에서 개발자가
두 가지 오프힙 메모리 모델을 동시에 관리해야 한다.
FFM API로 통일하면 오프힙 메모리 관련 버그의 원인이 단일 추상화로 좁혀진다.

**명시적 생명주기 관리**

이 프로젝트의 핵심 명제는 "GC 관여 없는 메모리 관리".
`ByteBuffer.allocateDirect()`는 내부적으로 `Cleaner`를 통해 해제되며
이는 GC가 간접적으로 관여함을 의미한다.
`Arena.ofConfined()`의 `close()`는 순수 명시적 해제 — 프로젝트 원칙에 정확히 부합한다.

**성능 동등성 확인됨**

Phase 0 벤치마크: FFM 오버헤드 ≈ 0. 선택 비용이 없다.

### 왜 write buffer를 1회만 할당하는가

WAL은 append-only이고 엔트리 구조가 고정(16바이트)이다.
write buffer를 매 append마다 새로 할당하면 오프힙이라도 `arena.allocate()` 호출 오버헤드가 쌓인다.
WAL 인스턴스 생성 시 단 1개의 16바이트 버퍼를 할당하고 `MemorySegment.set()`으로 덮어쓰면
추가 할당 비용이 전혀 없다.

---

## 알려진 트레이드오프

| 항목 | 비용 | 완화 |
|------|------|------|
| `asByteBuffer()` 호출 오버헤드 | FileChannel.write() 전 매번 호출 | 16바이트 고정 버퍼 1개 → 오버헤드 무시 가능 |
| NIO 대비 코드 친숙도 | FFM API를 모르는 기여자에게 낯설 수 있음 | 이미 MemTable에 동일 패턴 존재. CLAUDE.md 컨벤션으로 문서화됨 |
| `asByteBuffer()` view는 read-only일 수 있음 | Arena 할당 세그먼트는 readOnly=false — 문제없음 | - |

---

## 구현 스케치

```java
public final class WalWriter implements Closeable {

    private static final long ENTRY_BYTES = 16L;  // ts(8) + value(8)

    private final Arena arena;
    private final MemorySegment writeBuf;  // 16바이트, WAL 수명 동안 재사용
    private final ByteBuffer nioView;      // asByteBuffer() 캐시
    private final FileChannel channel;

    public WalWriter(Path walPath) throws IOException {
        this.arena = Arena.ofConfined();
        this.writeBuf = arena.allocate(ENTRY_BYTES, ValueLayout.JAVA_LONG.byteAlignment());
        this.nioView = writeBuf.asByteBuffer();
        this.channel = FileChannel.open(walPath,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC);
    }

    /** WAL에 단일 엔트리를 append한다. 단일 스레드 전용. */
    public void append(long timestamp, double value) throws IOException {
        writeBuf.set(ValueLayout.JAVA_LONG,   0, timestamp);
        writeBuf.set(ValueLayout.JAVA_DOUBLE, 8, value);
        nioView.rewind();
        channel.write(nioView);
    }

    @Override
    public void close() throws IOException {
        channel.close();
        arena.close();  // 오프힙 write buffer 명시적 해제
    }
}
```

---

## Arena 선택: `Arena.ofConfined()`

WAL 쓰기는 단일 스레드 경로다(ADR-002: MemTable put()이 단일 스레드이고,
WAL.append()는 MemTable.put() 직전에 같은 스레드에서 호출된다).
ADR-001의 원칙대로 `Arena.ofConfined()`를 유지한다.

---

## 재검토 시점

- **Phase 2 이후 멀티스레드 쓰기 도입 시**: `Arena.ofShared()` 또는 스레드별 Arena 전환 검토
- **WAL 그룹 커밋 최적화 필요 시**: write buffer를 단일 엔트리 → 배치 크기로 확장 검토
- **JMH에서 WAL append가 병목으로 확인될 시**: `O_DIRECT` + `MemorySegment` 조합 재검토
