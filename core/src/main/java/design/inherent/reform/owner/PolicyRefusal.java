package design.inherent.reform.owner;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.List;

/**
 * Structured refusal before effective publication. It says nothing about a separate file effect.
 */
public final class PolicyRefusal extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final transient ValidationReport report;

  PolicyRefusal(String code, String message) {
    this(code, message, true);
  }

  PolicyRefusal(String code, String message, boolean complete) {
    this(new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, message)), complete));
  }

  PolicyRefusal(ValidationReport report) {
    super(
        report.issues().isEmpty()
            ? "Runtime publication refused"
            : report.issues().getFirst().fallback());
    this.report = report;
  }

  public ValidationReport report() {
    return report;
  }

  @Serial
  private void writeObject(ObjectOutputStream stream) throws NotSerializableException {
    throw new NotSerializableException(
        "Use explicit bounded diagnostic transport, not Java serialization");
  }

  @Serial
  private void readObject(ObjectInputStream stream) throws NotSerializableException {
    throw new NotSerializableException("Local policy refusal cannot be deserialized");
  }
}
