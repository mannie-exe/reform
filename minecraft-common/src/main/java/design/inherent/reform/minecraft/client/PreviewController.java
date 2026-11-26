package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.*;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.reference.*;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** One mounted preview interest over the actual workflow host; no executor or settings writer. */
public final class PreviewController<T, S> implements AutoCloseable {
  public enum Automatic {
    DISABLED,
    ACCEPTED_CHANGES
  }

  public enum Phase {
    HIDDEN,
    IDLE,
    DEBOUNCING,
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

  public record State(
      Phase phase,
      ClientGraphicHost.State host,
      boolean pending,
      boolean cancelable,
      ValidationReport report,
      String basisLabel,
      GraphicSourceStamp.Kind basis) {}

  /**
   * One presentation attachment. Hiding releases its actual view while retaining local controls.
   */
  public final class Slot implements AutoCloseable {
    private boolean closed;
    private AbstractWidget widget;

    public void update() {
      if (!closed) PreviewController.this.update();
    }

    public Optional<AbstractWidget> widget() {
      require();
      if (closed) throw new PolicyRefusal("preview.closed", "This preview attachment has ended.");
      if (view == null) return Optional.empty();
      if (widget == null) widget = view.widget(title);
      return Optional.of(widget);
    }

    public void suspend() {
      if (!closed) {
        hide();
        widget = null;
      }
    }

    public void close() {
      if (closed) return;
      closed = true;
      hide();
      slot = null;
      widget = null;
    }
  }

  private final UiScope scope;
  private final ClientEditor<T> editor;
  private final ClientGraphicHost host;
  private final ClientGraphicProvider<T, S> provider;
  private final SubjectForms.Form<S> subjects;
  private final Component title;
  private final Automatic automatic;
  private final LongSupplier clock;
  private final UiReadKey<State> statusKey;
  private final UiRead<State> status;
  private final ViewSpec content;
  private UiObservation<State> cached;
  private long revision = 1, subjectRevision, deadline;
  private boolean mounted, activated, pending, ended, explicitRequired;
  private ClientGraphicHost.View<T, S> view;
  private ClientGraphicHost.Attempt attempt;
  private ReferenceAudition<T> audition;
  private PreviewInputStamp input;
  private ReferenceStamp resources;
  private GraphicCamera camera;
  private ValidationReport failure = ValidationReport.VALID;
  private Component basisDescription = currentDraft();
  private String basisLabel = basisDescription.getString();
  private Slot slot;

  public static <T, S> PreviewController<T, S> open(
      UiScope parent,
      ClientEditor<T> editor,
      ClientGraphicHost host,
      ClientGraphicProvider<T, S> provider,
      SubjectForms.Form<S> subjects,
      Component title,
      Automatic automatic) {
    return new PreviewController<>(
        parent, editor, host, provider, subjects, title, automatic, System::nanoTime);
  }

  private PreviewController(
      UiScope parent,
      ClientEditor<T> editor,
      ClientGraphicHost host,
      ClientGraphicProvider<T, S> provider,
      SubjectForms.Form<S> subjects,
      Component title,
      Automatic automatic,
      LongSupplier clock) {
    this.editor = Objects.requireNonNull(editor);
    this.host = Objects.requireNonNull(host);
    this.provider = Objects.requireNonNull(provider);
    this.subjects = Objects.requireNonNull(subjects);
    this.title = ClientReferenceTask.caption(title, 1024, false);
    this.automatic = Objects.requireNonNull(automatic);
    this.clock = clock;
    if (provider.subjectType() != subjects.type())
      throw new IllegalArgumentException("Use the provider's exact subject type");
    scope = parent.child();
    try {
      scope.onRetire(this::close);
      scope.project(List.of(subjects.value()), () -> subjects.value().snapshot());
      statusKey =
          new UiReadKey<>(
              DocumentId.parse("reform:preview_status"),
              SnapshotPolicy.immutable(),
              SnapshotContext.Limits.CONFIGURATION,
              32768,
              value ->
                  128L
                      + 2L * value.basisLabel().length()
                      + value.report().issues().stream()
                          .mapToLong(issue -> 64L + 2L * issue.toString().length())
                          .sum());
      cached =
          new UiObservation.Available<>(
              statusKey.capture(
                  new State(
                      Phase.HIDDEN,
                      ClientGraphicHost.State.IDLE,
                      false,
                      false,
                      ValidationReport.VALID,
                      basisLabel,
                      GraphicSourceStamp.Kind.DRAFT)),
              revision);
      status = scope.observe(statusKey, () -> cached);
      content =
          Views.section(
              "preview",
              UiText.of(this.title),
              List.of(
                  subjects.content(),
                  Views.text(
                      "preview-basis",
                      UiText.of(Component.empty()),
                      UiText.from(status, value -> Component.literal(value.basisLabel()))),
                  Views.text(
                      "preview-status",
                      UiText.of(Component.empty()),
                      UiText.from(status, PreviewController::message)),
                  Views.actions(
                      "preparation",
                      List.of(
                          Views.action("prepare", text("Prepare preview"), text(""), prepare()),
                          Views.action("cancel", text("Cancel preparation"), text(""), cancel()))),
                  Views.preview("viewport", this),
                  Views.actions(
                      "camera",
                      List.of(
                          Views.action(
                              "left", text("Rotate left"), text(""), camera(v -> v.orbit(-15, 0))),
                          Views.action(
                              "right", text("Rotate right"), text(""), camera(v -> v.orbit(15, 0))),
                          Views.action(
                              "zoom-in", text("Zoom in"), text(""), camera(v -> v.zoom(1.2))),
                          Views.action(
                              "zoom-out", text("Zoom out"), text(""), camera(v -> v.zoom(1 / 1.2))),
                          Views.action(
                              "reset",
                              text("Reset view"),
                              text(""),
                              camera(ClientGraphicHost.View::resetCamera))))));
    } catch (RuntimeException | Error failed) {
      scope.close();
      throw failed;
    }
  }

  public ViewSpec content() {
    return content;
  }

  public UiRead<State> state() {
    return status;
  }

  public Optional<ClientGraphicHost.Attempt> attempt() {
    require();
    return Optional.ofNullable(attempt);
  }

  public Component title() {
    return title.copy();
  }

  public Slot attach() {
    require();
    if (slot != null)
      throw new PolicyRefusal("preview.duplicate_attachment", "Mount this preview only once.");
    return slot = new Slot();
  }

  /** Pin an actual resolved audition; this never stages the inspected profile. */
  public UiActionResult audition(ReferenceAudition<T> requested, Component description) {
    require();
    if (!mounted) return refused("preview.hidden", "Open this preview before changing its basis.");
    if (provider.inputs() != ClientGraphicProvider.Inputs.DRAFT_AND_AUDITION)
      return refused("preview.audition_unsupported", "This provider supports draft previews only.");
    try {
      requested.requireCurrent(
          editor.actions(), DraftRevision.of(editor.view().snapshot().orElseThrow()));
      audition = Objects.requireNonNull(requested);
      basisDescription = ClientReferenceTask.caption(description, 1024, false);
      if (automatic == Automatic.ACCEPTED_CHANGES && !explicitRequired) schedule();
      else if (view != null) view.invalidate();
      return new UiActionResult.Applied();
    } catch (PolicyRefusal refused) {
      return refuse(refused);
    }
  }

  /**
   * Deliberately return to complete staged draft input; no silent fallback from a stale audition.
   */
  public UiActionResult draft() {
    require();
    audition = null;
    basisDescription = currentDraft();
    if (mounted && automatic == Automatic.ACCEPTED_CHANGES && !explicitRequired) schedule();
    else if (view != null) view.invalidate();
    return new UiActionResult.Applied();
  }

  public UiCommand<Void> prepare() {
    return command(
        () ->
            mounted
                ? UiAvailability.READY
                : UiAvailability.blocked(
                    "preview.hidden", "Open this preview before preparing it."),
        () -> {
          pending = false;
          explicitRequired = false;
          request();
          return new UiActionResult.Applied();
        });
  }

  public UiCommand<Void> cancel() {
    return command(
        () ->
            status.snapshot().value().cancelable()
                ? UiAvailability.READY
                : UiAvailability.blocked(
                    "preview.not_cancelable", "No cancelable preparation is active."),
        () -> {
          pending = false;
          activated = false;
          if (view != null) view.cancel();
          publish();
          return new UiActionResult.Applied();
        });
  }

  private UiCommand<Void> camera(Consumer<ClientGraphicHost.View<T, S>> change) {
    return command(
        () ->
            view != null && status.snapshot().value().host() == ClientGraphicHost.State.READY
                ? UiAvailability.READY
                : UiAvailability.blocked("preview.not_ready", "Prepare a preview first."),
        () -> {
          change.accept(view);
          camera = view.camera();
          return new UiActionResult.Applied();
        });
  }

  private UiCommand<Void> command(
      java.util.function.Supplier<UiAvailability> availability,
      java.util.function.Supplier<UiActionResult> invoke) {
    return new UiCommand<>() {
      public List<UiRead<?>> dependencies() {
        return List.of(status);
      }

      public UiAvailability availability() {
        require();
        return availability.get();
      }

      public UiActionResult invoke(Void ignored) {
        require();
        var ready = availability();
        if (!ready.available()) return new UiActionResult.Refused(ready.report(), Optional.empty());
        try {
          return invoke.get();
        } catch (PolicyRefusal refused) {
          return refuse(refused);
        }
      }
    };
  }

  private void update() {
    require();
    try {
      if (!mounted) {
        view = host.open(editor, provider);
        if (camera != null) view.camera(camera);
        mounted = true;
        activated = false;
        pending = false;
        failure = ValidationReport.VALID;
        subjectRevision = subjects.value().snapshot().revision();
        input = currentInput();
        resources = provider.resourceStamp();
      }
      view.refreshCurrentness();
      var nextResources = provider.resourceStamp();
      if (!Objects.equals(resources, nextResources)) {
        resources = nextResources;
        pending = false;
        activated = false;
        explicitRequired = true;
      }
      var next = currentInput();
      if (audition != null) audition.requireCurrent(editor.actions(), next.draft());
      var subject = subjects.value().snapshot();
      if (subject.revision() != subjectRevision) {
        subjectRevision = subject.revision();
        if (automatic == Automatic.ACCEPTED_CHANGES && !explicitRequired) schedule();
        else view.invalidate();
      } else if (!Objects.equals(input, next)
          && activated
          && automatic == Automatic.ACCEPTED_CHANGES
          && !explicitRequired) schedule();
      input = next;
      if (pending && clock.getAsLong() - deadline >= 0) {
        pending = false;
        request();
      }
      publish();
    } catch (PolicyRefusal refused) {
      refuse(refused);
    }
  }

  private PreviewInputStamp currentInput() {
    return editor
        .presentation()
        .previewInputStamp(DraftRevision.of(editor.view().snapshot().orElseThrow()));
  }

  private void schedule() {
    activated = true;
    pending = true;
    deadline = clock.getAsLong() + 120_000_000L;
    failure = ValidationReport.VALID;
    if (view != null) view.invalidate();
    publish();
  }

  private void request() {
    if (view == null)
      throw new PolicyRefusal("preview.hidden", "Open this preview before preparing it.");
    var frozen = subjects.value().snapshot().value();
    attempt = audition == null ? view.request(frozen) : view.request(frozen, audition);
    activated = true;
    failure = ValidationReport.VALID;
    subjectRevision = subjects.value().snapshot().revision();
    input = currentInput();
    publish();
  }

  private UiActionResult refuse(PolicyRefusal refused) {
    pending = false;
    failure = refused.report();
    if (failure.issues().stream().anyMatch(issue -> !issue.code().startsWith("preview.input_"))) {
      activated = false;
      explicitRequired = true;
    }
    if (view != null) view.refreshCurrentness();
    publish();
    return new UiActionResult.Refused(failure, Optional.of(refused));
  }

  private void hide() {
    design.inherent.reform.minecraft.internal.ClientSettingsRuntime.requireClientThread();
    mounted = false;
    pending = false;
    activated = false;
    if (view != null) {
      if (camera != null) camera = view.camera();
      view.close();
      view = null;
    }
    attempt = null;
    if (!ended) publish();
  }

  private void publish() {
    basisLabel = basisDescription.getString();
    var hostState = view == null ? ClientGraphicHost.State.IDLE : view.snapshot().state();
    var report = failure.valid() && view != null ? view.snapshot().report() : failure;
    var phase =
        ended
            ? Phase.CLOSED
            : !mounted
                ? Phase.HIDDEN
                : !failure.valid()
                    ? Phase.REFUSED
                    : pending ? Phase.DEBOUNCING : Phase.valueOf(hostState.name());
    boolean cancelable =
        pending
            || hostState == ClientGraphicHost.State.QUEUED
            || hostState == ClientGraphicHost.State.PREPARING;
    var next =
        new State(
            phase,
            hostState,
            pending,
            mounted && cancelable,
            report,
            basisLabel,
            audition == null ? GraphicSourceStamp.Kind.DRAFT : GraphicSourceStamp.Kind.AUDITION);
    if (!next.equals(cached.value()))
      cached = new UiObservation.Available<>(statusKey.capture(next), ++revision);
  }

  private void require() {
    scope.requireCurrent();
    if (ended) throw new PolicyRefusal("preview.closed", "This preview has ended.");
  }

  public void close() {
    design.inherent.reform.minecraft.internal.ClientSettingsRuntime.requireClientThread();
    if (ended) return;
    ended = true;
    hide();
    if (scope.isOpen()) scope.close();
  }

  private static UiActionResult refused(String code, String fallback) {
    return new UiActionResult.Refused(new PolicyRefusal(code, fallback).report(), Optional.empty());
  }

  private static Component currentDraft() {
    return Component.translatableWithFallback("reform.ui.preview_current_draft", "Current draft");
  }

  private static UiText text(String value) {
    return UiText.of(
        value.isEmpty()
            ? Component.empty()
            : Component.translatableWithFallback(
                "reform.ui." + value.toLowerCase(Locale.ROOT).replace(' ', '_'), value));
  }

  private static Component message(State state) {
    String caption =
        switch (state.phase()) {
          case HIDDEN -> "Preview is hidden.";
          case IDLE -> "Prepare a preview to see this subject.";
          case DEBOUNCING -> "Preview update pending…";
          case QUEUED -> "Preview preparation queued…";
          case PREPARING -> "Preparing preview…";
          case CANCELLING -> "Waiting for preparation to stop…";
          case READY -> "Preview ready.";
          case STALE -> "The preview changed. Prepare it again.";
          case UNAVAILABLE -> "Preview unavailable.";
          case REFUSED -> "Preview preparation refused.";
          case FAILED -> "Preview preparation failed.";
          case CANCELLED -> "Preview preparation cancelled.";
          case CLOSED -> "Preview closed.";
        };
    var result =
        Component.translatableWithFallback(
            "reform.ui.preview_" + state.phase().name().toLowerCase(Locale.ROOT), caption);
    for (var issue : state.report().issues()) result.append("\n").append(issue.fallback());
    return result;
  }
}
