package design.inherent.reform.presentation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete immutable semantic roles. Native bindings resolve these before measuring or drawing. */
public final class ThemeValues {
  public enum Role {
    SPACE_INLINE(FormTokens.Space.INLINE, 0),
    SPACE_TEXT(FormTokens.Space.TEXT, 0),
    SPACE_CONTENT(FormTokens.Space.CONTENT, 0),
    SPACE_FIELD(FormTokens.Space.FIELD, 0),
    SPACE_SECTION(FormTokens.Space.SECTION, 0),
    SPACE_RELATED(FormTokens.Space.RELATED, 0),
    SPACE_SCREEN(FormTokens.Space.SCREEN, 0),
    SPACE_NESTED_SECTION(FormTokens.Space.NESTED_SECTION, 0),
    CONTROL_HEIGHT(FormTokens.Control.HEIGHT, FormTokens.Control.HEIGHT),
    CONTROL_MINIMUM_WIDTH(FormTokens.Control.MINIMUM_WIDTH, FormTokens.Control.MINIMUM_WIDTH),
    CONTROL_CAPTION_PADDING(FormTokens.Control.CAPTION_PADDING, 0),
    CONTROL_NUMERIC_INPUT(FormTokens.Control.NUMERIC_INPUT, FormTokens.Control.MINIMUM_WIDTH),
    CONTROL_TEXT_INPUT(FormTokens.Control.TEXT_INPUT, FormTokens.Control.MINIMUM_WIDTH),
    CONTROL_SLIDER_PREFERRED(FormTokens.Control.SLIDER_PREFERRED, FormTokens.Control.MINIMUM_WIDTH),
    CONTROL_CHOICE_INDICATOR(FormTokens.Control.CHOICE_INDICATOR, 1),
    CONTROL_STEPPER(FormTokens.Control.STEPPER, FormTokens.Control.HEIGHT),
    CONTROL_INFORMATION(FormTokens.Control.INFORMATION, FormTokens.Control.HEIGHT),
    CONTROL_UNIT_MINIMUM(FormTokens.Control.UNIT_MINIMUM, FormTokens.Control.HEIGHT),
    CONTROL_UNIT_PREFERRED_LIMIT(
        FormTokens.Control.UNIT_PREFERRED_LIMIT, FormTokens.Control.HEIGHT),
    MEASURE_READING_COLUMNS(FormTokens.Measure.READING_COLUMNS, 1),
    MEASURE_TITLE_MINIMUM_HEIGHT(FormTokens.Measure.TITLE_MINIMUM_HEIGHT, 1),
    MEASURE_STATUS_MINIMUM_HEIGHT(FormTokens.Measure.STATUS_MINIMUM_HEIGHT, 1),
    MEASURE_TEXT_MINIMUM(FormTokens.Measure.TEXT_MINIMUM, 1),
    MEASURE_TEXT_PREFERRED_LIMIT(FormTokens.Measure.TEXT_PREFERRED_LIMIT, 1),
    MEASURE_LABEL_PREFERRED_LIMIT(FormTokens.Measure.LABEL_PREFERRED_LIMIT, 1),
    MEASURE_NAVIGATION_MINIMUM(FormTokens.Measure.NAVIGATION_MINIMUM, 1),
    MEASURE_EDITOR_MINIMUM(FormTokens.Measure.EDITOR_MINIMUM, 1),
    MEASURE_INFORMATION_MINIMUM(FormTokens.Measure.INFORMATION_MINIMUM, 1),
    MEASURE_INFORMATION_PREFERRED(FormTokens.Measure.INFORMATION_PREFERRED, 1),
    MEASURE_CONTENT_MINIMUM_HEIGHT(FormTokens.Measure.CONTENT_MINIMUM_HEIGHT, 1),
    SURFACE_FORM(FormTokens.Color.SURFACE_FORM),
    SURFACE_NAVIGATION(FormTokens.Color.SURFACE_NAVIGATION),
    SURFACE_SELECTED(FormTokens.Color.SURFACE_SELECTED),
    SURFACE_HOVER(FormTokens.Color.SURFACE_HOVER),
    LINE(FormTokens.Color.LINE),
    LINE_SELECTED(FormTokens.Color.LINE_SELECTED),
    FOCUS(FormTokens.Color.FOCUS),
    TEXT(0xff000000 | FormTokens.Color.TEXT),
    TEXT_SUPPORTING(0xff000000 | FormTokens.Color.TEXT_SUPPORTING),
    TEXT_ACTIVE(0xff000000 | FormTokens.Color.TEXT_ACTIVE),
    TEXT_ERROR(0xff000000 | FormTokens.Color.TEXT_ERROR);

    private final int initial;
    private final Integer minimum;

    Role(int initial, int minimum) {
      this.initial = initial;
      this.minimum = minimum;
    }

    Role(int argb) {
      initial = argb;
      minimum = null;
    }

    public int libraryDefault() {
      return initial;
    }

    public boolean color() {
      return minimum == null;
    }

    void require(int value) {
      if (minimum != null && value < minimum)
        throw new IllegalArgumentException("Theme role " + this + " requires at least " + minimum);
    }
  }

  private static final ThemeValues STANDARD;

  static {
    var values = new EnumMap<Role, Integer>(Role.class);
    for (var role : Role.values()) values.put(role, role.libraryDefault());
    STANDARD = new ThemeValues(values);
  }

  private final Map<Role, Integer> values;

  private ThemeValues(Map<Role, Integer> values) {
    this.values = Map.copyOf(values);
    if (values.size() != Role.values().length)
      throw new IllegalArgumentException("A resolved theme requires every semantic role");
    values.forEach(Role::require);
  }

  public static ThemeValues standard() {
    return STANDARD;
  }

  public int value(Role role) {
    return values.get(Objects.requireNonNull(role));
  }

  ThemeValues resolve(ThemePatch patch) {
    var resolved = new EnumMap<Role, Integer>(Role.class);
    resolved.putAll(values);
    for (var change : patch.changes()) {
      switch (change.mode()) {
        case SET -> resolved.put(change.role(), change.value().orElseThrow());
        case LIBRARY_DEFAULT -> resolved.put(change.role(), change.role().libraryDefault());
        case INHERIT -> {} // This scope's parent value is already in the complete map.
      }
    }
    return new ThemeValues(resolved);
  }
}
