package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.FieldInformation;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** One retained contextual capture; the native workspace owns its docked or inline presentation. */
final class WorkspaceInformation {
  private final Runnable rebuild;
  private final Function<FormInformation.Related, Optional<Runnable>> reveal;
  private final BooleanSupplier active;
  private SettingsView<?> owner;
  private FieldPath path;
  private Component label;
  private OperationHandle<FieldInformation> operation;
  private FieldInformation captured;
  private FormInformation.Subject metadata = FormInformation.Subject.EMPTY;
  private Optional<Component> numericBounds = Optional.empty();
  private Optional<Component> supplementalHelp = Optional.empty();
  private Component notice;
  private boolean stale;
  private boolean detailsExpanded;
  private Object widgets;
  private boolean focusRequested;
  private FormPanel dockedPanel;
  private design.inherent.reform.presentation.SectionNavigation.Bookmark dockedScroll;
  private String dockedFocus;
  private boolean dockedFocused;

  WorkspaceInformation(
      Runnable rebuild,
      Function<FormInformation.Related, Optional<Runnable>> reveal,
      BooleanSupplier active) {
    this.rebuild = rebuild;
    this.reveal = reveal;
    this.active = active;
  }

  boolean open() {
    return owner != null;
  }

  Optional<FieldInformation> observation() {
    return allowed() ? Optional.ofNullable(captured) : Optional.empty();
  }

  void show(SettingsView<?> owner, FieldPath path, Component label) {
    clear();
    this.owner = owner;
    this.path = path;
    this.label = label.copy();
    focusRequested = true;
    refresh();
    rebuild.run();
  }

  void clear() {
    if (operation != null && !operation.snapshot().terminal()) operation.requestCancellation();
    owner = null;
    path = null;
    label = null;
    operation = null;
    captured = null;
    metadata = FormInformation.Subject.EMPTY;
    numericBounds = Optional.empty();
    supplementalHelp = Optional.empty();
    notice = null;
    stale = false;
    detailsExpanded = false;
    widgets = null;
    focusRequested = false;
    dockedPanel = null;
    dockedScroll = null;
    dockedFocus = null;
    dockedFocused = false;
  }

  void close() {
    var returnedOwner = owner;
    var returnedField = path;
    clear();
    rebuild.run();
    if (returnedOwner != null && returnedOwner.mayInspect())
      returnedOwner.focusAfterLoad(returnedField);
  }

  private boolean allowed() {
    return owner != null && active.getAsBoolean() && owner.mayInspect();
  }

  /** Must run before native child rendering, so retired disclosure is never left in a panel. */
  boolean guard() {
    if (owner == null || allowed()) return false;
    clear();
    return true;
  }

  boolean tick() {
    if (guard()) return true;
    if (owner == null) return false;
    boolean changed = false;
    if (operation != null && operation.snapshot().terminal()) {
      var outcome = operation.snapshot();
      operation = null;
      captured = outcome.result().orElse(null);
      metadata = FormInformation.Subject.EMPTY;
      numericBounds = Optional.empty();
      supplementalHelp = Optional.empty();
      if (captured != null) {
        numericBounds = owner.numericBounds(captured);
        supplementalHelp = owner.supplementalHelp(captured);
        try {
          metadata = owner.describeInformation(captured);
          notice = null;
        } catch (RuntimeException unavailable) {
          notice = text("owner_unavailable", "Additional owner information is unavailable.");
        }
      } else
        notice =
            ClientStatusScreen.reason(
                outcome.report(),
                text(
                    "unavailable",
                    "Information is unavailable. Refresh after the current work settles."));
      changed = true;
    }
    boolean currentStale =
        captured != null
            && owner
                .editorView()
                .snapshot()
                .filter(value -> captured.target().matches(value))
                .isEmpty();
    if (stale != currentStale) {
      stale = currentStale;
      changed = true;
    }
    return changed;
  }

  private void refresh() {
    if (!allowed()) {
      clear();
      return;
    }
    captured = null;
    metadata = FormInformation.Subject.EMPTY;
    numericBounds = Optional.empty();
    supplementalHelp = Optional.empty();
    notice = null;
    stale = false;
    try {
      var snapshot = owner.editorView().snapshot().orElseThrow();
      operation = owner.editingActions().information(EntryPoint.GUI, snapshot.target(path));
    } catch (RuntimeException refused) {
      operation = null;
      notice =
          text(
              "unavailable", "Information is unavailable. Refresh after the current work settles.");
    }
  }

  void append(FormPanel panel) {
    if (dockedPanel != null) focusRequested = true;
    dockedPanel = null;
    contents(panel);
  }

  private void contents(FormPanel panel) {
    if (guard() || owner == null) return;
    var token = new Object();
    widgets = token;
    panel.section(
        "workspace:information",
        Component.translatableWithFallback("reform.information.about", "About %s", label));
    var close =
        button(
            token,
            text("close", "Close information"),
            () -> {
              close();
            });
    var refresh =
        button(
            token,
            text("refresh", "Refresh"),
            () -> {
              refresh();
              rebuild.run();
            });
    refresh.active = operation == null;
    panel.row(
        "workspace:information/actions", Component.empty(), Component.empty(), close, refresh);
    if (operation != null) panel.row(Component.empty(), text("loading", "Reading information…"));
    if (notice != null) panel.row(Component.empty(), notice);
    if (captured == null) return;
    if (stale)
      panel.row(
          text("earlier", "Earlier observation"),
          text("changed", "The draft changed. Refresh to inspect its current values."));
    if (!metadata.purpose().getString().isBlank()) panel.row(Component.empty(), metadata.purpose());
    supplementalHelp.ifPresent(value -> panel.row(Component.empty(), value));
    for (var fact : metadata.facts()) panel.row(fact.label(), fact.value());
    numericBounds.ifPresent(value -> panel.row(text("numeric_bounds", "Numeric bounds"), value));
    captured
        .effectiveObservation()
        .ifPresent(
            observed -> {
              if (!observed.observationReport().valid())
                panel.row(
                    text("effective_observation", "Effective observation"),
                    ClientStatusScreen.reason(
                        observed.observationReport(),
                        text(
                            "effective_unknown", "The current effective value is not confirmed.")));
              if (!observed.captureReport().valid())
                panel.row(
                    Component.empty(),
                    text(
                        "effective_unavailable", "Effective value details could not be captured."));
            });
    captured
        .application()
        .ifPresent(
            rule ->
                panel.row(
                    text("application", "Application"), Component.literal(rule.explanation())));
    if (!captured.observationReport().valid())
      panel.row(
          text("source_observation", "Source observation"),
          ClientStatusScreen.reason(
              captured.observationReport(),
              text("source_unknown", "The current source is not confirmed.")));
    if (!metadata.related().isEmpty()) {
      panel.heading(text("related", "Related settings"));
      for (var link : metadata.related()) {
        var destination = reveal.apply(link);
        var go =
            button(
                token, text("reveal", "Show setting"), () -> destination.ifPresent(Runnable::run));
        go.active = destination.isPresent();
        if (!go.active)
          go.setTooltip(
              Tooltip.create(
                  text("related_unavailable", "That setting is unavailable in this workspace.")));
        panel.row(
            "workspace:information/related/"
                + link.document()
                + ":"
                + design.inherent.reform.presentation.FormModel.key(link.path()),
            link.title(),
            Component.empty(),
            go);
      }
    }
    if (metadata.technicalDetails() == FormInformation.TechnicalDetails.DISCLOSURE) {
      panel.row(
          "workspace:information/details",
          Component.empty(),
          Component.empty(),
          button(
              token,
              detailsExpanded
                  ? text("hide_details", "Hide technical details")
                  : text("show_details", "Show technical details"),
              () -> {
                detailsExpanded = !detailsExpanded;
                rebuild.run();
              }));
      if (!detailsExpanded) return;
    }
    value(panel, "draft", "Accepted draft", captured.draft());
    value(panel, "baseline", "Observed saved value", captured.baseline());
    value(panel, "effective", "Last observed effective value", captured.effective());
    value(panel, "default", "Declared default", captured.declaredDefault());
    panel.heading(text("source", "Source"));
    panel.row(
        Component.literal(captured.source().description()),
        Component.literal(captured.source().location().orElse("")));
    var key = metadata.key().orElseGet(() -> captured.path().toString());
    panel.row(
        "workspace:information/key",
        text("key", "Key"),
        Component.literal(key),
        button(
            token,
            text("copy_key", "Copy key"),
            () -> Minecraft.getInstance().keyboardHandler.setClipboard(key)));
  }

  void preserve(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    dockedFocused = dockedPanel != null && focused == dockedPanel;
    if (dockedPanel != null) {
      dockedScroll = dockedPanel.bookmark();
      dockedFocus = dockedPanel.focusKey();
    }
  }

  void dock(FormPanel panel, Runnable focusPanel) {
    if (dockedPanel == null) focusRequested = true;
    contents(panel);
    panel.finish(0);
    if (dockedScroll != null) panel.restore(dockedScroll);
    if (dockedFocused && dockedFocus != null) {
      focusPanel.run();
      panel.restoreFocus(dockedFocus);
    }
    focus(panel, focusPanel);
    dockedPanel = panel;
  }

  void focus(FormPanel panel, Runnable focusPanel) {
    if (!focusRequested || owner == null) return;
    focusRequested = false;
    panel.jump("workspace:information");
    focusPanel.run();
    panel.restoreFocus(panel.fieldFocus("workspace:information/actions", null));
  }

  private void value(FormPanel panel, String key, String label, FieldInformation.Value value) {
    var detail =
        switch (value.state()) {
          case PRESENT ->
              design.inherent.reform.minecraft.ChoiceComponents.value(
                  value.choiceCaption(),
                  design.inherent.reform.minecraft.NumericComponents.value(
                      value.numericCaption(), Component.literal(value.text().orElseThrow())));
          case ABSENT -> text("absent", "Absent in this observation");
          case UNAVAILABLE ->
              text("not_observed", "Not available")
                  .copy()
                  .append(": ")
                  .append(value.reason().orElseThrow());
        };
    panel.row(text(key, label), detail);
  }

  private Button button(Object token, Component title, Runnable action) {
    return Button.builder(
            title,
            ignored -> {
              if (widgets == token && allowed()) action.run();
            })
        .bounds(0, 0, Math.max(52, Minecraft.getInstance().font.width(title) + 16), 20)
        .build();
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.information." + key, fallback);
  }
}
