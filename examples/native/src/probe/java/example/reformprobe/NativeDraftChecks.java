package example.reformprobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.neoforge.configuration.RegisteredConfig;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.reference.CollectionSelection;
import design.inherent.reform.serialization.JsonLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Typed collection services over an actual registered native owner; never commits its draft. */
public final class NativeDraftChecks {
  public static CompletionStage<Void> verify(
      OwnerContext context, RegisteredConfig owner, Path file, JsonObject parent) throws Exception {
    context.requireOwner();
    byte[] original = Files.readAllBytes(file);
    var report = new JsonObject();
    report.addProperty("status", "FAIL");
    parent.add("structure", report);
    var checks = java.util.Collections.synchronizedList(new ArrayList<String>());
    BiConsumer<String, Boolean> check =
        (name, valid) -> {
          if (!valid) throw new AssertionError(name);
          checks.add(name);
        };
    var access = context.access(() -> true, () -> false);
    var session = owner.session(access);
    var actor =
        new EditorActor<>(
            context,
            session,
            owner.definition(),
            new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
            DraftLimits.CONFIGURATION,
            32);
    var actions = new DocumentActions<>(context, access, actor);
    var names = FieldPath.ROOT.field("nested").field("names");
    var stale = new AtomicReference<DraftTarget>();
    var row = new AtomicReference<FieldPath>();
    CompletionStage<Void> work =
        run(context, () -> actions.ensureLoaded(EntryPoint.INTERNAL))
            .thenApply(
                view -> {
                  check.accept("native collection baseline is valid", view.validation().valid());
                  stale.set(view.snapshot().orElseThrow().target(names));
                  return null;
                });
    work =
        work.thenCompose(
                ignored -> edit(context, actions, names, new DraftEdit.InsertRow(Optional.empty())))
            .thenApply(
                view -> {
                  var added = (DraftNode.Sequence) view.snapshot().orElseThrow().draft().at(names);
                  row.set(names.row(added.rows().getLast().id()));
                  check.accept(
                      "native typed service inserts a stable collection row",
                      added.rows().size() == 1);
                  return null;
                });
    work =
        work.thenCompose(
                ignored -> edit(context, actions, row.get(), new DraftEdit.SetRaw("fixture")))
            .thenApply(
                view -> {
                  check.accept(
                      "native dynamic row uses its declared text type", view.validation().valid());
                  return null;
                });
    work =
        work.thenCompose(
            ignored -> {
              var before =
                  (DraftNode.Sequence) actions.view().snapshot().orElseThrow().draft().at(names);
              return context
                  .onOwner(() -> new CollectionSelection<>(actions, names))
                  .thenCompose(
                      selection ->
                          context
                              .onOwner(
                                  () ->
                                      selection.change(
                                          EntryPoint.GUI,
                                          selection.view().target(),
                                          new DraftEdit.ChangeScalarRows(
                                              List.of("choice-a", "choice-b"),
                                              Set.of(before.rows().getFirst().id()))))
                              .thenCompose(
                                  request ->
                                      request
                                          .operation()
                                          .completion()
                                          .thenCompose(
                                              result ->
                                                  context.onOwner(
                                                      () -> {
                                                        check.accept(
                                                            "native collection controller accepts its actual issued edit",
                                                            selection.accept(request)
                                                                == CollectionSelection.Acceptance
                                                                    .ACCEPTED);
                                                        selection.close();
                                                        return actions.view();
                                                      }))))
                  .thenApply(
                      view -> {
                        var changed =
                            (DraftNode.Sequence) view.snapshot().orElseThrow().draft().at(names);
                        check.accept(
                            "native membership stages the complete remove/add batch",
                            view.validation().valid()
                                && changed.rows().size() == 2
                                && changed.rows().stream()
                                    .map(r -> ((DraftNode.Scalar) r.value()).raw())
                                    .toList()
                                    .equals(List.of("choice-a", "choice-b")));
                        return null;
                      })
                  .thenCompose(value -> run(context, () -> actions.undo(EntryPoint.GUI)))
                  .thenApply(
                      view -> {
                        check.accept(
                            "native membership undo restores all prior input and row identities",
                            view.snapshot().orElseThrow().draft().at(names).equals(before));
                        return null;
                      });
            });
    work =
        work.thenCompose(
                ignored ->
                    edit(
                        context,
                        actions,
                        names,
                        new DraftEdit.DuplicateRow(
                            ((FieldPath.Row) row.get().segments().getLast()).id(),
                            Optional.empty())))
            .thenApply(
                view -> {
                  var values = (DraftNode.Sequence) view.snapshot().orElseThrow().draft().at(names);
                  check.accept(
                      "native duplicate preserves distinct session identity",
                      values.rows().size() == 2
                          && !values.rows().getFirst().id().equals(values.rows().getLast().id()));
                  return values;
                })
            .thenCompose(
                values ->
                    edit(
                        context,
                        actions,
                        names,
                        new DraftEdit.MoveRow(
                            values.rows().getLast().id(),
                            Optional.of(values.rows().getFirst().id()))))
            .thenCompose(
                view ->
                    edit(
                        context,
                        actions,
                        names,
                        new DraftEdit.RemoveRow(
                            ((FieldPath.Row) row.get().segments().getLast()).id())))
            .thenApply(
                view -> {
                  var values = (DraftNode.Sequence) view.snapshot().orElseThrow().draft().at(names);
                  check.accept(
                      "native anchored move/remove retains the copied row",
                      values.rows().size() == 1
                          && ((DraftNode.Scalar) values.rows().getFirst().value())
                              .raw()
                              .equals("fixture"));
                  return null;
                });
    work =
        work.thenCompose(
                ignored ->
                    context.onOwner(
                        () ->
                            actions.edit(
                                EntryPoint.GUI,
                                new DraftEdit(
                                    stale.get(), new DraftEdit.InsertRow(Optional.empty())))))
            .thenCompose(OperationHandle::completion)
            .thenApply(
                result -> {
                  check.accept(
                      "native stale collection action is a refusal",
                      result.state() == OperationSnapshot.State.REFUSED
                          && result
                              .report()
                              .issues()
                              .getFirst()
                              .code()
                              .equals("edit.stale_target"));
                  check.accept(
                      "native stale action retains actual diagnostic field",
                      result.report().issues().getFirst().path().equals(names));
                  return null;
                });
    var live = FieldPath.ROOT.field("live");
    work =
        work.thenCompose(ignored -> edit(context, actions, live, new DraftEdit.SetRaw("-")))
            .thenCompose(ignored -> run(context, () -> actions.validate(EntryPoint.COMMAND)))
            .thenApply(
                validation -> {
                  check.accept(
                      "native typed edit retains incomplete numeric input",
                      !validation.report().valid());
                  check.accept(
                      "native validation retains actual editor identity",
                      validation
                          .session()
                          .equals(actions.view().snapshot().orElseThrow().session()));
                  return null;
                })
            .thenCompose(ignored -> run(context, () -> actions.undo(EntryPoint.GUI)))
            .thenCompose(ignored -> run(context, () -> actions.validate(EntryPoint.COMMAND)))
            .thenApply(
                validation -> {
                  check.accept(
                      "native read-only owner preparation accepts the repaired draft",
                      validation.report().valid());
                  return null;
                });
    work =
        work.thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      boolean refused = false;
                      try {
                        actions.save(EntryPoint.GUI);
                      } catch (PolicyRefusal expected) {
                        refused = true;
                      }
                      check.accept(
                          "native structural editing does not grant Save authority", refused);
                      try {
                        check.accept(
                            "native structure/validation/undo leave TOML bytes unchanged",
                            Arrays.equals(original, Files.readAllBytes(file)));
                      } catch (java.io.IOException failed) {
                        throw new AssertionError(failed);
                      }
                      check.accept(
                          "native compound draft remains explicit unsaved work",
                          actions.view().dirty());
                      return null;
                    }));
    return work.handle((ignored, failure) -> failure)
        .thenCompose(
            failure ->
                context.onOwner(
                    () -> {
                      try {
                        report.add("checks", new Gson().toJsonTree(checks));
                        if (failure != null)
                          throw new java.util.concurrent.CompletionException(failure);
                        report.addProperty("status", "PASS");
                        return null;
                      } finally {
                        actions.close();
                        access.close();
                      }
                    }));
  }

  private static <T> CompletionStage<EditorActor.View<T>> edit(
      OwnerContext context, DocumentActions<T> actions, FieldPath path, DraftEdit.Action action) {
    return run(
        context,
        () ->
            actions.edit(
                EntryPoint.GUI,
                new DraftEdit(actions.view().snapshot().orElseThrow().target(path), action)));
  }

  private static <T> CompletionStage<T> run(
      OwnerContext context, Supplier<OperationHandle<T>> request) {
    return context.onOwner(request::get).thenCompose(DocumentActions::result);
  }

  private NativeDraftChecks() {}
}
