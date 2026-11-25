package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.UiText;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.presentation.ui.FieldBinding;
import design.inherent.reform.presentation.ui.FieldState;
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

  ClientFieldParts(
      UiScope owner,
      FieldBinding<?, V> binding,
      UiText label,
      UiText description,
      Supplier<List<AbstractWidget>> controls,
      Supplier<Optional<ClientTextInput>> input) {
    this.owner = Objects.requireNonNull(owner);
    this.binding = Objects.requireNonNull(binding);
    this.label = Objects.requireNonNull(label);
    this.description = Objects.requireNonNull(description);
    this.controls = Objects.requireNonNull(controls);
    this.input = Objects.requireNonNull(input);
    owner.onRetire(
        () -> {
          this.controls = null;
          this.input = null;
        });
  }

  public FieldBinding<?, V> binding() {
    return binding;
  }

  public UiRead<FieldState<V>> state() {
    return binding.state();
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
}
