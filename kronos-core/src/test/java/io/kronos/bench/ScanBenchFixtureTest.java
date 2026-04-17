package io.kronos.bench;

import io.kronos.lsm.SSTableReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanBenchFixtureTest {

    @Test
    void fileCount_one_produces_single_sstable_covering_full_range(@TempDir Path dir)
            throws Exception {
        ScanBenchFixture fx = ScanBenchFixture.build(dir, 1000, 1);
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
        ScanBenchFixture fx = ScanBenchFixture.build(dir, 1000, 4);
        try {
            List<SSTableReader> readers = fx.readers();
            assertThat(readers).hasSize(4);

            var metas = readers.stream().map(SSTableReader::meta)
                    .sorted((a, b) -> Long.compare(a.minTimestamp(), b.minTimestamp()))
                    .toList();

            for (int i = 0; i < 4; i++) {
                assertThat(metas.get(i).entryCount()).isEqualTo(250L);
            }
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
    void close_is_idempotent(@TempDir Path dir) throws Exception {
        ScanBenchFixture fx = ScanBenchFixture.build(dir, 1000, 4);
        fx.close();
        fx.close();
    }

    @Test
    void rejects_non_divisible_total(@TempDir Path dir) {
        assertThatThrownBy(() -> ScanBenchFixture.build(dir, 1001, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
