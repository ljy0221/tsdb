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
            long start = dataMin + rng.nextLong(maxStart - dataMin + 1);
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
