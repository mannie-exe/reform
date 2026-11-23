package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.DraftPreviewInput;
import java.util.Objects;
import net.minecraft.client.gui.screens.Screen;

/** One actual client editor/session and its retained generated view; never owns its document. */
public final class ClientEditor<T> implements AutoCloseable {
  private final SettingsScreen<T> screen;
  private final DocumentActions<T> actions;
  private Screen host;
  private Runnable abandoned;
  private boolean closed;
  private final java.util.Set<ClientPreviewCapture<T>> previewCaptures =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

  private ClientEditor(SettingsScreen<T> screen) {
    ClientSettingsRuntime.requireClientThread();
    this.screen = Objects.requireNonNull(screen);
    actions = screen.editingActions();
    screen.borrow(this::abandon);
  }

  public static <T> ClientEditor<T> retain(SettingsScreen<T> screen) {
    return new ClientEditor<>(screen);
  }

  public DocumentId document() {
    return actions.definition().id();
  }

  public boolean isOpen() {
    return !closed && actions.isOpen();
  }

  public DocumentActions<T> actions() {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen())
      throw new PolicyRefusal("editor.closed", "This client editing session is closed");
    return actions;
  }

  /**
   * Actual cached source view; unavailable/closed is not replaced with another editor or default.
   */
  public EditorActor.View<T> view() {
    return actions.view();
  }

  /** The actual native entry/return screen, including a workflow's shared root. */
  public Screen screen() {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen())
      throw new PolicyRefusal("editor.closed", "This client editing session is closed");
    return host == null ? screen : host;
  }

  /** The same retained fields and draft, without treating their presentation as another screen. */
  public SettingsForm<T> form() {
    return presentation();
  }

  SettingsView<T> presentation() {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen())
      throw new PolicyRefusal("editor.closed", "This client editing session is closed");
    return screen.content();
  }

  void bindHost(Screen root) {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen() || host != null)
      throw new IllegalStateException(
          "Bind a retained editor's native host once before presentation");
    host = Objects.requireNonNull(root);
  }

  public boolean dirty() {
    return !closed && (actions.view().dirty() || screen.retainedInput());
  }

  public boolean busy() {
    return !closed
        && (screen.pendingWork()
            || actions.hasActiveOperations()
            || actions.view().pending().isPresent());
  }

  /**
   * Client-thread presentation guard for a custom submission action. Unaccepted native input,
   * pending edits and required review refuse submission rather than saving only the actor's draft.
   * This performs no flush, validation, IO or authorization; check again immediately before using
   * the actual actions. A true result never replaces their owner and source checks.
   */
  public boolean canSubmit() {
    ClientSettingsRuntime.requireClientThread();
    return isOpen() && !busy() && screen.canSubmit();
  }

  /** Cached native-input preflight for an explicit field edit; actual owner checks still apply. */
  public boolean canStage(design.inherent.reform.definition.FieldPath path) {
    ClientSettingsRuntime.requireClientThread();
    return isOpen() && !busy() && presentation().canStage(path);
  }

  /** Capture one complete READ-authorized draft, independent of required save-review scope. */
  public ClientPreviewCapture<T> capturePreview(EntryPoint entry, DraftRevision expected) {
    ClientSettingsRuntime.requireClientThread();
    var view = presentation();
    var stamp = view.previewInputStamp(Objects.requireNonNull(expected));
    var references = view.previewReferenceStamps();
    var capture =
        new ClientPreviewCapture<>(
            actions.capturePreview(entry, expected),
            captured -> {
              if (!isOpen())
                throw new PolicyRefusal("editor.closed", "This client editor is closed");
              var current = view.previewInputStamp(expected);
              if (!stamp.equals(current) || !references.equals(view.previewReferenceStamps()))
                throw new PolicyRefusal(
                    "preview.stale", "Native input or reference sources changed during capture");
              return new DraftPreviewInput<>(
                  stamp,
                  captured.candidate().orElseThrow(),
                  captured.observation().provenance(),
                  references);
            });
    previewCaptures.add(capture);
    capture.completion().whenComplete((ignored, failed) -> previewCaptures.remove(capture));
    return capture;
  }

  /** Cached client-thread freshness and actual READ checks, without IO, capture, or validation. */
  public boolean isPreviewCurrent(DraftPreviewInput<?> input) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(input);
    if (!isOpen()) return false;
    try {
      var view = presentation();
      return input.stamp().equals(view.previewInputStamp(input.stamp().draft()))
          && input.observedReferences().equals(view.previewReferenceStamps());
    } catch (PolicyRefusal unavailable) {
      return false;
    }
  }

  void onAbandoned(Runnable owner) {
    ClientSettingsRuntime.requireClientThread();
    if (closed || abandoned != null)
      throw new IllegalStateException("Editor ownership is already transferred or closed");
    abandoned = Objects.requireNonNull(owner);
  }

  private void abandon() {
    var owner = abandoned;
    close();
    if (owner != null) owner.run();
  }

  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    if (closed) return;
    closed = true;
    for (var capture : java.util.List.copyOf(previewCaptures)) capture.abandon();
    previewCaptures.clear();
    abandoned = null;
    host = null;
    try {
      // Context closure already closes its actor/scope; its old native executor may be gone.
      if (actions.isOpen()) actions.close();
    } finally {
      screen.releaseBorrowed();
    }
  }
}
