package io.kronos.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OffHeapLongArrayTest {

    @Test
    void getAndSet() {
        try (var arr = new OffHeapLongArray(10)) {
            arr.set(0, 42L);
            arr.set(9, 99L);

            assertThat(arr.get(0)).isEqualTo(42L);
            assertThat(arr.get(9)).isEqualTo(99L);
        }
    }

    @Test
    void initialValuesAreZero() {
        try (var arr = new OffHeapLongArray(5)) {
            for (long i = 0; i < arr.length(); i++) {
                assertThat(arr.get(i)).isZero();
            }
        }
    }

    @Test
    void largeArray() {
        long size = 1_000_000L;
        try (var arr = new OffHeapLongArray(size)) {
            for (long i = 0; i < size; i++) {
                arr.set(i, i);
            }
            for (long i = 0; i < size; i++) {
                assertThat(arr.get(i)).isEqualTo(i);
            }
        }
    }

    @Test
    void throwsOnInvalidLength() {
        assertThatThrownBy(() -> new OffHeapLongArray(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OffHeapLongArray(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsOnOutOfBoundsIndex() {
        try (var arr = new OffHeapLongArray(5)) {
            assertThatThrownBy(() -> arr.get(5))
                .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> arr.get(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void throwsAfterClose() {
        var arr = new OffHeapLongArray(5);
        arr.close();
        assertThatThrownBy(() -> arr.get(0))
            .isInstanceOf(IllegalStateException.class);
    }
}
