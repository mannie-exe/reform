package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.*;
import design.inherent.reform.value.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual file-backed actor and aggregate kernel; this does not claim native widget or paint
 * behavior.
 */
class ClientSectionPartsTest {
  @TempDir Path directory;

  record Values(String first, boolean second) {}

  private static final FieldPath FIRST = FieldPath.ROOT.field("first"),
      SECOND = FieldPath.ROOT.field("second");
  private static final ValueType<String> TEXT = ValueTypes.text(64);
  private static final ValueType<Boolean> BOOLEAN = ValueTypes.bool();
  private static final FieldKey<String> FIRST_KEY = FieldKey.of("first", TEXT);
  private static final FieldKey<Boolean> SECOND_KEY = FieldKey.of("second", BOOLEAN);
  private static final ValueType<Values> TYPE =
      RecordType.<Values>builder(
              values -> new Values(values.get(FIRST_KEY), values.get(SECOND_KEY)))
          .field(FIRST_KEY, Values::first)
          .field(SECOND_KEY, Values::second)
          .build();
  private static final DocumentDefinition<Values> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("test:section"), 1, TYPE, () -> new Values("first", true));
  private static final FormTarget.Section GROUP = new FormTarget.Section("group:choices");
  private static final FormPresentation PRESENTATION =
      FormPresentation.EMPTY.withLayout(
          new FormLayout(
              List.of(),
              List.of(
                  new FormLayout.Group(
                      "choices", "test.choices", "Choices", "", List.of(FIRST, SECOND)))));

  @Test
  void canonicalFamiliesRetainOwnerIdentityOrderAndRefundTheirScope() {
    try (var h = new Host(directory)) {
      var before = DraftRevision.of(h.actions.view().snapshot().orElseThrow());
      var usage = h.scope.usage();
      var retained = new AtomicReference<ClientSectionParts<Values>>();
      try (var tree =
          MountedUiTree.section(
              h.scope,
              (scope, parts) -> {
                retained.set(parts);
                assertEquals(List.of(FIRST, SECOND), parts.fields());
                assertSame(
                    h.actions.definition(),
                    parts.field(FIRST, TEXT).binding().identity().definition());
                assertThrows(
                    IllegalArgumentException.class, () -> parts.field(FIRST, ValueTypes.text(64)));
                assertThrows(
                    IllegalArgumentException.class, () -> parts.field(FieldPath.ROOT, TEXT));
                assertTrue(parts.editor().isEmpty());
                assertEquals(
                    "ui.preview_host_unavailable",
                    assertThrows(
                            PolicyRefusal.class,
                            () ->
                                parts.graphics(
                                    new design.inherent.reform.presentation.graphics.GraphicLimits(
                                        65536, 1048576, 65536, 16, 4194304),
                                    1))
                        .report()
                        .issues()
                        .getFirst()
                        .code());
                return Views.column(
                    "section",
                    List.of(
                        parts.field(FIRST, TEXT).standard("first"),
                        parts.field(SECOND, BOOLEAN).standard("second")));
              },
              h::parts)) {
        assertTrue(tree.failure().isEmpty());
        assertEquals(
            List.of("First", "Second"),
            tree.snapshot().stream()
                .filter(
                    r ->
                        r.node().spec instanceof ViewSpec.FieldPart part
                            && part.role() == ClientFieldParts.Role.LABEL)
                .map(r -> r.label().getString())
                .filter(s -> !s.isEmpty())
                .toList());
        assertEquals(before, DraftRevision.of(h.actions.view().snapshot().orElseThrow()));
      }
      assertEquals(usage, h.scope.usage());
      assertTrue(h.actions.isOpen());
      assertThrows(PolicyRefusal.class, () -> retained.get().fields());
    }
  }

  @Test
  void missingDuplicatedAndForeignFamiliesRefuseWithNoPartialScopeOrActorRetirement() {
    try (var h = new Host(directory)) {
      var usage = h.scope.usage();
      var missing =
          assertThrows(
              PolicyRefusal.class,
              () ->
                  MountedUiTree.section(
                      h.scope,
                      (scope, parts) -> parts.field(FIRST, TEXT).standard("only"),
                      h::parts));
      assertEquals("ui.missing_field_part", missing.report().issues().getFirst().code());
      assertEquals(usage, h.scope.usage());
      var duplicate =
          assertThrows(
              PolicyRefusal.class,
              () ->
                  MountedUiTree.section(
                      h.scope,
                      (scope, parts) ->
                          Views.column(
                              "both",
                              List.of(
                                  parts.field(FIRST, TEXT).standard("first"),
                                  parts.field(SECOND, BOOLEAN).standard("second"),
                                  parts.field(FIRST, TEXT).label("again"))),
                      h::parts));
      assertEquals("ui.duplicate_field_part", duplicate.report().issues().getFirst().code());
      assertEquals(usage, h.scope.usage());
      var foreign = h.field(h.scope, FIRST, TEXT, "Foreign");
      var foreignUsage = h.scope.usage();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              MountedUiTree.section(
                  h.scope,
                  (scope, parts) ->
                      Views.text("wrong", Component.literal("Wrong"), Component.empty()),
                  scope ->
                      new ClientSectionParts<>(
                          scope, GROUP, Map.of(FIRST, foreign), Optional.empty(), null)));
      assertEquals(foreignUsage, h.scope.usage());
      assertTrue(h.actions.isOpen());
    }
  }

  @Test
  void perFieldNativeSlotsRemainDistinctInsideOneRequiredSection() {
    try (var h = new Host(directory);
        var tree =
            MountedUiTree.section(
                h.scope,
                (scope, parts) -> {
                  var closed =
                      scope.local(
                          new ViewStateKey<>(DocumentId.parse("test:closed"), BOOLEAN), false);
                  var open =
                      scope.local(new ViewStateKey<>(DocumentId.parse("test:open"), BOOLEAN), true);
                  var first = parts.field(FIRST, TEXT);
                  var second = parts.field(SECOND, BOOLEAN);
                  return Views.column(
                      "parts",
                      List.of(
                          first.label("first-label"),
                          first.controlSurface(
                              "first-control",
                              Views.text(
                                  "replacement",
                                  Component.literal("First choices"),
                                  Component.empty()),
                              UiBindings.local(closed),
                              UiText.of(Component.literal("First ID"))),
                          first.feedback("first-feedback"),
                          second.label("second-label"),
                          second.controlSurface(
                              "second-control",
                              Views.text(
                                  "replacement",
                                  Component.literal("Second choices"),
                                  Component.empty()),
                              UiBindings.local(open),
                              UiText.of(Component.literal("Second native"))),
                          second.feedback("second-feedback")));
                },
                h::parts)) {
      var fields =
          tree.snapshot().stream().filter(r -> r.kind() == MountedUiTree.Kind.FIELD).toList();
      assertEquals(1, fields.size());
      assertEquals(
          SECOND,
          ((ViewSpec.NativeFieldInput) fields.getFirst().node().spec)
              .surface()
              .field()
              .binding()
              .identity()
              .path());
      assertTrue(h.actions.isOpen());
    }
  }

  @Test
  void declarationsSurviveOptionCopiesAndRejectCompetingOrUnknownFields() {
    ClientSectionComponent<Values> component =
        (scope, parts) -> parts.field(FIRST, TEXT).standard("first");
    var options =
        new ClientFormOptions<Values>(PRESENTATION, Map.of())
            .withSection(GROUP, component)
            .withDefaults(Component.literal("Defaults"), Component.literal("Help"))
            .withInformation(definition -> Optional.empty())
            .withEntry(FIRST, ClientEntryPresentation.EMPTY);
    assertSame(component, options.sections().get(GROUP));
    assertThrows(IllegalArgumentException.class, () -> options.withSection(GROUP, component));
    assertThrows(
        IllegalArgumentException.class,
        () -> options.withSection(new FormTarget.Section("group:absent"), component));
    assertThrows(
        IllegalArgumentException.class,
        () -> options.withComponent(FIRST, (scope, parts) -> parts.standard("first")));
    assertThrows(
        IllegalArgumentException.class, () -> options.withControl(SECOND, binding -> null));
  }

  private static final class Host implements AutoCloseable {
    final OwnerContext context =
        new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD);
    final DocumentActions<Values> actions;
    final UiScope scope;

    Host(Path directory) {
      var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
      var store =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(StorageBinding.relative(directory, "section.json"), 8192, 4096),
              JsonStore.Format.NEW_ENVELOPE,
              json,
              DraftLimits.CONFIGURATION);
      var owner = new FileOwner<>(context, store, 16);
      var access = context.access(() -> true, () -> true);
      var session = owner.open(access);
      session.initialize().toCompletableFuture().join();
      var actor =
          new EditorActor<>(context, session, DEFINITION, json, DraftLimits.CONFIGURATION, 32);
      actions =
          new DocumentActions<>(context, access, actor, OperationScope.Limits.STANDARD, 262144);
      DocumentActions.result(actions.ensureLoaded(OperationDescriptor.EntryPoint.GUI))
          .toCompletableFuture()
          .join();
      scope = UiScope.borrow(context, UiLimits.STANDARD);
    }

    <V> ClientFieldParts<V> field(UiScope scope, FieldPath path, ValueType<V> type, String label) {
      return new ClientFieldParts<>(
          scope,
          FieldBinding.bind(scope, actions, path, type),
          UiText.of(Component.literal(label)),
          UiText.of(Component.empty()),
          List::of,
          Optional::empty);
    }

    ClientSectionParts<Values> parts(UiScope scope) {
      var fields = new LinkedHashMap<FieldPath, ClientFieldParts<?>>();
      fields.put(FIRST, field(scope, FIRST, TEXT, "First"));
      fields.put(SECOND, field(scope, SECOND, BOOLEAN, "Second"));
      return new ClientSectionParts<>(scope, GROUP, fields, Optional.empty(), null);
    }

    public void close() {
      actions.close();
      context.close();
    }
  }
}
