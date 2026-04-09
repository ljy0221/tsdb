---
name: lsm-tree
description: LSM-Tree 관련 작업 시 자동 로드. MemTable, SSTable, WAL, Compaction, flush, bloom filter 구현 패턴과 Kronos 파일 포맷 스펙을 포함한다.
---

# LSM-Tree Skill

## 발동 조건
LSM, MemTable, SSTable, WAL, Compaction, flush, bloom filter 관련 작업 시 자동 로드.

---

## Kronos LSM-Tree 구조

```
Write Path:
  write(key, value)
    → WAL append (durability)
    → MemTable insert (ConcurrentSkipListMap)
    → if MemTable >= threshold: flush to SSTable

Read Path:
  read(key)
    → MemTable 먼저 조회
    → SSTable 역순 조회 (newest first)
    → Bloom Filter로 불필요한 I/O 스킵
```

---

## 구현 가이드

### MemTable
```java
// ConcurrentSkipListMap으로 빠르게 시작 (직접 구현은 Phase 이후)
public class MemTable {
    private final ConcurrentSkipListMap<Long, Double> data = new ConcurrentSkipListMap<>();
    private final AtomicLong sizeBytes = new AtomicLong(0);
    static final long THRESHOLD = 4 * 1024 * 1024; // 4MB

    public void put(long timestamp, double value) {
        data.put(timestamp, value);
        sizeBytes.addAndGet(16); // timestamp(8) + value(8)
    }

    public boolean isFull() {
        return sizeBytes.get() >= THRESHOLD;
    }
}
```

### SSTable 포맷 (단순 버전 먼저)
```
[Header]
  magic: 4 bytes (0x4B524F4E = "KRON")
  version: 2 bytes
  count: 8 bytes (entry 수)

[Data Block] (count개)
  timestamp: 8 bytes (long)
  value: 8 bytes (double)

[Footer]
  min_timestamp: 8 bytes
  max_timestamp: 8 bytes
  checksum: 4 bytes (CRC32)
```

### WAL 포맷
```
[Entry]
  sequence: 8 bytes (단조 증가)
  type: 1 byte (PUT=1, DELETE=2)
  timestamp: 8 bytes
  value: 8 bytes
  crc32: 4 bytes
```

---

## Compaction 전략 (Phase 1: 단순 버전)

Phase 1에서는 Size-Tiered Compaction의 단순화 버전만 구현:
- SSTable이 4개 이상 쌓이면 전체 merge
- 중복 timestamp는 newer 값으로 override
- 고급 전략(Leveled, Tiered 분리)은 Phase 이후

---

## 시계열 특화 고려사항

일반 KV LSM-Tree와 다른 점:
- **키는 항상 timestamp (long)**: 단조 증가 패턴 활용 가능
- **최신 데이터 접근 빈도 높음**: MemTable 히트율이 중요
- **범위 쿼리 핵심**: `[startTs, endTs]` 범위 읽기가 기본 연산
- **압축 효율**: Delta-of-Delta가 timestamp에 극도로 효과적

---

## 파일 명명 규칙
```
wal-{sequence}.log          # WAL 파일
sst-{level}-{sequence}.sst  # SSTable
```

---

## 금지 패턴
- **MemTable에 오래된 데이터 누적**: 4MB 초과 시 반드시 flush
- **WAL 없이 MemTable만 사용**: 프로세스 크래시 시 데이터 유실
- **SSTable 파일 직접 수정**: 불변(immutable)이 SSTable의 핵심 속성