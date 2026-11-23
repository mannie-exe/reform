package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.reference.CollectionSelection;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceBrowser;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceQuery;
import design.inherent.reform.reference.SearchText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Paginated draft membership editing. No persistence, enumeration or domain compilation. */
final class CollectionScreen<T, V> extends NativeFormScreen {
  private record Choice(String key, Component label, String search, Component preview) {}

  private static final int PAGE_SIZE = 8;
  private final CollectionSelection<T> selection;
  private final ReferenceActions<String, V> source;
  private final String choiceKey;
  private final BooleanSupplier available;
  private final Runnable returned, abandoned;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private ReferenceBrowser<V> browser;
  private ReferenceBrowser.Search search;
  private ReferenceBrowser.Lookup<V> lookup;
  private UUID inspectedMember;
  private List<Choice> known = List.of(), shown = List.of();
  private Map<String, Long> counts = Map.of();
  private FormPanel panel;
  private FormChrome chrome;
  private EditBox filter, raw;
  private Component notice, sourceNotice, inspection;
  private String shownFilter = "";
  private net.minecraft.locale.Language knownLanguage;
  private Object widgetGeneration;
  private int nextOffset, memberOffset;
  private boolean localExhausted = true,
      started,
      closed,
      returning,
      wasCurrent = true,
      wasSourceCurrent = true;

  CollectionScreen(
      Component title,
      CollectionSelection<T> selection,
      ReferenceActions<String, V> source,
      String choiceKey,
      BooleanSupplier available,
      Runnable returned,
      Runnable abandoned) {
    super(title);
    this.selection = Objects.requireNonNull(selection);
    this.source = source;
    this.choiceKey = Objects.requireNonNull(choiceKey);
    this.available = Objects.requireNonNull(available);
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
    updateCounts();
    openBrowser();
    wasSourceCurrent = choicesCurrent();
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.collection." + key, fallback, arguments);
  }

  private boolean editable() {
    return !closed
        && selection.current()
        && selection.pending().isEmpty()
        && available.getAsBoolean();
  }

  private boolean choicesCurrent() {
    return browser == null ? selection.view().referenceSource().isEmpty() : browser.current();
  }

  private void updateCounts() {
    var values = new java.util.HashMap<String, Long>();
    for (var item : selection.view().items())
      item.raw().ifPresent(key -> values.merge(key, 1L, Long::sum));
    counts = Map.copyOf(values);
  }

  private void openBrowser() {
    sourceNotice = null;
    if (source == null) {
      if (selection.view().referenceSource().isPresent())
        sourceNotice =
            text(
                "unbound",
                "No source is bound. Existing entries and manual input remain available.");
      return;
    }
    try {
      if (selection.view().referenceSource().filter(source.source()::equals).isEmpty())
        throw new IllegalArgumentException("Collection source does not match its declared source");
      var next = new ReferenceBrowser<>(source);
      if (browser != null) browser.close();
      browser = next;
    } catch (RuntimeException refused) {
      sourceNotice = failure(refused);
    }
  }

  @Override
  protected void init() {
    widgetGeneration = new Object();
    var language = net.minecraft.locale.Language.getInstance();
    if (knownLanguage != language) {
      knownLanguage = language;
      buildKnown();
      if (started && browser == null) localSearch(0);
    }
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    footer.clear();
    if (filter == null) {
      filter = new FormEditBox(font, 0, 0, 180, 20, text("search", "Search choices"));
      filter.setMaxLength(
          source == null
              ? ReferenceLimits.STANDARD.maximumQueryLength()
              : source.limits().maximumQueryLength());
      filter.setResponder(ignored -> rebuildWidgets());
      raw = new FormEditBox(font, 0, 0, 180, 20, text("custom", "Custom value"));
      raw.setMaxLength(32768);
    }
    var refresh = action(text("refresh", "Refresh views"), null, false, this::refresh);
    var back = action(text("back", "Back"), null, false, this::onClose);
    footer.add(refresh);
    footer.add(back);
    footer.forEach(button -> button.active = selection.pending().isEmpty());
    chrome =
        new FormChrome(
            minecraft,
            width,
            height,
            title,
            Component.empty(),
            notice == null
                ? text("draft_only", "Selections change the draft only. Back retains your work.")
                : notice,
            footer,
            theme());
    panel = chrome.panel();
    chrome.mountActions(this::addRenderableWidget, panel);
    boolean editable = editable();
    var target = selection.view().target();
    panel.row(
        "semantics",
        text("entries", "Current entries"),
        text(
            selection.view().unique() ? "set_help" : "list_help",
            selection.view().unique()
                ? "%s entries; duplicates remain visible for repair (limit %s)."
                : "%s entries in list order; duplicates are retained (limit %s).",
            selection.view().items().size(),
            selection.view().maximumSize()));
    memberOffset =
        Math.min(
            memberOffset,
            Math.max(0, ((selection.view().items().size() - 1) / PAGE_SIZE) * PAGE_SIZE));
    var members =
        selection
            .view()
            .items()
            .subList(
                memberOffset, Math.min(selection.view().items().size(), memberOffset + PAGE_SIZE));
    inspection = null;
    for (int i = 0; i < members.size(); i++) {
      var item = members.get(i);
      String value = item.raw().orElse(null);
      var label =
          value == null ? text("malformed", "Non-scalar entry") : Component.literal(clip(value));
      var remove =
          action(
              text("remove", "Remove"),
              label,
              true,
              () -> change(target, List.of(), Set.of(item.id()), false));
      if (browser != null && value != null) {
        var inspect =
            action(text("inspect", "Inspect"), label, false, () -> inspect(item.id(), value));
        inspect.active = editable && browser.current();
        var summary =
            text(
                "raw_state",
                "Input: %s; resolution is separate.",
                item.inputState().name().toLowerCase(java.util.Locale.ROOT));
        var observed = inspectedValue(item.id(), value);
        if (observed != null) {
          inspection = observed;
          summary = summary.copy().append("\n").append(observed);
        }
        panel.anchorRow("member:" + item.id(), label, summary, remove, inspect);
      } else
        panel.row(
            "member:" + item.id(),
            label,
            text(
                "input_state",
                "Input: %s",
                item.inputState().name().toLowerCase(java.util.Locale.ROOT)),
            remove);
    }
    if (memberOffset > 0 || memberOffset + PAGE_SIZE < selection.view().items().size()) {
      var previous =
          action(
              text("previous_entries", "Previous entries"),
              null,
              false,
              () -> {
                memberOffset -= PAGE_SIZE;
                rebuildWidgets();
              });
      var next =
          action(
              text("next_entries", "Next entries"),
              null,
              false,
              () -> {
                memberOffset += PAGE_SIZE;
                rebuildWidgets();
              });
      previous.active = memberOffset > 0;
      next.active = memberOffset + PAGE_SIZE < selection.view().items().size();
      panel.row(
          "member_pages",
          text("entry_page", "Entries %s–%s", memberOffset + 1, memberOffset + members.size()),
          Component.empty(),
          previous,
          next);
    }
    var addCustom =
        action(
            text("add_custom", "Add custom"),
            null,
            true,
            () -> change(target, List.of(raw.getValue()), Set.of(), false));
    raw.setEditable(editable);
    panel.field(
        "custom",
        text("custom", "Custom value"),
        text(
            "custom_help", "Manual values remain drafts; the owner validates the complete result."),
        raw,
        addCustom);
    panel.heading(text("suggestions", "Available choices"));
    filter.setEditable(selection.pending().isEmpty());
    var query =
        action(text("search", "Search choices"), null, false, () -> search(Optional.empty(), 0));
    query.active = selection.pending().isEmpty() && choicesCurrent();
    panel.field(
        "filter",
        text("search", "Search choices"),
        sourceNotice == null
            ? text("search_help", "Search the admitted choices. No files or terrain are loaded.")
            : sourceNotice,
        filter,
        query);
    for (int i = 0; i < shown.size(); i++) {
      var choice = shown.get(i);
      long count = counts.getOrDefault(choice.key(), 0L);
      var add =
          action(
              text(count > 0 ? "add_another" : "add", count > 0 ? "Add another" : "Add"),
              choice.label(),
              true,
              () -> change(target, List.of(choice.key()), Set.of(), true));
      add.active =
          editable
              && choicesCurrent()
              && shownFilter.equals(filter.getValue())
              && (!selection.view().unique() || count == 0);
      var summary =
          count == 0
              ? text("not_included", "Not included")
              : text("included", "Included (%s)", count);
      if (choicesCurrent() && !choice.preview().getString().isEmpty())
        summary = summary.copy().append("\n").append(choice.preview());
      panel.row("choice:" + i, choice.label(), summary, add);
    }
    var missing = shown.stream().map(Choice::key).filter(key -> !counts.containsKey(key)).toList();
    var bulk =
        action(
            text("add_shown", "Add missing shown"),
            null,
            true,
            () -> change(target, missing, Set.of(), true));
    bulk.active =
        editable && choicesCurrent() && shownFilter.equals(filter.getValue()) && !missing.isEmpty();
    var next =
        action(
            text("next_choices", "Next choices"),
            null,
            false,
            () ->
                search(
                    browser == null
                        ? Optional.empty()
                        : browser.page().flatMap(ReferencePage::next),
                    nextOffset));
    next.active =
        selection.pending().isEmpty()
            && choicesCurrent()
            && shownFilter.equals(filter.getValue())
            && (browser == null
                ? !localExhausted
                : browser.page().flatMap(ReferencePage::next).isPresent());
    panel.row(
        "choice_actions",
        text("page_only", "%s choices shown; bulk add affects this page only.", shown.size()),
        Component.empty(),
        bulk,
        next);
    addRenderableWidget(panel);
    panel.finish(scroll);
    panel.restoreFocus(focus);
    if (focus != null) setFocused(panel);
    if (!started) {
      started = true;
      search(Optional.empty(), 0);
    }
  }

  private void buildKnown() {
    var limits = ReferenceLimits.STANDARD;
    long chars = 0;
    var choices = new ArrayList<Choice>();
    for (String key : selection.view().knownChoices()) {
      var label = Component.translatableWithFallback(choiceKey + key, key);
      chars += (long) key.length() + label.getString().length();
      if (choices.size() >= limits.maximumEntries() || chars > limits.maximumTextCharacters()) {
        known = List.of();
        sourceNotice =
            text(
                "choice_budget",
                "The complete choice catalog exceeds its display budget; manual editing remains available.");
        return;
      }
      String search = SearchText.fold(key + " " + label.getString());
      chars += search.length();
      if (choices.size() >= limits.maximumEntries() || chars > limits.maximumTextCharacters()) {
        known = List.of();
        sourceNotice =
            text(
                "choice_budget",
                "The complete choice catalog exceeds its display budget; manual editing remains available.");
        return;
      }
      choices.add(new Choice(key, label, search, Component.empty()));
    }
    known = List.copyOf(choices);
  }

  private void search(Optional<ReferenceQuery.Cursor> cursor, int offset) {
    if (closed || selection.pending().isPresent()) return;
    shown = List.of();
    shownFilter = filter.getValue();
    if (browser == null) {
      localSearch(offset);
      rebuildWidgets();
      return;
    }
    var controller = browser;
    try {
      var request =
          browser.search(
              EntryPoint.GUI,
              new ReferenceQuery(
                  shownFilter, Math.min(PAGE_SIZE, source.limits().maximumPageSize()), cursor));
      search = request;
      sourceNotice = text("searching", "Searching admitted choices…");
      request
          .operation()
          .completion()
          .whenComplete(
              (result, failed) ->
                  minecraft.execute(
                      () -> {
                        if (closed
                            || NativeScreens.current(minecraft) != this
                            || browser != controller
                            || search != request) return;
                        try {
                          if (failed != null) sourceNotice = failure(failed);
                          else if (browser.accept(request)) {
                            var page = browser.page().orElseThrow();
                            sourceNotice =
                                page.availability() == ReferencePage.Availability.READY
                                    ? text(
                                        "examined",
                                        "%s source entries examined on this page.",
                                        page.examined())
                                    : Component.literal(clip(page.reason()));
                            shown =
                                page.choices().stream()
                                    .map(
                                        choice ->
                                            new Choice(
                                                choice.key(),
                                                Component.literal(clip(choice.label())),
                                                "",
                                                ReferencePreview.format(choice.preview())))
                                    .toList();
                          } else sourceNotice = operationFailure(result);
                        } catch (RuntimeException refused) {
                          sourceNotice = failure(refused);
                        }
                        rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      sourceNotice = failure(refused);
    }
    rebuildWidgets();
  }

  private void localSearch(int offset) {
    var found = new ArrayList<Choice>();
    String needle = SearchText.fold(shownFilter);
    int position = Math.min(offset, known.size()), scanned = 0;
    while (position < known.size()
        && scanned++ < ReferenceLimits.STANDARD.maximumScan()
        && found.size() < PAGE_SIZE) {
      var choice = known.get(position++);
      if (choice.search().contains(needle)) found.add(choice);
    }
    nextOffset = position;
    localExhausted = position == known.size();
    shown = List.copyOf(found);
  }

  private Component inspectedValue(UUID member, String raw) {
    if (!member.equals(inspectedMember)
        || lookup == null
        || !lookup.raw().equals(raw)
        || browser == null
        || !choicesCurrent()) return null;
    return browser
        .resolution()
        .map(
            result -> {
              var status =
                  text(
                      "resolution",
                      "%s: %s",
                      result.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                      clip(raw));
              var details =
                  result.status()
                          == design.inherent.reform.reference.ReferenceResolution.Status.RESOLVED
                      ? ReferencePreview.format(result.preview())
                      : Component.literal(clip(result.reason()));
              return details.getString().isEmpty()
                  ? status
                  : status.copy().append(". ").append(details);
            })
        .orElse(null);
  }

  private void inspect(UUID member, String value) {
    if (closed || browser == null || selection.pending().isPresent()) return;
    var controller = browser;
    try {
      var request = browser.resolve(EntryPoint.GUI, value);
      lookup = request;
      inspectedMember = member;
      inspection = null;
      request
          .operation()
          .completion()
          .whenComplete(
              (result, failed) ->
                  minecraft.execute(
                      () -> {
                        if (closed
                            || NativeScreens.current(minecraft) != this
                            || browser != controller
                            || lookup != request) return;
                        try {
                          if (failed != null) notice = failure(failed);
                          else if (!browser.accept(request)) notice = operationFailure(result);
                        } catch (RuntimeException refused) {
                          notice = failure(refused);
                        }
                        rebuildWidgets();
                        if (inspection != null) panel.jump("member:" + member);
                      }));
    } catch (RuntimeException refused) {
      notice = failure(refused);
      rebuildWidgets();
    }
  }

  private void change(DraftTarget target, List<String> add, Set<UUID> remove, boolean fromCatalog) {
    if (!editable()) return;
    try {
      if (fromCatalog) {
        if (!shownFilter.equals(filter.getValue()) || !choicesCurrent()) return;
        if (browser != null) browser.requireCurrent();
      }
      var request =
          selection.change(EntryPoint.GUI, target, new DraftEdit.ChangeScalarRows(add, remove));
      notice = text("staging", "Staging entries; no file is being saved.");
      rebuildWidgets();
      request
          .operation()
          .completion()
          .whenComplete(
              (result, failed) ->
                  minecraft.execute(
                      () -> {
                        if (closed) return;
                        var accepted = selection.accept(request);
                        if (accepted == CollectionSelection.Acceptance.ACCEPTED) {
                          updateCounts();
                          notice =
                              text("staged", "Draft updated. Return to review, undo or save it.");
                        } else
                          notice =
                              failed == null && !result.report().issues().isEmpty()
                                  ? Component.literal(
                                      clip(result.report().issues().getFirst().fallback()))
                                  : failed == null
                                      ? text(
                                          "stale",
                                          "The draft changed. Refresh before editing again.")
                                      : failure(failed);
                        if (NativeScreens.current(minecraft) == this) rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      notice = failure(refused);
      rebuildWidgets();
    }
  }

  private Button action(Component label, Component context, boolean edits, Runnable callback) {
    var generation = widgetGeneration;
    var builder =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (!closed
                      && widgetGeneration == generation
                      && NativeScreens.current(minecraft) == this
                      && selection.pending().isEmpty()
                      && (!edits || editable())) callback.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20);
    if (context != null)
      builder.createNarration(original -> context.copy().append(": ").append(original.get()));
    var button = builder.build();
    if (edits) button.active = editable();
    return button;
  }

  private void refresh() {
    try {
      selection.refresh();
      updateCounts();
      openBrowser();
      lookup = null;
      search = null;
      notice = null;
      wasCurrent = true;
      wasSourceCurrent = choicesCurrent();
      buildKnown();
      search(Optional.empty(), 0);
    } catch (RuntimeException refused) {
      notice = failure(refused);
      rebuildWidgets();
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

  @Override
  public void tick() {
    boolean current = editable();
    boolean sourceCurrent = choicesCurrent();
    if (wasSourceCurrent != sourceCurrent) {
      sourceNotice =
          text(
              "source_changed",
              "The source changed. Refresh views to browse it; existing entries remain editable.");
      rebuildWidgets();
    }
    if (wasCurrent && !current && selection.pending().isEmpty()) {
      notice =
          text("stale", "The draft or availability changed. Refresh views before editing again.");
      rebuildWidgets();
    }
    wasCurrent = current;
    wasSourceCurrent = sourceCurrent;
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (closed || NativeScreens.current(minecraft) != this) return false;
    if (super.keyPressed(key, scan, modifiers)) return true;
    if (key == InputConstants.KEY_RETURN && filter.isFocused()) {
      search(Optional.empty(), 0);
      return true;
    }
    return false;
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(
        graphics,
        notice == null
            ? text("draft_only", "Selections change the draft only. Back retains your work.")
            : notice);
  }

  @Override
  public Component getNarrationMessage() {
    var message = title.copy();
    if (sourceNotice != null) message.append(". ").append(sourceNotice);
    if (inspection != null && choicesCurrent()) message.append(". ").append(inspection);
    return message;
  }

  @Override
  public void onClose() {
    if (closed || NativeScreens.current(minecraft) != this || selection.pending().isPresent())
      return;
    returning = true;
    release();
    returned.run();
  }

  @Override
  public void removed() {
    if (returning) return;
    release();
    abandoned.run();
  }

  private void release() {
    closed = true;
    selection.close();
    if (browser != null) browser.close();
  }

  private static String clip(String value) {
    return value.codePointCount(0, value.length()) <= 256
        ? value
        : value.substring(0, value.offsetByCodePoints(0, 256)) + "…";
  }

  private static Component operationFailure(OperationSnapshot<?> result) {
    return result.report().issues().isEmpty()
        ? text(
            "request_stale", "This response is no longer current. Refresh views before continuing.")
        : Component.literal(clip(result.report().issues().getFirst().fallback()));
  }

  private static Component failure(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof design.inherent.reform.owner.PolicyRefusal refusal
        && !refusal.report().issues().isEmpty())
      return Component.literal(clip(refusal.report().issues().getFirst().fallback()));
    return text("unavailable", "Selection is unavailable. Your draft is retained.");
  }
}
