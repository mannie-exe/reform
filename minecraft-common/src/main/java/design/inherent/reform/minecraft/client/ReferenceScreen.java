package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.TaskPresentation;
import design.inherent.reform.presentation.ThemeValues;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceQuery;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.reference.ReferenceSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Explicit bounded native lookup. Borrowed source/document lifetimes remain with their owners. */
final class ReferenceScreen<T, V> extends NativeFormScreen {
  private ReferenceSelection<T, V> selection;
  private final ReferenceLimits limits;
  private final Supplier<ReferenceSelection<T, V>> refresh;
  private final Runnable returned, abandoned;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final List<AbstractWidget> querying = new ArrayList<>();
  private EditBox filter, key;
  private FormPanel panel;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private int retainedFooter = -1;
  private boolean technicalDetails;
  private FormChrome chrome;
  private Button use, back, nextPage;
  private ReferenceSelection.Search search;
  private ReferenceSelection.Lookup<V> lookup;
  private Component notice, searchNotice, lookupNotice;
  private ReferencePage.Choice<String> inspectedChoice;
  private boolean started, staging, returning, closed, wasCurrent = true;

  ReferenceScreen(
      Component title,
      ReferenceSelection<T, V> selection,
      ReferenceLimits limits,
      Supplier<ReferenceSelection<T, V>> refresh,
      Runnable returned,
      Runnable abandoned) {
    super(title);
    this.selection = Objects.requireNonNull(selection);
    this.limits = Objects.requireNonNull(limits);
    this.refresh = Objects.requireNonNull(refresh);
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
  }

  private Object generation;

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.reference." + key, fallback);
  }

  @Override
  protected void init() {
    generation = new Object();
    footer.clear();
    querying.clear();
    nextPage = null;
    if (filter == null) {
      filter = new FormEditBox(font, 0, 0, 180, 20, text("filter", "Search"));
      filter.setMaxLength(limits.maximumQueryLength());
      filter.setResponder(ignored -> feedback());
      key = new FormEditBox(font, 0, 0, 180, 20, text("key", "Reference ID"));
      key.setMaxLength(Math.max(32768, selection.raw().length()));
      key.setValue(selection.raw());
      key.setResponder(ignored -> feedback());
    }
    use = footer(text("use", "Use selection"), this::choose);
    footer(text("refresh", "Refresh source"), this::refresh);
    back = footer(text("back", "Back"), this::onClose);
    var searchButton = queryButton(text("search", "Search"), () -> search(Optional.empty()));
    var inspectButton =
        queryButton(
            text("inspect", "Inspect"), text("key", "Reference ID"), () -> resolve(key.getValue()));
    int padding = 2 * theme().values().value(ThemeValues.Role.SPACE_CONTENT);
    int inline = theme().values().value(ThemeValues.Role.SPACE_INLINE);
    int primaryMinimum =
        Math.addExact(
            padding,
            Math.addExact(
                inline,
                Math.addExact(ControlSizing.preferred(filter).width(), searchButton.getWidth())));
    int detailMinimum =
        Math.addExact(
            padding,
            Math.addExact(
                inline,
                Math.addExact(ControlSizing.preferred(key).width(), inspectButton.getWidth())));
    int minimumHeight =
        Math.addExact(
            3 * font.lineHeight,
            Math.addExact(theme().values().value(ThemeValues.Role.CONTROL_HEIGHT), padding));
    var firstOffer =
        new TaskPresentation.Offer(
            new FormGeometry.Size(primaryMinimum, minimumHeight), primaryMinimum);
    var secondOffer =
        new TaskPresentation.Offer(
            new FormGeometry.Size(detailMinimum, minimumHeight), detailMinimum);
    chrome =
        TaskPanels.readingChrome(
            minecraft,
            width,
            height,
            title,
            text("choose_help", "Browse choices, then inspect and use the selected value."),
            status(),
            footer,
            theme(),
            firstOffer,
            secondOffer);
    panels =
        new TaskPanels(
            chrome,
            taskPresentation(),
            TaskPresentation.Kind.CHOOSER,
            firstOffer,
            secondOffer,
            theme(),
            retained);
    panel = panels.primary();
    var details = panels.detail();
    chrome.mountActions(this::addRenderableWidget, panel);
    panel.beginGroup("primary:search", text("filter", "Search"));
    panel.field(
        "primary:filter",
        Component.empty(),
        searchNotice == null ? text("search_help", "Search available values.") : searchNotice,
        filter,
        searchButton);
    panel.endGroup();
    appendChoices(panel);
    rememberInspectedChoice();
    details.beginGroup(
        "detail:selection", inspectedTitle().orElseGet(() -> text("selection", "Selected value")));
    selection
        .resolution()
        .filter(ignored -> selection.current())
        .ifPresent(
            result -> {
              var label =
                  result.status() == ReferenceResolution.Status.RESOLVED
                      ? text("resolved", "Resolved")
                      : Component.literal(readable(result.status().name()));
              details.row(
                  "detail:resolution",
                  label,
                  result.status() == ReferenceResolution.Status.RESOLVED
                      ? ReferencePreview.format(result.preview())
                      : Component.literal(clip(result.reason(), 512)));
            });
    details.field(
        "detail:key",
        text("key", "Reference ID"),
        lookupNotice == null
            ? text("lookup_help", "Inspect an ID before choosing it. Manual input is preserved.")
            : lookupNotice,
        key,
        inspectButton);
    var technical =
        queryButton(
            technicalDetails
                ? text("hide_source", "Hide source details")
                : text("show_source", "Source details"),
            () -> {
              technicalDetails = !technicalDetails;
              rebuildWidgets();
            });
    details.actions("detail:source_toggle", technical);
    if (technicalDetails) {
      selection
          .page()
          .ifPresent(
              page ->
                  details.row(
                      "detail:accounting",
                      text("source_accounting", "Source accounting"),
                      Component.translatableWithFallback(
                          "reform.reference.examined",
                          "%s shown; %s source entries examined on this page",
                          page.choices().size(),
                          page.examined())));
      var stamp = selection.stamp();
      details.row(
          "detail:source", text("source", "Source"), Component.literal(stamp.source().toString()));
      details.row(
          "detail:revision", text("revision", "Revision"), Component.literal(stamp.revision()));
      details.row(
          "detail:snapshot",
          text("snapshot", "Snapshot"),
          Component.literal(stamp.snapshot().toString()));
      details.row(
          "detail:context",
          text("context", "Context"),
          Component.literal(stamp.context().toString()));
    }
    details.endGroup();
    addRenderableWidget(panel);
    if (details != panel) addRenderableWidget(details);
    panels.finish().ifPresent(this::setFocused);
    if (retainedFooter >= 0 && retainedFooter < footer.size())
      setFocused(footer.get(retainedFooter));
    feedback();
    if (!started) {
      started = true;
      search(Optional.empty());
      resolve(key.getValue());
    }
  }

  private void appendChoices(FormPanel target) {
    target.beginGroup("primary:choices", text("choices", "Choices"));
    selection
        .page()
        .ifPresent(
            page -> {
              if (page.availability() != ReferencePage.Availability.READY) {
                target.row(
                    "primary:availability",
                    Component.literal(readable(page.availability().name())),
                    Component.literal(clip(page.reason(), 512)));
                return;
              }
              target.row(
                  "primary:accounting",
                  Component.empty(),
                  Component.translatableWithFallback(
                      "reform.reference.page_values",
                      "Values on this page: %s",
                      page.choices().size()));
              for (var choice : page.choices()) {
                var inspect =
                    queryButton(
                        Component.literal(choice.label()),
                        text("inspect_choice", "Inspect choice"),
                        () -> {
                          if (selection.page().filter(current -> current == page).isEmpty()) return;
                          key.setMaxLength(Math.max(32768, choice.key().length()));
                          key.setValue(choice.key());
                          resolve(choice.key());
                        });
                if (!choice.label().equals(choice.key()))
                  inspect.setTooltip(
                      net.minecraft.client.gui.components.Tooltip.create(
                          Component.literal(choice.key())));
                target.row(
                    "primary:choice:" + choice.key(),
                    Component.empty(),
                    Component.empty(),
                    inspect);
              }
              if (page.exhausted())
                target.row("primary:end", text("end", "End of this search"), Component.empty());
              else {
                nextPage = queryButton(text("next", "Next page"), () -> search(page.next()));
                target.row(
                    "primary:continued",
                    text("continued", "More source entries remain"),
                    text("continued_help", "An empty page can still have further matches."),
                    nextPage);
              }
            });
    target.endGroup();
  }

  private void rememberInspectedChoice() {
    if (!selection.current()) {
      inspectedChoice = null;
      return;
    }
    var selected = selection.selectedKey();
    if (selected.isEmpty()) {
      inspectedChoice = null;
      return;
    }
    var id = selected.orElseThrow();
    if (inspectedChoice != null && !inspectedChoice.key().equals(id)) inspectedChoice = null;
    selection
        .page()
        .filter(page -> page.availability() == ReferencePage.Availability.READY)
        .flatMap(
            page -> page.choices().stream().filter(choice -> choice.key().equals(id)).findFirst())
        .ifPresent(choice -> inspectedChoice = choice);
  }

  private Optional<Component> inspectedTitle() {
    return inspectedChoice != null
            && selection.current()
            && selection.selectedKey().filter(inspectedChoice.key()::equals).isPresent()
        ? Optional.of(Component.literal(inspectedChoice.label()))
        : Optional.empty();
  }

  private void rememberPresentation() {
    if (panels != null) retained = panels.remember(getFocused());
    retainedFooter = footer.indexOf(getFocused());
  }

  @Override
  protected void beforeResize() {
    rememberPresentation();
  }

  @Override
  protected void setInitialFocus() {
    if (panels != null && (getFocused() == panels.primary() || getFocused() == panels.detail()))
      return;
    setFocused(panel);
    panel.focusVisibleControl();
  }

  @Override
  protected void rebuildWidgets() {
    rememberPresentation();
    super.rebuildWidgets();
  }

  private Button footer(Component label, Runnable action) {
    var token = generation;
    var button =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (!closed && generation == token && NativeScreens.current(minecraft) == this)
                    action.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    footer.add(button);
    return button;
  }

  private Button queryButton(Component label, Runnable action) {
    return queryButton(label, null, action);
  }

  private Button queryButton(Component label, Component context, Runnable action) {
    var token = generation;
    var builder =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (!closed
                      && generation == token
                      && NativeScreens.current(minecraft) == this
                      && !staging) action.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20);
    if (context != null)
      builder.createNarration(original -> context.copy().append(": ").append(original.get()));
    var button = builder.build();
    querying.add(button);
    return button;
  }

  private void search(Optional<ReferenceQuery.Cursor> cursor) {
    if (closed || staging) return;
    var controller = selection;
    search = null;
    try {
      var request =
          selection.search(
              EntryPoint.GUI,
              new ReferenceQuery(filter.getValue(), Math.min(8, limits.maximumPageSize()), cursor));
      search = request;
      searchNotice = text("searching", "Searching the admitted source…");
      request
          .operation()
          .completion()
          .whenComplete(
              (result, failure) ->
                  minecraft.execute(
                      () -> {
                        if (closed
                            || NativeScreens.current(minecraft) != this
                            || selection != controller
                            || search != request) return;
                        try {
                          if (failure != null) searchNotice = failure(failure);
                          else if (selection.accept(request)) searchNotice = null;
                          else searchNotice = operationFailure(result);
                        } catch (RuntimeException refused) {
                          searchNotice = failure(refused);
                        }
                        rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      searchNotice = failure(refused);
    }
    rebuildWidgets();
  }

  private void resolve(String raw) {
    if (closed || staging) return;
    var controller = selection;
    lookup = null;
    try {
      var request = selection.resolve(EntryPoint.GUI, raw);
      lookup = request;
      lookupNotice = text("inspecting", "Inspecting this ID…");
      request
          .operation()
          .completion()
          .whenComplete(
              (result, failure) ->
                  minecraft.execute(
                      () -> {
                        if (closed
                            || NativeScreens.current(minecraft) != this
                            || selection != controller
                            || lookup != request) return;
                        try {
                          if (failure != null) lookupNotice = failure(failure);
                          else if (selection.accept(request)) lookupNotice = null;
                          else lookupNotice = operationFailure(result);
                        } catch (RuntimeException refused) {
                          lookupNotice = failure(refused);
                        }
                        rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      lookupNotice = failure(refused);
    }
    rebuildWidgets();
  }

  private void refresh() {
    if (closed || staging) return;
    try {
      var next = refresh.get();
      selection.close();
      selection = next;
      inspectedChoice = null;
      search = null;
      lookup = null;
      notice = null;
      searchNotice = lookupNotice = null;
      wasCurrent = true;
      search(Optional.empty());
      resolve(key.getValue());
      rebuildWidgets();
    } catch (RuntimeException refused) {
      notice = failure(refused);
      feedback();
    }
  }

  private void choose() {
    if (staging || closed || selection.selectedKey().filter(key.getValue()::equals).isEmpty())
      return;
    try {
      var operation = selection.choose(EntryPoint.GUI);
      staging = true;
      notice = text("staging", "Staging the selected ID; this does not save or publish it.");
      feedback();
      operation
          .completion()
          .whenComplete(
              (result, failure) ->
                  minecraft.execute(
                      () -> {
                        staging = false;
                        if (closed || NativeScreens.current(minecraft) != this) return;
                        if (failure == null && result.state() == OperationSnapshot.State.COMPLETED)
                          onClose();
                        else {
                          notice = failure == null ? operationFailure(result) : failure(failure);
                          feedback();
                        }
                      }));
    } catch (RuntimeException refused) {
      notice = failure(refused);
      feedback();
    }
  }

  private void feedback() {
    if (use == null) return;
    boolean current = selection.current();
    filter.setEditable(!staging && current);
    key.setEditable(!staging && current);
    for (var button : footer) button.active = !staging;
    use.active =
        !staging && current && selection.selectedKey().filter(key.getValue()::equals).isPresent();
    back.active = !staging;
    for (var button : querying) button.active = !staging && current;
    if (nextPage != null)
      nextPage.active =
          !staging
              && current
              && search != null
              && search.query().filter().equals(filter.getValue())
              && selection.page().flatMap(ReferencePage::next).isPresent();
  }

  @Override
  public void tick() {
    boolean current = selection.current();
    if (wasCurrent && !current) {
      wasCurrent = false;
      notice = text("stale", "The field or source changed. Refresh before choosing an ID.");
      rebuildWidgets();
    }
  }

  private Component sourceLabel() {
    return Component.literal(
        selection.stamp().source() + " · " + clip(selection.stamp().revision(), 120));
  }

  private Component status() {
    return notice == null ? text("draft_only", "Use selection changes the draft only.") : notice;
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(graphics, status());
  }

  @Override
  public Component getNarrationMessage() {
    var message = title.copy().append(". ").append(sourceLabel()).append(". ").append(status());
    inspectedTitle().ifPresent(label -> message.append(". ").append(label));
    if (searchNotice != null) message.append(". ").append(searchNotice);
    if (lookupNotice != null) message.append(". ").append(lookupNotice);
    selection
        .resolution()
        .filter(ignored -> selection.current())
        .ifPresent(
            result ->
                message
                    .append(". ")
                    .append(
                        Component.translatableWithFallback(
                            "reform.reference.status."
                                + result.status().name().toLowerCase(java.util.Locale.ROOT),
                            readable(result.status().name())))
                    .append(". ")
                    .append(
                        result.status() == ReferenceResolution.Status.RESOLVED
                            ? ReferencePreview.format(result.preview())
                            : Component.literal(clip(result.reason(), 512))));
    return message;
  }

  @Override
  public boolean keyPressed(int keyCode, int scan, int modifiers) {
    if (closed || NativeScreens.current(minecraft) != this) return false;
    if (super.keyPressed(keyCode, scan, modifiers)) return true;
    if (!staging && keyCode == InputConstants.KEY_RETURN) {
      if (filter.isFocused()) search(Optional.empty());
      else if (key.isFocused()) resolve(key.getValue());
      else return false;
      return true;
    }
    return false;
  }

  @Override
  public void onClose() {
    if (closed || NativeScreens.current(minecraft) != this) return;
    if (staging) return;
    returning = true;
    closed = true;
    selection.close();
    returned.run();
  }

  @Override
  public void removed() {
    if (returning) return;
    closed = true;
    selection.close();
    abandoned.run();
  }

  private static String readable(String value) {
    return value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
  }

  private static String clip(String value, int maximum) {
    return value.codePointCount(0, value.length()) <= maximum
        ? value
        : value.substring(0, value.offsetByCodePoints(0, maximum)) + "…";
  }

  private static Component operationFailure(OperationSnapshot<?> result) {
    return result.report().issues().isEmpty()
        ? text(
            "not_accepted", "This response is no longer current. Refresh and inspect the ID again.")
        : Component.literal(clip(result.report().issues().getFirst().fallback(), 512));
  }

  private static Component failure(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof design.inherent.reform.owner.PolicyRefusal refusal
        && !refusal.report().issues().isEmpty())
      return Component.literal(clip(refusal.report().issues().getFirst().fallback(), 512));
    return text("unavailable", "Reference lookup is unavailable. Your draft is retained.");
  }
}
