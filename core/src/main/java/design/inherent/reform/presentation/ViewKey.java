package design.inherent.reform.presentation;

import java.util.Objects;

/** Typed presentation identity; independent of a document's schema and persistence identity. */
public record ViewKey<S>(DestinationId id, ViewStateKey<S> state) {
  public ViewKey {
    Objects.requireNonNull(id);
    Objects.requireNonNull(state);
  }
}
