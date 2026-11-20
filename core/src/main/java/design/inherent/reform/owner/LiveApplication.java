package design.inherent.reform.owner;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A trusted domain owner's synchronous, bounded live effect and its readback. Both run on the
 * actual owner executor. The readback checks the domain state, including any required derived
 * state. Neither callback performs persistence or calls back into the document service.
 */
public record LiveApplication<T>(Predicate<T> isEffective, Consumer<T> apply) {
  public LiveApplication {
    Objects.requireNonNull(isEffective);
    Objects.requireNonNull(apply);
  }
}
