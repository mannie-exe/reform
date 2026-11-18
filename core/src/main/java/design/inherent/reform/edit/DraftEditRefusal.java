package design.inherent.reform.edit;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.List;

/** A refused local edit carries its actual field, without inferring completed owner effects. */
public final class DraftEditRefusal extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;
  private final transient ValidationReport report;

  DraftEditRefusal(String code, FieldPath path, String message) {
    super(message);
    report = new ValidationReport(List.of(Issue.error(code, path, message)), true);
  }

  public ValidationReport report() {
    return report;
  }

  @Serial
  private void writeObject(ObjectOutputStream stream) throws NotSerializableException {
    throw new NotSerializableException("Local edit refusals use explicit diagnostic transport");
  }

  @Serial
  private void readObject(ObjectInputStream stream) throws NotSerializableException {
    throw new NotSerializableException("Local edit refusal cannot be deserialized");
  }
}
