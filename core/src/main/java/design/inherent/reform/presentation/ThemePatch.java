package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Explicit local role overrides; duplicate operations refuse rather than silently winning. */
public record ThemePatch(List<Change> changes) {
  public enum Mode {
    SET,
    INHERIT,
    LIBRARY_DEFAULT
  }

  public record Change(ThemeValues.Role role, Mode mode, OptionalInt value) {
    public Change {
      Objects.requireNonNull(role);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(value);
      if ((mode == Mode.SET) != value.isPresent())
        throw new IllegalArgumentException("Only a theme Set operation carries a value");
      if (value.isPresent()) role.require(value.getAsInt());
    }
  }

  public ThemePatch {
    changes = List.copyOf(changes);
    var roles = new HashSet<ThemeValues.Role>();
    for (var change : changes)
      if (!roles.add(change.role()))
        throw new IllegalArgumentException("Duplicate theme operation for " + change.role());
  }

  public static ThemePatch empty() {
    return new ThemePatch(List.of());
  }

  public ThemePatch set(ThemeValues.Role role, int value) {
    return append(new Change(role, Mode.SET, OptionalInt.of(value)));
  }

  public ThemePatch inherit(ThemeValues.Role role) {
    return append(new Change(role, Mode.INHERIT, OptionalInt.empty()));
  }

  public ThemePatch libraryDefault(ThemeValues.Role role) {
    return append(new Change(role, Mode.LIBRARY_DEFAULT, OptionalInt.empty()));
  }

  private ThemePatch append(Change change) {
    var next = new ArrayList<>(changes);
    next.add(change);
    return new ThemePatch(next);
  }
}
