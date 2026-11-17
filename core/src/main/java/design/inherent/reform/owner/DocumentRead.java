package design.inherent.reform.owner;

import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.validation.ValidationReport;
import java.util.Optional;

/** Immutable owner-source observation. A revision is a baseline, never write authority. */
public interface DocumentRead<T> {
  String revision();

  Optional<DraftTree> draft();

  Optional<Candidate<T>> candidate();

  ValidationReport report();
}
