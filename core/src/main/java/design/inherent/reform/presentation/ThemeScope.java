package design.inherent.reform.presentation;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** A resolved value scope; it retains neither a mutable parent tree nor native resources. */
public record ThemeScope(
    ThemeValues values, Set<ThemeValues.Role> specified, Set<ThemeValues.Role> bound) {
  public ThemeScope {
    Objects.requireNonNull(values);
    specified = Set.copyOf(specified);
    bound = Set.copyOf(bound);
    if (!bound.containsAll(specified))
      throw new IllegalArgumentException("Authored roles require native bindings");
  }

  /** An explicitly supplied complete value is authoritative for each of its roles. */
  public ThemeScope(ThemeValues values) {
    this(values, EnumSet.allOf(ThemeValues.Role.class), EnumSet.allOf(ThemeValues.Role.class));
  }

  public static ThemeScope root(ThemePatch patch) {
    return new ThemeScope(ThemeValues.standard(), Set.of(), Set.of())
        .child(Objects.requireNonNull(patch));
  }

  public ThemeScope child(ThemePatch patch) {
    Objects.requireNonNull(patch);
    if (patch.changes().isEmpty()) return this;
    var authored = EnumSet.noneOf(ThemeValues.Role.class);
    authored.addAll(specified);
    for (var change : patch.changes())
      if (change.mode() != ThemePatch.Mode.INHERIT) authored.add(change.role());
    var bindings = EnumSet.noneOf(ThemeValues.Role.class);
    bindings.addAll(bound);
    bindings.addAll(authored);
    return new ThemeScope(values.resolve(patch), authored, bindings);
  }

  /** Component defaults fill unspecified roles without resetting semantic author choices. */
  public ThemeScope componentDefaults(ThemePatch defaults) {
    Objects.requireNonNull(defaults);
    if (defaults.changes().isEmpty()) return this;
    var admitted =
        defaults.changes().stream().filter(change -> !specified.contains(change.role())).toList();
    var bindings = EnumSet.noneOf(ThemeValues.Role.class);
    bindings.addAll(bound);
    for (var change : admitted)
      if (change.mode() != ThemePatch.Mode.INHERIT) bindings.add(change.role());
    return new ThemeScope(values.resolve(new ThemePatch(admitted)), specified, bindings);
  }
}
