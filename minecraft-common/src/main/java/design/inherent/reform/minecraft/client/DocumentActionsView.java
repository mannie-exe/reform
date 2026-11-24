package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.DocumentActionsScreen.Group;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.SectionNavigation;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Explicit secondary actions over a retained owner; this view owns no editing model. */
final class DocumentActionsView extends MetadataView {
  private final DocumentActions<?> actions;
  private final Component owner, context;
  private SectionNavigation.Bookmark viewport;
  private String retainedFocus;
  private boolean returnFocused;
  private final List<Group> groups;
  private final BooleanSupplier available;
  private final Runnable back, abandoned;
  private FormPanel panel;
  private FormChrome chrome;
  private Object generation;
  private boolean departed;

  DocumentActionsView(
      DocumentActions<?> actions,
      Component owner,
      Component context,
      List<Group> groups,
      BooleanSupplier available,
      Runnable back,
      Runnable abandoned) {
    super(text("document_actions_title", "Document actions"));
    this.actions = Objects.requireNonNull(actions);
    this.owner = owner.copy();
    this.context = context.copy();
    this.groups = List.copyOf(groups);
    if (groups.isEmpty() || groups.size() > 8)
      throw new IllegalArgumentException("Document actions must be bounded");
    this.available = Objects.requireNonNull(available);
    this.back = Objects.requireNonNull(back);
    this.abandoned = Objects.requireNonNull(abandoned);
  }

  boolean borrows(DocumentActions<?> actual) {
    return actions == actual;
  }

  design.inherent.reform.definition.DocumentId document() {
    return actions.definition().id();
  }

  @Override
  FormPanel primary() {
    return panel;
  }

  @Override
  void preserve() {
    if (panel == null || !visible) return;
    viewport = panel.bookmark();
    if (getFocused() == panel) {
      retainedFocus = panel.focusKey();
      returnFocused = false;
    } else if (getFocused() instanceof Button) {
      retainedFocus = null;
      returnFocused = true;
    }
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
    departed = true;
  }

  @Override
  boolean ownsFocus(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    return focused == panel;
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  @Override
  protected void init() {
    visible = true;
    preserve();
    String focus = retainedFocus;
    generation = new Object();
    var token = generation;
    var close =
        ClientControls.action(
                font, text("back_to_settings", "Back to settings"), this::onClose, theme())
            .build();
    chrome =
        new FormChrome(
            minecraft,
            new FormGeometry.Placement(left, top, width, height),
            composed ? Component.empty() : title,
            Component.empty(),
            Component.empty(),
            List.of(close),
            theme());
    panel = chrome.panel();
    chrome.mountActions(this::addRenderableWidget, panel);
    if (composed) {
      prefix.accept(panel);
      panel.heading(title);
    }
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
                                  if (departed
                                      || generation != token
                                      || !currentHost()
                                      || !available.getAsBoolean()
                                      || !action.enabled().getAsBoolean()) return;
                                  departed = true;
                                  back.run();
                                  action.run().run();
                                },
                                theme())
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
    if (composed) suffix.accept(panel);
    panel.finish(0);
    if (viewport != null) panel.restore(viewport);
    addRenderableWidget(panel);
    if (focus == null || returnFocused) setFocused(close);
    else {
      setFocused(panel);
      panel.restoreFocus(focus);
    }
  }

  @Override
  public void onClose() {
    if (departed || minecraft == null || !currentHost()) return;
    departed = true;
    back.run();
  }

  @Override
  public void removed() {
    if (!departed) {
      departed = true;
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
