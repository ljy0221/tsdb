# FFM API (Project Panama) Skill

## 발동 조건
MemorySegment, Arena, MemoryLayout, FFM, 오프힙, off-heap, VarHandle 관련 작업 시 자동 로드.

---

## 핵심 원칙

### Arena 선택 기준
| Arena 종류 | 사용 시점 | 주의 |
|---|---|---|
| `Arena.ofConfined()` | **기본값**. 단일 스레드 소유. | 생성 스레드에서만 접근 가능 |
| `Arena.ofShared()` | 멀티스레드 공유 필요 시 | 성능 오버헤드 있음. 필요할 때만 |
| `Arena.ofAuto()` | **사용 금지** | GC 연동 — Zero-GC 목표에 반함 |
| `Arena.global()` | 프로세스 수명과 같은 전역 버퍼 | close 불가. 신중하게 사용 |

### 필수 패턴: try-with-resources
```java
// ✅ 올바른 패턴
try (Arena arena = Arena.ofConfined()) {
    MemorySegment seg = arena.allocate(ValueLayout.JAVA_LONG, 1024);
    // ... 작업
} // 자동 해제 보장

// ❌ 금지: 수동 close에만 의존
Arena arena = Arena.ofConfined();
// close() 누락 시 오프힙 누수
```

### MemoryLayout 설계 원칙
```java
// 시계열 데이터 포인트 레이아웃 예시
StructLayout POINT_LAYOUT = MemoryLayout.structLayout(
    ValueLayout.JAVA_LONG.withName("timestamp"),   // 8 bytes
    ValueLayout.JAVA_DOUBLE.withName("value"),      // 8 bytes
    MemoryLayout.paddingLayout(0)                   // 정렬 필요 시
);

// VarHandle로 타입 안전 접근
VarHandle TIMESTAMP_HANDLE = POINT_LAYOUT.varHandle(
    MemoryLayout.PathElement.groupElement("timestamp")
);
```

### 대용량 배열 패턴
```java
// long 배열 N개 오프힙 할당
long count = 1_000_000L;
MemorySegment seg = arena.allocate(
    ValueLayout.JAVA_LONG.byteSize() * count,
    ValueLayout.JAVA_LONG.byteAlignment()
);

// 접근: byteOffset 직접 계산
long get(MemorySegment seg, long index) {
    return seg.get(ValueLayout.JAVA_LONG, index * Long.BYTES);
}

void set(MemorySegment seg, long index, long value) {
    seg.set(ValueLayout.JAVA_LONG, index * Long.BYTES, value);
}
```

---

## JMH 벤치마크 템플릿

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class OffHeapBench {

    private static final int SIZE = 1_000_000;
    private long[] heapArray;
    private MemorySegment offHeapSeg;
    private Arena arena;

    @Setup
    public void setup() {
        heapArray = new long[SIZE];
        arena = Arena.ofConfined();
        offHeapSeg = arena.allocate(
            ValueLayout.JAVA_LONG.byteSize() * SIZE,
            ValueLayout.JAVA_LONG.byteAlignment()
        );
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void heapWrite(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) heapArray[i] = i;
    }

    @Benchmark
    public void offHeapWrite(Blackhole bh) {
        for (int i = 0; i < SIZE; i++) {
            offHeapSeg.set(ValueLayout.JAVA_LONG, (long) i * Long.BYTES, i);
        }
    }
}
```

---

## 자주 하는 실수

1. **index를 byteOffset으로 착각**: `seg.get(JAVA_LONG, index)` ← 틀림. `index * Long.BYTES` 필요
2. **Arena 닫힌 후 접근**: `arena.close()` 후 segment에 접근하면 `IllegalStateException`
3. **ofAuto 사용**: GC가 닫아주므로 편하지만 Zero-GC 목표 위반

---

## 참고
- JEP 454: https://openjdk.org/jeps/454
- MemorySegment Javadoc: Java 22 공식 API 문서
