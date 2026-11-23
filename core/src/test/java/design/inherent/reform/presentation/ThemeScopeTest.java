package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ThemeScopeTest {
  @Test
  void parentChildInheritAndLibraryDefaultAreDifferentAndLeaveTheParentUnchanged() {
    var parent = ThemeScope.root(ThemePatch.empty().set(ThemeValues.Role.SPACE_CONTENT, 18));
    var inherited = parent.child(ThemePatch.empty().inherit(ThemeValues.Role.SPACE_CONTENT));
    var reset = parent.child(ThemePatch.empty().libraryDefault(ThemeValues.Role.SPACE_CONTENT));
    var changed = parent.child(ThemePatch.empty().set(ThemeValues.Role.SPACE_CONTENT, 24));
    assertEquals(18, inherited.values().value(ThemeValues.Role.SPACE_CONTENT));
    assertEquals(FormTokens.Space.CONTENT, reset.values().value(ThemeValues.Role.SPACE_CONTENT));
    assertEquals(24, changed.values().value(ThemeValues.Role.SPACE_CONTENT));
    assertEquals(18, parent.values().value(ThemeValues.Role.SPACE_CONTENT));
    assertEquals(
        FormTokens.Space.CONTENT, ThemeValues.standard().value(ThemeValues.Role.SPACE_CONTENT));
  }

  @Test
  void equalDefaultValuesDoNotTieUnrelatedSemanticRolesTogether() {
    assertEquals(FormTokens.Space.SECTION, FormTokens.Space.SCREEN);
    var scope = ThemeScope.root(ThemePatch.empty().set(ThemeValues.Role.SPACE_SECTION, 30));
    assertEquals(30, scope.values().value(ThemeValues.Role.SPACE_SECTION));
    assertEquals(FormTokens.Space.SCREEN, scope.values().value(ThemeValues.Role.SPACE_SCREEN));
  }

  @Test
  void nativeControlMinimaRefuseInsteadOfClampingAndColorsKeepExplicitAlpha() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ThemePatch.empty().set(ThemeValues.Role.CONTROL_HEIGHT, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> ThemePatch.empty().set(ThemeValues.Role.SPACE_SCREEN, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> ThemePatch.empty().set(ThemeValues.Role.MEASURE_READING_COLUMNS, 0));
    var scope =
        ThemeScope.root(
            ThemePatch.empty()
                .set(ThemeValues.Role.SPACE_INLINE, 0)
                .set(ThemeValues.Role.TEXT, 0x00123456)
                .set(ThemeValues.Role.SURFACE_FORM, 0x80123456));
    assertEquals(0, scope.values().value(ThemeValues.Role.SPACE_INLINE));
    assertEquals(0x00123456, scope.values().value(ThemeValues.Role.TEXT));
    assertEquals(0x80123456, scope.values().value(ThemeValues.Role.SURFACE_FORM));
    assertEquals(0xffffffff, ThemeValues.standard().value(ThemeValues.Role.TEXT));
  }

  @Test
  void patchCopiesInputsAndRejectsDuplicateOrContradictoryOperations() {
    var entries = new ArrayList<ThemePatch.Change>();
    entries.add(
        new ThemePatch.Change(
            ThemeValues.Role.SPACE_CONTENT, ThemePatch.Mode.SET, OptionalInt.of(20)));
    var patch = new ThemePatch(entries);
    entries.clear();
    assertEquals(1, patch.changes().size());
    assertThrows(UnsupportedOperationException.class, () -> patch.changes().clear());
    assertThrows(
        IllegalArgumentException.class, () -> patch.libraryDefault(ThemeValues.Role.SPACE_CONTENT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThemePatch.Change(
                ThemeValues.Role.TEXT, ThemePatch.Mode.INHERIT, OptionalInt.of(0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThemePatch.Change(ThemeValues.Role.TEXT, ThemePatch.Mode.SET, OptionalInt.empty()));
    assertEquals(ThemePatch.empty(), new ThemePatch(List.of()));
  }
}
