package design.inherent.reform.owner;

import java.util.UUID;

/** An authorized read issued by one bound owner session, not a frontend-supplied context grant. */
public interface OwnerObservation<T> {
  UUID context();

  UUID session();

  DocumentRead<T> document();
}
