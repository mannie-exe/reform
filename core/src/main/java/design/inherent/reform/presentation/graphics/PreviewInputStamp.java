package design.inherent.reform.presentation.graphics;

import design.inherent.reform.edit.DraftRevision;
import java.util.Objects;

/** Native input can change without advancing the actor's accepted draft revision. */
public record PreviewInputStamp(DraftRevision draft, long nativeInputEpoch) {
  public PreviewInputStamp {
    Objects.requireNonNull(draft);
    if (nativeInputEpoch < 0) throw new IllegalArgumentException("Negative native input epoch");
  }
}
