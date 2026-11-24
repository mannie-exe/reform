package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeGraphicCleanupTest {
  @Test
  void failedReleaseStillAttemptsEverySiblingAndKeepsTheOriginalCause() {
    var first = new IllegalStateException("first actual cleanup");
    var second = new AssertionError("second actual cleanup");
    var calls = new ArrayList<Integer>();
    var actual =
        assertThrows(
            IllegalStateException.class,
            () ->
                NativeGraphicCleanup.run(
                    () -> {
                      calls.add(1);
                      throw first;
                    },
                    () -> {
                      calls.add(2);
                      throw second;
                    },
                    () -> calls.add(3)));
    assertSame(first, actual);
    assertArrayEquals(new Throwable[] {second}, actual.getSuppressed());
    assertEquals(List.of(1, 2, 3), calls);
  }

  @Test
  void repeatedIdenticalFailureDoesNotPreventTheRemainingRelease() {
    var failed = new AssertionError("same cause");
    var released = new boolean[1];
    assertSame(
        failed,
        assertThrows(
            AssertionError.class,
            () ->
                NativeGraphicCleanup.run(
                    () -> {
                      throw failed;
                    },
                    () -> {
                      throw failed;
                    },
                    () -> released[0] = true)));
    assertTrue(released[0]);
    assertEquals(0, failed.getSuppressed().length);
  }
}
