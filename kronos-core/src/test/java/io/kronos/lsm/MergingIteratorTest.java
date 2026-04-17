package io.kronos.lsm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MergingIteratorTest {

    private static Iterator<TimestampValuePair> src(long[] ts, double[] vals) {
        assertThat(ts.length).isEqualTo(vals.length);
        List<TimestampValuePair> list = new ArrayList<>(ts.length);
        for (int i = 0; i < ts.length; i++) list.add(new TimestampValuePair(ts[i], vals[i]));
        return list.iterator();
    }

    private static List<TimestampValuePair> drain(Iterator<TimestampValuePair> it) {
        List<TimestampValuePair> out = new ArrayList<>();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    // ── 기본 병합 ─────────────────────────────────────────────────────

    @Test
    void merges_two_sorted_sources_in_order() {
        var a = src(new long[]{10, 30, 50}, new double[]{1, 3, 5});
        var b = src(new long[]{20, 40, 60}, new double[]{2, 4, 6});

        var out = drain(MergingIterator.of(a, b));

        assertThat(out).extracting(TimestampValuePair::timestamp)
            .containsExactly(10L, 20L, 30L, 40L, 50L, 60L);
    }

    @Test
    void empty_sources_produce_empty_iterator() {
        var empty = List.<Iterator<TimestampValuePair>>of();
        assertThat(new MergingIterator(empty).hasNext()).isFalse();
    }

    @Test
    void single_source_passes_through() {
        var a = src(new long[]{1, 2, 3}, new double[]{10, 20, 30});
        var out = drain(MergingIterator.of(a));
        assertThat(out).extracting(TimestampValuePair::timestamp).containsExactly(1L, 2L, 3L);
    }

    // ── 중복 타임스탬프: newest-wins ──────────────────────────────────

    @Test
    void duplicate_timestamp_highest_priority_wins() {
        // newest first: memTable-stub, sst1, sst2
        var memStub = src(new long[]{100}, new double[]{99.0}); // winner for ts=100
        var sst1    = src(new long[]{100, 200}, new double[]{50.0, 200.0});
        var sst2    = src(new long[]{100, 300}, new double[]{10.0, 300.0});

        var out = drain(new MergingIterator(List.of(memStub, sst1, sst2)));

        assertThat(out).containsExactly(
            new TimestampValuePair(100L, 99.0),  // memStub 이김
            new TimestampValuePair(200L, 200.0), // sst1 only
            new TimestampValuePair(300L, 300.0)  // sst2 only
        );
    }

    @Test
    void duplicate_resolved_between_sstables_by_priority() {
        // memTable 없음. sst_newer가 sst_older를 이겨야 한다.
        var newer = src(new long[]{50, 100}, new double[]{5.5, 10.5});
        var older = src(new long[]{50, 150}, new double[]{0.0, 15.0});

        var out = drain(new MergingIterator(List.of(newer, older)));

        assertThat(out).containsExactly(
            new TimestampValuePair(50L, 5.5),    // newer 이김
            new TimestampValuePair(100L, 10.5),
            new TimestampValuePair(150L, 15.0)
        );
    }

    // ── 경계 케이스 ──────────────────────────────────────────────────

    @Test
    void handles_unequal_length_sources() {
        var short_ = src(new long[]{1}, new double[]{1});
        var long_  = src(new long[]{2, 3, 4, 5}, new double[]{2, 3, 4, 5});

        var out = drain(new MergingIterator(List.of(short_, long_)));
        assertThat(out).extracting(TimestampValuePair::timestamp)
            .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void all_sources_share_same_timestamps_returns_only_highest_priority() {
        var a = src(new long[]{1, 2, 3}, new double[]{1.1, 2.1, 3.1});
        var b = src(new long[]{1, 2, 3}, new double[]{1.2, 2.2, 3.2});
        var c = src(new long[]{1, 2, 3}, new double[]{1.3, 2.3, 3.3});

        var out = drain(new MergingIterator(List.of(a, b, c)));

        assertThat(out).containsExactly(
            new TimestampValuePair(1L, 1.1),
            new TimestampValuePair(2L, 2.1),
            new TimestampValuePair(3L, 3.1)
        );
    }
}
