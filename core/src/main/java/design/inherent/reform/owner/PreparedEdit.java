package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentId;
import java.util.UUID;

/** Opaque complete preparation; only its issuing owner/session can consume it for an effect. */
public interface PreparedEdit<T> {
  DocumentId document();

  UUID context();

  UUID session();
}
