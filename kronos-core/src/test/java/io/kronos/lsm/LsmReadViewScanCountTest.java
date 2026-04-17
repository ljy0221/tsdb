package io.kronos.lsm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
        List<SSTableReader> readers = buildFourSSTables(dir, 10_000);

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
            assertThat(view.scanCount(-1000L, -1L)).isZero();
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

    private static List<SSTableReader> buildFourSSTables(Path dir, int total) throws Exception {
        if (total % 4 != 0) throw new IllegalArgumentException("total must be divisible by 4");
        int perFile = total / 4;
        List<SSTableReader> readers = new ArrayList<>(4);

        for (int f = 0; f < 4; f++) {
            SkipListMemTable mt = new SkipListMemTable((long) perFile * 32L);
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
        Collections.reverse(readers);
        return readers;
    }
}
