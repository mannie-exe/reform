package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** An inline native control over the form's existing input, validation, and edit pipeline. */
@FunctionalInterface
public interface ClientControlAdapter {
  /**
   * Construct without editing or changing screens. The host owns mounting and disposal. A wrapper
   * may keep {@link Binding#nativeControl()}; a replacement dispatches through {@link
   * Binding#editRaw}.
   */
  Control create(Binding binding);

  /** Native text selection, independent of the actor's accepted value. */
  record Selection(int cursor, int anchor) {
    public Selection {
      if (cursor < 0 || anchor < 0)
        throw new IllegalArgumentException("A control selection must be nonnegative");
    }
  }

  /**
   * Cached local input and owner feedback. Reading this performs no owner or resource operation.
   */
  record State(
      String raw,
      boolean editable,
      int maximumCharacters,
      ValidationReport validation,
      ClientTheme theme,
      Optional<Selection> selection) {
    public State {
      Objects.requireNonNull(raw);
      Objects.requireNonNull(validation);
      Objects.requireNonNull(theme);
      Objects.requireNonNull(selection);
      if (maximumCharacters < raw.length())
        throw new IllegalArgumentException("The current input must remain representable");
    }
  }

  /**
   * One native root, its intrinsic offer, and presentation callbacks. Synchronization must only
   * display the supplied state; it must not save, apply, or retain another value authority. Native
   * input/narration and consumed child events remain the adapter's responsibility. Selection is
   * captured before detaching; disposal runs once after editing authority has been revoked.
   */
  record Control(
      AbstractWidget widget,
      FormGeometry.Size preferred,
      Consumer<State> synchronize,
      Supplier<Optional<Selection>> selection,
      Runnable dispose) {
    public Control {
      Objects.requireNonNull(widget);
      Objects.requireNonNull(preferred);
      Objects.requireNonNull(synchronize);
      Objects.requireNonNull(selection);
      Objects.requireNonNull(dispose);
      if (preferred.width() < design.inherent.reform.presentation.FormTokens.Control.MINIMUM_WIDTH
          || preferred.height() < design.inherent.reform.presentation.FormTokens.Control.HEIGHT)
        throw new IllegalArgumentException("A native field control needs a usable intrinsic offer");
    }

    public Control(
        AbstractWidget widget, FormGeometry.Size preferred, Consumer<State> synchronize) {
      this(widget, preferred, synchronize, Optional::empty, () -> {});
    }
  }

  /** Restricted field interaction; no writer, owner actions, or authority to submit a document. */
  final class Binding {
    private final FieldPath path;
    private final ValueShape shape;
    private final Component label;
    private final AbstractWidget nativeControl;
    private final Supplier<State> state;
    private final Consumer<String> edits;
    private final Supplier<Optional<Selection>> nativeSelection;

    Binding(
        FieldPath path,
        ValueShape shape,
        Component label,
        AbstractWidget nativeControl,
        Supplier<State> state,
        Consumer<String> edits,
        Supplier<Optional<Selection>> nativeSelection) {
      this.path = path;
      this.shape = shape;
      this.label = label.copy();
      this.nativeControl = nativeControl;
      this.state = state;
      this.edits = edits;
      this.nativeSelection = nativeSelection;
    }

    public FieldPath path() {
      return path;
    }

    public ValueShape shape() {
      return shape;
    }

    public Component label() {
      return label.copy();
    }

    /** Borrowed standard control, for wrappers; never close it or change its responder. */
    public AbstractWidget nativeControl() {
      return nativeControl;
    }

    /** Exact selection of the borrowed standard input, without private native access. */
    public Optional<Selection> nativeSelection() {
      return nativeSelection.get();
    }

    public State state() {
      return state.get();
    }

    /**
     * Request one existing raw-input edit on the client thread. Local lifetime/availability checks
     * precede dispatch; actual owner admission remains authoritative. A refused value stays in the
     * same form input and blocks submission normally. Synchronization echoes do not dispatch.
     */
    public void editRaw(String raw) {
      edits.accept(Objects.requireNonNull(raw));
    }
  }
}
