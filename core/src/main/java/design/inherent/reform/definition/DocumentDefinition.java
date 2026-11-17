package design.inherent.reform.definition;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Pure complete-value rules. Context-dependent authority and application remain owner services. */
public final class DocumentDefinition<T> {
  private final DocumentId id;
  private final int schemaVersion;
  private final ValueType<T> type;
  private final T defaults;
  private final Function<T, ValidationReport> validator;

  public DocumentDefinition(
      DocumentId id,
      int schemaVersion,
      ValueType<T> type,
      Supplier<T> defaults,
      Function<T, ValidationReport> validator) {
    this.id = Objects.requireNonNull(id);
    if (schemaVersion < 1) throw new IllegalArgumentException("Schema version must be positive");
    this.schemaVersion = schemaVersion;
    this.type = Objects.requireNonNull(type);
    this.validator = Objects.requireNonNull(validator);
    var initial = validate(Objects.requireNonNull(defaults).get());
    if (!initial.report().valid())
      throw new IllegalArgumentException(
          "Invalid definition defaults: " + initial.report().issues());
    this.defaults = initial.value().orElseThrow();
  }

  public DocumentDefinition(
      DocumentId id, int schemaVersion, ValueType<T> type, Supplier<T> defaults) {
    this(id, schemaVersion, type, defaults, value -> ValidationReport.VALID);
  }

  public DocumentId id() {
    return id;
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public ValueType<T> type() {
    return type;
  }

  public T defaults() {
    return type.freeze(defaults);
  }

  public DecodedValue<T> decode(JsonInput.Parsed input) {
    var decoded = input.decode(type);
    return decoded.value().isEmpty() ? decoded : validate(decoded.value().orElseThrow());
  }

  public DecodedValue<T> validate(T candidate) {
    return check(candidate).decoded();
  }

  private record Checked<T>(DecodedValue<T> decoded, DataResult<JsonElement> encoded) {}

  private Checked<T> check(T candidate) {
    final T frozen;
    try {
      frozen = type.freeze(candidate);
    } catch (RuntimeException invalid) {
      return new Checked<>(
          DecodedValue.failure(
              Issue.error(
                  "value.snapshot",
                  FieldPath.ROOT,
                  invalid.getMessage() == null ? "Cannot snapshot value" : invalid.getMessage())),
          null);
    }
    try {
      var encoded = type.codec().encodeStart(JsonOps.INSTANCE, frozen);
      if (encoded.error().isPresent()) {
        return new Checked<>(
            DecodedValue.failure(
                Issue.error(
                    "value.encode", FieldPath.ROOT, encoded.error().orElseThrow().message())),
            null);
      }
      var report = Objects.requireNonNull(validator.apply(frozen));
      return report.valid()
          ? new Checked<>(new DecodedValue<>(java.util.Optional.of(frozen), report), encoded)
          : new Checked<>(DecodedValue.failure(report), null);
    } catch (RuntimeException failure) {
      return new Checked<>(
          DecodedValue.failure(
              new ValidationReport(
                  java.util.List.of(
                      Issue.error(
                          "validation.unavailable",
                          FieldPath.ROOT,
                          "Required validation could not complete")),
                  false)),
          null);
    }
  }

  public DataResult<JsonElement> encode(T candidate) {
    var checked = check(candidate);
    if (checked.decoded().value().isEmpty())
      return DataResult.error(() -> "Complete candidate validation failed");
    return checked.encoded();
  }
}
