package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.UiRead;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** Fixed or explicitly dependent text, projected and copied on updates rather than native paint. */
public final class UiText {
  private final List<UiRead<?>> inputs;
  private final Supplier<Component> text;

  private UiText(List<UiRead<?>> inputs, Supplier<Component> text) {
    this.inputs = inputs;
    this.text = text;
  }

  public static UiText of(Component text) {
    var captured = bounded(text);
    return new UiText(List.of(), captured::copy);
  }

  public static <T> UiText from(UiRead<T> input, Function<T, Component> projection) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(projection);
    return new UiText(List.of(input), () -> bounded(projection.apply(input.snapshot().value())));
  }

  public List<UiRead<?>> dependencies() {
    return inputs;
  }

  /** Host projection entry; callers must enter the scope's declared pure projection phase. */
  public Component resolve() {
    return bounded(text.get());
  }

  private static Component bounded(Component value) {
    Objects.requireNonNull(value);
    if (value.getString().length() > 4096)
      throw new IllegalArgumentException("Component text exceeds its declared allowance");
    return value.copy();
  }
}
