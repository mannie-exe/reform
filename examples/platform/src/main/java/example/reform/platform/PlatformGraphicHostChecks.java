package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.*;
import design.inherent.reform.value.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.components.EditBox;

/** Actual workflow/editor/operation/worker/native preparation; no private renderer or writer. */
final class PlatformGraphicHostChecks implements AutoCloseable {
  private static final GraphicLimits LIMITS =
      new GraphicLimits(65536, 65536, 1024 * 1024, 64, 16 * 1024 * 1024);
  private final BiConsumer<String, Boolean> check;
  private final ClientEditingWorkflow workflow;
  private final ClientEditor<PlatformWorkflowExample.Preferences> editor;
  private final ClientGraphicHost host;
  private final ClientGraphicHost.View<PlatformWorkflowExample.Preferences, Integer> view, other;
  private final Map<Integer, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
  private final Set<Integer> entered = ConcurrentHashMap.newKeySet();
  private final Queue<Integer> order = new ConcurrentLinkedQueue<>();
  private final UUID sourceContext = UUID.randomUUID();
  private UUID generation = UUID.randomUUID();
  private ClientGraphicHost.Attempt first, replaced, latest, unavailable, stale, closing;
  private int stage;
  private long peakBeforeWrongStamp;

  PlatformGraphicHostChecks(
      ClientEditingWorkflow workflow,
      ClientEditor<PlatformWorkflowExample.Preferences> editor,
      BiConsumer<String, Boolean> check) {
    this.workflow = workflow;
    this.editor = editor;
    this.check = check;
    host = workflow.graphics(LIMITS, 2);
    var provider =
        new ClientGraphicProvider<PlatformWorkflowExample.Preferences, Integer>() {
          public DocumentId id() {
            return DocumentId.parse("fixture:host_subject");
          }

          public ValueType<Integer> subjectType() {
            return ValueTypes.integer(1, 8);
          }

          public GraphicLimits limits() {
            return LIMITS;
          }

          public ReferenceStamp resourceStamp() {
            return new ReferenceStamp(sourceContext, id(), generation, "same-readable-revision");
          }

          public CompletionStage<GraphicScenePreparation> prepare(
              GraphicRequest<PlatformWorkflowExample.Preferences, Integer> request,
              ClientGraphicWork work) {
            check.accept(
                "provider entry retains actual client/native stage",
                net.minecraft.client.Minecraft.getInstance().isSameThread());
            return work.onWorker(
                () -> {
                  if (net.minecraft.client.Minecraft.getInstance().isSameThread())
                    throw new AssertionError("Domain compilation ran on client thread");
                  int subject = request.subject();
                  order.add(subject);
                  entered.add(subject);
                  if (Set.of(1, 4, 5, 8).contains(subject))
                    gates
                        .computeIfAbsent(subject, ignored -> new CompletableFuture<>())
                        .get(15, TimeUnit.SECONDS);
                  work.checkpoint();
                  if (subject == 7)
                    return new GraphicScenePreparation.Unavailable(
                        new ValidationReport(
                            List.of(
                                Issue.error(
                                    "fixture.unavailable",
                                    FieldPath.ROOT,
                                    "This fixture capability is unavailable")),
                            true));
                  var stamp = request.stamp();
                  if (subject == 6)
                    stamp =
                        new GraphicRequest.Stamp(
                            stamp.provider(),
                            stamp.view(),
                            UUID.randomUUID(),
                            stamp.context(),
                            stamp.input(),
                            stamp.resources());
                  return new GraphicScenePreparation.Ready(
                      new GraphicSceneData(
                          stamp,
                          PlatformMeshChecks.pattern(),
                          GraphicCamera.STANDARD,
                          List.of(
                              new GraphicSceneData.Fact(
                                  DocumentId.parse("fixture:distance"),
                                  "Captured distance",
                                  Integer.toString(
                                      request.input().candidate().value().distance())))));
                });
          }
        };
    view = host.open(editor, provider);
    other = host.open(editor, provider);
  }

  private void release(int subject) {
    gates.get(subject).complete(null);
  }

  private void edit(String value) {
    var path = FieldPath.ROOT.field("distance");
    if (!editor.form().focusField(path))
      throw new AssertionError("Actual distance field is not mounted");
    replace(workflow.screen(), (EditBox) editor.form().fieldControl(path).orElseThrow(), value);
  }

  private boolean valid() {
    return !editor.busy() && editor.view().validation().valid() && editor.canSubmit();
  }

  private boolean cancelled(ClientGraphicHost.Attempt attempt) {
    return attempt.operation().orElseThrow().snapshot().cancellationRequested();
  }

  private boolean empty() {
    return host.nativeOwnership().bytes() == 0
        && host.nativeOwnership().resources() == 0
        && host.retainedOutput().bytes() == 0
        && host.retainedOutput().resources() == 0;
  }

  boolean tick() {
    switch (stage) {
      case 0 -> {
        first = view.request(1);
        stage++;
      }
      case 1 -> {
        if (!entered.contains(1)) return false;
        replaced = view.request(2);
        latest = view.request(3);
        unavailable = other.request(7);
        check.accept(
            "superseded queued preview has no invented operation",
            replaced.operation().isEmpty() && replaced.completion().toCompletableFuture().isDone());
        check.accept(
            "entered cancellation retains workflow preparation slot",
            cancelled(first)
                && !first.completion().toCompletableFuture().isDone()
                && latest.operation().isEmpty()
                && unavailable.operation().isEmpty());
        release(1);
        stage++;
      }
      case 2 -> {
        if (!latest.completion().toCompletableFuture().isDone()
            || !unavailable.completion().toCompletableFuture().isDone()) return false;
        check.accept(
            "cancelled entered provider actually drains before replacement",
            first.operation().orElseThrow().snapshot().state()
                == OperationSnapshot.State.CANCELLED);
        check.accept(
            "only latest pending request per viewport enters",
            new ArrayList<>(order).equals(List.of(1, 3, 7)));
        check.accept(
            "prepared native host retains full captured candidate facts",
            view.snapshot().state() == ClientGraphicHost.State.READY
                && view.facts().getFirst().value().equals("128"));
        check.accept(
            "unavailable capability is separate from invalid input",
            other.snapshot().state() == ClientGraphicHost.State.UNAVAILABLE
                && other
                    .snapshot()
                    .report()
                    .issues()
                    .getFirst()
                    .code()
                    .equals("fixture.unavailable"));
        view.visible(false);
        other.close();
        stage++;
      }
      case 3 -> {
        if (!empty()) return false;
        view.visible(true);
        check.accept(
            "returning to a preview does not automatically prepare",
            order.size() == 3 && view.snapshot().state() == ClientGraphicHost.State.STALE);
        stale = view.request(4);
        stage++;
      }
      case 4 -> {
        if (!entered.contains(4)) return false;
        edit("broken");
        stage++;
      }
      case 5 -> {
        if (!cancelled(stale)) return false;
        check.accept(
            "native rejected input cancels the actual old preparation",
            view.snapshot().state() == ClientGraphicHost.State.STALE
                && !stale.completion().toCompletableFuture().isDone());
        release(4);
        stage++;
      }
      case 6 -> {
        if (!stale.completion().toCompletableFuture().isDone()) return false;
        edit("128");
        stage++;
      }
      case 7 -> {
        if (!valid() || !empty()) return false;
        stale = view.request(5);
        stage++;
      }
      case 8 -> {
        if (!entered.contains(5)) return false;
        generation = UUID.randomUUID();
        stage++;
      }
      case 9 -> {
        if (!cancelled(stale)) return false;
        check.accept(
            "actual resource identity changes cancel despite identical revision text",
            !stale.completion().toCompletableFuture().isDone()
                && view.snapshot().state() == ClientGraphicHost.State.STALE);
        release(5);
        stage++;
      }
      case 10 -> {
        if (!stale.completion().toCompletableFuture().isDone() || !empty()) return false;
        peakBeforeWrongStamp = host.nativeOwnership().peakBytes();
        stale = view.request(6);
        stage++;
      }
      case 11 -> {
        if (!stale.completion().toCompletableFuture().isDone()) return false;
        var result = stale.completion().toCompletableFuture().join();
        check.accept(
            "provider result cannot mount a different request identity",
            result.state() == ClientGraphicHost.State.REFUSED
                && result.report().issues().getFirst().code().equals("preview.stale")
                && empty()
                && host.nativeOwnership().peakBytes() == peakBeforeWrongStamp);
        closing = view.request(8);
        stage++;
      }
      case 12 -> {
        if (!entered.contains(8)) return false;
        workflow.close();
        check.accept(
            "workflow close revokes view but does not claim entered worker completion",
            view.snapshot().state() == ClientGraphicHost.State.CLOSED
                && !closing.completion().toCompletableFuture().isDone());
        release(8);
        stage++;
      }
      case 13 -> {
        if (!closing.completion().toCompletableFuture().isDone() || !empty()) return false;
        check.accept(
            "closed workflow preparation drains without editor resurrection",
            !workflow.isOpen()
                && !editor.isOpen()
                && view.snapshot().state() == ClientGraphicHost.State.CLOSED
                && closing.operation().orElseThrow().snapshot().state()
                    == OperationSnapshot.State.CANCELLED
                && host.releaseFailure().isEmpty());
        stage++;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }

  @Override
  public void close() {
    for (var gate : gates.values()) gate.complete(null);
    host.close();
  }
}
