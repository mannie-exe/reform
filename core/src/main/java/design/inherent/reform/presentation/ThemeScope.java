package design.inherent.reform.presentation;

import java.util.Objects;

/** A resolved value scope; it retains neither a mutable parent tree nor native resources. */
public record ThemeScope(ThemeValues values) {
  public ThemeScope {
    Objects.requireNonNull(values);
  }

  public static ThemeScope root(ThemePatch patch) {
    return new ThemeScope(ThemeValues.standard().resolve(Objects.requireNonNull(patch)));
  }

  public ThemeScope child(ThemePatch patch) {
    return new ThemeScope(values.resolve(Objects.requireNonNull(patch)));
  }
}
