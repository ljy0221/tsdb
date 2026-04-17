# Phase 2 Scan Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a JMH range-scan benchmark (3 axes: selectivity × distribution × fileCount) that measures `LsmReadView.scanCount` p99 latency and applies the spec's exit criteria to pick the next ADR (internal sparse index vs Gorilla compression).

**Architecture:** Add a raw-primitive counting path (`scanCount`) on `LsmReadView` to eliminate `BiConsumer + Double-boxing` overhead from measurements. Pair it with a `ScanDistribution` enum that builds query ranges from `(dataMin, dataMax, selectivity)`. The JMH bench wires them together with `@Setup(Level.Invocation)` range rotation to defeat WSL2 page-cache warmth bias. Fixture builds either 1 SSTable (post-compaction) or 4 non-overlapping SSTables (pre-compaction).

**Tech Stack:** Java 22, FFM API (existing), JMH 1.37 (existing via `me.champeau.jmh` plugin), JUnit 5 + AssertJ, Gradle Kotlin DSL.

**Crucial observation (caught during planning):** `SSTableReader.scan()` (single-file path) **already uses binary search** ([`SSTableReader.java:123-132`](../../../kronos-core/src/main/java/io/kronos/lsm/SSTableReader.java#L123-L132)). The O(n) behavior lives in `LsmReadView.scan()` which takes `r.iterator()` to feed `MergingIterator` — the iterator walks the entire file regardless of range. The bench must exercise the **MergingIterator path** (the actual production code path) to validate the spec's hypothesis. `scanCount` must mirror `scan()` exactly, not call `SSTableReader.scan` directly.

---

## Task 0: Context lock — verify working tree, no dependencies to add

**Files:** None (verification only)

- [ ] **Step 1: Verify we're on main at the spec commit**

Run: `git log --oneline -3`
Expected: Top line should show `docs(phase2): scan bench 설계 spec — 측정 기반 ADR 선행 결정`

- [ ] **Step 2: Verify test baseline passes**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 3: Verify JMH plugin is wired**

Run: `./gradlew :kronos-core:tasks --group=jmh`
Expected: Lists `jmh` task. If missing, stop and fix Gradle config before continuing.

---

## Task 1: `ScanDistribution` enum + tests (TDD)

**Files:**
- Create: `kronos-core/src/jmh/java/io/kronos/bench/ScanDistribution.java`
- Create: `kronos-core/src/test/java/io/kronos/bench/ScanDistributionTest.java`

### Why JMH source set, not main

JMH benchmarks live in `src/jmh/java/`. `ScanDistribution` is a bench-only helper — placing it in `main` would pollute the public API of `kronos-core`. Tests still go in `src/test/java/` under the same package (JUnit can reach the JMH source set via Gradle's default jmh test configuration, or we duplicate the class into `src/test` only if the configuration doesn't allow it — check in Step 4).

- [ ] **Step 1: Write failing tests**

Create `kronos-core/src/test/java/io/kronos/bench/ScanDistributionTest.java`:

```java
package io.kronos.bench;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanDistributionTest {

    @Test
    void recent_returns_tail_window_equal_to_selectivity() {
        long[] r = ScanDistribution.RECENT.range(
                /*dataMin*/ 1_000_000L,
                /*dataMax*/ 2_000_000L,
                /*selectivity*/ 0.01,
                new Random(42));

        // tail 1% of [1_000_000, 2_000_000] → length 10_000, ends at dataMax
        assertThat(r[1]).isEqualTo(2_000_000L);
        assertThat(r[1] - r[0]).isEqualTo(10_000L);
    }

    @Test
    void recent_selectivity_one_covers_whole_range() {
        long[] r = ScanDistribution.RECENT.range(100L, 200L, 1.0, new Random(42));
        assertThat(r[0]).isEqualTo(100L);
        assertThat(r[1]).isEqualTo(200L);
    }

    @Test
    void uniform_window_length_equals_selectivity_and_fits_inside() {
        long dataMin = 0L, dataMax = 1_000_000L;
        long[] r = ScanDistribution.UNIFORM.range(dataMin, dataMax, 0.1, new Random(42));

        assertThat(r[1] - r[0]).isEqualTo(100_000L);     // exactly 10%
        assertThat(r[0]).isGreaterThanOrEqualTo(dataMin);
        assertThat(r[1]).isLessThanOrEqualTo(dataMax);
    }

    @Test
    void uniform_with_different_seeds_produces_different_ranges() {
        long[] a = ScanDistribution.UNIFORM.range(0L, 1_000_000L, 0.1, new Random(1));
        long[] b = ScanDistribution.UNIFORM.range(0L, 1_000_000L, 0.1, new Random(2));
        assertThat(a[0]).isNotEqualTo(b[0]);
    }

    @Test
    void rejects_non_positive_selectivity() {
        assertThatThrownBy(() ->
                ScanDistribution.UNIFORM.range(0L, 100L, 0.0, new Random()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                ScanDistribution.UNIFORM.range(0L, 100L, -0.1, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_selectivity_greater_than_one() {
        assertThatThrownBy(() ->
                ScanDistribution.UNIFORM.range(0L, 100L, 1.5, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_inverted_bounds() {
        assertThatThrownBy(() ->
                ScanDistribution.UNIFORM.range(200L, 100L, 0.5, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify they fail**

Run: `./gradlew :kronos-core:test --tests "io.kronos.bench.ScanDistributionTest"`
Expected: FAIL with "ScanDistribution class not found".

- [ ] **Step 3: Implement `ScanDistribution`**

Create `kronos-core/src/jmh/java/io/kronos/bench/ScanDistribution.java`:

```java
package io.kronos.bench;

import java.util.Random;

/**
 * 벤치 쿼리 분포 — {@code (dataMin, dataMax, selectivity)}로부터
 * {@code [startTs, endTs]} 쿼리 구간을 계산한다.
 *
 * <p>{@link #UNIFORM}은 데이터 전 범위에서 랜덤 구간을,
 * {@link #RECENT}는 데이터 말미의 selectivity 비율 구간을 리턴한다.
 * TSDB의 "최근 N분" 쿼리 패턴과 균등 랜덤 접근을 모두 커버한다.
 */
public enum ScanDistribution {
    UNIFORM {
        @Override
        public long[] range(long dataMin, long dataMax, double selectivity, Random rng) {
            validate(dataMin, dataMax, selectivity);
            long span = dataMax - dataMin;
            long window = (long) Math.floor(span * selectivity);
            if (window <= 0) window = 1;
            if (window >= span) return new long[]{dataMin, dataMax};

            long maxStart = dataMax - window;
            long start = dataMin + (rng.nextLong(maxStart - dataMin + 1));
            return new long[]{start, start + window};
        }
    },
    RECENT {
        @Override
        public long[] range(long dataMin, long dataMax, double selectivity, Random rng) {
            validate(dataMin, dataMax, selectivity);
            long span = dataMax - dataMin;
            long window = (long) Math.floor(span * selectivity);
            if (window <= 0) window = 1;
            if (window >= span) return new long[]{dataMin, dataMax};

            return new long[]{dataMax - window, dataMax};
        }
    };

    public abstract long[] range(long dataMin, long dataMax, double selectivity, Random rng);

    private static void validate(long dataMin, long dataMax, double selectivity) {
        if (dataMin >= dataMax) {
            throw new IllegalArgumentException(
                    "dataMin must be < dataMax, got [" + dataMin + ", " + dataMax + "]");
        }
        if (!(selectivity > 0.0 && selectivity <= 1.0)) {
            throw new IllegalArgumentException(
                    "selectivity must be in (0, 1], got " + selectivity);
        }
    }
}
```

- [ ] **Step 4: Confirm test source set reaches JMH classes**

Run: `./gradlew :kronos-core:test --tests "io.kronos.bench.ScanDistributionTest"`
Expected outcomes:
- PASS: configuration already includes `jmh` in `test` classpath. Proceed.
- FAIL with `ScanDistribution` unresolved: Gradle doesn't wire jmh → test by default. Fix by adding to `kronos-core/build.gradle.kts`:

```kotlin
sourceSets["test"].java.srcDir("src/jmh/java")
```

Rerun the test command. Expected: PASS.

- [ ] **Step 5: Run all unit tests to confirm no regression**

Run: `./gradlew :kronos-core:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add kronos-core/src/jmh/java/io/kronos/bench/ScanDistribution.java \
        kronos-core/src/test/java/io/kronos/bench/ScanDistributionTest.java \
        kronos-core/build.gradle.kts
git commit -m "feat(phase2-bench): ScanDistribution enum — UNIFORM/RECENT 쿼리 구간 생성

벤치 전용. (dataMin, dataMax, selectivity, rng) → [start, end].
UNIFORM은 전범위 랜덤, RECENT는 말미 selectivity 비율.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `LsmReadView.scanCount` — raw primitive counting path (TDD)

**Files:**
- Modify: `kronos-core/src/main/java/io/kronos/lsm/LsmReadView.java`
- Create: `kronos-core/src/test/java/io/kronos/lsm/LsmReadViewScanCountTest.java`

### Why this method exists

Spec §3 comp 2: `scan()` uses `BiConsumer<Long, Double>`. Each entry triggers `Double` auto-boxing + lambda dispatch (~20-50 ns). For 1M entries at `selectivity=1.0`, that dominates the measurement. `scanCount` mirrors `scan()` — same merging iterator, same overlaps filter, same boundary semantics — but replaces `consumer.accept(...)` with `count++`. The count is the only thing we return; the benchmark hands it to `Blackhole` so JMH can't DCE the work.

- [ ] **Step 1: Write failing tests**

Create `kronos-core/src/test/java/io/kronos/lsm/LsmReadViewScanCountTest.java`:

```java
package io.kronos.lsm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class LsmReadViewScanCountTest {

    @Test
    void scanCount_matches_scan_accumulated_count_across_random_queries(@TempDir Path dir)
            throws Exception {
        // Fixture: 10_000 entries spread across 4 non-overlapping SSTables (2500 each)
        List<SSTableReader> readers = buildFourSSTables(dir, /*totalEntries*/ 10_000);

        try {
            LsmReadView view = new LsmReadView(null, readers);
            Random rng = new Random(7);

            for (int i = 0; i < 20; i++) {
                long startTs = rng.nextLong(10_000);
                long endTs = startTs + rng.nextLong(10_000 - startTs);

                AtomicLong viaScan = new AtomicLong();
                view.scan(startTs, endTs, (ts, v) -> viaScan.incrementAndGet());

                long viaCount = view.scanCount(startTs, endTs);

                assertThat(viaCount)
                        .as("query #%d [%d, %d]", i, startTs, endTs)
                        .isEqualTo(viaScan.get());
            }
        } finally {
            for (SSTableReader r : readers) r.close();
        }
    }

    @Test
    void scanCount_empty_range_returns_zero(@TempDir Path dir) throws Exception {
        List<SSTableReader> readers = buildFourSSTables(dir, 1_000);
        try {
            LsmReadView view = new LsmReadView(null, readers);
            // range entirely before dataMin
            assertThat(view.scanCount(-1000L, -1L)).isZero();
            // range entirely after dataMax
            assertThat(view.scanCount(10_000L, 20_000L)).isZero();
        } finally {
            for (SSTableReader r : readers) r.close();
        }
    }

    @Test
    void scanCount_full_range_equals_total_entries(@TempDir Path dir) throws Exception {
        int total = 1_000;
        List<SSTableReader> readers = buildFourSSTables(dir, total);
        try {
            LsmReadView view = new LsmReadView(null, readers);
            assertThat(view.scanCount(Long.MIN_VALUE, Long.MAX_VALUE))
                    .isEqualTo(total);
        } finally {
            for (SSTableReader r : readers) r.close();
        }
    }

    /**
     * Builds 4 non-overlapping SSTables with monotonic timestamps 0..total-1.
     * Each file holds total/4 entries. File i covers [i*perFile, (i+1)*perFile - 1].
     */
    private static List<SSTableReader> buildFourSSTables(Path dir, int total) throws Exception {
        if (total % 4 != 0) throw new IllegalArgumentException("total must be divisible by 4");
        int perFile = total / 4;
        List<SSTableReader> readers = new ArrayList<>(4);

        for (int f = 0; f < 4; f++) {
            SkipListMemTable mt = new SkipListMemTable(
                    /*flushThresholdBytes*/ (long) perFile * 32L);
            for (int i = 0; i < perFile; i++) {
                long ts = (long) f * perFile + i;
                mt.put(ts, ts * 1.5);
            }
            mt.freeze();
            Path path = dir.resolve("sst-" + f + ".sst");
            SSTableWriter.write(mt, path);
            mt.close();
            readers.add(new SSTableReader(path));
        }
        // newest first — but all files are equally "new" for count purposes;
        // order affects newest-wins tie-breaking only. Reverse so higher ts = "newer"
        // to match LSM conventions, though with non-overlapping ranges it's moot.
        java.util.Collections.reverse(readers);
        return readers;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kronos-core:test --tests "io.kronos.lsm.LsmReadViewScanCountTest"`
Expected: FAIL with "method scanCount not found".

- [ ] **Step 3: Implement `scanCount` in `LsmReadView.java`**

Add to `LsmReadView.java` right after the `scan()` method (around line 89):

```java
    /**
     * {@code [startTs, endTs]} 범위 내 엔트리 수만 카운트한다.
     *
     * <p>벤치 전용 측정 경로: {@link #scan(long, long, BiConsumer)}과 동일한
     * {@link MergingIterator} + {@link SSTableMeta#overlaps(long, long)} 로직을
     * 쓰되 {@link BiConsumer} 호출을 제거하여 {@code Double} 박싱/람다 디스패치
     * 비용이 측정에 섞이지 않도록 한다.
     *
     * <p>부수 효과: 향후 {@code COUNT(*)} 집계 쿼리의 베이스라인으로도 재사용 가능.
     *
     * @return {@code startTs <= ts <= endTs}인 엔트리 개수 (newest-wins 적용 후)
     */
    public long scanCount(long startTs, long endTs) {
        List<Iterator<TimestampValuePair>> sources = new ArrayList<>(sstables.size() + 1);

        if (memTable != null && memTable.size() > 0) {
            sources.add(memTable.iterator());
        }
        for (SSTableReader r : sstables) {
            SSTableMeta m = r.meta();
            if (m.maxTimestamp() < startTs || m.minTimestamp() > endTs) continue;
            sources.add(r.iterator());
        }

        if (sources.isEmpty()) return 0L;

        MergingIterator merged = new MergingIterator(sources);
        long count = 0L;
        while (merged.hasNext()) {
            TimestampValuePair p = merged.next();
            if (p.timestamp() < startTs) continue;
            if (p.timestamp() > endTs) break;
            count++;
        }
        return count;
    }
```

- [ ] **Step 4: Run test to verify they pass**

Run: `./gradlew :kronos-core:test --tests "io.kronos.lsm.LsmReadViewScanCountTest"`
Expected: PASS.

- [ ] **Step 5: Run full test suite to confirm no regression**

Run: `./gradlew :kronos-core:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add kronos-core/src/main/java/io/kronos/lsm/LsmReadView.java \
        kronos-core/src/test/java/io/kronos/lsm/LsmReadViewScanCountTest.java
git commit -m "feat(phase2-bench): LsmReadView.scanCount — raw primitive counting path

scan()과 동일한 MergingIterator/overlaps 로직, 단 consumer 없이
count만 증가. BiConsumer+Double boxing 오버헤드 제거 목적 (벤치 전용).
부수적으로 COUNT(*) 집계 구현의 베이스라인이 된다.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `LsmScanBench` fixture builder + fixture test (TDD)

**Files:**
- Create: `kronos-core/src/jmh/java/io/kronos/bench/ScanBenchFixture.java`
- Create: `kronos-core/src/test/java/io/kronos/bench/ScanBenchFixtureTest.java`

### Why extract a fixture class

JMH `@State` classes are instantiated by the framework with complex lifecycle. Extracting fixture logic to a plain class makes it unit-testable (4-file non-overlapping invariants, dataMin/dataMax accuracy, cleanup correctness).

- [ ] **Step 1: Write failing fixture tests**

Create `kronos-core/src/test/java/io/kronos/bench/ScanBenchFixtureTest.java`:

```java
package io.kronos.bench;

import io.kronos.lsm.SSTableReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ScanBenchFixtureTest {

    @Test
    void fileCount_one_produces_single_sstable_covering_full_range(@TempDir Path dir)
            throws Exception {
        ScanBenchFixture fx = ScanBenchFixture.build(dir, /*totalEntries*/ 1000, /*fileCount*/ 1);
        try {
            assertThat(fx.readers()).hasSize(1);
            assertThat(fx.dataMin()).isEqualTo(0L);
            assertThat(fx.dataMax()).isEqualTo(999L);
            assertThat(fx.readers().get(0).meta().entryCount()).isEqualTo(1000L);
        } finally {
            fx.close();
        }
    }

    @Test
    void fileCount_four_produces_non_overlapping_ranges(@TempDir Path dir) throws Exception {
        ScanBenchFixture fx = ScanBenchFixture.build(dir, /*totalEntries*/ 1000, /*fileCount*/ 4);
        try {
            List<SSTableReader> readers = fx.readers();
            assertThat(readers).hasSize(4);

            // Sort by minTs to check non-overlap regardless of list order
            var metas = readers.stream().map(SSTableReader::meta)
                    .sorted((a, b) -> Long.compare(a.minTimestamp(), b.minTimestamp()))
                    .toList();

            for (int i = 0; i < 4; i++) {
                assertThat(metas.get(i).entryCount()).isEqualTo(250L);
            }
            // Non-overlap: max of file i < min of file i+1
            for (int i = 0; i < 3; i++) {
                assertThat(metas.get(i).maxTimestamp())
                        .as("file %d max < file %d min", i, i + 1)
                        .isLessThan(metas.get(i + 1).minTimestamp());
            }
            assertThat(fx.dataMin()).isEqualTo(metas.get(0).minTimestamp());
            assertThat(fx.dataMax()).isEqualTo(metas.get(3).maxTimestamp());
        } finally {
            fx.close();
        }
    }

    @Test
    void close_releases_readers(@TempDir Path dir) throws Exception {
        ScanBenchFixture fx = ScanBenchFixture.build(dir, 1000, 4);
        fx.close();
        // After close, attempting to read from any reader should throw
        // (Arena closed → WrongThreadException or IllegalStateException).
        // We don't assert a specific exception type; we assert close() was idempotent.
        fx.close(); // should not throw
    }

    @Test
    void rejects_non_divisible_total(@TempDir Path dir) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ScanBenchFixture.build(dir, /*totalEntries*/ 1001, /*fileCount*/ 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify they fail**

Run: `./gradlew :kronos-core:test --tests "io.kronos.bench.ScanBenchFixtureTest"`
Expected: FAIL with "ScanBenchFixture class not found".

- [ ] **Step 3: Implement `ScanBenchFixture`**

Create `kronos-core/src/jmh/java/io/kronos/bench/ScanBenchFixture.java`:

```java
package io.kronos.bench;

import io.kronos.lsm.LsmReadView;
import io.kronos.lsm.SSTableReader;
import io.kronos.lsm.SSTableWriter;
import io.kronos.lsm.SkipListMemTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 벤치 고정(fixture). {@code totalEntries}개의 타임스탬프(0..total-1)를
 * {@code fileCount}개의 비중첩 SSTable로 분할 기록하고, 열린 리더 리스트를
 * 보유한다. 매 trial 시작 시 build(), 종료 시 close() 한다.
 */
public final class ScanBenchFixture implements AutoCloseable {

    private final List<SSTableReader> readers;
    private final LsmReadView readView;
    private final long dataMin;
    private final long dataMax;
    private final Path dir;

    private ScanBenchFixture(List<SSTableReader> readers, LsmReadView view,
                             long dataMin, long dataMax, Path dir) {
        this.readers = readers;
        this.readView = view;
        this.dataMin = dataMin;
        this.dataMax = dataMax;
        this.dir = dir;
    }

    public List<SSTableReader> readers() { return readers; }
    public LsmReadView readView()        { return readView; }
    public long dataMin()                { return dataMin; }
    public long dataMax()                { return dataMax; }

    public static ScanBenchFixture build(Path dir, int totalEntries, int fileCount)
            throws IOException {
        if (fileCount <= 0) {
            throw new IllegalArgumentException("fileCount must be > 0");
        }
        if (totalEntries % fileCount != 0) {
            throw new IllegalArgumentException(
                    "totalEntries (" + totalEntries + ") must be divisible by fileCount ("
                    + fileCount + ")");
        }
        int perFile = totalEntries / fileCount;
        long bufferBytes = Math.max((long) perFile * 32L, 1024L);

        List<SSTableReader> readers = new ArrayList<>(fileCount);
        for (int f = 0; f < fileCount; f++) {
            SkipListMemTable mt = new SkipListMemTable(bufferBytes);
            for (int i = 0; i < perFile; i++) {
                long ts = (long) f * perFile + i;
                mt.put(ts, ts * 1.5);
            }
            mt.freeze();
            Path path = dir.resolve("sst-" + f + ".sst");
            SSTableWriter.write(mt, path);
            mt.close();
            readers.add(new SSTableReader(path));
        }
        // newest-first ordering: higher ts range = "newer"
        Collections.reverse(readers);

        long dataMin = 0L;
        long dataMax = (long) totalEntries - 1L;
        LsmReadView view = new LsmReadView(null, readers);
        return new ScanBenchFixture(readers, view, dataMin, dataMax, dir);
    }

    @Override
    public void close() {
        for (SSTableReader r : readers) {
            try { r.close(); } catch (Exception ignored) {}
        }
        // tmpDir 삭제는 JMH @TempDir 또는 호출자 책임
    }
}
```

- [ ] **Step 4: Run test to verify they pass**

Run: `./gradlew :kronos-core:test --tests "io.kronos.bench.ScanBenchFixtureTest"`
Expected: PASS.

- [ ] **Step 5: Run full tests**

Run: `./gradlew :kronos-core:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add kronos-core/src/jmh/java/io/kronos/bench/ScanBenchFixture.java \
        kronos-core/src/test/java/io/kronos/bench/ScanBenchFixtureTest.java
git commit -m "feat(phase2-bench): ScanBenchFixture — 비중첩 N-파일 고정 빌더

totalEntries를 fileCount개의 비중첩 SSTable로 분할 기록.
fileCount=1은 compaction 직후, fileCount=4는 전(前) 상태 모사.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `LsmScanBench` JMH benchmark

**Files:**
- Create: `kronos-core/src/jmh/java/io/kronos/bench/LsmScanBench.java`

### Why no separate test for this class

`LsmScanBench` is 100% JMH scaffold (`@State`, `@Setup`, `@Benchmark`). Its correctness is covered transitively — `ScanDistribution` and `ScanBenchFixture` are unit-tested, and the `@Benchmark` body is a single line calling `scanCount` which has its own test. Testing the bench itself would be testing the framework.

- [ ] **Step 1: Create the benchmark class**

Create `kronos-core/src/jmh/java/io/kronos/bench/LsmScanBench.java`:

```java
package io.kronos.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 범위 스캔 성능 측정. Phase 2 다음 ADR(내부 인덱스 vs Gorilla 압축) 방향을
 * spec의 판정식으로 결정하기 위한 베이스라인.
 *
 * <p>축: selectivity × distribution × fileCount = 4 × 2 × 2 = 16 조합.
 * 각 조합에서 p99 latency 측정 (SingleShotTime + 10 iterations × 3s).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 3)
@Fork(2)
public class LsmScanBench {

    /** Spec §3 기준 규모. 1M × 16 B = 16 MB → WSL2 page cache에 안착. */
    private static final int TOTAL_ENTRIES = 1_000_000;

    @Param({"0.01", "0.1", "0.5", "1.0"})
    public double selectivity;

    @Param({"UNIFORM", "RECENT"})
    public ScanDistribution distribution;

    @Param({"1", "4"})
    public int fileCount;

    private Path tmpDir;
    private ScanBenchFixture fixture;

    // Invocation마다 새 구간을 뽑기 위한 rng (trial 내 시드 고정 → 결정론적)
    private Random rng;

    // @Setup(Invocation)에서 갱신되어 @Benchmark가 읽는다
    private long currentStart;
    private long currentEnd;

    @Setup(Level.Trial)
    public void setUpTrial() throws IOException {
        tmpDir = Files.createTempDirectory("lsm-scan-bench-");
        fixture = ScanBenchFixture.build(tmpDir, TOTAL_ENTRIES, fileCount);
        // 시드: 설정 조합별로 달라야 UNIFORM이 같은 구간만 반복하지 않는다
        long seed = 0xC0FFEE ^ ((long) fileCount << 32) ^ distribution.ordinal();
        rng = new Random(seed);
    }

    @Setup(Level.Invocation)
    public void pickRange() {
        long[] r = distribution.range(fixture.dataMin(), fixture.dataMax(), selectivity, rng);
        currentStart = r[0];
        currentEnd = r[1];
    }

    @Benchmark
    public void scanCount(Blackhole bh) {
        long n = fixture.readView().scanCount(currentStart, currentEnd);
        bh.consume(n);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        if (fixture != null) fixture.close();
        if (tmpDir != null) {
            try (Stream<Path> walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }
}
```

**Note on `Mode.SampleTime` vs `Mode.SingleShotTime`:** Spec §3 wrote `SingleShotTime` for p99, but `SampleTime` gives us p50/p99/p999 percentiles from a large sample (better statistical confidence at μs–ms scale) while `SingleShotTime` is one-invocation-per-iteration (forces cold state but gives fewer samples). `SampleTime` + `@Setup(Level.Invocation)` range rotation satisfies the spec's intent (warm-cache bias defeated per invocation) and gives better p99 stability. Locking this choice in now — document deviation in the benchmark report.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :kronos-core:jmhCompileGeneratedClasses` (or fallback: `./gradlew :kronos-core:jmhJar`)
Expected: BUILD SUCCESSFUL. If `jmhCompileGeneratedClasses` doesn't exist, `jmhJar` serves as a compile check.

- [ ] **Step 3: Smoke run — tiny warmup to confirm it runs**

Run:
```bash
./gradlew :kronos-core:jmh \
  -Pjmh.include='io\.kronos\.bench\.LsmScanBench' \
  -Pjmh.warmupIterations=1 \
  -Pjmh.iterations=1 \
  -Pjmh.fork=1 \
  -Pjmh.warmupBatchSize=1 \
  -Pjmh.timeUnit=us
```
Expected: Runs all 16 param combinations without error. Actual numbers meaningless at this iteration count — just confirming the harness works end-to-end. If gradle param keys differ (`me.champeau.jmh` plugin syntax), fall back to passing JMH args via the extension block in `build.gradle.kts`.

- [ ] **Step 4: If the smoke run fails with `jmh.include` unrecognized**

Edit `kronos-core/build.gradle.kts` jmh block (find `jmh {` … `}`). Add:

```kotlin
jmh {
    // existing config…
    // add or replace:
    includes.add("io\\.kronos\\.bench\\.LsmScanBench")
}
```

Rerun:
```bash
./gradlew :kronos-core:jmh
```
Revert the includes pin before running the full benchmark in Task 5.

- [ ] **Step 5: Commit**

```bash
git add kronos-core/src/jmh/java/io/kronos/bench/LsmScanBench.java
git commit -m "feat(phase2-bench): LsmScanBench — selectivity × distribution × fileCount

16개 @Param 조합. SampleTime 모드로 p50/p99 추출. Warmup 5×2s,
Measurement 10×3s, Fork 2, @Setup(Invocation)으로 구간 rotate
(WSL2 page cache warm 편향 방지).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Full benchmark run + report

**Files:**
- Create: `docs/benchmarks/phase2-2026-04-17.md`

### Run parameters

- No `-Pjmh.include` pin (or keep the class pin if added in Task 4 Step 4)
- Full warmup/measurement as declared in annotations
- Total wall time estimate: 16 combos × (5×2s warmup + 10×3s measurement) × 2 forks = ~21 minutes. Plan for this.

- [ ] **Step 1: Run the full benchmark, capture output**

Run:
```bash
./gradlew :kronos-core:jmh 2>&1 | tee /tmp/phase2-scan-bench.log
```
Expected: BUILD SUCCESSFUL after ~20 min. Output includes a table like:

```
Benchmark                       (distribution)  (fileCount)  (selectivity)  Mode  Cnt  Score  Error  Units
LsmScanBench.scanCount          RECENT          1            0.01           sample  N    X      ±Y     us/op
LsmScanBench.scanCount          RECENT          1            0.1            sample  N    X      ±Y     us/op
...
```

Plus percentiles:
```
LsmScanBench.scanCount·p0.50   ...   us/op
LsmScanBench.scanCount·p0.99   ...   us/op
```

- [ ] **Step 2: Extract p99 values to a table**

The raw log is dense. From `/tmp/phase2-scan-bench.log`, find lines containing `p0.99` and pair with their `@Param` combo. Build this table in the report (example structure, fill from actual numbers):

| fileCount | distribution | selectivity | p50 (μs) | p99 (μs) |
|-----------|--------------|-------------|----------|----------|
| 1         | RECENT       | 0.01        | …        | …        |
| 1         | RECENT       | 0.1         | …        | …        |
| 1         | RECENT       | 0.5         | …        | …        |
| 1         | RECENT       | 1.0         | …        | …        |
| 1         | UNIFORM      | 0.01        | …        | …        |
| ... (8 more rows for fileCount=4 × 2 distributions × 4 selectivities) |

- [ ] **Step 3: Apply exit criteria from the spec**

From the spec §4 — compute:
- `cond1 = p99(files=4, RECENT, 0.01) < 5 ms (5000 μs)` — true/false
- `cond2 = p99(files=1, RECENT, 0.01) < 5 ms` — true/false
- `ratio = p99(files=1, RECENT, 1.0) / p99(files=1, RECENT, 0.01)` — report the ratio
- `cond3 = ratio > 20` — true/false
- Decision: if `cond1 ∧ cond2 ∧ cond3` → next ADR = Gorilla. Else → next ADR = internal sparse index.

If any p99 lands in the `[4.5, 5.5] ms` ambiguity band, apply the §4 3-step fallback: rerun the ambiguous config twice more, take median; if still ambiguous, defer to `cond3`; if still ambiguous, default to "internal index" per spec.

- [ ] **Step 4: Write the report**

Create `docs/benchmarks/phase2-2026-04-17.md` with this structure (fill numeric values from Step 2):

```markdown
# Phase 2 Scan Benchmark — 2026-04-17

## 환경

- Java: [output of `java --version`]
- JMH: 1.37
- 호스트: WSL2 / Linux [uname -r]
- 하드웨어: [사용자 기재]
- 파일시스템: WSL2 ext4 (`/tmp`)

## 벤치 파라미터

- totalEntries = 1,000,000
- selectivity ∈ {0.01, 0.1, 0.5, 1.0}
- distribution ∈ {UNIFORM, RECENT}
- fileCount ∈ {1, 4}
- Warmup 5×2s, Measurement 10×3s, Fork 2
- Mode: SampleTime / μs

## 측정 경로

`LsmReadView.scanCount(start, end)` — MergingIterator를 경유하는 실제 쿼리 경로.
`BiConsumer<Long, Double>` 소비 경로(`scan()`)는 Double boxing + 람다 디스패치
오버헤드로 측정 대상이 오염되어 제외.

## 결과 (p50 / p99, 단위 μs)

| fileCount | distribution | selectivity | p50 | p99 |
|-----------|--------------|-------------|-----|-----|
| 1 | RECENT  | 0.01 | … | … |
| 1 | RECENT  | 0.1  | … | … |
| 1 | RECENT  | 0.5  | … | … |
| 1 | RECENT  | 1.0  | … | … |
| 1 | UNIFORM | 0.01 | … | … |
| 1 | UNIFORM | 0.1  | … | … |
| 1 | UNIFORM | 0.5  | … | … |
| 1 | UNIFORM | 1.0  | … | … |
| 4 | RECENT  | 0.01 | … | … |
| 4 | RECENT  | 0.1  | … | … |
| 4 | RECENT  | 0.5  | … | … |
| 4 | RECENT  | 1.0  | … | … |
| 4 | UNIFORM | 0.01 | … | … |
| 4 | UNIFORM | 0.1  | … | … |
| 4 | UNIFORM | 0.5  | … | … |
| 4 | UNIFORM | 1.0  | … | … |

## Spec §4 판정식 적용

- 조건 1: `p99(files=4, RECENT, 0.01) = … μs` < 5000 μs → **[PASS/FAIL]**
- 조건 2: `p99(files=1, RECENT, 0.01) = … μs` < 5000 μs → **[PASS/FAIL]**
- 조건 3: `ratio = p99(files=1, RECENT, 1.0) / p99(files=1, RECENT, 0.01) = …` > 20× → **[PASS/FAIL]**

**결정**: [다음 ADR = "ADR-005 Gorilla XOR 압축" 또는 "ADR-005 SSTable 내부 sparse index"]

## 해석

[2-4 문단. 측정값이 spec의 어느 가설을 지지/기각하는지, 예상과 어긋난 지점이 있으면
그 원인 분석 — 예: fileCount=4가 fileCount=1보다 느리다면 overlaps() 프루닝이
효과를 내고 있음에도 MergingIterator 힙 비용이 상쇄하고 있음을 시사.]

## 다음 액션

1. ADR-005 초안: [결정된 방향]
2. [구현 태스크 제목]
```

- [ ] **Step 5: Commit the report**

```bash
git add docs/benchmarks/phase2-2026-04-17.md /tmp/phase2-scan-bench.log
# 로그는 /tmp에 있으므로 add 안 됨. 실제로는 리포트만 커밋.
git add docs/benchmarks/phase2-2026-04-17.md
git commit -m "docs(phase2): scan bench 결과 — [Gorilla 선행 또는 인덱스 선행] 결정

16개 @Param 조합 측정. Spec §4 판정식 적용 결과 [요약].

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Update CLAUDE.md Phase 2 checklist + TIL

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/TIL.md`

- [ ] **Step 1: Update CLAUDE.md Phase 2 section**

Edit `CLAUDE.md` — find the `### Phase 2 — 시계열 특화` block and update the checkboxes based on what was delivered. Specifically, add a new line for the bench work (it's not on the original checklist but it's the prerequisite that unblocks the rest):

Replace the `[ ] 타임스탬프 인덱스 (시간 범위 쿼리)` line with two lines:

```
[x] Scan benchmark baseline (scanCount path, selectivity × distribution × fileCount) — docs/benchmarks/phase2-2026-04-17.md
[ ] 타임스탬프 인덱스 (시간 범위 쿼리) — [필요 여부는 벤치 결과로 확정]
```

If the decision was "Gorilla first," reorder so Gorilla moves up:

```
[ ] Gorilla XOR 압축 구현 (벤치가 인덱스보다 먼저 필요로 결정)
[ ] Delta-of-Delta 인코딩
[ ] 타임스탬프 내부 인덱스 (Gorilla 이후 재평가)
```

- [ ] **Step 2: Append TIL entry**

Edit `docs/TIL.md` — append under a new `## 2026-04-17 (2)` section (or continue the existing 2026-04-17 block):

```markdown
- Phase 2 킥오프 — spec/plan 작성 후 벤치부터 돌렸다. `LsmReadView.scan()`이 O(n)이라는
  내 첫 가정은 **부분적으로 틀렸다**: `SSTableReader.scan()`은 이미 이진 탐색 기반이고,
  O(n)은 MergingIterator 경유 경로에만 존재. spec의 판정식은 이 경로를 타깃으로
  `scanCount`를 새로 만들어 `BiConsumer + Double` boxing 오버헤드를 제거한 상태에서
  검증한 게 맞는 선택이었다.
- 판정식 `p99 < 5ms AND ratio > 20×` → [결과: Gorilla 선행 / 인덱스 선행]. 이 결정이
  ADR-005의 이름을 미리 결정하지 않는 구조가 "측정 우선" 원칙의 실제 구현이다.
- Tech-decision-debate 스킬의 **비판자 에이전트가** "파일 1개 편향 / consumer overhead가
  분모 오염" 두 지점을 찔러서 A안(time_ratio 단일 기준)이 구조적으로 무너졌다.
  혼자 추론했으면 `time_ratio < 10` 임계값을 그냥 확정했을 것.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/TIL.md
git commit -m "docs(phase2): Phase 2 체크리스트·TIL 업데이트 — 벤치 완료, 다음 ADR 방향 확정

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

Done inline after writing. Key checks:

1. **Spec coverage:** every spec section (§2 purpose/scope, §3 components, §4 exit criteria, §5 deliverables, §6 dependencies, §7 risks) maps to a task or is implicit in the benchmark output. §7 risk R1 (WSL2 variance) is addressed by Task 5 Step 3's ambiguity-band fallback. R2 is addressed by `Level.Invocation` being O(1). R3 is addressed by Task 2's cross-check tests. R4/R5 are follow-up ADR concerns, not this plan.
2. **Placeholder scan:** The only `…` markers are in the **report template** which is the deliverable — filled at run time by actual measurements. That's expected, not a planning gap. No TBD/TODO in the implementation tasks themselves.
3. **Type consistency:**
   - `ScanDistribution.range(long, long, double, Random) → long[]` — used identically in tests, fixture, and bench ✓
   - `ScanBenchFixture.build(Path, int, int) → ScanBenchFixture` + `readView()`, `dataMin()`, `dataMax()`, `readers()`, `close()` — consistent ✓
   - `LsmReadView.scanCount(long, long) → long` — consistent in test + bench ✓

One adjustment made during review: Task 4 Step 1 initially wrote `@BenchmarkMode(Mode.SingleShotTime)` to match spec §3 wording, but `SampleTime` is technically correct for p99 extraction and matches spec §4's intent (p99 is explicitly percentile-based). Noted as explicit deviation in the task + plan for re-justification in the benchmark report.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-04-17-phase2-scan-bench.md`. User asked for plan → implement → verify loop to continue until no more verification remains. Proceeding with **inline execution via superpowers:executing-plans** so the verification loop stays in one session.
