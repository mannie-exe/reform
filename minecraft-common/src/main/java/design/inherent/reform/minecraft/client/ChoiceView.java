package design.inherent.reform.minecraft.client;

import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormTokens;
import java.util.*;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Same cached choices and native controls in a standalone host or retained workspace. */
final class ChoiceView extends MetadataView {
  private final Component explanation, backLabel;
  private final List<ClientChoiceScreen.Choice> choices;
  private Runnable returned;
  private Map<String, Optional<Component>> reasons = Map.of();
  private FormPanel panel;
  private FormChrome chrome;
  private net.minecraft.client.gui.components.Button back;
  private boolean backFocused;
  private net.minecraft.locale.Language language;
  private Object generation;
  private design.inherent.reform.presentation.SectionNavigation.Bookmark viewport;
  private String focus;
  private boolean closed;
  private Component notice;

  ChoiceView(
      Component title,
      Component explanation,
      List<ClientChoiceScreen.Choice> choices,
      Component backLabel,
      Runnable returned,
      int maximumChoices) {
    super(Objects.requireNonNull(title).copy());
    if (maximumChoices < 1 || maximumChoices > 256)
      throw new IllegalArgumentException("Choice admission is bounded to 256");
    this.explanation = Objects.requireNonNull(explanation).copy();
    this.choices = List.copyOf(choices);
    this.backLabel = Objects.requireNonNull(backLabel).copy();
    this.returned = Objects.requireNonNull(returned);
    var ids = new HashSet<String>();
    if (choices.isEmpty()
        || choices.size() > maximumChoices
        || choices.stream().anyMatch(choice -> !ids.add(choice.id())))
      throw new IllegalArgumentException("Choices require bounded distinct identities");
  }

  void returned(Runnable action) {
    if (minecraft != null) throw new IllegalStateException("Bind the return before mounting");
    returned = Objects.requireNonNull(action);
  }

  @Override
  FormPanel primary() {
    return panel;
  }

  @Override
  void preserve() {
    if (panel == null || !visible) return;
    backFocused = getFocused() == back || getFocused() == panel && panel.getFocused() == back;
    viewport = panel.bookmark();
    focus = panel.focusKey();
  }

  @Override
  void hide() {
    preserve();
    visible = false;
    generation = new Object();
  }

  @Override
  void retireForNavigation() {
    hide();
    closed = true;
  }

  @Override
  boolean ownsFocus(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    return focused == panel || focused == back;
  }

  private Map<String, Optional<Component>> readiness() {
    var result = new LinkedHashMap<String, Optional<Component>>();
    for (var choice : choices)
      result.put(choice.id(), Objects.requireNonNull(choice.blocked().get()).map(Component::copy));
    return Map.copyOf(result);
  }

  @Override
  protected void init() {
    visible = true;
    generation = new Object();
    var token = generation;
    reasons = readiness();
    language = net.minecraft.locale.Language.getInstance();
    back =
        ClientControls.action(
                font,
                backCaption(backLabel),
                () -> {
                  if (!closed && generation == token && currentHost()) onClose();
                },
                theme())
            .build();
    var content =
        FormGeometry.centeredWidth(
            new FormGeometry.Placement(left, top, width, height),
            Math.addExact(
                Math.multiplyExact(font.width("M"), FormTokens.Measure.READING_COLUMNS),
                2 * FormTokens.Space.SCREEN));
    chrome =
        new FormChrome(
            minecraft,
            content,
            composed ? Component.empty() : title,
            composed ? Component.empty() : explanation,
            Component.empty(),
            List.of(back),
            theme());
    panel = chrome.panel();
    if (composed) {
      prefix.accept(panel);
      panel.heading(title);
      panel.row("explanation", Component.empty(), explanation);
    }
    if (notice != null) panel.row("choice:notice", Component.empty(), notice);
    for (var choice : choices) {
      var reason = reasons.get(choice.id());
      var button =
          ClientControls.action(
                  font,
                  choice.label(),
                  () -> {
                    if (closed || generation != token || !currentHost()) return;
                    if (choice.blocked().get().isPresent()) {
                      rebuildWidgets();
                      return;
                    }
                    try {
                      choice.selected().run();
                    } catch (PolicyRefusal refused) {
                      notice =
                          ClientStatusScreen.reason(
                              refused.report(),
                              Component.translatableWithFallback(
                                  "reform.choice.unavailable",
                                  "That choice is unavailable. Review the reason and try again."));
                      if (currentHost()) rebuildWidgets();
                    }
                  },
                  theme())
              .createNarration(
                  original ->
                      original
                          .get()
                          .copy()
                          .append("\n")
                          .append(choice.help())
                          .append(
                              reason
                                  .map(value -> Component.literal("\n").append(value))
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
    if (composed) suffix.accept(panel);
    chrome.mountActions(this::addRenderableWidget, panel);
    panel.finish(0);
    if (viewport != null) panel.restore(viewport);
    addRenderableWidget(panel);
    if (backFocused) {
      if (panel.children().contains(back)) {
        setFocused(panel);
        panel.setFocused(back);
        back.setFocused(true);
      } else setFocused(back);
    } else {
      setFocused(panel);
      if (focus == null) panel.focusVisibleControl();
      else panel.restoreFocus(focus);
    }
  }

  @Override
  protected void rebuildWidgets() {
    preserve();
    super.rebuildWidgets();
  }

  @Override
  public void tick() {
    if (!closed
        && currentHost()
        && (language != net.minecraft.locale.Language.getInstance()
            || !reasons.equals(readiness()))) rebuildWidgets();
  }

  @Override
  public void onClose() {
    if (!closed && currentHost()) returned.run();
  }

  @Override
  public void removed() {
    hide();
  }

  @Override
  public void render(FormCanvas canvas, int x, int y, float delta) {
    super.render(canvas, x, y, delta);
    chrome.render(canvas);
  }

  @Override
  public Component getNarrationMessage() {
    return composed ? explanation.copy() : title.copy().append("\n").append(explanation);
  }
}
