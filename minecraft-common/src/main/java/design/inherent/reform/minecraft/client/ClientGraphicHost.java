package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** Workflow-owned preview admission and observation over actual OperationScope jobs. */
public final class ClientGraphicHost implements AutoCloseable {
  public enum State {
    IDLE,
    QUEUED,
    PREPARING,
    CANCELLING,
    READY,
    STALE,
    UNAVAILABLE,
    REFUSED,
    FAILED,
    CANCELLED,
    CLOSED
  }

  public record Result(
      State state,
      Optional<GraphicSourceStamp> stamp,
      List<GraphicSceneData.Fact> facts,
      Optional<GraphicCamera> camera,
      ValidationReport report,
      Optional<Throwable> cause) {
    public Result {
      Objects.requireNonNull(state);
      Objects.requireNonNull(stamp);
      Objects.requireNonNull(camera);
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
      facts = List.copyOf(facts);
    }

    static Result from(GraphicScenePreparation value) {
      if (value instanceof GraphicScenePreparation.Ready ready) {
        var graphic = ready.graphic();
        return new Result(
            State.READY,
            Optional.of(graphic.stamp()),
            graphic.facts(),
            Optional.of(graphic.camera()),
            ValidationReport.VALID,
            Optional.empty());
      }
      if (value instanceof GraphicScenePreparation.Unavailable unavailable)
        return plain(State.UNAVAILABLE, unavailable.reason(), null);
      if (value instanceof GraphicScenePreparation.Refused refused)
        return plain(State.REFUSED, refused.report(), null);
      if (value instanceof GraphicScenePreparation.Failed failed)
        return plain(State.FAILED, failed.report(), failed.cause());
      return plain(State.CANCELLED, ValidationReport.VALID, null);
    }

    static Result plain(State state, ValidationReport report, Throwable cause) {
      return new Result(
          state, Optional.empty(), List.of(), Optional.empty(), report, Optional.ofNullable(cause));
    }
  }

  public record Snapshot(
      State state,
      boolean visible,
      ValidationReport report,
      Optional<OperationSnapshot<Result>> operation) {}

  /** Pending requests have no invented operation handle; entry binds the real scope handle. */
  public static class Attempt {
    volatile OperationHandle<Result> operation;
    final CompletableFuture<Result> completion = new CompletableFuture<>();

    public Optional<OperationHandle<Result>> operation() {
      return Optional.ofNullable(operation);
    }

    public CompletionStage<Result> completion() {
      return completion.minimalCompletionStage();
    }
  }

  private interface Pending {
    UUID view();

    void start();

    void superseded();

    void cancel();

    boolean current();
  }

  private final OwnerContext context;
  private final Predicate<ClientEditor<?>> owns;
  private final GraphicLedger nativeLedger, outputLedger;
  private final int maximumViews;
  private final Map<UUID, View<?, ?>> views = new LinkedHashMap<>();
  private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
  private final ClientRegistration.Subscription ticking;
  private Pending active;
  private boolean closed;
  private Throwable releaseFailure;
  private int releaseFailures;

  public Optional<Throwable> releaseFailure() {
    return Optional.ofNullable(releaseFailure);
  }

  public int releaseFailureCount() {
    return releaseFailures;
  }

  ClientGraphicHost(
      OwnerContext context,
      Predicate<ClientEditor<?>> owns,
      GraphicLimits limits,
      int maximumViews) {
    ClientSettingsRuntime.requireClientThread();
    if (maximumViews < 1 || maximumViews > 16)
      throw new IllegalArgumentException("A workflow supports between1and16 preview views");
    this.context = context;
    this.owns = owns;
    this.maximumViews = maximumViews;
    nativeLedger = new GraphicLedger(limits);
    outputLedger =
        new GraphicLedger(
            new GraphicLimits(
                limits.inputBytes(),
                limits.outputBytes(),
                limits.decodedPixels(),
                limits.resources(),
                limits.outputBytes()));
    ticking = ClientSettingsRuntime.onTick(this::tick);
  }

  public int maximumViews() {
    return maximumViews;
  }

  public GraphicLimits limits() {
    return nativeLedger.limits();
  }

  public GraphicLedger.State nativeOwnership() {
    return nativeLedger.state();
  }

  /** Known retained scene payload, distinct from the native image/buffer payload ledger. */
  public GraphicLedger.State retainedOutput() {
    return outputLedger.state();
  }

  public <T, S> View<T, S> open(ClientEditor<T> editor, ClientGraphicProvider<T, S> provider) {
    requireOpen();
    if (!owns.test(editor) || !editor.isOpen())
      throw new IllegalArgumentException("Use this workflow's actual retained editor");
    if (views.size() >= maximumViews)
      throw new PolicyRefusal("preview.budget", "The workflow preview-view allowance is full");
    var view = new View<T, S>(editor, Objects.requireNonNull(provider), null);
    views.put(view.id, view);
    return view;
  }

  /** Read-only loaded resources, with no hidden document admission or synthetic draft. */
  public <S> View<Void, S> openLoaded(ClientLoadedGraphicProvider<S> provider) {
    requireOpen();
    if (views.size() >= maximumViews)
      throw new PolicyRefusal("preview.budget", "The workflow preview-view allowance is full");
    var view = new View<Void, S>(null, null, Objects.requireNonNull(provider));
    views.put(view.id, view);
    return view;
  }

  public final class View<T, S> implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final ClientEditor<T> editor;
    private final ClientGraphicProvider<T, S> provider;
    private final ClientLoadedGraphicProvider<S> loadedProvider;
    private final ClientGraphicSource<S> source;
    private final DocumentId providerId;
    private final GraphicLedger ledger;
    private final OwnerContext.Access access;
    private final OperationScope operations;
    private final OperationDescriptor<S, Result> descriptor;
    private Request<T, S> latest;
    private PreparedGraphic nativeGraphic;
    private Result displayed;
    private GraphicCamera camera = GraphicCamera.STANDARD;
    private boolean visible = true, ended;
    private State state = State.IDLE;
    private ValidationReport report = ValidationReport.VALID;
    private ValidationReport cleanupReport = ValidationReport.VALID;
    private Throwable failure;

    private View(
        ClientEditor<T> editor,
        ClientGraphicProvider<T, S> provider,
        ClientLoadedGraphicProvider<S> loadedProvider) {
      this.editor = editor;
      this.provider = provider;
      this.loadedProvider = loadedProvider;
      source = provider == null ? Objects.requireNonNull(loadedProvider) : provider;
      providerId = Objects.requireNonNull(source.id());
      ledger = nativeLedger.child(Objects.requireNonNull(source.limits()));
      var subjectType = Objects.requireNonNull(source.subjectType());
      access = context.access(this::readable, () -> false);
      operations =
          new OperationScope(
              context,
              access,
              new OperationScope.Limits(1, 1, 1, OperationScope.Limits.STANDARD.input()));
      descriptor =
          new OperationDescriptor<>(
              DocumentId.parse("reform:graphic/" + id),
              subjectType,
              OwnerContext.Right.READ,
              Set.of(OperationDescriptor.Effect.INSPECT),
              Set.of(OperationDescriptor.EntryPoint.GUI),
              editor == null ? Optional.empty() : Optional.of(editor.document()));
      operations.register(
          descriptor,
          (execution, subject) -> {
            var request = latest;
            if (request == null || active != request)
              throw new PolicyRefusal("preview.stale", "The entered preview request was replaced");
            return request.prepare(execution, subject);
          });
      operations.freeze();
    }

    private boolean readable() {
      return context.isOpen()
          && (editor == null || editor.isOpen() && editor.actions().mayInspect());
    }

    public Snapshot snapshot() {
      ClientSettingsRuntime.requireClientThread();
      return new Snapshot(
          state,
          visible,
          report.and(cleanupReport),
          latest == null ? Optional.empty() : latest.operation().map(OperationHandle::snapshot));
    }

    /** Current native result; historical operation receipts contain metadata, never this lease. */
    public Optional<GraphicPreparation> preparation() {
      ClientSettingsRuntime.requireClientThread();
      if (!readable())
        return Optional.of(
            new GraphicPreparation.Unavailable(
                problem("owner.permission", "Read access to this preview is unavailable")));
      if (state == State.READY && nativeGraphic != null && latest != null && latest.current())
        return Optional.of(new GraphicPreparation.Ready(nativeGraphic));
      return switch (state) {
        case UNAVAILABLE -> Optional.of(new GraphicPreparation.Unavailable(report));
        case REFUSED -> Optional.of(new GraphicPreparation.Refused(report));
        case FAILED ->
            Optional.of(
                new GraphicPreparation.Failed(
                    report.and(cleanupReport),
                    failure == null
                        ? new IllegalStateException("Native preview failed")
                        : failure));
        case CANCELLED -> Optional.of(new GraphicPreparation.Cancelled());
        case STALE ->
            Optional.of(
                new GraphicPreparation.Refused(
                    problem("preview.stale", "The preview input changed; prepare it again")));
        default -> Optional.empty();
      };
    }

    public Optional<Throwable> failure() {
      ClientSettingsRuntime.requireClientThread();
      return Optional.ofNullable(failure);
    }

    public List<GraphicSceneData.Fact> facts() {
      ClientSettingsRuntime.requireClientThread();
      if (!readable()) return List.of();
      return displayed == null ? List.of() : displayed.facts();
    }

    public Optional<GraphicSourceStamp> displayedStamp() {
      ClientSettingsRuntime.requireClientThread();
      if (!readable()) return Optional.empty();
      return displayed == null ? Optional.empty() : displayed.stamp();
    }

    public Attempt request(S subject) {
      requireOpen();
      if (ended || !visible)
        throw new PolicyRefusal("preview.closed", "This preview is closed or hidden");
      PreviewInputStamp input = null;
      if (editor != null) {
        var revision =
            DraftRevision.of(
                editor
                    .view()
                    .snapshot()
                    .orElseThrow(
                        () ->
                            new PolicyRefusal(
                                "editor.unavailable", "No current draft is available")));
        input = editor.presentation().previewInputStamp(revision);
      }
      var frozen = descriptor.input().freeze(Objects.requireNonNull(subject));
      var resources = Objects.requireNonNull(source.resourceStamp());
      var request = new Request<T, S>(this, input, resources, frozen);
      var old = latest;
      var previous = pending.put(id, request);
      latest = request;
      state = State.QUEUED;
      report = ValidationReport.VALID;
      failure = null;
      closeGraphic();
      if (old != null && active == old) old.cancel();
      if (previous != null) previous.superseded();
      startNext();
      return request;
    }

    /** Hiding cancels obsolete work; showing alone never recompiles or changes a setting. */
    public void visible(boolean visible) {
      ClientSettingsRuntime.requireClientThread();
      if (ended) return;
      this.visible = visible;
      if (!visible) {
        invalidate();
        closeGraphic();
      }
    }

    public void cancel() {
      ClientSettingsRuntime.requireClientThread();
      var waiting = pending.remove(id);
      if (waiting != null) {
        state = State.STALE;
        waiting.superseded();
      }
      if (latest != null && active == latest) {
        state = State.CANCELLING;
        latest.cancel();
      }
    }

    /**
     * Retire the current subject or input without requesting another preparation. Entered work
     * still drains before its resources can be reused.
     */
    public void invalidate() {
      ClientSettingsRuntime.requireClientThread();
      if (ended) return;
      state = State.STALE;
      displayed = null;
      if (latest != null) latest.invalidated = true;
      var waiting = pending.remove(id);
      if (waiting != null) waiting.superseded();
      if (latest != null && active == latest) latest.cancel();
      closeGraphic();
    }

    public GraphicCamera camera() {
      return camera;
    }

    public void orbit(double yaw, double pitch) {
      ClientSettingsRuntime.requireClientThread();
      camera =
          new GraphicCamera(
              camera.yaw() + yaw,
              Math.max(-89, Math.min(89, camera.pitch() + pitch)),
              camera.zoom(),
              camera.panX(),
              camera.panY());
    }

    public void zoom(double factor) {
      ClientSettingsRuntime.requireClientThread();
      if (!Double.isFinite(factor) || factor <= 0)
        throw new IllegalArgumentException("Zoom factor must be finite and positive");
      camera =
          new GraphicCamera(
              camera.yaw(),
              camera.pitch(),
              Math.max(1.0 / 64, Math.min(64, camera.zoom() * factor)),
              camera.panX(),
              camera.panY());
    }

    public void resetCamera() {
      ClientSettingsRuntime.requireClientThread();
      camera =
          displayed == null
              ? GraphicCamera.STANDARD
              : displayed.camera().orElse(GraphicCamera.STANDARD);
    }

    /** Shared read-only focusable viewport. Preparation and camera controls remain explicit. */
    public AbstractWidget widget(Component description) {
      ClientSettingsRuntime.requireClientThread();
      return new NativeGraphicWidget(description) {
        @Override
        protected Component graphicNarration() {
          return getMessage().copy().append(". ").append(label(state));
        }

        @Override
        protected void drawGraphic(FormCanvas canvas) {
          observeClosedLease();
          if (state == State.READY && latest != null && !latest.current()) {
            invalidate();
            closeGraphic();
          }
          if (nativeGraphic != null && state == State.READY && latest != null && latest.current()) {
            try {
              var outcome =
                  nativeGraphic.draw(canvas, getX(), getY(), getWidth(), getHeight(), camera);
              if (outcome == ClientMeshGraphic.DrawOutcome.RENDERED) return;
              feedback(
                  canvas,
                  outcome == ClientMeshGraphic.DrawOutcome.EMPTY
                      ? "No visible geometry"
                      : "Waiting for the renderer");
              return;
            } catch (RuntimeException failed) {
              failure = failed;
              report =
                  problem("preview.prepare_failed", "The native preview failed; prepare it again");
              state = State.FAILED;
              closeGraphic();
            }
          }
          feedback(canvas, statusText());
        }

        private String statusText() {
          return report.issues().isEmpty()
              ? cleanupReport.issues().isEmpty()
                  ? label(state)
                  : cleanupReport.issues().getFirst().fallback()
              : report.issues().getFirst().fallback();
        }

        private void feedback(FormCanvas canvas, String message) {
          canvas.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
          try {
            canvas.drawWordWrap(
                Minecraft.getInstance().font,
                Component.literal(message),
                getX() + 4,
                getY() + 4,
                Math.max(1, getWidth() - 8),
                0xffbbbbbb);
          } finally {
            canvas.disableScissor();
          }
        }
      };
    }

    private void closeGraphic() {
      var previous = nativeGraphic;
      nativeGraphic = null;
      if (previous != null) closeOne(previous);
    }

    private void observeClosedLease() {
      if (nativeGraphic == null || !nativeGraphic.state().closing()) return;
      nativeGraphic = null;
      if (state == State.READY) state = State.IDLE;
    }

    private void recordReleaseFailure(UUID request, Throwable failed) {
      ClientSettingsRuntime.requireClientThread();
      cleanupReport =
          problem(
              "preview.release_failed",
              "Preview cleanup failed; its remaining resources are still accounted for");
      releaseFailures++;
      if (releaseFailure == null) releaseFailure = failed;
      // A retired interest cannot replace a newer request's outcome or reopen a closed view.
      if (latest == null || latest.id.equals(request)) {
        if (failure == null) failure = failed;
        if (!ended && (state == State.READY || state == State.IDLE)) state = State.FAILED;
      }
    }

    private void closeOne(PreparedGraphic previous) {
      try {
        previous.close();
      } catch (RuntimeException | Error failed) {
        // RenderLifetime settles its callback before propagating synchronous release failure.
        // Non-release close failures have no such callback and still need their own report.
        if (previous.state().releaseFailure().isEmpty())
          recordReleaseFailure(previous.stamp().request(), failed);
      }
    }

    @Override
    public void close() {
      ClientSettingsRuntime.requireClientThread();
      if (ended) return;
      ended = true;
      state = State.CLOSED;
      var waiting = pending.remove(id);
      if (waiting != null) waiting.superseded();
      try {
        operations.close();
      } finally {
        try {
          closeGraphic();
        } finally {
          displayed = null;
          access.close();
          views.remove(id);
        }
      }
    }
  }

  private final class Request<T, S> extends Attempt implements Pending {
    private final View<T, S> view;
    private final UUID id = UUID.randomUUID();
    private final PreviewInputStamp initial;
    private final ReferenceStamp resources;
    private final S subject;
    private final Map<DocumentId, ReferenceStamp> observedReferences;
    private DraftPreviewInput<T> captured;
    private ClientPreviewCapture<T> capturing;
    private PreparedGraphic enteredGraphic;
    private boolean finished, invalidated;
    private Throwable failure;

    Request(View<T, S> view, PreviewInputStamp initial, ReferenceStamp resources, S subject) {
      this.view = view;
      this.initial = initial;
      this.resources = resources;
      this.subject = subject;
      observedReferences =
          view.editor == null ? Map.of() : view.editor.presentation().previewReferenceStamps();
    }

    @Override
    public UUID view() {
      return view.id;
    }

    @Override
    public boolean current() {
      if (closed
          || invalidated
          || view.ended
          || !view.visible
          || view.latest != this
          || !view.readable()) return false;
      try {
        return resources.equals(view.source.resourceStamp())
            && (view.editor == null
                || (captured == null
                    ? (initial.equals(view.editor.presentation().previewInputStamp(initial.draft()))
                        && observedReferences.equals(
                            view.editor.presentation().previewReferenceStamps()))
                    : view.editor.isPreviewCurrent(captured)));
      } catch (RuntimeException unavailable) {
        failure = unavailable;
        return false;
      }
    }

    private void requireCurrent() {
      ClientSettingsRuntime.requireClientThread();
      if (!current())
        throw new PolicyRefusal(
            "preview.stale", "The preview input or resource generation changed; prepare it again");
    }

    @Override
    public void superseded() {
      completion.complete(
          Result.plain(
              State.STALE,
              problem("preview.stale", "The preview request was replaced before preparation"),
              null));
    }

    @Override
    public void cancel() {
      if (operation != null && !operation.snapshot().terminal()) {
        try {
          operation.requestCancellation();
        } catch (PolicyRefusal unavailable) {
          view.operations.close();
        }
      }
      if (capturing != null) {
        try {
          capturing.requestCancellation();
        } catch (PolicyRefusal unavailable) {
          /* Actual owner closure also cancels its scope. */
        }
      }
    }

    @Override
    public void start() {
      try {
        requireCurrent();
        view.state = State.PREPARING;
        operation =
            view.operations.submit(view.descriptor, OperationDescriptor.EntryPoint.GUI, subject);
        operation
            .completion()
            .whenComplete(
                (outcome, failed) ->
                    Minecraft.getInstance().execute(() -> finish(outcome, failed)));
      } catch (RuntimeException failed) {
        finish(null, failed);
      }
    }

    private CompletionStage<Result> prepare(OperationScope.Execution execution, S frozen) {
      requireCurrent();
      execution.checkpoint();
      if (view.editor == null) {
        var stamp =
            new LoadedGraphicRequest.Stamp(view.providerId, view.id, id, context.id(), resources);
        var work = new ClientGraphicWork(context, execution, view.ledger, this::requireCurrent);
        try {
          return prepareResult(
              stamp,
              Objects.requireNonNull(
                  view.loadedProvider.prepare(
                      new LoadedGraphicRequest<>(stamp, frozen, view.ledger.limits()), work)),
              execution);
        } catch (RuntimeException | Error failed) {
          failure = failed;
          throw failed;
        }
      }
      capturing = view.editor.capturePreview(OperationDescriptor.EntryPoint.GUI, initial.draft());
      return capturing
          .completion()
          .thenCompose(
              input -> {
                captured = input;
                requireCurrent();
                execution.checkpoint();
                var stamp =
                    new GraphicRequest.Stamp(
                        view.providerId,
                        view.id,
                        id,
                        initial.draft().context(),
                        input.stamp(),
                        resources);
                var request = new GraphicRequest<T, S>(stamp, input, frozen, view.ledger.limits());
                var work =
                    new ClientGraphicWork(context, execution, view.ledger, this::requireCurrent);
                return prepareResult(
                    stamp, Objects.requireNonNull(view.provider.prepare(request, work)), execution);
              })
          .whenComplete((ignored, failed) -> failure = failed);
    }

    private CompletionStage<Result> prepareResult(
        GraphicSourceStamp stamp,
        CompletionStage<GraphicScenePreparation> preparation,
        OperationScope.Execution execution) {
      return preparation
          .thenCompose(
              result ->
                  context.onOwner(
                      () -> {
                        requireCurrent();
                        execution.checkpoint();
                        if (result instanceof GraphicScenePreparation.Cancelled)
                          throw new PolicyRefusal(
                              "operation.cancelled",
                              "Preview preparation was cancelled after entered work drained");
                        if (result instanceof GraphicScenePreparation.Ready ready) {
                          var prepared = ready.graphic();
                          if (!stamp.equals(prepared.stamp()))
                            throw new PolicyRefusal(
                                "preview.stale",
                                "The provider returned a different preview identity");
                          prepared.scene().requireBudget(view.ledger.limits());
                          if (prepared.outputBytes() > view.ledger.limits().outputBytes())
                            throw new PolicyRefusal(
                                "preview.budget",
                                "The prepared scene and facts exceed the provider output allowance");
                          var output = outputLedger.reserve(prepared.outputBytes(), 1);
                          var observed = new java.util.concurrent.atomic.AtomicBoolean();
                          try {
                            var nativeGraphic =
                                ClientMeshGraphic.prepare(
                                    prepared.scene(),
                                    view.ledger,
                                    8,
                                    this::current,
                                    lifetime -> {
                                      observed.set(true);
                                      lifetime
                                          .releaseCompletion()
                                          .thenAccept(
                                              release -> {
                                                if (release.released()) {
                                                  try {
                                                    output.close();
                                                  } catch (RuntimeException | Error failed) {
                                                    view.recordReleaseFailure(id, failed);
                                                  }
                                                } else {
                                                  view.recordReleaseFailure(
                                                      id,
                                                      release
                                                          .releaseFailure()
                                                          .orElseGet(
                                                              () ->
                                                                  new IllegalStateException(
                                                                      "Native preview release did not complete")));
                                                }
                                              });
                                    });
                            enteredGraphic = new PreparedGraphic(prepared, nativeGraphic);
                          } catch (RuntimeException | Error failed) {
                            if (!observed.get()) output.close();
                            throw failed;
                          }
                          try {
                            requireCurrent();
                            execution.checkpoint();
                          } catch (RuntimeException | Error failed) {
                            enteredGraphic.close();
                            throw failed;
                          }
                        }
                        return Result.from(result);
                      }))
          .whenComplete((ignored, failed) -> failure = failed);
    }

    private void finish(OperationSnapshot<Result> outcome, Throwable failed) {
      if (finished) return;
      finished = true;
      Result result;
      if (failed != null)
        result =
            failed instanceof PolicyRefusal refused
                ? Result.plain(State.REFUSED, refused.report(), failed)
                : Result.plain(
                    State.FAILED,
                    problem(
                        "preview.prepare_failed",
                        "Preview preparation failed; inspect the cause and retry"),
                    failed);
      else if (outcome.state() == OperationSnapshot.State.CANCELLED)
        result = Result.plain(State.CANCELLED, outcome.report(), failure);
      else if (outcome.result().isPresent()) result = outcome.result().orElseThrow();
      else
        result =
            Result.plain(
                outcome.state() == OperationSnapshot.State.REFUSED ? State.REFUSED : State.FAILED,
                outcome.report(),
                failure);
      boolean current = current();
      var nativeResult = enteredGraphic;
      enteredGraphic = null;
      if (current && result.state() == State.READY) {
        view.displayed = result;
        view.nativeGraphic = nativeResult;
        view.camera = result.camera().orElse(GraphicCamera.STANDARD);
        view.state = State.READY;
      } else {
        if (nativeResult != null) view.closeOne(nativeResult);
        if (view.latest == this && !view.ended) {
          view.state = current ? result.state() : State.STALE;
          view.report = result.report();
          view.failure = result.cause().orElse(failure);
        }
      }
      captured = null;
      capturing = null;
      completion.complete(result);
      if (active == this) active = null;
      if (!closed) startNext();
    }
  }

  private void startNext() {
    if (closed || active != null || pending.isEmpty()) return;
    var next = pending.entrySet().iterator().next();
    active = next.getValue();
    pending.remove(next.getKey());
    active.start();
  }

  private void tick() {
    if (closed) return;
    for (var view : List.copyOf(views.values())) {
      view.observeClosedLease();
      if (!context.isOpen() || view.editor != null && !view.editor.isOpen()) {
        view.close();
        continue;
      }
      if (view.latest != null && !view.latest.current()) {
        view.invalidate();
        view.closeGraphic();
      }
    }
    startNext();
  }

  private void requireOpen() {
    ClientSettingsRuntime.requireClientThread();
    if (closed || !context.isOpen())
      throw new PolicyRefusal("preview.closed", "The preview workflow is closed");
  }

  private static ValidationReport problem(String code, String text) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, text)), true);
  }

  private static String label(State state) {
    return switch (state) {
      case IDLE -> "Prepare a preview";
      case QUEUED -> "Waiting for earlier preview preparation";
      case READY -> "Preview ready";
      case PREPARING -> "Preparing preview";
      case CANCELLING -> "Cancellation requested; waiting for entered work";
      case STALE -> "Preview is stale; prepare it again";
      case CLOSED -> "Preview closed";
      case CANCELLED -> "Preview preparation cancelled";
      case REFUSED -> "Preview request refused";
      case FAILED -> "Preview failed";
      case UNAVAILABLE -> "Preview unavailable";
    };
  }

  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    if (closed) return;
    closed = true;
    for (var request : List.copyOf(pending.values())) request.superseded();
    pending.clear();
    Throwable failure = null;
    for (var view : List.copyOf(views.values()))
      try {
        view.close();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else failure.addSuppressed(failed);
      }
    ticking.close();
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }
}
