# Kronos 아키텍처 상세 (Mermaid 다이어그램)

> 이 문서는 Kronos의 계층별 아키텍처와 데이터 흐름을 Mermaid 다이어그램으로 설명합니다.

---

## 🏗️ 전체 시스템 아키텍처

```mermaid
graph TB
    API["🌐 API Layer<br/>(HTTP PUT/GET + Prometheus Remote Write)"]
    
    subgraph Request["요청 분기"]
        Write["✍️ Write Path<br/>(timestamp + datapoint)"]
        Read["📖 Read Path<br/>(time-range query)"]
    end
    
    API --> Write
    API --> Read
    
    subgraph LSM["LSM-Tree Engine Core"]
        MemTable["📦 MemTable<br/>(In-Memory)<br/>ConcurrentSkipListMap<br/>정렬된 insertion order"]
        WAL["📝 WAL<br/>(Disk)<br/>DSYNC flush<br/>내구성 보장"]
        SSTable["💾 SSTable Files<br/>(Immutable)<br/>Level 0, Level 1, ...<br/>정렬된 범위"]
        MergingIterator["🔀 MergingIterator<br/>(Multi-file merge read)"]
        Compactor["🔧 Compactor<br/>(Async)<br/>Merge"]
        
        Write --> MemTable
        Write --> WAL
        MemTable --> WAL
        WAL --> SSTable
        SSTable --> Compactor
        SSTable --> MergingIterator
        MemTable --> MergingIterator
        Read --> MemTable
        Read --> MergingIterator
    end
    
    Write --> LSM
    Read --> LSM
    
    Data["💾 Data<br/>(Bytes)"]
    Metadata["📋 Metadata<br/>(Index, Stats)"]
    
    MergingIterator --> Data
    MergingIterator --> Metadata
    
    OffHeap["🧠 Off-Heap Memory Layer<br/>(FFM API + Arena)<br/>MemorySegment allocation<br/>Arena.ofConfined()"]
    FileSystem["📂 File System<br/>(Linux/WSL2)<br/>mmap • fsync • DSYNC<br/>page cache"]
    
    Data --> OffHeap
    Metadata --> OffHeap
    OffHeap --> FileSystem
    
    style API fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style Request fill:#f0f0f0,stroke:#666
    style LSM fill:#fff5e6,stroke:#ff7f0e,stroke-width:2px
    style MemTable fill:#e6f0ff,stroke:#1f77b4
    style WAL fill:#f0e6ff,stroke:#9467bd
    style SSTable fill:#ffe6e6,stroke:#d62728
    style MergingIterator fill:#fffae6,stroke:#bcbd22
    style Compactor fill:#e6ffe6,stroke:#2ca02c
    style OffHeap fill:#e6fdff,stroke:#17becf,stroke-width:2px
    style FileSystem fill:#f0f0f0,stroke:#7f7f7f,stroke-width:2px
```

---

## 📊 데이터 구조별 메모리 레이아웃

### MemTable (In-Memory)

```mermaid
graph TB
    subgraph Heap["Java Heap"]
        ConcurrentSkipListMap["ConcurrentSkipListMap<br/>&lt;Long, Double&gt;<br/>(메타데이터 + 참조만)"]
    end
    
    ConcurrentSkipListMap -->|keys/values 참조| OffHeapArray
    
    subgraph OffHeap["Off-Heap Memory<br/>(FFM API Arena-allocated)"]
        OffHeapArray["MemorySegment Array<br/>Entry 1: TS₁ 8B | Val₁ 8B<br/>Entry 2: TS₂ 8B | Val₂ 8B<br/>Entry N: TSₙ 8B | Valₙ 8B"]
    end
    
    OffHeapArray -->|Arena.close()| GCFree["✅ GC 영향 제로<br/>메모리 즉시 해제"]
    
    style Heap fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style OffHeap fill:#e6fdff,stroke:#17becf,stroke-width:2px
    style ConcurrentSkipListMap fill:#fff0e6,stroke:#ff7f0e
    style OffHeapArray fill:#e6ffe6,stroke:#2ca02c
    style GCFree fill:#ffe6e6,stroke:#d62728
```

**메모리 정보**:
- 크기: N × 16 bytes (N = MemTable entries)
- 수명: MemTable 생성 ~ flush 완료 후 close()
- GC Impact: Zero (Arena.close() → 오프힙 메모리 해제)

---

## 🔄 쓰기 경로 (Write Flow)

```mermaid
graph TD
    A["PUT /metrics?ts=1234567890&value=42.5"]
    B["🌐 HTTP Handler<br/>Parse TS + Value"]
    C["⚙️ LsmEngine.put<br/>Lock MemTable write mutex"]
    D["📦 MemTable.put<br/>ConcurrentSkipListMap<br/>~100 ns"]
    E["📝 WAL.append<br/>fsync DSYNC<br/>~2.57 ms"]
    F["🔍 MemTable Size Check"]
    G["❌ if size > MAX_SIZE<br/>freeze & flush"]
    H["✅ Return OK"]
    I["💾 SSTableWriter.write<br/>Sorted data to disk<br/>fsync durability"]
    J["🔧 Compactor async<br/>Merge N files"]
    
    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F -->|true| G
    F -->|false| H
    G --> I
    I --> J
    J --> H
    
    style A fill:#fff5e6,stroke:#ff7f0e
    style B fill:#e6f0ff,stroke:#1f77b4
    style C fill:#e6f0ff,stroke:#1f77b4
    style D fill:#fffae6,stroke:#bcbd22
    style E fill:#f0e6ff,stroke:#9467bd
    style F fill:#fff0e6,stroke:#ff7f0e
    style G fill:#ffe6e6,stroke:#d62728
    style H fill:#e6ffe6,stroke:#2ca02c
    style I fill:#ffe6e6,stroke:#d62728
    style J fill:#e6ffe6,stroke:#2ca02c
```

| 구간 | 비용 | 주요 병목 |
|------|------|----------|
| MemTable.put | ~100 ns | Java heap operation |
| WAL fsync | ~2.57 ms | 물리 I/O (지배적) |
| SSTable write | ~30.6ms/100k | 배치 처리로 amortize |

---

## 🔍 읽기 경로 (Read Flow)

### Point Query

```mermaid
graph TD
    A["GET /metric?ts=1234567890"]
    B["🌐 HTTP Handler<br/>Parse TS"]
    C["⚙️ LsmEngine.get"]
    D["📦 MemTable.get<br/>Binary Search O(log n)"]
    E["💾 SSTable.get<br/>Binary Search O(log n)"]
    F["✅ Return value"]
    
    A --> B
    B --> C
    C --> D
    D --> F
    C --> E
    E --> F
    
    style A fill:#ffe6e6,stroke:#d62728
    style B fill:#e6f0ff,stroke:#1f77b4
    style D fill:#fffae6,stroke:#bcbd22
    style E fill:#fffae6,stroke:#bcbd22
    style F fill:#e6ffe6,stroke:#2ca02c
```

**성능**: MemTable hit: ~100 ns | SSTable hit: ~1-10 μs | Cold start: +page fault

### Range Query (Scan)

```mermaid
graph TD
    A["GET /metrics?start=ts1&end=ts2"]
    B["⚙️ LsmEngine.scan"]
    C["📸 LsmReadView<br/>Snapshot"]
    D["🏗️ Build Iterator Heap"]
    E["MemTable.rangeIterator"]
    F["SSTableReader.scan"]
    G["🔀 MergingIterator<br/>Min heap"]
    H["Phase 1: O(n)<br/>전체 스캔"]
    I["Phase 2: O(log n)<br/>sparse index"]
    J["Stream Results"]
    
    A --> B --> C
    C --> D
    D --> E
    D --> F
    E --> G
    F --> G
    G --> H
    G --> I
    H --> J
    I --> J
    
    style A fill:#ffe6e6,stroke:#d62728
    style C fill:#fff5e6,stroke:#ff7f0e
    style G fill:#fffae6,stroke:#bcbd22,stroke-width:2px
    style H fill:#f0e6ff,stroke:#9467bd
    style I fill:#e6ffe6,stroke:#2ca02c,stroke-width:2px
    style J fill:#e6ffe6,stroke:#2ca02c
```

| 메트릭 | Phase 1 | Phase 2 목표 | 개선율 |
|--------|---------|------------|--------|
| p99 (1% 선택성) | 21.1 ms | < 5 ms | 4-5× |

---

## 💾 Off-Heap 메모리 관리

### Arena 생명주기

```mermaid
graph TB
    A["try-with-resources<br/>Arena.ofConfined()"]
    B["자동 생성"]
    C["데이터 쓰기<br/>put/flush"]
    D["segment API 호출"]
    E["flush 완료"]
    F["블록 종료"]
    G["arena.close()<br/>자동 호출"]
    H["오프힙 메모리<br/>즉시 해제"]
    I["✅ GC 영향 제로"]
    
    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    
    style A fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style B fill:#e6ffe6,stroke:#2ca02c
    style C fill:#fffae6,stroke:#bcbd22
    style G fill:#f0e6ff,stroke:#9467bd,stroke-width:2px
    style H fill:#e6ffe6,stroke:#2ca02c
    style I fill:#e6ffe6,stroke:#2ca02c
```

---

## 🎯 Phase별 아키텍처 진화

```mermaid
graph LR
    Phase0["Phase 0<br/>OffHeapLongArray<br/>FFM API 검증"]
    Phase1["Phase 1<br/>LSM-Tree 완성<br/>9.47M ops/s"]
    Phase2["Phase 2<br/>Sparse Index<br/>5× 개선"]
    Phase3["Phase 3<br/>Full TSDB<br/>0.1.0 Release"]
    
    Phase0 -->|✅ 완료| Phase1
    Phase1 -->|✅ 완료| Phase2
    Phase2 -->|🚀 진행 중| Phase3
    
    style Phase0 fill:#e6ffe6,stroke:#2ca02c,stroke-width:2px
    style Phase1 fill:#e6ffe6,stroke:#2ca02c,stroke-width:2px
    style Phase2 fill:#fff5e6,stroke:#ff7f0e,stroke-width:2px
    style Phase3 fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
```

---

## 🔐 동시성 모델

### Write Path (Single-Threaded)

```mermaid
graph TB
    subgraph WriteThread["Thread 1 - Write"]
        A["HTTP Handler"]
        B["LsmEngine.put"]
        C["MemTable.put<br/>thread-safe"]
    end
    
    subgraph CompactThread["Thread N - Compaction"]
        D["Async task"]
        E["Merge SSTables"]
        F["No contention"]
    end
    
    A --> B --> C
    D --> E --> F
    
    style WriteThread fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style CompactThread fill:#e6ffe6,stroke:#2ca02c,stroke-width:2px
    style C fill:#fffae6,stroke:#bcbd22,stroke-width:2px
    style F fill:#fffae6,stroke:#bcbd22,stroke-width:2px
```

**이유**: TSDB는 write-heavy single-sequence 워크로드. 멀티스레드 write는 불필요.

### Read Path (Concurrent)

모든 읽기 쿼리가 동시에 실행 가능하며, 쓰기 작업과도 경합 없음.

---

## 📈 확장성 고려사항

### Horizontal Scaling (향후)

```mermaid
graph TB
    B["Query Broker<br/>Aggregate"]
    
    K1["Kronos 1<br/>shard=A"]
    K2["Kronos 2<br/>shard=B"]
    K3["Kronos 3<br/>shard=C"]
    
    K1 --> B
    K2 --> B
    K3 --> B
    
    style K1 fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style K2 fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style K3 fill:#e6f0ff,stroke:#1f77b4,stroke-width:2px
    style B fill:#fff5e6,stroke:#ff7f0e,stroke-width:2px
```

현재: 단일 engine 성능 최적화 우선

### Vertical Scaling

- **CPU**: Single write thread, reads/compaction은 병렬
- **Memory**: 오프힙 → GC 없음 → 메모리 충분하면 무한정 확장
- **Disk**: I/O 병목 가능 (compaction fsync) → NVMe 또는 sharding으로 해결
