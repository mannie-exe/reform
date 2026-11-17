package design.inherent.reform.owner;

import java.util.Optional;
import java.util.UUID;

/** An authorized read issued by one bound owner session, not a frontend-supplied context grant. */
public interface OwnerObservation<T> {
  UUID context();

  UUID session();

  DocumentRead<T> document();

  default SourceInfo provenance() {
    return SourceInfo.UNKNOWN;
  }

  /** Empty means this owner has not qualified a domain-effective value observation. */
  default Optional<SettingsHandle.View<T>> effectiveView() {
    return Optional.empty();
  }
}
