package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FormCatalogTest {
  private record Profile(String name, Map<String, Integer> roles) {}

  private record Profiles(List<Profile> profiles, Optional<Integer> optional) {}

  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(64));
  private static final FieldKey<Map<String, Integer>> ROLES =
      FieldKey.of("roles", ValueTypes.objectMap(ValueTypes.text(64), ValueTypes.integer(0, 10), 8));
  private static final ValueType<Profile> PROFILE =
      RecordType.<Profile>builder(value -> new Profile(value.get(NAME), value.get(ROLES)))
          .field(NAME, Profile::name)
          .field(ROLES, Profile::roles)
          .build();
  private static final FieldKey<List<Profile>> LIST =
      FieldKey.of("profiles", ValueTypes.list(PROFILE, 8));
  private static final FieldKey<Optional<Integer>> OPTIONAL =
      FieldKey.of(
          "optional",
          ValueTypes.optional(
              ValueTypes.integer(0, 10), ValueShape.OptionalEncoding.SINGLETON_LIST));
  private static final ValueType<Profiles> TYPE =
      RecordType.<Profiles>builder(value -> new Profiles(value.get(LIST), value.get(OPTIONAL)))
          .field(LIST, Profiles::profiles)
          .field(OPTIONAL, Profiles::optional)
          .build();
  private static final FieldPath PROFILES = FieldPath.ROOT.field("profiles");
  private static final FormCatalog.Section SECTION =
      new FormCatalog.Section(PROFILES, "example.profiles", "Profiles", "Authored profiles");

  private static DraftTree draft() {
    return DraftTree.from(
        TYPE,
        new Profiles(
            List.of(
                new Profile("Island", Map.of("anchor", 3, "guard", 4)),
                new Profile("Marine", Map.of("vessel", 5))),
            Optional.empty()),
        DraftLimits.CONFIGURATION);
  }

  private static Optional<String> label(FormModel.Node node) {
    return node.type().filter(type -> type == PROFILE).isPresent()
        ? node.children().stream()
            .filter(child -> child.name().equals("name"))
            .map(FormModel.Node::input)
            .filter(DraftNode.Scalar.class::isInstance)
            .map(DraftNode.Scalar.class::cast)
            .map(DraftNode.Scalar::raw)
            .findFirst()
        : Optional.empty();
  }

  private static FormCatalog catalog() {
    return new FormCatalog(List.of(SECTION), FormCatalog.Limits.STANDARD, FormCatalogTest::label);
  }

  private static FieldPath member(DraftTree draft, int index) {
    return PROFILES.row(((DraftNode.Sequence) draft.at(PROFILES)).rows().get(index).id());
  }

  @Test
  void projectsActualRowsAndPartsWithGenericLabelsAndBreadcrumbs() {
    var draft = draft();
    var snapshot = catalog().snapshot(new FormModel(TYPE, draft));
    var profile = member(draft, 0);
    var roles = profile.field("roles");
    var role = roles.row(((DraftNode.Mapping) draft.at(roles)).rows().getFirst().id());
    assertEquals("Island", snapshot.entry(profile).orElseThrow().label());
    assertEquals(
        ((DraftNode.Scalar) draft.at(role.append(FieldPath.Part.KEY))).raw(),
        snapshot.entry(role).orElseThrow().label());
    assertEquals(
        List.of(FieldPath.ROOT, PROFILES, profile, roles, role),
        snapshot.breadcrumbs(role).stream().map(FormCatalog.Entry::path).toList());
    assertSame(draft.at(role), snapshot.entry(role).orElseThrow().node().orElseThrow().input());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
    assertTrue(snapshot.entry(profile.field("name")).isEmpty());
    assertEquals(profile, snapshot.nearest(profile.field("name")).path());
  }

  @Test
  void renameAndReorderNeverChangeSelectedMemberIdentityOrInvalidRawInput() {
    var draft = draft();
    var catalog = catalog();
    var row = member(draft, 0);
    var navigation = new FormCatalog.Navigation(catalog.snapshot(new FormModel(TYPE, draft)));
    navigation.select(row);
    var roles = row.field("roles");
    var role = roles.row(((DraftNode.Mapping) draft.at(roles)).rows().getFirst().id());
    var changed =
        draft
            .change(row.field("name"), ignored -> new DraftNode.Scalar("Renamed"))
            .change(role.append(FieldPath.Part.VALUE), ignored -> new DraftNode.Scalar("-"))
            .change(
                PROFILES,
                values ->
                    ((DraftNode.Sequence) values)
                        .move(((FieldPath.Row) row.segments().getLast()).id(), 1));
    var replacement = catalog.snapshot(new FormModel(TYPE, changed));
    navigation.update(replacement);
    assertEquals(row, navigation.selected().path());
    assertEquals("Renamed", navigation.selected().label());
    assertEquals(
        List.of(row), replacement.search("renamed").stream().map(FormCatalog.Entry::path).toList());
    assertEquals("-", ((DraftNode.Scalar) changed.at(role.append(FieldPath.Part.VALUE))).raw());
    replacement.search("no match");
    assertEquals(row, navigation.selected().path());
    assertSame(changed.at(row), navigation.selected().node().orElseThrow().input());
  }

  @Test
  void duplicateAuthoredMapKeysRetainSeparateRepairableRows() {
    var draft = draft();
    var roles = member(draft, 0).field("roles");
    var rows = ((DraftNode.Mapping) draft.at(roles)).rows();
    var first = roles.row(rows.getFirst().id());
    var second = roles.row(rows.get(1).id());
    var changed =
        draft.change(
            second.append(FieldPath.Part.KEY),
            ignored -> draft.at(first.append(FieldPath.Part.KEY)));
    String key = ((DraftNode.Scalar) changed.at(first.append(FieldPath.Part.KEY))).raw();
    var snapshot = catalog().snapshot(new FormModel(TYPE, changed));
    assertEquals(2, snapshot.search(key).size());
    assertNotEquals(snapshot.search(key).getFirst().path(), snapshot.search(key).get(1).path());
    assertEquals(2, ((DraftNode.Mapping) changed.at(roles)).rows().size());
  }

  @Test
  void removalFallsBackToAncestorAndUndoRestoresRequestedIdentity() {
    var draft = draft();
    var catalog = catalog();
    var original = catalog.snapshot(new FormModel(TYPE, draft));
    var row = member(draft, 0);
    var navigation = new FormCatalog.Navigation(original);
    navigation.select(PROFILES);
    navigation.select(row);
    var removed =
        draft.change(
            PROFILES,
            values ->
                ((DraftNode.Sequence) values)
                    .remove(((FieldPath.Row) row.segments().getLast()).id()));
    navigation.update(catalog.snapshot(new FormModel(TYPE, removed)));
    assertEquals(PROFILES, navigation.selected().path());
    assertEquals(row, navigation.requested());
    navigation.update(original);
    assertEquals(row, navigation.selected().path());
    navigation.back();
    assertEquals(PROFILES, navigation.selected().path());
    navigation.forward();
    assertEquals(row, navigation.selected().path());
  }

  @Test
  void historyIsBoundedAndNewNavigationRetiresOnlyForwardHistory() {
    var draft = draft();
    var catalog =
        new FormCatalog(
            List.of(SECTION),
            new FormCatalog.Limits(32, 1024, 4096, 32, 3),
            FormCatalogTest::label);
    var navigation = new FormCatalog.Navigation(catalog.snapshot(new FormModel(TYPE, draft)));
    navigation.select(PROFILES);
    navigation.select(member(draft, 0));
    navigation.select(member(draft, 0).field("roles"));
    navigation.back();
    navigation.back();
    assertFalse(navigation.canBack());
    assertEquals(PROFILES, navigation.selected().path());
    assertTrue(navigation.canForward());
    navigation.select(member(draft, 1));
    assertFalse(navigation.canForward());
    assertThrows(
        IllegalArgumentException.class, () -> navigation.select(PROFILES.row(UUID.randomUUID())));
    assertEquals(member(draft, 1), navigation.selected().path());
  }

  @Test
  void inactiveDeclaredSectionStaysDiscoverableWithoutInventingInput() {
    var path = FieldPath.ROOT.field("optional").append(FieldPath.Part.VALUE);
    var catalog =
        new FormCatalog(
            List.of(
                new FormCatalog.Section(
                    path, "example.optional", "Optional value", "An optional section")));
    var draft = draft();
    var snapshot = catalog.snapshot(new FormModel(TYPE, draft));
    assertFalse(snapshot.entry(path).orElseThrow().available());
    var navigation = new FormCatalog.Navigation(snapshot);
    navigation.select(path);
    assertEquals(path, navigation.selected().path());
    var present =
        draft.change(
            FieldPath.ROOT.field("optional"),
            node -> ((DraftNode.OptionalValue) node).withPresent(true));
    navigation.update(catalog.snapshot(new FormModel(TYPE, present)));
    assertTrue(navigation.selected().available());
    assertInstanceOf(DraftNode.Missing.class, navigation.selected().node().orElseThrow().input());
    navigation.update(snapshot);
    assertEquals(path, navigation.selected().path());
    assertFalse(navigation.selected().available());
  }

  @Test
  void admissionPrecedesExtraLabelCallbacksAndPublishesNoPartialCatalog() {
    var calls = new AtomicInteger();
    var small =
        new FormCatalog(
            List.of(SECTION),
            new FormCatalog.Limits(2, 32, 64, 8, 4),
            node -> {
              calls.incrementAndGet();
              return Optional.of("member");
            });
    assertThrows(
        IllegalArgumentException.class, () -> small.snapshot(new FormModel(TYPE, draft())));
    assertEquals(0, calls.get());
    assertThrows(IllegalArgumentException.class, () -> new FormCatalog(List.of(SECTION, SECTION)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FormCatalog.Section(PROFILES.row(UUID.randomUUID()), "key", "row", ""));
    var foreign =
        new FormCatalog(
            List.of(
                new FormCatalog.Section(FieldPath.ROOT.field("foreign"), "key", "Foreign", "")));
    assertThrows(IllegalArgumentException.class, () -> foreign.requireCompatible(TYPE));
  }

  @Test
  void localizedLabelsAreCompleteBoundedAndSearchUsesRootLocale() {
    var original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      var draft = draft();
      var snapshot = catalog().snapshot(new FormModel(TYPE, draft));
      var localized =
          snapshot.relabel(
              entry -> entry.label().equals("Island") ? "ISLAND locale" : entry.label());
      assertEquals(1, localized.search("island locale").size());
      assertTrue(snapshot.search("locale").isEmpty());
      assertThrows(IllegalArgumentException.class, () -> localized.search("x".repeat(257)));
      assertThrows(
          IllegalArgumentException.class, () -> snapshot.relabel(entry -> "x".repeat(32769)));
      assertEquals("Island", snapshot.entry(member(draft, 0)).orElseThrow().label());
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void projectionSearchAndMetadataDoNotDecodeOrConstructValues() {
    var calls = new AtomicInteger();
    var type =
        ValueType.of(
            Codec.STRING.xmap(
                value -> {
                  calls.incrementAndGet();
                  return value;
                },
                value -> {
                  calls.incrementAndGet();
                  return value;
                }),
            ValueTypes.text(32).shape(),
            SnapshotPolicy.immutable());
    var catalog =
        new FormCatalog(
            List.of(new FormCatalog.Section(FieldPath.ROOT, "example.value", "Value", "")));
    catalog.requireCompatible(type);
    var draft = new DraftTree(new DraftNode.Scalar("-"), DraftLimits.CONFIGURATION);
    var snapshot = catalog.snapshot(new FormModel(type, draft));
    for (int i = 0; i < 30; i++) snapshot.search("v");
    assertEquals(0, calls.get());
    assertSame(
        draft.root(), snapshot.entry(FieldPath.ROOT).orElseThrow().node().orElseThrow().input());
    assertEquals(1, FormCatalog.NONE.snapshot(new FormModel(type, draft)).entries().size());
    assertFalse(FormCatalog.NONE.enabled());
  }

  @Test
  void failedRelabelDoesNotReplaceNavigationAndLimitsStayBoundToItsLifetime() {
    var snapshot = catalog().snapshot(new FormModel(TYPE, draft()));
    var navigation = new FormCatalog.Navigation(snapshot);
    navigation.select(PROFILES);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            snapshot.relabel(
                entry -> {
                  throw new IllegalArgumentException("unavailable labels");
                }));
    assertEquals(PROFILES, navigation.selected().path());
    var small =
        new FormCatalog(
            List.of(SECTION),
            new FormCatalog.Limits(64, 1024, 16384, 32, 4),
            FormCatalogTest::label);
    assertThrows(
        IllegalArgumentException.class,
        () -> navigation.update(small.snapshot(new FormModel(TYPE, draft()))));
    assertEquals(PROFILES, navigation.selected().path());
    var presentation = FormPresentation.EMPTY.withCatalog(catalog());
    presentation.requireCompatible(TYPE);
    assertTrue(presentation.catalog().enabled());
    assertFalse(FormPresentation.EMPTY.catalog().enabled());
  }
}
