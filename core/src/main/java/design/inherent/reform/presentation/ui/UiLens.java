package design.inherent.reform.presentation.ui;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Pure access/replacement of one part of local state; no document or effect capability. */
public record UiLens<S, V>(Function<S, V> get, BiFunction<S, V, S> replace) {
  public UiLens {
    Objects.requireNonNull(get);
    Objects.requireNonNull(replace);
  }
}
