package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.CompletionParts;
import design.inherent.reform.minecraft.client.ui.DisclosureParts;
import design.inherent.reform.minecraft.client.ui.UiText;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.presentation.ui.FieldBinding;
import design.inherent.reform.presentation.ui.FieldState;
import design.inherent.reform.presentation.ui.UiBinding;
import design.inherent.reform.presentation.ui.UiRead;
import design.inherent.reform.presentation.ui.UiScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * One field family minted by the actual form. Descriptions can arrange its parts, but cannot
 * replace the field's writer, validation, caption meaning, or native input responder. Each active
 * family requires exactly one label, control, and feedback part; description is optional.
 */
public final class ClientFieldParts<V> {
  /**
   * An opaque field-owned surface. Its native-input slot is supplied by the form, not the caller.
   */
  public static final class Surface<V> {
    private final ClientFieldParts<V> field;
    private final ViewSpec content;

    private Surface(
        ClientFieldParts<V> field,
        ViewSpec replacement,
        UiBinding<Boolean> nativeExpanded,
        UiText nativeLabel,
        Optional<CompletionParts> completion) {
      this.field = field;
      completion.ifPresent(parts -> parts.suggestions().requireField(field.binding()));
      content =
          Views.column(
              "surface",
              List.of(
                  Objects.requireNonNull(replacement),
                  Views.disclosure(
                      "native-control",
                      nativeExpanded,
                      DisclosureParts.retained(
                          nativeLabel,
                          UiText.of(net.minecraft.network.chat.Component.empty()),
                          UiText.of(net.minecraft.network.chat.Component.empty()),
                          branch ->
                              completion
                                  .<ViewSpec>map(
                                      parts ->
                                          Views.column(
                                              "native",
                                              List.of(
                                                  new ViewSpec.NativeFieldInput("input", this),
                                                  new ViewSpec.FieldCompletion(
                                                      "completion", this, parts))))
                                  .orElseGet(
                                      () -> new ViewSpec.NativeFieldInput("input", this))))));
    }

    public ClientFieldParts<V> field() {
      return field;
    }

    public ViewSpec content() {
      return content;
    }
  }

  public enum Role {
    LABEL,
    DESCRIPTION,
    CONTROL,
    FEEDBACK
  }

  private final UiScope owner;
  private final FieldBinding<?, V> binding;
  private final UiText label, description;
  private Supplier<List<AbstractWidget>> controls;
  private Supplier<Optional<ClientTextInput>> input;
  private Supplier<List<AbstractWidget>> nativeControls;

  ClientFieldParts(
      UiScope owner,
      FieldBinding<?, V> binding,
      UiText label,
      UiText description,
      Supplier<List<AbstractWidget>> controls,
      Supplier<Optional<ClientTextInput>> input) {
    this(owner, binding, label, description, controls, controls, input);
  }

  ClientFieldParts(
      UiScope owner,
      FieldBinding<?, V> binding,
      UiText label,
      UiText description,
      Supplier<List<AbstractWidget>> controls,
      Supplier<List<AbstractWidget>> nativeControls,
      Supplier<Optional<ClientTextInput>> input) {
    this.owner = Objects.requireNonNull(owner);
    this.binding = Objects.requireNonNull(binding);
    this.label = Objects.requireNonNull(label);
    this.description = Objects.requireNonNull(description);
    this.controls = Objects.requireNonNull(controls);
    this.input = Objects.requireNonNull(input);
    this.nativeControls = Objects.requireNonNull(nativeControls);
    owner.onRetire(
        () -> {
          this.controls = null;
          this.input = null;
          this.nativeControls = null;
        });
  }

  public FieldBinding<?, V> binding() {
    return binding;
  }

  public UiRead<FieldState<V>> state() {
    return binding.state();
  }

  /** The original field's native input/ack and edit guards, not another input or writer. */
  public design.inherent.reform.presentation.ui.FieldFrontend frontend() {
    owner.requireCurrent();
    return binding.frontend();
  }

  /** The same native text command path, if this field has a text input rather than a cycle. */
  public Optional<ClientTextInput> input() {
    owner.requireCurrent();
    return input.get();
  }

  public ViewSpec label(String key) {
    return new ViewSpec.FieldPart(key, this, Role.LABEL);
  }

  public ViewSpec description(String key) {
    return new ViewSpec.FieldPart(key, this, Role.DESCRIPTION);
  }

  public ViewSpec control(String key) {
    return new ViewSpec.FieldPart(key, this, Role.CONTROL);
  }

  /**
   * Replace the default control presentation while keeping its original input reachable through a
   * retained disclosure. The label and feedback parts remain mandatory. This creates no widget or
   * alternate edit callback; all input responders and guarded commands remain authoritative.
   */
  public ViewSpec controlSurface(
      String key, ViewSpec replacement, UiBinding<Boolean> nativeExpanded, UiText nativeLabel) {
    owner.requireCurrent();
    return new ViewSpec.FieldSurface(
        key,
        new Surface<>(
            this,
            replacement,
            Objects.requireNonNull(nativeExpanded),
            Objects.requireNonNull(nativeLabel),
            Optional.empty()));
  }

  /** Completion borrows this same field's original input; it cannot provide another writer. */
  public ViewSpec controlSurface(
      String key,
      ViewSpec replacement,
      UiBinding<Boolean> nativeExpanded,
      UiText nativeLabel,
      CompletionParts completion) {
    owner.requireCurrent();
    return new ViewSpec.FieldSurface(
        key,
        new Surface<>(
            this,
            replacement,
            Objects.requireNonNull(nativeExpanded),
            Objects.requireNonNull(nativeLabel),
            Optional.of(Objects.requireNonNull(completion))));
  }

  public ViewSpec feedback(String key) {
    return new ViewSpec.FieldPart(key, this, Role.FEEDBACK);
  }

  public ViewSpec standard(String key) {
    return Views.column(
        key,
        List.of(
            label("label"), description("description"), control("control"), feedback("feedback")));
  }

  boolean belongsTo(UiScope root) {
    return owner == root && owner.isOpen();
  }

  UiText labelText() {
    return label;
  }

  UiText descriptionText() {
    return description;
  }

  List<AbstractWidget> widgets() {
    owner.requireCurrent();
    return List.copyOf(controls.get());
  }

  List<AbstractWidget> nativeWidgets() {
    owner.requireCurrent();
    return List.copyOf(nativeControls.get());
  }
}
