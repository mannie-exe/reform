package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.DestinationGraph;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormTarget;
import design.inherent.reform.presentation.NavigationHistory;
import design.inherent.reform.presentation.SectionNavigation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

/** One persistent native tree over real workflow destinations and admitted field identities. */
final class WorkspaceNavigation {
  enum Kind {
    HOME,
    CATEGORY,
    DESTINATION,
    SECTION,
    INSPECTION,
    FIELD
  }

  record Key(Kind kind, DocumentId placement, Optional<FieldPath> field, Optional<String> section) {
    Key(Kind kind, DocumentId placement, Optional<FieldPath> field) {
      this(kind, placement, field, Optional.empty());
    }

    Key {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(placement);
      Objects.requireNonNull(field);
      Objects.requireNonNull(section);
      if ((kind == Kind.FIELD) != field.isPresent())
        throw new IllegalArgumentException("Only a field navigation key names a field");
      if ((kind == Kind.SECTION) != section.isPresent())
        throw new IllegalArgumentException("Only a section navigation key names a layout box");
      section.ifPresent(FormTarget.Section::new);
    }

    static Key form(DocumentId placement, FormTarget target) {
      return switch (target) {
        case FormTarget.Field field -> new Key(Kind.FIELD, placement, Optional.of(field.path()));
        case FormTarget.Section box ->
            new Key(Kind.SECTION, placement, Optional.empty(), Optional.of(box.id()));
      };
    }

    Optional<FormTarget> target() {
      if (field.isPresent()) return Optional.of(new FormTarget.Field(field.orElseThrow()));
      return section.map(FormTarget.Section::new).map(value -> (FormTarget) value);
    }
  }

  record Entry(
      Key key,
      Optional<Key> parent,
      Component title,
      Component help,
      Runnable select,
      boolean searchOnly,
      String searchTerms) {
    Entry(Key key, Optional<Key> parent, Component title, Component help, Runnable select) {
      this(key, parent, title, help, select, false, title.getString() + " " + help.getString());
    }

    Entry {
      Objects.requireNonNull(key);
      Objects.requireNonNull(parent);
      title = Objects.requireNonNull(title).copy();
      help = Objects.requireNonNull(help).copy();
      Objects.requireNonNull(searchTerms);
      if (parent.filter(key::equals).isPresent())
        throw new IllegalArgumentException("A navigation entry cannot parent itself");
    }
  }

  private final BooleanSupplier active;
  private final Runnable rebuild;
  private final Consumer<Key> reveal;
  private final Runnable queryChanged;
  private NavigationHistory<Key> history;
  private int maximumHistory;
  private Key observedSelection;
  private Key task;

  void task(Key task) {
    this.task = task;
  }

  private boolean restoring;
  private Key historyOrigin, historyTarget, temporaryReveal;
  private boolean historyForward, mounting;
  private final Map<Key, Position> positions = new LinkedHashMap<>();
  private final Set<Key> expanded = new HashSet<>();
  private final Map<Key, Long> rowIds = new HashMap<>();
  private long nextRowId;
  private FormEditBox search;
  private FormPanel panel;
  private List<Button> historyWidgets = List.of();
  private String query = "", focusedRow;
  private int queryCursor, querySelection;
  private boolean searchFocused;
  private Object generation;
  private ClientTheme theme = ClientTheme.standard();

  record Widgets(FormEditBox search, FormPanel panel, List<Button> history) {}

  record Position(SectionNavigation.Bookmark scroll, String focus, DocumentId owner) {}

  record UiState(
      String query,
      int cursor,
      int selection,
      boolean searchFocused,
      Set<Key> expanded,
      SectionNavigation.Bookmark viewport,
      String focus) {
    UiState {
      expanded = Set.copyOf(expanded);
    }
  }

  private UiState pendingUi;

  UiState uiState() {
    return new UiState(
        query,
        queryCursor,
        querySelection,
        searchFocused,
        expanded,
        panel == null ? null : panel.bookmark(),
        focusedRow);
  }

  void restoreUi(UiState state) {
    query = state.query();
    queryCursor = state.cursor();
    querySelection = state.selection();
    searchFocused = state.searchFocused();
    focusedRow = state.focus();
    expanded.clear();
    expanded.addAll(state.expanded());
    pendingUi = state;
  }

  WorkspaceNavigation(BooleanSupplier active, Runnable rebuild, Consumer<Key> reveal) {
    this(active, rebuild, reveal, () -> {});
  }

  WorkspaceNavigation(
      BooleanSupplier active, Runnable rebuild, Consumer<Key> reveal, Runnable queryChanged) {
    this.queryChanged = Objects.requireNonNull(queryChanged);
    this.active = Objects.requireNonNull(active);
    this.rebuild = Objects.requireNonNull(rebuild);
    this.reveal = Objects.requireNonNull(reveal);
  }

  boolean restoring() {
    return restoring;
  }

  void remember(Key key, Position position) {
    if (key == null || position == null || history == null) return;
    positions.remove(key);
    positions.put(key, position);
    while (positions.size() > maximumHistory)
      positions.remove(positions.keySet().iterator().next());
  }

  Optional<Position> position(Key key) {
    return Optional.ofNullable(positions.get(key));
  }

  void visit(Key selected, FormCatalog.Limits limits) {
    if (selected == null || mounting) return;
    if (task != null) selected = task;
    if (historyTarget != null) {
      if (selected.equals(historyOrigin)) return;
      if (selected.equals(historyTarget)) {
        observedSelection = selected;
        historyOrigin = historyTarget = null;
      } else cancelRestoration();
    }
    if (history == null) {
      maximumHistory = limits.maximumHistoryEntries();
      history = new NavigationHistory<>(selected, maximumHistory);
    } else if (maximumHistory != limits.maximumHistoryEntries())
      throw new IllegalArgumentException("A navigation lifetime retains its history admission");
    if (!selected.equals(observedSelection)) history.select(selected);
    observedSelection = selected;
    refreshHistoryControls();
  }

  private void refreshHistoryControls() {
    if (historyWidgets.size() == 2) {
      historyWidgets.get(0).active = history != null && history.canBack();
      historyWidgets.get(1).active = history != null && history.canForward();
    }
  }

  List<Button> historyControls(net.minecraft.client.gui.Font font) {
    var token = new Object();
    generation = token;
    return historyControls(font, token);
  }

  private List<Button> historyControls(net.minecraft.client.gui.Font font, Object token) {
    var historyButtons = new ArrayList<Button>();
    for (boolean forward : List.of(false, true)) {
      var label = forward ? text("forward", "Forward") : text("back", "Back");
      var button =
          theme
              .buttonBuilder(
                  label,
                  ignored -> {
                    if (current(token)) move(forward);
                  })
              .bounds(0, 0, Math.max(40, font.width(label) + 16), 20)
              .build();
      button.active = history != null && (forward ? history.canForward() : history.canBack());
      button.setTooltip(Tooltip.create(label));
      historyButtons.add(button);
    }
    historyWidgets = List.copyOf(historyButtons);
    return historyWidgets;
  }

  private void move(boolean forward) {
    if (history == null
        || !active.getAsBoolean()
        || (forward ? !history.canForward() : !history.canBack())) return;
    cancelRestoration();
    historyOrigin = history.selected();
    historyForward = forward;
    var requested = forward ? history.forward() : history.back();
    historyTarget = requested;
    searchFocused = false;
    focusedRow = null;
    restoring = true;
    try {
      reveal.accept(requested);
    } finally {
      restoring = false;
    }
    rebuild.run();
  }

  boolean canBack() {
    return history != null && history.canBack();
  }

  void beginMount() {
    mounting = true;
  }

  void endMount() {
    mounting = false;
  }

  void cancelRestoration() {
    if (historyTarget == null) return;
    if (historyForward) history.back();
    else history.forward();
    historyOrigin = historyTarget = null;
    refreshHistoryControls();
  }

  void temporaryReveal(Key key) {
    temporaryReveal = key;
  }

  boolean filtered() {
    return !query.isBlank();
  }

  void preserve(GuiEventListener focused) {
    if (pendingUi != null) return; // Old mounted controls must not overwrite an admitted return.
    if (search != null) {
      queryCursor = search.getCursorPosition();
      querySelection = search.selection();
      searchFocused = focused == search;
    }
    focusedRow = panel != null && focused == panel ? panel.focusKey() : null;
  }

  boolean restoreSearchFocus() {
    return searchFocused;
  }

  boolean restoreTreeFocus() {
    return focusedRow != null;
  }

  void restoreTree() {
    if (panel != null && focusedRow != null) panel.restoreFocus(focusedRow);
  }

  boolean focusSearch() {
    if (search == null || !active.getAsBoolean()) return false;
    searchFocused = true;
    search.setFocused(true);
    return true;
  }

  boolean clearFocusedFilter() {
    if (search == null || !search.isFocused() || query.isEmpty()) return false;
    search.setValue("");
    return true;
  }

  Widgets build(
      Minecraft client,
      FormGeometry.Placement bounds,
      List<Entry> declared,
      Key selected,
      FormCatalog.Limits limits) {
    return build(client, bounds, declared, selected, limits, List.of());
  }

  Widgets build(
      Minecraft client,
      FormGeometry.Placement bounds,
      List<Entry> declared,
      Key selected,
      FormCatalog.Limits limits,
      List<Component> notices) {
    return build(client, bounds, declared, selected, limits, notices, ignored -> {});
  }

  Widgets build(
      Minecraft client,
      FormGeometry.Placement bounds,
      List<Entry> declared,
      Key selected,
      FormCatalog.Limits limits,
      List<Component> notices,
      Consumer<FormPanel> prefix) {
    return build(
        client, bounds, declared, selected, limits, notices, prefix, ClientTheme.standard());
  }

  Widgets build(
      Minecraft client,
      FormGeometry.Placement bounds,
      List<Entry> declared,
      Key selected,
      FormCatalog.Limits limits,
      List<Component> notices,
      Consumer<FormPanel> prefix,
      ClientTheme theme) {
    this.theme = Objects.requireNonNull(theme);
    Objects.requireNonNull(limits);
    var entries = admit(declared, limits.maximumEntries());
    visit(selected, limits);
    rowIds.keySet().retainAll(entries.keySet());
    expanded.retainAll(entries.keySet());
    if (selected != null && !rowIds.containsKey(selected)) expanded.add(selected);
    for (var current = entries.get(nearest(selected, entries)); current != null; ) {
      if (current.parent().isEmpty()) break;
      expanded.add(current.parent().orElseThrow());
      current = entries.get(current.parent().orElseThrow());
    }
    var token = new Object();
    generation = token;
    var historyButtons = historyControls(client.font, token);
    var historyGeometry =
        FormGeometry.controls(
            historyButtons.stream()
                .map(button -> new FormGeometry.Size(button.getWidth(), button.getHeight()))
                .toList(),
            Math.max(1, bounds.width()),
            4);
    for (int i = 0; i < historyButtons.size(); i++) {
      var placement = historyGeometry.placements().get(i);
      var button = historyButtons.get(i);
      button.setPosition(bounds.x() + placement.x(), bounds.y() + placement.y());
      button.setWidth(placement.width());
    }
    int searchTop = bounds.y() + historyGeometry.height() + 4;
    int searchWidth = Math.max(1, bounds.width());
    search =
        new FormEditBox(
            client.font, bounds.x(), searchTop, searchWidth, 20, text("search", "Search settings"));
    ClientControls.theme(search, theme);
    search.setMaxLength(limits.maximumQueryCharacters());
    search.setHint(text("search", "Search settings"));
    search.setValue(query);
    search.setCursorPosition(Math.min(queryCursor, query.length()));
    search.setHighlightPos(Math.min(querySelection, query.length()));
    search.setResponder(
        value -> {
          if (!current(token) || query.equals(value)) return;
          query = value;
          temporaryReveal = null;
          queryChanged.run();
          queryCursor = search.getCursorPosition();
          querySelection = search.selection();
          searchFocused = true;
          rebuild.run();
        });
    float scroll = panel == null ? 0 : panel.scroll();
    panel =
        new FormPanel(
            client,
            bounds.x(),
            searchTop + 26,
            bounds.width(),
            Math.max(1, bounds.height() - (searchTop - bounds.y()) - 26),
            true,
            theme);
    Objects.requireNonNull(prefix).accept(panel);
    var children = new LinkedHashMap<Key, List<Entry>>();
    var roots = new ArrayList<Entry>();
    for (var entry : entries.values()) {
      if (entry.parent().isEmpty()) roots.add(entry);
      else
        children
            .computeIfAbsent(entry.parent().orElseThrow(), ignored -> new ArrayList<>())
            .add(entry);
    }
    var matches = new HashSet<>(matching(entries));
    if (temporaryReveal != null && !query.isBlank()) {
      for (var reveal = entries.get(temporaryReveal);
          reveal != null;
          reveal = reveal.parent().map(entries::get).orElse(null)) matches.add(reveal.key());
    }
    boolean filtering = !query.isBlank();
    var shownSelected = nearest(selected, entries);
    while (!filtering
        && shownSelected != null
        && entries.containsKey(shownSelected)
        && entries.get(shownSelected).searchOnly())
      shownSelected = entries.get(shownSelected).parent().orElse(null);
    for (var notice : notices) panel.row(Component.empty(), notice);
    if (temporaryReveal != null && filtering)
      panel.row(
          Component.empty(),
          text(
              "temporary_reveal",
              "Temporarily showing the linked destination; your filter is unchanged."));
    record Item(Entry entry, int depth) {}
    var pending = new ArrayDeque<Item>();
    for (var root : roots.reversed()) pending.push(new Item(root, 0));
    int shown = 0;
    while (!pending.isEmpty()) {
      var item = pending.pop();
      var entry = item.entry();
      if (!filtering && entry.searchOnly()) continue;
      if (filtering && !matches.contains(entry.key())) continue;
      boolean branch = children.containsKey(entry.key());
      boolean open = branch && (filtering || expanded.contains(entry.key()));
      int indent = Math.min(28, item.depth() * 8);
      int width = Math.max(24, bounds.width() - 20);
      var button =
          FormPanel.navigationButton(
              entry.title(),
              width,
              branch,
              entry.key().equals(shownSelected),
              () -> {
                if (!current(token)) return;
                if (entry.select() != null) {
                  if (branch && !expanded.remove(entry.key())) expanded.add(entry.key());
                  searchFocused = false;
                  entry.select().run();
                  // Disclosure is immediate even while the selected owner is admitted
                  // asynchronously.
                  if (branch && current(token)) rebuild.run();
                } else {
                  if (!expanded.remove(entry.key())) expanded.add(entry.key());
                  searchFocused = false;
                  rebuild.run();
                }
              });
      button.treeRow(indent, branch ? open : null);
      button.context(entry.help());
      if (!entry.help().getString().isBlank()) button.setTooltip(Tooltip.create(entry.help()));
      long row = rowIds.computeIfAbsent(entry.key(), ignored -> nextRowId++);
      panel.row("workspace:" + row, Component.empty(), Component.empty(), button);
      shown++;
      if (open)
        for (var child : children.get(entry.key()).reversed())
          pending.push(new Item(child, item.depth() + 1));
    }
    if (shown == 0) panel.row(text("no_matches", "No matching settings"), Component.empty());
    panel.finish(scroll);
    if (pendingUi != null) {
      panel.restore(pendingUi.viewport());
      if (pendingUi.focus() != null) panel.restoreFocus(pendingUi.focus());
      pendingUi = null;
    }
    return new Widgets(search, panel, List.copyOf(historyButtons));
  }

  private static Key nearest(Key requested, Map<Key, Entry> entries) {
    if (requested == null || entries.containsKey(requested)) return requested;
    if (requested.kind() != Kind.FIELD) return null;
    var fallback = new Key(Kind.DESTINATION, requested.placement(), Optional.empty());
    int depth = -1;
    for (var key : entries.keySet())
      if (key.kind() == Kind.FIELD
          && key.placement().equals(requested.placement())
          && requested.field().orElseThrow().startsWith(key.field().orElseThrow())
          && key.field().orElseThrow().segments().size() > depth) {
        fallback = key;
        depth = key.field().orElseThrow().segments().size();
      }
    return entries.containsKey(fallback) ? fallback : null;
  }

  private Set<Key> matching(Map<Key, Entry> entries) {
    if (query.isBlank()) return Set.of();
    var result = new HashSet<Key>();
    var terms = List.of(query.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    var inherited = new HashMap<Key, BitSet>();
    for (var entry : entries.values()) {
      var chain = new ArrayDeque<Entry>();
      var parent = entry;
      while (parent != null && !inherited.containsKey(parent.key())) {
        chain.push(parent);
        parent = parent.parent().map(entries::get).orElse(null);
      }
      var flags = parent == null ? new BitSet(terms.size()) : inherited.get(parent.key());
      while (!chain.isEmpty()) {
        var current = chain.pop();
        flags = (BitSet) flags.clone();
        var text = current.searchTerms().toLowerCase(Locale.ROOT);
        for (int i = 0; i < terms.size(); i++)
          if (!flags.get(i) && text.contains(terms.get(i))) flags.set(i);
        inherited.put(current.key(), flags);
        if (flags.cardinality() == terms.size()) {
          for (var match = current; match != null && result.add(match.key()); )
            match = match.parent().map(entries::get).orElse(null);
        }
      }
    }
    return result;
  }

  private static Map<Key, Entry> admit(List<Entry> declared, int maximumEntries) {
    var graph =
        new DestinationGraph<>(
            declared.stream()
                .map(entry -> new DestinationGraph.Node<>(entry.key(), entry.parent(), entry))
                .toList(),
            new DestinationGraph.Limits(maximumEntries, maximumEntries));
    var entries = new LinkedHashMap<Key, Entry>();
    graph.nodes().forEach(node -> entries.put(node.id(), node.target()));
    return entries;
  }

  private boolean current(Object token) {
    return generation == token && active.getAsBoolean();
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.workspace." + key, fallback);
  }
}
