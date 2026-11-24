package design.inherent.reform.minecraft.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Measured native choices; owners retain admission, effects, and persistence. */
public final class ClientChoiceScreen extends FormHostScreen {
  /**
   * Blocked reasons read cached owner state only; selection checks them again before the action.
   */
  public record Choice(
      String id,
      Component label,
      Component help,
      Supplier<Optional<Component>> blocked,
      Runnable selected) {
    public Choice {
      if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9._/-]{0,127}"))
        throw new IllegalArgumentException("A choice needs a stable bounded identity");
      label = Objects.requireNonNull(label).copy();
      help = Objects.requireNonNull(help).copy();
      Objects.requireNonNull(blocked);
      Objects.requireNonNull(selected);
      if (label.getString().isBlank()
          || label.getString().length() > 1024
          || help.getString().length() > 4096)
        throw new IllegalArgumentException("Choice captions must be bounded");
    }

    @Override
    public Component label() {
      return label.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  private final ChoiceView view;
  private boolean borrowed, mounted;

  public ClientChoiceScreen(
      Screen parent, Component title, Component explanation, List<Choice> choices) {
    this(
        parent,
        title,
        explanation,
        choices,
        Component.translatableWithFallback("reform.settings.back", "Back"));
  }

  public ClientChoiceScreen(
      Screen parent,
      Component title,
      Component explanation,
      List<Choice> choices,
      Component backLabel) {
    this(parent, title, explanation, choices, backLabel, 64);
  }

  ClientChoiceScreen(
      Screen parent,
      Component title,
      Component explanation,
      List<Choice> choices,
      Component backLabel,
      int maximumChoices) {
    super(Objects.requireNonNull(title).copy(), parent);
    view =
        new ChoiceView(
            title,
            explanation,
            choices,
            backLabel,
            () -> NativeScreens.open(net.minecraft.client.Minecraft.getInstance(), parent),
            maximumChoices);
  }

  ChoiceView borrowView() {
    if (borrowed || mounted) throw new IllegalStateException("Choices already have a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed) throw new IllegalStateException("Choices belong to their invoking workspace");
    mounted = true;
    suspendView();
    view.presentation(theme(), taskPresentation());
    mount(view);
  }

  @Override
  protected void beforeRebuild() {
    view.preserve();
  }
}
