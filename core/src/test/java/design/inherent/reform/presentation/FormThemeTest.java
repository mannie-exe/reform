package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.value.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FormThemeTest {
  private static final FieldPath A = FieldPath.ROOT.field("enabled");
  private static final FieldPath B = FieldPath.ROOT.field("title");
  private static final ThemePatch PATCH = ThemePatch.empty().set(ThemeValues.Role.SPACE_FIELD, 17);

  @Test
  void declarationsBindOnlyMetadataAndLeaveCodecOrderAndValuesAlone() {
    var enabled = FieldKey.of("enabled", ValueTypes.bool());
    var title = FieldKey.of("title", ValueTypes.text(32));
    var type =
        RecordType.<String>builder(
                v -> {
                  throw new AssertionError("Constructed a value");
                })
            .field(enabled, v -> true)
            .field(title, v -> "untouched")
            .build();
    var group = new FormLayout.Group("general", "test.general", "General", "", List.of(B, A));
    var theme = FormTheme.EMPTY.withRoot(PATCH).withGroup("general", PATCH).withField(A, PATCH);
    var presentation =
        FormPresentation.EMPTY
            .withLayout(new FormLayout(List.of(), List.of(group)))
            .withTheme(theme);
    assertDoesNotThrow(() -> presentation.bind(type));
    assertSame(theme, presentation.withCatalog(new FormCatalog(List.of())).theme());
    assertSame(theme, presentation.withUnavailableFields(List.of()).theme());
    assertEquals(
        List.of("enabled", "title"),
        ((ValueShape.ObjectValue) type.shape()).fields().stream().map(FieldKey::id).toList());
    assertEquals(PATCH, theme.section("group:general").orElseThrow().patch());
    assertTrue(theme.section("range:general").isEmpty());
    assertTrue(FormTheme.EMPTY.fields().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> theme.fields().clear());
  }

  @Test
  void duplicateMissingAndIncompatibleScopesRefuseWithoutLastWriterWins() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FormTheme.EMPTY.withField(A, PATCH).withField(A, PATCH));
    assertThrows(
        IllegalArgumentException.class, () -> FormTheme.EMPTY.withField(FieldPath.ROOT, PATCH));
    assertThrows(
        IllegalArgumentException.class,
        () -> FormPresentation.EMPTY.withTheme(FormTheme.EMPTY.withGroup("absent", PATCH)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormTheme.Section(
                FormTheme.SectionKind.GROUP, "bad space", PATCH, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormTheme.Section(
                FormTheme.SectionKind.GROUP,
                "general",
                PATCH,
                Optional.of(ComponentVariant.standard(ComponentVariant.Kind.ROW))));
    var enabled = FieldKey.of("enabled", ValueTypes.bool());
    var type = RecordType.<Boolean>builder(v -> v.get(enabled)).field(enabled, v -> v).build();
    var titleReference =
        new FormTheme.Field(
            A,
            PATCH,
            Optional.of(ComponentVariant.standard(ComponentVariant.Kind.TITLE_REFERENCE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FormPresentation.EMPTY.withTheme(FormTheme.EMPTY.withField(titleReference)).bind(type));
    assertThrows(
        IllegalArgumentException.class,
        () -> FormPresentation.EMPTY.withTheme(FormTheme.EMPTY.withField(B, PATCH)).bind(type));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormTheme.Field(
                A,
                PATCH,
                Optional.of(ComponentVariant.standard(ComponentVariant.Kind.DESTINATION_CHOICE))));
  }
}
