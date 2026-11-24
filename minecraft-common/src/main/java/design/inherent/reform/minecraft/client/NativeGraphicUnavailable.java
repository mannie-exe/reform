package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Objects;

/** A native capability is absent; this is distinct from malformed input or failed native work. */
public final class NativeGraphicUnavailable extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final transient ValidationReport report;

  public NativeGraphicUnavailable(ValidationReport report) {
    super("Native graphic capability is unavailable");
    this.report = Objects.requireNonNull(report);
    if (report.issues().isEmpty()) throw new IllegalArgumentException("Unavailable needs a reason");
  }

  public NativeGraphicUnavailable(String code, String reason) {
    this(new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, reason)), true));
  }

  public ValidationReport report() {
    return report;
  }

  @java.io.Serial
  private void writeObject(java.io.ObjectOutputStream ignored) throws java.io.IOException {
    throw new java.io.NotSerializableException(getClass().getName());
  }

  @java.io.Serial
  private void readObject(java.io.ObjectInputStream ignored) throws java.io.IOException {
    throw new java.io.NotSerializableException(getClass().getName());
  }
}
