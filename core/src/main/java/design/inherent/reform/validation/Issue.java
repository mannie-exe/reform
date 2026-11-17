package design.inherent.reform.validation;

import design.inherent.reform.definition.FieldPath;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable routing and localization inputs; readable text is never parsed to recover a location. */
public record Issue(
    String code,
    Severity severity,
    FieldPath path,
    List<FieldPath> related,
    Map<String, String> arguments,
    String fallback) {
  public enum Severity {
    INFO,
    WARNING,
    ERROR
  }

  public Issue {
    if (Objects.requireNonNull(code).isBlank())
      throw new IllegalArgumentException("Missing issue code");
    Objects.requireNonNull(severity);
    Objects.requireNonNull(path);
    related = List.copyOf(related);
    arguments = Map.copyOf(arguments);
    Objects.requireNonNull(fallback);
  }

  public static Issue error(String code, FieldPath path, String fallback) {
    return new Issue(code, Severity.ERROR, path, List.of(), Map.of(), fallback);
  }

  public Issue under(FieldPath prefix) {
    return new Issue(
        code,
        severity,
        prefix.resolve(path),
        related.stream().map(prefix::resolve).toList(),
        arguments,
        fallback);
  }
}
