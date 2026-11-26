package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.reference.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.*;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual owner/source and shared mounted projection; native paint is qualified separately. */
class ReferencePickerViewTest {
  @TempDir Path directory;

  @Test
  void unavailablePageCauseIsVisibleWithoutDisclosureAndRetainedInSourceDetails() throws Exception {
    try (var context =
        new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD)) {
      var source = DocumentId.parse("example:profiles");
      var type = ValueTypes.reference(ValueTypes.text(128), source);
      var definition =
          new DocumentDefinition<>(
              DocumentId.parse("example:picker"), 1, type, () -> "example:default");
      var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(StorageBinding.relative(directory, "picker.json"), 8192, 4096),
              JsonStore.Format.NEW_ENVELOPE,
              json,
              DraftLimits.CONFIGURATION);
      var fileOwner = new FileOwner<>(context, store, 16);
      var access = context.access(() -> true, () -> true);
      var session = fileOwner.open(access);
      session.initialize().toCompletableFuture().join();
      var actions =
          new DocumentActions<>(
              context,
              access,
              new EditorActor<>(context, session, definition, json, DraftLimits.CONFIGURATION, 32));
      DocumentActions.result(actions.ensureLoaded(OperationDescriptor.EntryPoint.GUI))
          .toCompletableFuture()
          .join();
      var original = Files.readAllBytes(directory.resolve("picker.json"));
      var references =
          new ReferenceActions<>(
              context,
              context.access(() -> true, () -> false),
              source,
              ValueTypes.text(128),
              ValueTypes.list(ValueTypes.text(128), 8).snapshotPolicy(),
              ReferenceLimits.STANDARD);
      String cause = "The required profile pack is missing. Enable it, then refresh choices.";
      references.invalidate("missing-pack", ReferencePage.Availability.MISSING_DEPENDENCY, cause);
      var controller = new AtomicReference<ReferencePicker<String, List<String>>>();
      var declaration = new AtomicReference<ViewSpec>();
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(context),
              UiLimits.STANDARD,
              (scope, props) -> {
                var role = new PickerRole<String>("value", FieldPath.ROOT);
                var picker =
                    ReferencePicker.open(
                        scope,
                        actions,
                        source,
                        () -> references,
                        List.of(role),
                        Map.of(),
                        ReferencePicker.Policy.STANDARD,
                        () -> 0L);
                controller.set(picker);
                var spec =
                    ReferencePickerView.build(
                        scope,
                        picker,
                        new ReferencePickerView.Presentation<>(
                            UiText.of(Component.literal("Profiles")),
                            UiText.of(Component.literal("Choose a profile.")),
                            UiText.of(Component.literal("Available profiles")),
                            UiText.of(Component.literal("Search profiles")),
                            List.of(
                                new ReferencePickerView.RoleAction<>(
                                    role, UiText.of(Component.literal("Use profile")))),
                            4,
                            2),
                        Optional.empty());
                declaration.set(spec);
                return spec;
              },
              "fixed")) {
        var picker = controller.get();
        var operation = assertInstanceOf(UiActionResult.Pending.class, picker.admit().invoke(null));
        operation.operation().completion().toCompletableFuture().join();
        for (int i = 0; i < 4; i++) {
          picker.update();
          tree.refresh(true);
        }
        var state = picker.state().snapshot().value();
        assertEquals(
            ReferencePage.Availability.MISSING_DEPENDENCY,
            state.page().orElseThrow().availability());
        assertEquals(OperationSnapshot.State.COMPLETED, state.searchWork().orElseThrow().state());
        assertEquals(cause, state.page().orElseThrow().reason());
        String ordinary = text(tree);
        var managed = assertInstanceOf(ViewSpec.Managed.class, declaration.get());
        var section = assertInstanceOf(ViewSpec.Section.class, managed.content());
        var details =
            section.children().stream()
                .filter(ViewSpec.Actions.class::isInstance)
                .map(ViewSpec.Actions.class::cast)
                .flatMap(actionsRow -> actionsRow.children().stream())
                .filter(ViewSpec.Disclosure.class::isInstance)
                .map(ViewSpec.Disclosure.class::cast)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(UiActionResult.Applied.class, details.expanded().changes().invoke(true));
        tree.refresh(true);
        String expanded = text(tree);
        assertAll(
            () ->
                assertTrue(
                    ordinary.contains(cause), "Actionable source cause belongs beside the list"),
            () ->
                assertTrue(
                    expanded.contains(cause), "Source details preserve the actual page reason"));
        assertFalse(actions.view().dirty());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("picker.json")));
      }
      assertTrue(actions.isOpen());
    }
  }

  private static String text(MountedUiTree tree) {
    return tree.snapshot().stream()
        .map(row -> row.label().getString() + "\n" + row.body().getString())
        .collect(java.util.stream.Collectors.joining("\n"));
  }
}
