package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.minecraft.client.ui.UiText;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.ui.FieldBinding;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual field owner/aggregate kernel, with no native widget/paint claim. */
class ClientFieldPartsTest {
  @TempDir Path directory;

  @Test
  void surfaceKeepsOneSemanticControlAndLazilyRevealsTheOriginalInput() {
    try (var h = new Host(directory)) {
      var expanded = new AtomicReference<design.inherent.reform.presentation.ui.UiLocal<Boolean>>();
      var baseline = h.scope.usage();
      try (var tree =
          MountedUiTree.field(
              h.scope,
              (scope, parts) -> {
                var open =
                    scope.local(
                        new design.inherent.reform.presentation.ViewStateKey<>(
                            DocumentId.parse("example:native_open"), ValueTypes.bool()),
                        false);
                expanded.set(open);
                return Views.column(
                    "field",
                    List.of(
                        parts.label("label"),
                        parts.controlSurface(
                            "control",
                            Views.text(
                                "replacement", Component.literal("Choices"), Component.empty()),
                            design.inherent.reform.presentation.ui.UiBindings.local(open),
                            UiText.of(Component.literal("ID"))),
                        parts.feedback("feedback")));
              },
              h::parts)) {
        assertTrue(tree.failure().isEmpty());
        assertEquals(
            1,
            tree.snapshot().stream()
                .filter(row -> row.kind() == MountedUiTree.Kind.SURFACE)
                .count());
        assertEquals(
            0,
            tree.snapshot().stream().filter(row -> row.kind() == MountedUiTree.Kind.FIELD).count());
        expanded.get().replace(true);
        tree.refresh(true);
        assertTrue(tree.failure().isEmpty());
        assertEquals(
            1,
            tree.snapshot().stream().filter(row -> row.kind() == MountedUiTree.Kind.FIELD).count());
        expanded.get().replace(false);
        tree.refresh(true);
        assertEquals(
            0,
            tree.snapshot().stream().filter(row -> row.kind() == MountedUiTree.Kind.FIELD).count());
        assertFalse(h.actions.view().dirty());
      }
      assertEquals(baseline, h.scope.usage());
      assertTrue(h.actions.isOpen());
    }
  }

  @Test
  void completionRequiresTheSameVisibleNativeSlotAndDoesNotAdmitItsSourceDuringProjection() {
    try (var h = new Host(directory, true)) {
      var baseline = h.scope.usage();
      for (int invalid = 0; invalid < 3; invalid++) {
        final int mode = invalid;
        java.util.function.Supplier<MountedUiTree> mount =
            () ->
                MountedUiTree.field(
                    h.scope,
                    (scope, parts) -> {
                      var picker =
                          design.inherent.reform.presentation.ui.ReferencePicker.open(
                              scope,
                              h.actions,
                              DocumentId.parse("example:choices"),
                              () -> {
                                throw new AssertionError(
                                    "Collapsed/projected suggestions must not admit a source");
                              },
                              List.of(
                                  new design.inherent.reform.presentation.ui.PickerRole<String>(
                                      "value", FieldPath.ROOT)),
                              Map.of(),
                              design.inherent.reform.presentation.ui.ReferencePicker.Policy
                                  .STANDARD,
                              () -> 0L);
                      var completion =
                          new design.inherent.reform.minecraft.client.ui.CompletionParts(
                              picker.suggestions(
                                  design.inherent.reform.presentation.ui.ReferenceSuggestions.Policy
                                      .STANDARD),
                              UiText.of(Component.literal("Suggested IDs")),
                              4);
                      var open =
                          scope.local(
                              new design.inherent.reform.presentation.ViewStateKey<>(
                                  DocumentId.parse("example:completion_open"), ValueTypes.bool()),
                              mode == 0);
                      var surface =
                          (design.inherent.reform.minecraft.client.ui.ViewSpec.FieldSurface)
                              parts.controlSurface(
                                  "surface",
                                  Views.text("choices", Component.empty(), Component.empty()),
                                  design.inherent.reform.presentation.ui.UiBindings.local(open),
                                  UiText.of(Component.literal("Edit ID")),
                                  completion);
                      var orphan =
                          new design.inherent.reform.minecraft.client.ui.ViewSpec.FieldCompletion(
                              "orphan", surface.surface(), completion);
                      return Views.column(
                          "field",
                          List.of(
                              parts.label("label"),
                              mode == 2 ? orphan : surface,
                              mode == 1
                                  ? orphan
                                  : Views.text("empty", Component.empty(), Component.empty()),
                              parts.feedback("feedback")));
                    },
                    h::parts);
        if (mode == 0) {
          try (var tree = mount.get()) {
            assertTrue(tree.failure().isEmpty());
            assertEquals(
                1,
                tree.snapshot().stream()
                    .filter(row -> row.kind() == MountedUiTree.Kind.COMPLETION)
                    .count());
            assertEquals(
                1,
                tree.snapshot().stream()
                    .filter(row -> row.kind() == MountedUiTree.Kind.FIELD)
                    .count());
          }
        } else
          assertEquals(
              "ui.foreign_field_part",
              assertThrows(PolicyRefusal.class, mount::get).report().issues().getFirst().code());
        assertEquals(baseline, h.scope.usage());
        assertFalse(h.actions.view().dirty());
      }
    }
  }

  @Test
  void duplicateSurfaceOrOrphanNativeInputCannotReplaceTheRequiredFieldFamily() {
    try (var h = new Host(directory)) {
      var baseline = h.scope.usage();
      for (boolean orphan : List.of(false, true)) {
        var failure =
            assertThrows(
                PolicyRefusal.class,
                () ->
                    MountedUiTree.field(
                        h.scope,
                        (scope, parts) -> {
                          var open =
                              scope.local(
                                  new design.inherent.reform.presentation.ViewStateKey<>(
                                      DocumentId.parse("example:native_open"), ValueTypes.bool()),
                                  false);
                          var surface =
                              (design.inherent.reform.minecraft.client.ui.ViewSpec.FieldSurface)
                                  parts.controlSurface(
                                      "surface",
                                      Views.text("empty", Component.empty(), Component.empty()),
                                      design.inherent.reform.presentation.ui.UiBindings.local(open),
                                      UiText.of(Component.literal("ID")));
                          return Views.column(
                              "field",
                              List.of(
                                  parts.label("label"),
                                  orphan
                                      ? new design.inherent.reform.minecraft.client.ui.ViewSpec
                                          .NativeFieldInput("orphan", surface.surface())
                                      : surface,
                                  parts.control("duplicate"),
                                  parts.feedback("feedback")));
                        },
                        h::parts));
        assertEquals(
            orphan ? "ui.foreign_field_part" : "ui.duplicate_field_part",
            failure.report().issues().getFirst().code());
        assertEquals(baseline, h.scope.usage());
      }
    }
  }

  @Test
  void missingDuplicateAndForeignPartsRefuseBeforeAttachmentAndRefundChildAdmission() {
    try (var h = new Host(directory)) {
      var baseline = h.scope.usage();
      for (boolean duplicate : List.of(false, true)) {
        var failure =
            assertThrows(
                PolicyRefusal.class,
                () ->
                    MountedUiTree.field(
                        h.scope,
                        (scope, parts) ->
                            duplicate
                                ? Views.column(
                                    "bad",
                                    List.of(
                                        parts.label("label"),
                                        parts.control("one"),
                                        parts.control("two"),
                                        parts.feedback("error")))
                                : Views.text(
                                    "unrelated",
                                    Component.literal("No control"),
                                    Component.empty()),
                        h::parts));
        assertEquals(
            duplicate ? "ui.duplicate_field_part" : "ui.missing_field_part",
            failure.report().issues().getFirst().code());
        assertEquals(baseline, h.scope.usage());
        assertTrue(h.actions.isOpen());
      }
      var original = new AtomicReference<ClientFieldParts<?>>();
      try (var first =
          MountedUiTree.field(
              h.scope,
              (scope, parts) -> {
                original.set(parts);
                return parts.standard("root");
              },
              h::parts)) {
        var before = h.scope.usage();
        var failure =
            assertThrows(
                PolicyRefusal.class,
                () ->
                    MountedUiTree.field(
                        h.scope,
                        (scope, parts) ->
                            Views.column(
                                "foreign",
                                List.of(
                                    original.get().label("label"),
                                    parts.control("control"),
                                    parts.feedback("error"))),
                        h::parts));
        assertEquals("ui.foreign_field_part", failure.report().issues().getFirst().code());
        assertEquals(before, h.scope.usage());
        first.refresh(true);
        assertTrue(first.failure().isEmpty());
      }
      assertEquals(baseline, h.scope.usage());
      assertTrue(h.actions.isOpen());
    }
  }

  @Test
  void actualBindingIsSharedAndClosingPartsDoesNotCloseItsOwner() throws Exception {
    try (var h = new Host(directory)) {
      var reference = new AtomicReference<ClientFieldParts<?>>();
      var before = java.nio.file.Files.readAllBytes(h.path);
      try (var tree =
          MountedUiTree.field(
              h.scope,
              (scope, parts) -> {
                reference.set(parts);
                return parts.standard("root");
              },
              h::parts)) {
        var parts = reference.get();
        assertSame(h.actions.definition(), parts.binding().identity().definition());
        h.actions
            .edit(
                EntryPoint.GUI,
                new design.inherent.reform.edit.DraftEdit(
                    h.actions.view().snapshot().orElseThrow().target(FieldPath.ROOT),
                    new design.inherent.reform.edit.DraftEdit.SetRaw("changed")))
            .completion()
            .toCompletableFuture()
            .join();
        tree.refresh(false);
        assertEquals("changed", parts.state().snapshot().value().value().orElseThrow());
        assertFalse(tree.snapshot().isEmpty());
      }
      assertTrue(h.actions.isOpen());
      assertThrows(PolicyRefusal.class, () -> reference.get().state().snapshot());
      assertArrayEquals(before, java.nio.file.Files.readAllBytes(h.path));
    }
  }

  @Test
  void independentlyMountedFieldTreesCannotCollideInOneNativePanel() {
    try (var h = new Host(directory);
        var first =
            MountedUiTree.field(h.scope, (scope, parts) -> parts.standard("first"), h::parts);
        var second =
            MountedUiTree.field(h.scope, (scope, parts) -> parts.standard("second"), h::parts)) {
      var identities =
          first.snapshot().stream()
              .map(row -> row.node().id)
              .collect(java.util.stream.Collectors.toSet());
      assertTrue(
          second.snapshot().stream().noneMatch(row -> identities.contains(row.node().id)),
          "Field roots that share one native panel require independent native identities");
    }
  }

  @Test
  void optionCopiesRetainComponentsAndRefuseTwoControlAuthorities() {
    ClientFieldComponent component = (scope, parts) -> parts.standard("root");
    var options =
        new ClientFormOptions<String>(FormPresentation.EMPTY, Map.of())
            .withComponent(FieldPath.ROOT, component);
    assertSame(
        component,
        options
            .withDefaults(Component.literal("Defaults"), Component.empty())
            .components()
            .get(FieldPath.ROOT));
    assertThrows(
        IllegalArgumentException.class, () -> options.withControl(FieldPath.ROOT, binding -> null));
    assertThrows(
        IllegalArgumentException.class, () -> options.withComponent(FieldPath.ROOT, component));
  }

  private static final class Host implements AutoCloseable {
    final OwnerContext context =
        new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD);
    final design.inherent.reform.value.ValueType<String> type;
    final DocumentActions<String> actions;
    final UiScope scope;
    final Path path;

    Host(Path directory) {
      this(directory, false);
    }

    Host(Path directory, boolean reference) {
      type =
          reference
              ? ValueTypes.reference(ValueTypes.text(128), DocumentId.parse("example:choices"))
              : ValueTypes.text(128);
      path = directory.resolve("parts.json");
      var definition =
          new DocumentDefinition<>(DocumentId.parse("example:parts"), 1, type, () -> "original");
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(StorageBinding.relative(directory, "parts.json"), 8192, 4096),
              JsonStore.Format.NEW_ENVELOPE,
              new JsonLimits(8192, 32, 1024, 512, 64, 1024),
              DraftLimits.CONFIGURATION);
      var owner = new FileOwner<>(context, store, 16);
      var access = context.access(() -> true, () -> true);
      var session = owner.open(access);
      session.initialize().toCompletableFuture().join();
      var actor =
          new EditorActor<>(
              context,
              session,
              definition,
              new JsonLimits(8192, 32, 1024, 512, 64, 1024),
              DraftLimits.CONFIGURATION,
              32);
      actions =
          new DocumentActions<>(context, access, actor, OperationScope.Limits.STANDARD, 262144);
      DocumentActions.result(actions.ensureLoaded(EntryPoint.GUI)).toCompletableFuture().join();
      scope = UiScope.borrow(context, UiLimits.STANDARD);
    }

    ClientFieldParts<String> parts(UiScope root) {
      return new ClientFieldParts<>(
          root,
          FieldBinding.bind(root, actions, FieldPath.ROOT, type),
          UiText.of(Component.literal("Actual field")),
          UiText.of(Component.literal("Help")),
          List::of,
          Optional::empty);
    }

    @Override
    public void close() {
      actions.close();
      context.close();
    }
  }
}
