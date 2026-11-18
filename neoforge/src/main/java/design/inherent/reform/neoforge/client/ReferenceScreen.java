package design.inherent.reform.neoforge.client;

import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.presentation.FormGeometry;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Explicit bounded native lookup. Borrowed source/document lifetimes remain with their owners. */
final class ReferenceScreen<T, V> extends FormScreen {
  private ReferenceSelection<T, V> selection;
  private final ReferenceLimits limits;
  private final Supplier<ReferenceSelection<T, V>> refresh;
  private final Runnable returned, abandoned;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final List<AbstractWidget> querying = new ArrayList<>();
  private EditBox filter, key;
  private FormPanel panel;
  private Button use, back, nextPage;
  private ReferenceSelection.Search search;
  private ReferenceSelection.Lookup<V> lookup;
  private Component notice, searchNotice, lookupNotice;
  private int bodyTop, statusTop;
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

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.reference." + key, fallback);
  }

  @Override
  protected void init() {
    float scroll = panel == null ? 0 : panel.scroll();
    String focused = panel == null ? null : panel.focusKey();
    footer.clear();
    querying.clear();
    nextPage = null;
    if (filter == null) {
      filter = new EditBox(font, 0, 0, 180, 20, text("filter", "Search"));
      filter.setMaxLength(limits.maximumQueryLength());
      filter.setResponder(ignored -> feedback());
      key = new EditBox(font, 0, 0, 180, 20, text("key", "Reference ID"));
      key.setMaxLength(Math.max(32768, selection.raw().length()));
      key.setValue(selection.raw());
      key.setResponder(ignored -> feedback());
    }
    use = footer(text("use", "Use selection"), this::choose);
    footer(text("refresh", "Refresh source"), this::refresh);
    back = footer(text("back", "Back"), this::onClose);
    var geometry =
        FormGeometry.controls(
            footer.stream().map(w -> new FormGeometry.Size(w.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int footerTop = height - 12 - geometry.height();
    statusTop = footerTop - 24;
    for (int i = 0; i < footer.size(); i++) {
      var at = geometry.placements().get(i);
      var widget = footer.get(i);
      widget.setPosition(12 + at.x(), footerTop + at.y());
      widget.setWidth(at.width());
      addRenderableWidget(widget);
    }
    bodyTop = 40 + font.split(sourceLabel(), Math.max(1, width - 24)).size() * font.lineHeight;
    panel =
        new FormPanel(
            minecraft,
            12,
            bodyTop,
            Math.max(1, width - 24),
            Math.max(1, statusTop - bodyTop - 8),
            false);
    panel.field(
        "filter",
        text("filter", "Search"),
        searchNotice == null
            ? text("search_help", "Search the admitted source; no files or terrain are loaded.")
            : searchNotice,
        filter,
        queryButton(text("search", "Search"), () -> search(Optional.empty())));
    panel.field(
        "key",
        text("key", "Reference ID"),
        lookupNotice == null
            ? text("lookup_help", "Inspect an ID before choosing it. Manual input is preserved.")
            : lookupNotice,
        key,
        queryButton(text("inspect", "Inspect"), () -> resolve(key.getValue())));
    selection
        .resolution()
        .ifPresent(
            result -> {
              if (selection
                  .page()
                  .filter(
                      page ->
                          page.reason().equals(result.reason())
                              && (page.availability()
                                          == ReferencePage.Availability.MISSING_DEPENDENCY
                                      && result.status()
                                          == ReferenceResolution.Status.MISSING_DEPENDENCY
                                  || page.availability()
                                          == ReferencePage.Availability.UNAVAILABLE_CONTEXT
                                      && result.status()
                                          == ReferenceResolution.Status.UNAVAILABLE_CONTEXT))
                  .isPresent()) return;
              var label =
                  result.status() == ReferenceResolution.Status.RESOLVED
                      ? text("resolved", "Resolved")
                      : Component.literal(readable(result.status().name()));
              panel.row(
                  "resolution",
                  label,
                  result.status() == ReferenceResolution.Status.RESOLVED
                      ? preview(result.preview())
                      : Component.literal(clip(result.reason(), 512)));
            });
    selection
        .page()
        .ifPresent(
            page -> {
              if (page.availability() != ReferencePage.Availability.READY) {
                panel.row(
                    "availability",
                    Component.literal(readable(page.availability().name())),
                    Component.literal(clip(page.reason(), 512)));
                return;
              }
              panel.row(
                  "accounting",
                  text("choices", "Choices"),
                  Component.translatableWithFallback(
                      "reform.reference.examined",
                      "%s shown; %s source entries examined on this page",
                      page.choices().size(),
                      page.examined()));
              for (int i = 0; i < page.choices().size(); i++) {
                var choice = page.choices().get(i);
                var inspect =
                    queryButton(
                        text("inspect", "Inspect"),
                        Component.literal(choice.key()),
                        () -> {
                          if (selection.page().filter(current -> current == page).isEmpty()) return;
                          key.setMaxLength(Math.max(32768, choice.key().length()));
                          key.setValue(choice.key());
                          resolve(choice.key());
                        });
                inspect.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(clip(choice.key(), 512))));
                panel.row(
                    "choice:" + i,
                    Component.literal(clip(choice.label(), 160)),
                    choice.label().equals(choice.key())
                        ? Component.empty()
                        : Component.literal(clip(choice.key(), 256)),
                    inspect);
              }
              if (page.exhausted()) panel.row(text("end", "End of this search"), Component.empty());
              else {
                nextPage = queryButton(text("next", "Next page"), () -> search(page.next()));
                panel.row(
                    text("continued", "More source entries remain"),
                    text("continued_help", "An empty page can still have further matches."),
                    nextPage);
              }
            });
    addRenderableWidget(panel);
    panel.finish(scroll);
    panel.restoreFocus(focused);
    if (focused != null) setFocused(panel);
    feedback();
    if (!started) {
      started = true;
      search(Optional.empty());
      resolve(key.getValue());
    }
  }

  @Override
  protected void setInitialFocus() {
    setFocused(panel);
    panel.focusVisibleControl();
  }

  @Override
  protected void rebuildWidgets() {
    var bookmark = panel == null ? null : panel.bookmark();
    String focus = panel == null ? null : panel.focusKey();
    super.rebuildWidgets();
    if (bookmark != null) panel.restore(bookmark);
    panel.restoreFocus(focus);
    if (focus != null) setFocused(panel);
  }

  private Button footer(Component label, Runnable action) {
    var button =
        Button.builder(
                label,
                ignored -> {
                  if (!closed && minecraft.screen == this) action.run();
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
    var builder =
        Button.builder(
                label,
                ignored -> {
                  if (!closed && minecraft.screen == this && !staging) action.run();
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
                            || minecraft.screen != this
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
                            || minecraft.screen != this
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
                        if (closed || minecraft.screen != this) return;
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
  public void render(GuiGraphics graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    graphics.drawWordWrap(
        font, sourceLabel(), 12, 30, Math.max(1, width - 24), FormPanel.DESCRIPTION_TEXT);
    var lines = font.split(status(), Math.max(1, width - 24));
    for (int i = 0; i < Math.min(2, lines.size()); i++)
      graphics.drawString(
          font, lines.get(i), 12, statusTop + i * font.lineHeight, FormPanel.DESCRIPTION_TEXT);
  }

  @Override
  public Component getNarrationMessage() {
    var message = title.copy().append(". ").append(sourceLabel()).append(". ").append(status());
    if (searchNotice != null) message.append(". ").append(searchNotice);
    if (lookupNotice != null) message.append(". ").append(lookupNotice);
    selection
        .resolution()
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
                    .append(clip(result.reason(), 512)));
    return message;
  }

  @Override
  public boolean keyPressed(int keyCode, int scan, int modifiers) {
    if (closed || minecraft.screen != this) return false;
    if (super.keyPressed(keyCode, scan, modifiers)) return true;
    if (!staging && keyCode == GLFW.GLFW_KEY_ENTER) {
      if (filter.isFocused()) search(Optional.empty());
      else if (key.isFocused()) resolve(key.getValue());
      else return false;
      return true;
    }
    return false;
  }

  @Override
  public void onClose() {
    if (closed || minecraft.screen != this) return;
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

  private static Component preview(java.util.Map<String, String> values) {
    var text = new StringBuilder();
    for (var entry : values.entrySet()) {
      if (!text.isEmpty()) text.append("; ");
      text.append(clip(entry.getKey(), 64)).append(": ").append(clip(entry.getValue(), 256));
      if (text.length() > 512)
        return Component.literal(clip(text.toString(), 512))
            .append(" ")
            .append(ReferenceScreen.text("abbreviated", "(preview abbreviated)"));
    }
    return Component.literal(text.toString());
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
