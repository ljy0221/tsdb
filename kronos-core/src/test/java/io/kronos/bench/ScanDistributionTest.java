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

        assertThat(r[1] - r[0]).isEqualTo(100_000L);
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
