package design.inherent.reform.minecraft.client;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Explicit secondary actions over a retained owner; this screen owns no editing model. */
final class DocumentActionsScreen extends NativeFormScreen {
  record Action(Component title, Component help, BooleanSupplier enabled, Runnable run) {
    Action {
      title = Objects.requireNonNull(title).copy();
      help = Objects.requireNonNull(help).copy();
      Objects.requireNonNull(enabled);
      Objects.requireNonNull(run);
    }
  }

  record Group(Component title, List<Action> actions) {
    Group {
      title = Objects.requireNonNull(title).copy();
      actions = List.copyOf(actions);
      if (actions.isEmpty() || actions.size() > 16)
        throw new IllegalArgumentException("Action groups must be bounded");
    }
  }

  private final Component owner, context;
  private final List<Group> groups;
  private final BooleanSupplier available;
  private final Runnable back, abandoned;
  private FormPanel panel;
  private FormChrome chrome;
  private Object generation;
  private boolean left;

  DocumentActionsScreen(
      Component owner,
      Component context,
      List<Group> groups,
      BooleanSupplier available,
      Runnable back,
      Runnable abandoned) {
    super(text("document_actions_title", "Document actions"));
    this.owner = owner.copy();
    this.context = context.copy();
    this.groups = List.copyOf(groups);
    if (groups.isEmpty() || groups.size() > 8)
      throw new IllegalArgumentException("Document actions must be bounded");
    this.available = Objects.requireNonNull(available);
    this.back = Objects.requireNonNull(back);
    this.abandoned = Objects.requireNonNull(abandoned);
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  @Override
  protected void init() {
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel != null && getFocused() == panel ? panel.focusKey() : null;
    generation = new Object();
    var token = generation;
    var close =
        ClientControls.action(font, text("back_to_settings", "Back to settings"), this::onClose)
            .build();
    chrome =
        new FormChrome(
            minecraft, width, height, title, Component.empty(), Component.empty(), List.of(close));
    panel = chrome.panel();
    chrome.mountActions(this::addRenderableWidget, panel);
    panel.heading(owner);
    panel.row(Component.empty(), context);
    panel.row(
        Component.empty(),
        text(
            "whole_document_actions",
            "These actions use the whole document, including settings outside the current section."));
    int index = 0;
    for (var group : groups) {
      panel.beginGroup("actions:" + index++, group.title());
      var buttons =
          group.actions().stream()
              .map(
                  action -> {
                    var button =
                        ClientControls.action(
                                font,
                                action.title(),
                                () -> {
                                  if (left
                                      || generation != token
                                      || NativeScreens.current(minecraft) != this
                                      || !available.getAsBoolean()
                                      || !action.enabled().getAsBoolean()) return;
                                  left = true;
                                  back.run();
                                  action.run().run();
                                })
                            .createNarration(
                                original ->
                                    owner
                                        .copy()
                                        .append(": ")
                                        .append(original.get())
                                        .append(". ")
                                        .append(action.help()))
                            .build();
                    button.active = available.getAsBoolean() && action.enabled().getAsBoolean();
                    if (!action.help().getString().isBlank())
                      button.setTooltip(Tooltip.create(action.help()));
                    return button;
                  })
              .toArray(Button[]::new);
      panel.actions("actions:buttons:" + index, buttons);
      panel.endGroup();
    }
    panel.finish(scroll);
    addRenderableWidget(panel);
    if (focus == null) setFocused(close);
    else {
      setFocused(panel);
      panel.restoreFocus(focus);
    }
  }

  @Override
  public void onClose() {
    if (left || minecraft == null || NativeScreens.current(minecraft) != this) return;
    left = true;
    back.run();
  }

  @Override
  public void removed() {
    if (!left) {
      left = true;
      abandoned.run();
    }
  }

  @Override
  public void render(FormCanvas canvas, int x, int y, float delta) {
    super.render(canvas, x, y, delta);
    chrome.render(canvas);
  }

  @Override
  public Component getNarrationMessage() {
    return title.copy().append(". ").append(owner).append(". ").append(context);
  }
}
