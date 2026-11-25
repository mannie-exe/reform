package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.DocumentId;

/** Internal common data contract of portable local-state keys and nonportable cached-read keys. */
public interface UiCaptureKey<T> {
  DocumentId id();

  UiSnapshot<T> capture(T value);

  /** True only for snapshots actually captured by this exact admitted contract. */
  boolean owns(UiSnapshot<?> snapshot);
}
