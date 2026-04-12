package io.kronos.lsm;

/**
 * 시계열 데이터의 단일 포인트 — (타임스탬프, 값) 쌍.
 *
 * <p>record는 JIT의 스칼라 치환(scalar replacement) 대상이 되어
 * 힙 할당 없이 레지스터 또는 스택에 직접 적재될 수 있다.
 */
public record TimestampValuePair(long timestamp, double value) {}
