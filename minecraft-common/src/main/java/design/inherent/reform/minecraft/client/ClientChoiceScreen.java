package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormTokens;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Measured native entry choices. The caller retains permission, admission and action ownership. */
public final class ClientChoiceScreen extends NativeFormScreen {
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

  private final Screen parent;
  private final Component explanation, backLabel;
  private final List<Choice> choices;
  private Map<String, Optional<Component>> reasons = Map.of();
  private FormPanel panel;
  private FormChrome chrome;
  private Object generation;

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
    super(Objects.requireNonNull(title).copy());
    if (maximumChoices < 1 || maximumChoices > 256)
      throw new IllegalArgumentException("Choice admission is bounded to256");
    this.parent = parent;
    this.explanation = Objects.requireNonNull(explanation).copy();
    this.choices = List.copyOf(choices);
    this.backLabel = Objects.requireNonNull(backLabel).copy();
    var ids = new HashSet<String>();
    if (choices.isEmpty()
        || choices.size() > maximumChoices
        || choices.stream().anyMatch(choice -> !ids.add(choice.id())))
      throw new IllegalArgumentException("Choices require bounded distinct identities");
  }

  private Map<String, Optional<Component>> readiness() {
    var result = new LinkedHashMap<String, Optional<Component>>();
    for (var choice : choices)
      result.put(choice.id(), Objects.requireNonNull(choice.blocked().get()).map(Component::copy));
    return Map.copyOf(result);
  }

  @Override
  protected void init() {
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    generation = new Object();
    var token = generation;
    reasons = readiness();
    var back = ClientControls.action(font, backLabel, this::onClose).build();
    var content =
        FormGeometry.centeredWidth(
            new FormGeometry.Placement(0, 0, width, height),
            Math.addExact(
                Math.multiplyExact(font.width("M"), FormTokens.Measure.READING_COLUMNS),
                2 * FormTokens.Space.SCREEN));
    chrome =
        new FormChrome(minecraft, content, title, explanation, Component.empty(), List.of(back));
    panel = chrome.panel();
    for (var choice : choices) {
      var reason = reasons.get(choice.id());
      var button =
          ClientControls.action(
                  font,
                  choice.label(),
                  () -> {
                    if (generation != token || NativeScreens.current(minecraft) != this) return;
                    if (choice.blocked().get().isPresent()) {
                      rebuildWidgets();
                      return;
                    }
                    choice.selected().run();
                  })
              .createNarration(
                  original ->
                      original
                          .get()
                          .copy()
                          .append(". ")
                          .append(choice.help())
                          .append(
                              reason
                                  .map(value -> Component.literal(". ").append(value))
                                  .orElse(Component.empty())))
              .build();
      button.active = reason.isEmpty();
      reason.ifPresent(value -> button.setTooltip(Tooltip.create(value)));
      var help = choice.help().copy();
      reason.ifPresent(
          value -> {
            if (!help.getString().isEmpty()) help.append("\n");
            help.append(value);
          });
      panel.row("choice:" + choice.id(), Component.empty(), help, button);
    }
    chrome.mountActions(this::addRenderableWidget, panel);
    panel.finish(scroll);
    addRenderableWidget(panel);
    setFocused(panel);
    if (focus == null) panel.focusVisibleControl();
    else panel.restoreFocus(focus);
  }

  @Override
  public void tick() {
    if (NativeScreens.current(minecraft) == this && !reasons.equals(readiness())) rebuildWidgets();
  }

  @Override
  public void onClose() {
    NativeScreens.open(minecraft, parent);
  }

  @Override
  public void render(FormCanvas canvas, int x, int y, float delta) {
    super.render(canvas, x, y, delta);
    chrome.render(canvas);
  }

  @Override
  public Component getNarrationMessage() {
    return title.copy().append(". ").append(explanation);
  }
}
