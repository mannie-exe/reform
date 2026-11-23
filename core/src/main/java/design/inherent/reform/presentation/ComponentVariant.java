package design.inherent.reform.presentation;

import design.inherent.reform.definition.DocumentId;
import java.util.Locale;
import java.util.Objects;

/** A presentation family and local defaults, never an interaction or persistence implementation. */
public record ComponentVariant(DocumentId id, Kind kind, ThemePatch defaults) {
  public enum Kind {
    ROW,
    GROUPED_SECTION,
    TITLE_REFERENCE,
    DESTINATION_CHOICE,
    ILLUSTRATED_ENTRY
  }

  public ComponentVariant {
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(defaults);
  }

  public static ComponentVariant standard(Kind kind) {
    return new ComponentVariant(
        DocumentId.parse("reform:" + kind.name().toLowerCase(Locale.ROOT)),
        kind,
        ThemePatch.empty());
  }
}
