package io.kronos.lsm;

import java.util.Iterator;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;

/**
 * LSM-Tree의 인메모리 쓰기 버퍼.
 *
 * <h2>스레드 안전성</h2>
 * <ul>
 *   <li>{@link #put}은 단일 스레드에서만 호출해야 한다.</li>
 *   <li>{@link #get}은 어느 스레드에서나 호출 가능하다.</li>
 *   <li>{@link #forEachInOrder}는 {@link #freeze()} 이후에만 유효하며,
 *       flush 전담 스레드 단독으로 호출한다.</li>
 * </ul>
 *
 * <h2>생명주기</h2>
 * <pre>
 * Active: put() 가능, get() 가능
 *   ↓ freeze()
 * Frozen: put() 불가, get() 가능, forEachInOrder() 가능
 *   ↓ flush 완료 후
 * close(): 오프힙 메모리 해제
 * </pre>
 */
public interface MemTable extends AutoCloseable {

    /**
     * 타임스탬프-값 쌍을 삽입한다.
     * 동일 타임스탬프가 이미 존재하면 값을 덮어쓴다.
     *
     * @throws IllegalStateException freeze() 이후 호출 시
     */
    void put(long timestamp, double value);

    /**
     * 정확한 타임스탬프로 값을 조회한다.
     *
     * @return 값이 존재하면 {@code OptionalDouble.of(value)}, 없으면 {@code OptionalDouble.empty()}
     */
    OptionalDouble get(long timestamp);

    /**
     * 모든 엔트리를 타임스탬프 오름차순으로 순회한다.
     * flush 경로 전용이며, {@link #freeze()} 이후에만 호출 가능하다.
     *
     * @throws IllegalStateException freeze() 이전 호출 시
     */
    void forEachInOrder(BiConsumer<Long, Double> consumer);

    /**
     * 모든 엔트리를 타임스탬프 오름차순으로 반환하는 이터레이터.
     *
     * <p>merge 경로 전용 — 다른 소스(SSTable)와 k-way merge 가능하도록
     * {@link #forEachInOrder}의 pull 버전을 제공한다.
     * {@link #freeze()} 이후에만 호출 가능하다.
     *
     * @throws IllegalStateException freeze() 이전 호출 시
     */
    Iterator<TimestampValuePair> iterator();

    /** 오프힙 버퍼의 물리적 사용 바이트 수. */
    long sizeBytes();

    /** 논리적 유니크 엔트리 수 (덮어쓰기는 1로 카운트). */
    int size();

    /** flush 임계값 초과 여부. */
    boolean isFull();

    /**
     * MemTable을 불변 상태로 전환한다.
     * 이후 {@link #put}은 {@link IllegalStateException}을 던진다.
     */
    void freeze();

    boolean isFrozen();

    /** 오프힙 메모리를 해제한다. */
    @Override
    void close();
}
