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
  private boolean auditionRequested;
  private ReferencePicker<T, ?> picker;
  private PickerRole<T> role;
  private Component roleTitle;
  private long inspectionGesture;
  private List<ReferencePicker<T, ?>> draftSelections = List.of();
  private final Map<ReferencePicker<T, ?>, UUID> observedSelections = new IdentityHashMap<>();
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
      status = scope.observe(statusKey, this::observeStatus);
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

  /**
   * Follow this picker's resolved inspection for one explicit role. Construction/admission and
   * search remain the picker's responsibility; no source, writer, or second scheduler is made. A
   * role switch while visible is a deliberate audition gesture. Return alone never prepares.
   */
  public UiActionResult follow(
      ReferencePicker<T, ?> requested, PickerRole<T> requestedRole, Component requestedRoleTitle) {
    require();
    Objects.requireNonNull(requested);
    if (!requested.roles().contains(Objects.requireNonNull(requestedRole)))
      throw new IllegalArgumentException("Use one of the picker's declared roles");
    if (provider.inputs() != ClientGraphicProvider.Inputs.DRAFT_AND_AUDITION)
      return refused("preview.audition_unsupported", "This provider supports draft previews only.");
    // Verify the shared presentation root without admitting a source or borrowing a native widget.
    scope.project(List.of(requested.state()), () -> requested.state().snapshot());
    var caption = ClientReferenceTask.caption(requestedRoleTitle, 128, false);
    draftSelections = List.of();
    observedSelections.clear();
    picker = requested;
    role = requestedRole;
    roleTitle = caption;
    auditionRequested = true;
    audition = null;
    basisDescription = auditionUnavailable();
    pending = false;
    if (view != null) view.invalidate();
    try {
      if (mounted) followInspection(true);
      publish();
      return new UiActionResult.Applied();
    } catch (PolicyRefusal refused) {
      return refuse(refused);
    }
  }

  /**
   * Declare shared picker edit results that activate this complete DRAFT preview. Binding itself
   * performs no admission, edit, or preparation. Sources may be collapsed/unadmitted; only a fresh
   * actual accepted edit at the current complete revision activates the existing demand lane.
   */
  public void activateAfterSelection(List<? extends ReferencePicker<T, ?>> selections) {
    require();
    if (auditionRequested)
      throw new IllegalStateException("Return to the draft before binding draft selections");
    if (selections.size() > 16)
      throw new IllegalArgumentException("Declare at most16 selection controllers");
    var identities =
        Collections.newSetFromMap(new IdentityHashMap<ReferencePicker<T, ?>, Boolean>());
    for (var selection : selections) {
      Objects.requireNonNull(selection);
      if (!identities.add(selection))
        throw new IllegalArgumentException("Bind each actual selection controller once");
      scope.project(List.of(selection.state()), () -> selection.state().snapshot());
    }
    draftSelections = List.copyOf(selections);
    selectionBaseline();
  }

  private void selectionBaseline() {
    observedSelections.clear();
    for (var selection : draftSelections) {
      var state = selection.state().snapshot();
      observedSelections.put(
          selection,
          state instanceof UiObservation.Available<PickerState>
              ? state.value().accepted().map(PickerState.Accepted::operation).orElse(null)
              : null);
    }
  }

  private void pollSelections() {
    for (var selection : draftSelections) {
      var observed = selection.state().snapshot();
      if (!(observed instanceof UiObservation.Available<PickerState>)) continue;
      var state = observed.value();
      if (state.source() != PickerState.Source.READY || state.accepted().isEmpty()) continue;
      var accepted = state.accepted().orElseThrow();
      if (accepted.operation().equals(observedSelections.get(selection))) continue;
      observedSelections.put(selection, accepted.operation());
      if (state.stamp().filter(accepted.source()::equals).isPresent()
          && accepted.after().matches(editor.view().snapshot().orElseThrow())
          && automatic == Automatic.ACCEPTED_CHANGES
          && !explicitRequired) schedule();
    }
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
      draftSelections = List.of();
      observedSelections.clear();
      picker = null;
      auditionRequested = true;
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
    picker = null;
    auditionRequested = false;
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
          if (picker == null || followInspection(false)) request();
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
      boolean entering = !mounted;
      if (entering) {
        view = host.open(editor, provider);
        if (camera != null) view.camera(camera);
        mounted = true;
        activated = false;
        pending = false;
        failure = ValidationReport.VALID;
        subjectRevision = subjects.value().snapshot().revision();
        input = currentInput();
        resources = provider.resourceStamp();
        if (picker != null) inspectionGesture = picker.inspectionGestures().snapshot().value();
        selectionBaseline();
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
      if (!entering) pollSelections();
      if (picker != null && !followInspection(false)) {
        publish();
        return;
      }
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

  private boolean followInspection(boolean roleGesture) {
    long nextGesture = picker.inspectionGestures().snapshot().value();
    var value = picker.state().snapshot().value();
    if (value.source() != PickerState.Source.READY
        && value.source() != PickerState.Source.UNADMITTED)
      throw new PolicyRefusal(
          "preview.reference_unavailable",
          "Refresh the available profiles before preparing again.");
    var inspected = value.inspection();
    if (value.source() == PickerState.Source.UNADMITTED
        || value.inspecting() != PickerState.Phase.READY
        || inspected.filter(v -> v.status() == ReferenceResolution.Status.RESOLVED).isEmpty()) {
      pending = false;
      audition = null;
      basisDescription = auditionUnavailable();
      failure =
          new PolicyRefusal(
                  "preview.reference_unresolved", "Inspect a resolved profile before preparing it.")
              .report();
      if (view != null) view.invalidate();
      return false;
    }
    var next = picker.audition(role);
    next.requireCurrent(editor.actions(), DraftRevision.of(editor.view().snapshot().orElseThrow()));
    boolean changed = !next.sameSelection(audition);
    audition = next;
    var choice = inspected.orElseThrow();
    basisDescription =
        Component.translatableWithFallback(
            "reform.ui.preview_trying_profile",
            "Trying %s for %s",
            choice.title().orElse(choice.key()),
            roleTitle);
    if (changed) {
      failure = ValidationReport.VALID;
      if ((roleGesture || nextGesture != inspectionGesture)
          && automatic == Automatic.ACCEPTED_CHANGES
          && !explicitRequired) schedule();
      else if (view != null) view.invalidate();
    }
    inspectionGesture = nextGesture;
    return true;
  }

  /** Withdraw cached private source facts on the actual presentation refresh. */
  private UiObservation<State> observeStatus() {
    long version = Math.multiplyExact(cached.revision(), 2);
    try {
      if (auditionRequested && audition != null)
        audition.requireCurrent(
            editor.actions(), DraftRevision.of(editor.view().snapshot().orElseThrow()));
      var available = (UiObservation.Available<State>) cached;
      return new UiObservation.Available<>(available.data(), version);
    } catch (PolicyRefusal refused) {
      return new UiObservation.Unavailable<>(refused.report(), Optional.of(refused), version + 1);
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
    if (auditionRequested && audition == null)
      throw new PolicyRefusal(
          "preview.reference_unresolved", "Inspect a resolved profile before preparing it.");
    attempt = auditionRequested ? view.request(frozen, audition) : view.request(frozen);
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
    if (auditionRequested) basisDescription = auditionUnavailable();
    if (view != null) {
      view.refreshCurrentness();
      view.invalidate();
    }
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
            auditionRequested ? GraphicSourceStamp.Kind.AUDITION : GraphicSourceStamp.Kind.DRAFT);
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

  private static Component auditionUnavailable() {
    return Component.translatableWithFallback(
        "reform.ui.preview_audition_unavailable", "Profile audition unavailable");
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
